package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(AndroidJUnit4::class)
class EpubReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDiagnosticCheckpoints() {
        File(context.cacheDir, "epub-diagnostic-checkpoints").deleteRecursively()
    }

    // region Opening and locator contract

    @Test
    fun opensEpub2AndEpub3WithExpectedToc() = runBlocking {
        val epub2 = open("epub2")
        assertEquals("Mihon EPUB 2 Fixture", epub2.publication.metadata.title)
        assertEquals(3, epub2.publication.tableOfContents.size)
        epub2.close()

        val epub3 = open("epub3")
        assertEquals("Mihon EPUB 3 Arabic Fixture", epub3.publication.metadata.title)
        assertEquals(3, epub3.publication.tableOfContents.size)
        assertEquals(1, epub3.publication.tableOfContents.first().children.size)
        epub3.close()
    }

    @Test
    fun corruptFixtureReturnsControlledError() = runBlocking {
        val result = EpubPublicationOpener(context).open(reference("corrupt"))
        assertTrue(result is EpubPublicationOpener.Result.Failure)
    }

    @Test
    fun locatorRoundTripAndMissingResourceRecovery() = runBlocking {
        val target = reference("epub3")
        val session = open("epub3")
        val base = requireNotNull(session.publication.locatorFromLink(session.publication.readingOrder.first()))
        val locator = base.copy(
            locations = Locator.Locations(
                fragments = listOf("epub3-start"),
                progression = 0.25,
                otherLocations = mapOf("cssSelector" to "#epub3-start"),
            ),
            text = Locator.Text(highlight = "EPUB3-PASSAGE-START"),
        )
        val encoded = EpubLocatorCodec.encode(target, session.fingerprint, locator)
        val restored = EpubLocatorCodec.decode(encoded, target, session.fingerprint, session.publication)
        assertTrue(restored is EpubLocatorCodec.DecodeResult.Restored)
        assertEquals(
            locator.toJSON().toString(),
            (restored as EpubLocatorCodec.DecodeResult.Restored).locator.toJSON().toString(),
        )

        val missing = locator.copy(href = requireNotNull(Url("text/missing%20resource.xhtml?x=1#marker")))
        val fallback = EpubLocatorCodec.decode(
            EpubLocatorCodec.encode(target, session.fingerprint, missing),
            target,
            session.fingerprint,
            session.publication,
        )
        assertEquals(
            EpubRecoveryReason.MissingResource,
            (fallback as EpubLocatorCodec.DecodeResult.Start).reason,
        )
        session.close()
    }

    @Test
    fun openerCreationFailureAndRapidReplacementLeaveNoFiles() = runBlocking {
        val blocked = File(context.cacheDir, "epub-opener-blocked").apply {
            deleteRecursively()
            writeText("not a directory")
        }
        val failure = EpubPublicationOpener(context, workDirectory = File(blocked, "child")).open(reference("epub3"))
        assertTrue(failure is EpubPublicationOpener.Result.Failure)
        blocked.delete()

        val workDirectory = File(context.cacheDir, "epub-opener-rapid").apply { deleteRecursively() }
        val opener = EpubPublicationOpener(context, workDirectory = workDirectory)
        val adopted = mutableListOf<EpubPublicationSession>()
        repeat(6) { index ->
            val job = async(Dispatchers.Default) { opener.open(reference("epub3")) }
            // Cancel at varying points of copying/parsing/return dispatch.
            repeat(index * 3) { yield() }
            SystemClock.sleep(index * 15L)
            job.cancel()
            runCatching { job.await() }.getOrNull()
                ?.let { (it as? EpubPublicationOpener.Result.Success)?.session?.let(adopted::add) }
        }
        val survivors = (0 until 3).map { async(Dispatchers.Default) { opener.open(reference("epub3")) } }.awaitAll()
        survivors.forEach { adopted += (it as EpubPublicationOpener.Result.Success).session }
        assertEquals(adopted.size, workDirectory.listFiles().orEmpty().size)
        adopted.forEach { it.close() }
        assertEquals(0, workDirectory.listFiles().orEmpty().size)
    }

    // endregion

    // region Navigator ownership and replacement

    @Test
    fun normalReopenBindsNavigatorOfNewSession() = launch("epub3") { scenario ->
        val first = awaitReady(scenario)
        scenario.onActivity { it.viewModel.reopen() }
        val second = awaitReady(scenario, afterSession = first)
        assertOwnedAndNavigable(scenario, second)
    }

    @Test
    fun rapidRepeatedReopenEndsWithOneOwnedNavigator() = launch("epub3") { scenario ->
        val first = awaitReady(scenario)
        scenario.onActivity { activity -> repeat(5) { activity.viewModel.reopen() } }
        val last = awaitReady(scenario, afterSession = first)
        assertOwnedAndNavigable(scenario, last)
    }

    @Test
    fun reopenCaptureFailureKeepsSessionAndRetries() = launch("epub3") { scenario ->
        val stored = persistAThenShowB(scenario) { throw IOException("injected lookup failure") }
        assertReopenFailureThenRetry(scenario, stored, keepsAcceptedA = true) { EpubReaderTestHooks.lookup = {} }
    }

    @Test
    fun reopenUnavailableCaptureKeepsSessionAndRetries() = launch("epub3") { scenario ->
        val stored = persistAThenShowB(scenario, unavailable = true) {}
        assertReopenFailureThenRetry(scenario, stored, keepsAcceptedA = true) {
            EpubReaderTestHooks.anchorUnavailable = false
        }
    }

    @Test
    fun reopenPersistenceFailureKeepsSessionAndRetries() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 25)
        checkpointPassage(scenario)
        val stored = checkpointFile.readText()
        EpubReaderTestHooks.persist = { throw IOException("injected write failure") }
        goToPassage(scenario, BACK_PASSAGE)
        // B may be accepted (SavedStateHandle) automatically; only its write fails.
        assertReopenFailureThenRetry(scenario, stored, keepsAcceptedA = false) { EpubReaderTestHooks.persist = {} }
    }

    @Test
    fun reopenDuringReaderErrorRecoversFromCheckpoint() = launch("epub3") { scenario ->
        val session = awaitReady(scenario)
        goToPassage(scenario, 20)
        val passage = checkpointPassage(scenario)
        val binding = onMain(scenario) { it.viewModel.adapter!!.currentBinding!! }
        onMainNow { binding.reportResourceFailure("injected failure") }
        awaitCondition("controlled resource error") {
            onMain(scenario) { it.viewModel.state.value.phase == EpubRestorationPhase.Error }
        }

        // No current position can be captured in Error, so Reopen recovers instead of failing.
        scenario.onActivity { it.viewModel.reopen() }
        awaitCondition("recovery reopen started") {
            onMain(scenario) { it.viewModel.state.value.phase != EpubRestorationPhase.Error }
        }
        val reopened = awaitReady(scenario, afterSession = session)
        assertEquals(null, onMain(scenario) { it.viewModel.state.value.reopenFailed })
        assertPassage(scenario, passage, "after recovering from an error")
        assertOwnedAndNavigable(scenario, reopened)
    }

    @Test
    fun openCompletingWhileStoppedIsBoundOnResume() = launch("epub3") { scenario ->
        val first = awaitReady(scenario)
        // Background while an open is pending (it may finish on either side of the stop)...
        scenario.onActivity { it.viewModel.reopen() }
        scenario.moveToState(Lifecycle.State.CREATED)
        val stoppedAt = currentSession(scenario).coerceAtLeast(first)
        // ...and one that certainly starts and completes while stopped.
        scenario.onActivity { it.viewModel.reopen() }
        awaitCondition("new session installed while stopped") {
            onMain(scenario) { it.viewModel.state.value.sessionId > stoppedAt }
        }
        scenario.onActivity { activity ->
            assertTrue(activity.readerFragment!!.boundSessionId != activity.viewModel.state.value.sessionId)
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        val resumed = awaitReady(scenario, afterSession = first)
        assertOwnedAndNavigable(scenario, resumed)
    }

    @Test
    fun recreationDuringOpeningBindsTheNewSession() = launch("epub3") { scenario ->
        val first = awaitReady(scenario)
        scenario.onActivity { it.viewModel.reopen() }
        scenario.recreate()
        val recreated = awaitReady(scenario, afterSession = first)
        assertOwnedAndNavigable(scenario, recreated)
    }

    @Test
    fun processRestorationReplacesDummyNavigator() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 30)
        val expected = checkpointPassage(scenario)
        // Clearing the store creates a new ViewModel with no session when the restored host fragment
        // is created, so the restored child navigator comes from Readium's dummy factory.
        scenario.onActivity { it.viewModelStore.clear() }
        scenario.recreate()
        val restored = awaitReady(scenario)
        assertOwnedAndNavigable(scenario, restored, expectedPassage = expected)
    }

    @Test
    fun obsoleteOwnerEventsChangeNeitherCheckpointNorState() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 20)
        checkpointPassage(scenario)
        val oldOwner = AtomicReference<EpubOwner>()
        val oldLocator = AtomicReference<Locator>()
        scenario.onActivity { activity ->
            oldOwner.set(activity.viewModel.activeOwner)
            oldLocator.set(activity.viewModel.adapter!!.currentLocator())
        }

        // Old binding of the same session (recreation), then old session (reopen).
        scenario.recreate()
        awaitReady(scenario)
        val reboundOwner = AtomicReference<EpubOwner>()
        scenario.onActivity { reboundOwner.set(it.viewModel.activeOwner) }
        assertEquals(oldOwner.get().sessionId, reboundOwner.get().sessionId)
        assertStaleEventsIgnored(scenario, oldOwner.get(), oldLocator.get())

        scenario.onActivity { it.viewModel.reopen() }
        awaitReady(scenario, afterSession = oldOwner.get().sessionId)
        assertStaleEventsIgnored(scenario, oldOwner.get(), oldLocator.get())
        assertStaleEventsIgnored(scenario, reboundOwner.get(), oldLocator.get())
    }

    // endregion

    // region Restoration of a passage inside a long resource

    @Test
    fun passageSurvivesFontChangeRotationRebuildAndRecreation() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 40)
        val expected = checkpointPassage(scenario)

        scenario.onActivity { it.viewModel.changeFontSize(0.2) }
        awaitReady(scenario)
        assertPassage(scenario, expected, "after font increase")
        scenario.onActivity { assertEquals(1.2, it.viewModel.state.value.preferences.fontSizeOverride!!, 0.001) }

        // A real display rotation, not a requested orientation the platform may ignore.
        try {
            awaitRecreatedReady(scenario) { device.setOrientationLandscape() }
            assertPassage(scenario, expected, "after rotation to landscape")
            awaitRecreatedReady(scenario) { device.setOrientationPortrait() }
            assertPassage(scenario, expected, "after rotation back to portrait")
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }

        var session = currentSession(scenario)
        repeat(3) { cycle ->
            scenario.onActivity { it.viewModel.reopen() }
            session = awaitReady(scenario, afterSession = session)
            assertPassage(scenario, expected, "after reopen ${cycle + 1}")
        }

        scenario.recreate()
        awaitReady(scenario)
        assertPassage(scenario, expected, "after Activity recreation")
    }

    @Test
    fun diagnosticCheckpointRestoresPassageInFreshActivity() {
        val expected = AtomicInteger()
        launch("epub3") { scenario ->
            awaitReady(scenario)
            goToPassage(scenario, 50)
            expected.set(checkpointPassage(scenario))
            scenario.onActivity { it.finish() }
        }
        assertTrue(File(context.cacheDir, "epub-diagnostic-checkpoints/epub3.json").isFile)

        launch("epub3") { scenario ->
            awaitReady(scenario)
            scenario.onActivity { assertEquals(null, it.viewModel.state.value.recoveryReason) }
            assertPassage(scenario, expected.get(), "in a fresh Activity/ViewModel")
        }
    }

    @Test
    fun corruptDiagnosticCheckpointStartsWithRecoveryReason() {
        File(context.cacheDir, "epub-diagnostic-checkpoints").mkdirs()
        File(context.cacheDir, "epub-diagnostic-checkpoints/epub3.json").writeText("{corrupt")
        launch("epub3") { scenario ->
            awaitReady(scenario)
            scenario.onActivity {
                assertEquals(EpubRecoveryReason.MalformedEnvelope, it.viewModel.state.value.recoveryReason)
            }
        }
    }

    // endregion

    // region Network policy and timing

    @Test
    fun externalFixtureNeverReachesLoopbackSocket() {
        val accepted = AtomicInteger()
        ServerSocket(38080).use { trap ->
            trap.soTimeout = 100
            val monitor = thread(name = "loopback-trap") {
                while (!trap.isClosed) {
                    try {
                        trap.accept().use { accepted.incrementAndGet() }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            try {
                launch("external") { scenario ->
                    awaitCondition("external fixture rendered or blocked resource observed") {
                        onMain(scenario) {
                            val state = it.viewModel.state.value
                            state.phase == EpubRestorationPhase.Ready ||
                                state.error is EpubOpenError.ResourceUnavailable
                        }
                    }
                    val ready = onMain(scenario) { it.viewModel.state.value.phase == EpubRestorationPhase.Ready }
                    if (ready) {
                        assertTrue(visibleAnchors(scenario).contains("external-start"))
                    }
                    // Keep monitoring after rendering, while the WebView may still resolve subresources.
                    SystemClock.sleep(1_000)
                }
            } finally {
                trap.close()
                monitor.join()
            }
        }
        assertEquals(0, accepted.get())
    }

    @Test
    fun measuresSingleFixtureOpen() {
        val startedAt = SystemClock.elapsedRealtime()
        launch("epub3") { scenario ->
            awaitReady(scenario)
            Log.i(TIMING_TAG, "cold_open_rendered_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    @Test
    fun measuresWarmFixtureReopens() = launch("epub3") { scenario ->
        var session = awaitReady(scenario)
        repeat(5) { sample ->
            val startedAt = SystemClock.elapsedRealtime()
            scenario.onActivity { it.viewModel.reopen() }
            session = awaitReady(scenario, afterSession = session)
            Log.i(TIMING_TAG, "warm_open_rendered_${sample + 1}_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    // endregion

    // region Back persistence (no checkpointNow before Back; delayed capture and persistence)

    @Test
    fun systemBackPersistsLatestPassage() = assertBackPersists { device.pressBack() }

    @Test
    fun headerBackPersistsLatestPassage() = assertBackPersists {
        requireNotNull(device.wait(Until.findObject(By.text("Back")), 5_000)).click()
    }

    @Test
    fun repeatedBackSavesAndExitsOnce() = assertBackPersists {
        device.pressBack()
        device.pressBack()
        device.pressBack()
    }

    @Test
    fun backPersistenceFailureKeepsReaderAndOffersRetry() {
        launch("epub3") { scenario ->
            awaitReady(scenario)
            goToPassage(scenario, 50)
            EpubReaderTestHooks.persist = {
                delay(500)
                throw IOException("injected write failure")
            }

            device.pressBack()
            awaitExit<EpubReaderViewModel.Exit.Failed>(scenario)
            requireNotNull(device.wait(Until.findObject(By.text("Reading position not saved")), 5_000))
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))

            requireNotNull(device.wait(Until.findObject(By.text("Stay")), 5_000)).click()
            awaitCondition("reader usable after Stay") {
                onMain(scenario) { it.viewModel.state.value.canNavigate }
            }

            device.pressBack()
            awaitExit<EpubReaderViewModel.Exit.Failed>(scenario)
            EpubReaderTestHooks.persist = {}
            requireNotNull(device.wait(Until.findObject(By.text("Retry")), 5_000)).click()
            awaitCondition("Activity finished after Retry") { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    // endregion

    // region Authoritative restored checkpoint (SavedStateHandle newer than the diagnostic file)

    @Test
    fun savedStateCheckpointOverridesOlderFile() = assertRestoredCheckpointPersists(deleteFile = false)

    @Test
    fun savedStateCheckpointWithoutFile() = assertRestoredCheckpointPersists(deleteFile = true)

    @Test
    fun restoredCheckpointWriteFailureOffersRetry() {
        launch("epub3") { scenario ->
            val older = savedStateNewerThanFile(scenario, deleteFile = false)
            recreateViewModelFromSavedState(scenario) { throw IOException("injected write failure") }

            device.pressBack()
            awaitExit<EpubReaderViewModel.Exit.Failed>(scenario)
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            assertEquals(older, checkpointFile.readText())

            EpubReaderTestHooks.persist = {}
            requireNotNull(device.wait(Until.findObject(By.text("Retry")), 5_000)).click()
            awaitCondition("Activity finished after Retry") { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    // endregion

    // region Reader-event threading

    @Test
    fun workerThreadResourceFailureIsHandledOnMain() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 20)
        checkpointPassage(scenario)
        val checkpoint = onMain(scenario) { it.viewModel.acceptedCheckpoint }
        val binding = onMain(scenario) { it.viewModel.adapter!!.currentBinding!! }

        // The ViewModel asserts the main thread, so off-main handling would crash the worker.
        thread(name = "resource-worker") { binding.reportResourceFailure("worker failure") }.join()
        awaitCondition("controlled resource error") {
            onMain(scenario) { it.viewModel.state.value.error is EpubOpenError.ResourceUnavailable }
        }
        onMain(scenario) { activity ->
            assertEquals(EpubRestorationPhase.Error, activity.viewModel.state.value.phase)
            activity.viewModel.checkpoint()
        }
        SystemClock.sleep(500)
        assertEquals(checkpoint, onMain(scenario) { it.viewModel.acceptedCheckpoint })
        assertEquals(checkpoint, checkpointFile.readText())
    }

    @Test
    fun queuedObsoleteFailureIsIgnoredAfterRebind() = launch("epub3") { scenario ->
        val session = awaitReady(scenario)
        goToPassage(scenario, 20)
        checkpointPassage(scenario)
        val checkpoint = onMain(scenario) { it.viewModel.acceptedCheckpoint }
        val oldOwner = AtomicReference<EpubOwner>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<EpubReaderActivity>().single()
            val old = activity.viewModel.adapter!!.currentBinding!!
            oldOwner.set(old.owner)
            // Queued behind this main-thread block, so it is delivered after the rebinding below.
            thread(name = "resource-worker") { old.reportResourceFailure("obsolete failure") }.join()
            activity.viewModel.detachBinding(old)
            activity.readerFragment!!.attach()
            assertTrue(activity.viewModel.activeOwner != old.owner)
        }

        awaitReady(scenario)
        onMain(scenario) { activity ->
            val state = activity.viewModel.state.value
            assertEquals(null, state.error)
            assertEquals(session, state.sessionId)
            assertEquals(session, activity.viewModel.activeOwner?.sessionId)
            assertTrue(activity.viewModel.activeOwner != oldOwner.get())
            assertEquals(checkpoint, activity.viewModel.acceptedCheckpoint)
        }
    }

    @Test
    fun currentFailureDuringCaptureClosesPersistence() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 20)
        checkpointPassage(scenario)
        val checkpoint = onMain(scenario) { it.viewModel.acceptedCheckpoint }

        EpubReaderTestHooks.captureDelayMs = 5_000
        goToPassage(scenario, 40)
        val binding = onMain(scenario) { it.viewModel.adapter!!.currentBinding!! }
        thread(name = "resource-worker") { binding.reportResourceFailure("failure during capture") }.join()
        awaitCondition("controlled resource error") {
            onMain(scenario) { it.viewModel.state.value.error is EpubOpenError.ResourceUnavailable }
        }
        // Past the pending capture's lookup: its result must not be accepted.
        SystemClock.sleep(6_000)
        onMain(scenario) { activity ->
            assertEquals(EpubRestorationPhase.Error, activity.viewModel.state.value.phase)
            assertEquals(checkpoint, activity.viewModel.acceptedCheckpoint)
        }
        assertEquals(checkpoint, checkpointFile.readText())
    }

    @Test
    fun currentFailureDuringReflowClosesPersistence() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 40)
        checkpointPassage(scenario)
        val checkpoint = onMain(scenario) { it.viewModel.acceptedCheckpoint }

        EpubReaderTestHooks.operationDelayMs = 5_000
        scenario.onActivity { it.viewModel.changeFontSize(0.2) }
        awaitPhase(scenario, EpubRestorationPhase.Reflowing)
        val binding = onMainNow { it.viewModel.adapter!!.currentBinding!! }
        thread(name = "resource-worker") { binding.reportResourceFailure("failure during reflow") }.join()
        SystemClock.sleep(6_000)
        onMainNow { activity ->
            val state = activity.viewModel.state.value
            assertEquals(EpubRestorationPhase.Error, state.phase)
            assertTrue(state.error is EpubOpenError.ResourceUnavailable)
            assertEquals(null, state.preferences.fontSizeOverride)
            assertEquals(checkpoint, activity.viewModel.acceptedCheckpoint)
        }
        assertEquals(checkpoint, checkpointFile.readText())
    }

    // endregion

    // region Exit and reopen as competing lifecycle actions (delayed capture and persistence)

    @Test
    fun reopenThenBackExitsWithoutReopening() = assertCompetingExit { viewModel ->
        viewModel.reopen()
        viewModel.exit()
    }

    @Test
    fun backThenReopenIsRejected() = assertCompetingExit { viewModel ->
        viewModel.exit()
        viewModel.reopen()
    }

    @Test
    fun mixedReopenAndBackRequestsExitOnce() = assertCompetingExit { viewModel ->
        viewModel.reopen()
        viewModel.exit()
        viewModel.reopen()
        viewModel.exit()
        viewModel.reopen()
    }

    @Test
    fun reopenButtonIsDisabledWhileSaving() {
        launch("epub3") { scenario ->
            awaitReady(scenario)
            goToPassage(scenario, BACK_PASSAGE)
            EpubReaderTestHooks.captureDelayMs = 3_000
            EpubReaderTestHooks.persist = { delay(3_000) }
            // The footer scrolls horizontally; bring its last button, Reopen, on screen. The swipe
            // avoids the screen edges, where it would be the system Back gesture.
            val footer = onMain(scenario) { activity -> Rect().also { activity.footer.getGlobalVisibleRect(it) } }
            val y = footer.centerY()
            device.swipe(footer.left + footer.width() * 4 / 5, y, footer.left + footer.width() / 5, y, 10)
            onMain(scenario) { assertEquals(EpubReaderViewModel.Exit.Idle, it.viewModel.state.value.exit) }
            requireNotNull(device.wait(Until.findObject(By.hasChild(By.text("Reopen")).enabled(true)), 5_000))

            onMainNow { it.viewModel.exit() }
            withoutIdleWait {
                requireNotNull(device.wait(Until.findObject(By.hasChild(By.text("Reopen")).enabled(false)), 5_000))
            }
            awaitCondition("Activity finished after saving", timeoutMs = 15_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    @Test
    fun recreationWhileSavingStillFinishes() {
        val expected = AtomicInteger()
        launch("epub3") { scenario ->
            val session = awaitReady(scenario)
            goToPassage(scenario, BACK_PASSAGE)
            val stored = checkpointPassage(scenario)
            val exits = recordExits(scenario)
            // The save spanning recreation is the slow write of an already accepted page (within
            // the 5 s exit bound). A capture pending across recreation is covered below.
            EpubReaderTestHooks.persist = { delay(3_000) }
            onMainNow { assertTrue(it.viewModel.next()) }
            awaitCondition("next page accepted") {
                onMainNow { it.viewModel.anchorSelector }?.removePrefix("#long-p")?.toIntOrNull()
                    ?.takeIf { it > stored }?.also(expected::set) != null
            }

            val first = onMainNow { activity ->
                activity.viewModel.exit()
                activity.recreate()
                System.identityHashCode(activity)
            }
            // Not scenario.recreate(): it requires RESUMED, which a fast save legitimately skips.
            awaitCondition("Activity recreated while saving") {
                Stage.values().filter { it != Stage.DESTROYED }.any { stage ->
                    readerActivities(stage).any { System.identityHashCode(it) != first }
                }
            }
            awaitCondition("recreated Activity finished after saving", timeoutMs = 20_000) {
                Stage.values().filter { it != Stage.DESTROYED }.all { readerActivities(it).isEmpty() }
            }
            assertSingleExit(exits, session)
        }
        assertPassageInFreshActivity(expected.get())
    }

    // endregion

    // region Capture pending across recreation (A persisted, B displayed, B's lookup pending)

    @Test
    fun cancellableCapturePendingAcrossRecreationSavesDisplayedPassage() =
        assertCaptureStageRecreation(obsoleteLookup = { delay(60_000) })

    @Test
    fun callbackCapturePendingAcrossRecreationSavesDisplayedPassage() {
        // Like Readium's WebView calls: ignores cancellation and resumes only when called back.
        val held = Collections.synchronizedList(mutableListOf<Continuation<Unit>>())
        assertCaptureStageRecreation(
            obsoleteLookup = { suspendCoroutine { held += it } },
            beforeRelease = {
                val pending = synchronized(held) { held.toList() }
                assertTrue("The replaced binding's lookup must still be pending", pending.isNotEmpty())
                InstrumentationRegistry.getInstrumentation().runOnMainSync { pending.forEach { it.resume(Unit) } }
            },
        )
    }

    @Test
    fun capturePendingPastExitBoundFailsAndKeepsCheckpoint() {
        launch("epub3") { scenario ->
            val stored = persistAThenShowB(scenario) { delay(60_000) }
            onMainNow { activity ->
                activity.viewModel.exit()
                activity.recreate()
            }
            awaitCondition("exit Failed after recreation") {
                onResumed { it.viewModel.state.value.exit is EpubReaderViewModel.Exit.Failed } == true
            }
            onMainNow { assertEquals(stored, it.viewModel.acceptedCheckpoint) }
            assertEquals(stored, checkpointFile.readText())

            EpubReaderTestHooks.lookup = {}
            requireNotNull(device.wait(Until.findObject(By.text("Retry")), 5_000)).click()
            awaitCondition("Activity finished after Retry", timeoutMs = 15_000) { noReaderActivities() }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    @Test
    fun captureFailureDuringBackFailsAndKeepsCheckpoint() {
        launch("epub3") { scenario ->
            val stored = persistAThenShowB(scenario) { throw IOException("injected lookup failure") }
            device.pressBack()
            awaitExit<EpubReaderViewModel.Exit.Failed>(scenario)
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
            assertEquals(stored, onMain(scenario) { it.viewModel.acceptedCheckpoint })
            assertEquals(stored, checkpointFile.readText())

            EpubReaderTestHooks.lookup = {}
            requireNotNull(device.wait(Until.findObject(By.text("Retry")), 5_000)).click()
            awaitCondition("Activity finished after Retry") { scenario.state == Lifecycle.State.DESTROYED }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    @Test
    fun cancellableCapturePendingAcrossRecreationReopensAtDisplayedPassage() =
        assertReopenCaptureStageRecreation(obsoleteLookup = { delay(60_000) })

    @Test
    fun callbackCapturePendingAcrossRecreationReopensAtDisplayedPassage() {
        val held = Collections.synchronizedList(mutableListOf<Continuation<Unit>>())
        assertReopenCaptureStageRecreation(
            obsoleteLookup = { suspendCoroutine { held += it } },
            beforeRelease = {
                val pending = synchronized(held) { held.toList() }
                assertTrue("The replaced binding's lookup must still be pending", pending.isNotEmpty())
                InstrumentationRegistry.getInstrumentation().runOnMainSync { pending.forEach { it.resume(Unit) } }
            },
        )
    }

    @Test
    fun reopenCapturePendingPastBoundAcrossRecreationFailsThenRetry() = launch("epub3") { scenario ->
        val (session, stored) = reopenTimesOutAcrossRecreation(scenario)
        EpubReaderTestHooks.lookup = {}
        requireNotNull(device.wait(Until.findObject(By.text("Retry")), 5_000)).click()
        val reopened = awaitReady(scenario, afterSession = session)
        val visible = visiblePassages(scenario)
        assertTrue("Expected paragraph $BACK_PASSAGE after Retry, got $visible", BACK_PASSAGE in visible)
        assertTrue("File must name B after Retry", checkpointFile.readText() != stored)
        assertOwnedAndNavigable(scenario, reopened)
    }

    @Test
    fun reopenCapturePendingPastBoundAcrossRecreationFailsThenStay() = launch("epub3") { scenario ->
        val (session, _) = reopenTimesOutAcrossRecreation(scenario)
        EpubReaderTestHooks.lookup = {}
        requireNotNull(device.wait(Until.findObject(By.text("Stay")), 5_000)).click()
        awaitCondition("reader usable after Stay") { onMain(scenario) { it.viewModel.state.value.canNavigate } }
        assertEquals(session, currentSession(scenario))
        assertTrue(BACK_PASSAGE in visiblePassages(scenario))
        // The dropped Reopen target must not steer a later rebinding: it lands on the new passage.
        goToPassage(scenario, STAY_PASSAGE)
        awaitCondition("passage $STAY_PASSAGE accepted") {
            onMain(scenario) { it.viewModel.anchorSelector }?.removePrefix("#long-p")?.toIntOrNull()
                ?.let { it < BACK_PASSAGE - 10 && it in visiblePassages(scenario) } == true
        }
        val accepted = onMain(scenario) { it.viewModel.anchorSelector }!!.removePrefix("#long-p").toInt()
        awaitRecreatedReady(scenario) { scenario.recreate() }
        val visible = visiblePassages(scenario)
        assertTrue("Expected accepted paragraph $accepted after recreation, got $visible", accepted in visible)
        assertFalse("The dropped target B must not be restored: $visible", BACK_PASSAGE in visible)
        assertEquals(session, currentSession(scenario))
    }

    @Test
    fun backSupersedesPendingReopenAcrossRecreation() {
        launch("epub3") { scenario ->
            val session = awaitReady(scenario)
            val obsolete = AtomicReference<EpubOwner>()
            val release = CompletableDeferred<Unit>()
            val stored = persistAThenShowB(scenario) { owner ->
                if (owner == obsolete.get()) delay(60_000) else release.await()
            }
            obsolete.set(onMain(scenario) { it.viewModel.activeOwner })
            val exits = recordExits(scenario)
            reopenAndRecreate(scenario, obsolete.get(), session, stored)

            onMainNow { it.viewModel.exit() }
            release.complete(Unit)
            awaitCondition("recreated Activity finished after saving", timeoutMs = 20_000) { noReaderActivities() }
            assertSingleExit(exits, session)
            assertTrue("Back must store B, not A", checkpointFile.readText() != stored)
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    @Test
    fun navigationDuringPendingReopenIsIgnored() = launch("epub3") { scenario ->
        val session = awaitReady(scenario)
        goToPassage(scenario, BACK_PASSAGE)
        EpubReaderTestHooks.captureDelayMs = 1_500
        onMainNow { activity ->
            activity.viewModel.reopen()
            assertFalse(activity.viewModel.state.value.canNavigate)
            assertFalse(activity.viewModel.next())
            assertFalse(activity.viewModel.goToToc(activity.viewModel.state.value.tableOfContents.first().id))
        }
        awaitReady(scenario, afterSession = session)
        val visible = visiblePassages(scenario)
        assertTrue("Expected paragraph $BACK_PASSAGE after Reopen, got $visible", BACK_PASSAGE in visible)
    }

    // endregion

    // region Exit and reopen as competing lifecycle actions (continued)

    @Test
    fun overlappingRequestsThenFailureStayAndRetry() {
        launch("epub3") { scenario ->
            val session = awaitReady(scenario)
            goToPassage(scenario, BACK_PASSAGE)
            EpubReaderTestHooks.captureDelayMs = 1_500
            EpubReaderTestHooks.persist = {
                delay(500)
                throw IOException("injected write failure")
            }

            onMainNow { activity ->
                activity.viewModel.reopen()
                activity.viewModel.exit()
            }
            awaitExit<EpubReaderViewModel.Exit.Failed>(scenario)
            onMain(scenario) { activity ->
                // Reopen is rejected while the failed exit awaits a decision.
                activity.viewModel.reopen()
                assertEquals(session, activity.viewModel.state.value.sessionId)
                assertEquals(EpubRestorationPhase.Ready, activity.viewModel.state.value.phase)
            }

            requireNotNull(device.wait(Until.findObject(By.text("Stay")), 5_000)).click()
            awaitCondition("reader usable after Stay") { onMain(scenario) { it.viewModel.state.value.canNavigate } }
            EpubReaderTestHooks.captureDelayMs = 0
            // Reopen also saves the displayed passage first, so it waits until writes succeed.
            EpubReaderTestHooks.persist = { delay(1_500) }
            scenario.onActivity { it.viewModel.reopen() }
            val reopened = awaitReady(scenario, afterSession = session)
            val exits = recordExits(scenario)

            device.pressBack()
            awaitCondition("Activity finished after Retry by Back", timeoutMs = 15_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
            assertSingleExit(exits, reopened)
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    // endregion

    // region Navigation policy during restoration and reflow

    @Test
    fun navigationDuringInitialRestorationIsIgnored() {
        val expected = AtomicInteger()
        launch("epub3") { scenario ->
            awaitReady(scenario)
            goToPassage(scenario, 50)
            expected.set(checkpointPassage(scenario))
            scenario.onActivity { it.finish() }
        }

        EpubReaderTestHooks.operationDelayMs = BUSY_WINDOW_MS
        launch("epub3") { scenario ->
            awaitPhase(scenario, EpubRestorationPhase.Restoring)
            attemptAllNavigation(scenario)
            assertEquals(EpubRestorationPhase.Restoring, onMainNow { it.viewModel.state.value.phase })

            EpubReaderTestHooks.reset()
            awaitReady(scenario, timeoutMs = 2 * BUSY_WINDOW_MS)
            onMain(scenario) { assertEquals(null, it.viewModel.state.value.error) }
            assertPassage(scenario, expected.get(), "after navigation attempts during restoration")
            assertEquals(expected.get(), checkpointPassage(scenario))
            // The same edge tap and key that were ignored while busy turn the page once Ready, so
            // the ignored inputs above did reach the reader. (Swipes do not page this navigator.)
            val restoredPage = visiblePassages(scenario)
            tapAndKeyForward(scenario)
            awaitCondition("swipe navigation once Ready") {
                (visiblePassages(scenario).minOrNull() ?: 0) >= restoredPage.max()
            }
        }
    }

    @Test
    fun navigationDuringReflowIsIgnored() = launch("epub3") { scenario ->
        awaitReady(scenario)
        goToPassage(scenario, 40)
        val expected = checkpointPassage(scenario)

        EpubReaderTestHooks.operationDelayMs = BUSY_WINDOW_MS
        scenario.onActivity { it.viewModel.changeFontSize(0.2) }
        awaitPhase(scenario, EpubRestorationPhase.Reflowing)
        attemptAllNavigation(scenario)
        assertEquals(EpubRestorationPhase.Reflowing, onMainNow { it.viewModel.state.value.phase })

        EpubReaderTestHooks.reset()
        awaitReady(scenario, timeoutMs = 2 * BUSY_WINDOW_MS)
        onMain(scenario) { assertEquals(null, it.viewModel.state.value.error) }
        assertPassage(scenario, expected, "after navigation attempts during reflow")
        scenario.onActivity { assertEquals(1.2, it.viewModel.state.value.preferences.fontSizeOverride!!, 0.001) }
        assertEquals(expected, checkpointPassage(scenario))
    }

    // endregion

    // region Reading viewport

    @Test
    fun readerViewportIsNotObscuredByControls() {
        try {
            launch("epub3") { scenario ->
                awaitReady(scenario)
                goToPassage(scenario, 40)
                assertUnobscured(scenario, "portrait")
                awaitRecreatedReady(scenario) { device.setOrientationLandscape() }
                assertUnobscured(scenario, "landscape")
                val expected = checkpointPassage(scenario)
                awaitRecreatedReady(scenario) { device.setOrientationPortrait() }
                assertPassage(scenario, expected, "after rotation with reserved control space")
            }
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
        }

        shell("settings put system font_scale 1.3")
        try {
            launch("epub3") { scenario ->
                awaitReady(scenario)
                goToPassage(scenario, 40)
                assertUnobscured(scenario, "font-scale-1.3")
                val expected = checkpointPassage(scenario)
                scenario.recreate()
                awaitReady(scenario)
                assertPassage(scenario, expected, "after recreation with larger system font")
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }

    // endregion

    // region Helpers

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @After
    fun resetHooks() {
        EpubReaderTestHooks.reset()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    /**
     * Back with a slow anchor lookup and a slow disk write: the reader must stay alive until the
     * newest passage is stored, so a fresh Activity/ViewModel (no SavedStateHandle) restores it.
     */
    private fun assertBackPersists(press: () -> Unit) {
        launch("epub3") { scenario ->
            awaitReady(scenario)
            goToPassage(scenario, 50)
            EpubReaderTestHooks.captureDelayMs = 1_500
            EpubReaderTestHooks.persist = { delay(1_500) }

            press()
            awaitExit<EpubReaderViewModel.Exit.Saving>(scenario, allowDestroyed = true)
            awaitCondition("Activity finished after saving", timeoutMs = 15_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    /**
     * Back, then recreation while B's capture by the replaced binding is pending ([obsoleteLookup]).
     * The replacement must restore B and capture it; only then may the exit store B and finish.
     * Lookups of the replacement wait until [beforeRelease] ran, so no capture can be accepted
     * meanwhile: until then the accepted checkpoint must still be A.
     */
    private fun assertCaptureStageRecreation(obsoleteLookup: suspend () -> Unit, beforeRelease: () -> Unit = {}) {
        launch("epub3") { scenario ->
            val session = awaitReady(scenario)
            val obsolete = AtomicReference<EpubOwner>()
            val release = CompletableDeferred<Unit>()
            val stored = persistAThenShowB(scenario) { owner ->
                if (owner == obsolete.get()) obsoleteLookup() else release.await()
            }
            obsolete.set(onMain(scenario) { it.viewModel.activeOwner })
            val exits = recordExits(scenario)

            val first = onMainNow { activity ->
                activity.viewModel.exit()
                activity.recreate()
                System.identityHashCode(activity)
            }
            awaitCondition("replacement navigator Ready while saving", timeoutMs = 4_000) {
                onResumed { activity ->
                    val viewModel = activity.viewModel
                    System.identityHashCode(activity) != first &&
                        viewModel.activeOwner.let { it != null && it != obsolete.get() } &&
                        viewModel.state.value.phase == EpubRestorationPhase.Ready
                } == true
            }
            onMainNow { activity ->
                assertEquals(EpubReaderViewModel.Exit.Saving, activity.viewModel.state.value.exit)
                assertEquals(stored, activity.viewModel.acceptedCheckpoint)
            }
            beforeRelease()
            // Resumed obsolete lookups ran on the main thread before this block.
            onMainNow { assertEquals(stored, it.viewModel.acceptedCheckpoint) }
            release.complete(Unit)

            awaitCondition("recreated Activity finished after saving", timeoutMs = 20_000) { noReaderActivities() }
            assertSingleExit(exits, session)
            assertTrue("The exit must store B, not A", checkpointFile.readText() != stored)
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    /**
     * Reopen counterpart of [assertCaptureStageRecreation]: Reopen, then recreation while B's
     * capture by the replaced binding is pending. Obsolete lookups must neither save A nor satisfy
     * the Reopen; only the replacement's capture of the restored B may, and the new session then
     * opens at B. B is checked again in a fresh Activity and ViewModel.
     */
    private fun assertReopenCaptureStageRecreation(obsoleteLookup: suspend () -> Unit, beforeRelease: () -> Unit = {}) {
        launch("epub3") { scenario ->
            val session = awaitReady(scenario)
            val obsolete = AtomicReference<EpubOwner>()
            val release = CompletableDeferred<Unit>()
            val stored = persistAThenShowB(scenario) { owner ->
                if (owner == obsolete.get()) obsoleteLookup() else release.await()
            }
            obsolete.set(onMain(scenario) { it.viewModel.activeOwner })
            reopenAndRecreate(scenario, obsolete.get(), session, stored)

            beforeRelease()
            // Resumed obsolete lookups ran on the main thread before this block.
            onMainNow { activity ->
                assertEquals(stored, activity.viewModel.acceptedCheckpoint)
                assertEquals(session, activity.viewModel.state.value.sessionId)
            }
            release.complete(Unit)

            awaitReady(scenario, afterSession = session)
            val visible = visiblePassages(scenario)
            assertTrue("Reopen must restore B, got $visible", BACK_PASSAGE in visible)
            assertTrue("Reopen must store B, not A", checkpointFile.readText() != stored)
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    /**
     * Reopen, then recreation while B's capture by the replaced [obsolete] binding is pending.
     * The replacement must restore B (not the accepted A) while the session is kept and the
     * accepted checkpoint and file still name A ([stored]). With [withinBound], the replacement
     * must be Ready before the Reopen's 5 s bound, so the Reopen is still pending.
     */
    private fun reopenAndRecreate(
        scenario: ActivityScenario<EpubReaderActivity>,
        obsolete: EpubOwner,
        session: Long,
        stored: String,
        withinBound: Boolean = true,
    ) {
        val first = onMainNow { activity ->
            activity.viewModel.reopen()
            activity.recreate()
            System.identityHashCode(activity)
        }
        val seen = AtomicReference<String>()
        try {
            awaitCondition("replacement navigator Ready", timeoutMs = if (withinBound) 4_000 else 20_000) {
                onResumed { activity ->
                    val viewModel = activity.viewModel
                    seen.set(
                        "new=${System.identityHashCode(
                            activity,
                        ) != first} ${viewModel.activeOwner} ${viewModel.state.value}",
                    )
                    System.identityHashCode(activity) != first &&
                        viewModel.activeOwner.let { it != null && it != obsolete } &&
                        viewModel.state.value.phase == EpubRestorationPhase.Ready
                } == true
            }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; last seen ${seen.get()}", e)
        }
        onMainNow { activity ->
            assertEquals(session, activity.viewModel.state.value.sessionId)
            if (withinBound) assertEquals(null, activity.viewModel.state.value.reopenFailed)
            assertEquals(stored, activity.viewModel.acceptedCheckpoint)
        }
        assertEquals(stored, checkpointFile.readText())
        val visible = visiblePassages(scenario)
        assertTrue("The replacement must restore the pending B, got $visible", BACK_PASSAGE in visible)
    }

    /**
     * A persisted, B displayed; every lookup stalls past the 5 s bound, so Reopen across
     * recreation fails. The session is kept at B and the file keeps A. Returns (session, A).
     */
    private fun reopenTimesOutAcrossRecreation(scenario: ActivityScenario<EpubReaderActivity>): Pair<Long, String> {
        val session = awaitReady(scenario)
        val stored = persistAThenShowB(scenario) { delay(60_000) }
        val obsolete = onMain(scenario) { it.viewModel.activeOwner }!!
        reopenAndRecreate(scenario, obsolete, session, stored, withinBound = false)
        awaitCondition("Reopen failed after recreation") {
            onResumed { it.viewModel.state.value.reopenFailed != null } == true
        }
        requireNotNull(device.wait(Until.findObject(By.textContains("The reader was not reopened.")), 5_000))
        onMainNow { activity ->
            val state = activity.viewModel.state.value
            assertEquals(session, state.sessionId)
            assertEquals(EpubRestorationPhase.Ready, state.phase)
            assertFalse("Navigation stays deferred until Retry or Stay", state.canNavigate)
            assertEquals(stored, activity.viewModel.acceptedCheckpoint)
        }
        assertEquals(stored, checkpointFile.readText())
        assertTrue(BACK_PASSAGE in visiblePassages(scenario))
        return session to stored
    }

    /**
     * Stores passage A, installs [lookup], then shows the distant passage B: B is on screen while
     * the accepted checkpoint (and the file) still name A. Returns A's checkpoint.
     */
    private fun persistAThenShowB(
        scenario: ActivityScenario<EpubReaderActivity>,
        unavailable: Boolean = false,
        lookup: suspend (EpubOwner) -> Unit,
    ): String {
        awaitReady(scenario)
        goToPassage(scenario, 25)
        checkpointPassage(scenario)
        val stored = requireNotNull(onMain(scenario) { it.viewModel.acceptedCheckpoint })
        assertEquals(stored, checkpointFile.readText())

        val atA = onMain(scenario) { it.viewModel.adapter?.currentLocator() }
        EpubReaderTestHooks.lookup = lookup
        EpubReaderTestHooks.anchorUnavailable = unavailable
        goToPassage(scenario, BACK_PASSAGE)
        // Readium's locator can lag a jump inside one resource; B must be its reported location.
        awaitCondition("Readium reports B") { onMain(scenario) { it.viewModel.adapter?.currentLocator() } != atA }
        assertTrue(BACK_PASSAGE in visiblePassages(scenario))
        assertEquals(stored, onMain(scenario) { it.viewModel.acceptedCheckpoint })
        return stored
    }

    /**
     * B is displayed and [stored] (A) is the newest stored checkpoint. Reopen cannot save B: the
     * session is not replaced, B stays displayed, the file keeps A and the failure is shown. After
     * [clearFailure], the dialog's Retry captures B and reopens there.
     */
    private fun assertReopenFailureThenRetry(
        scenario: ActivityScenario<EpubReaderActivity>,
        stored: String,
        keepsAcceptedA: Boolean,
        clearFailure: () -> Unit,
    ) {
        val session = currentSession(scenario)
        scenario.onActivity { it.viewModel.reopen() }
        awaitCondition("Reopen failed or replaced the session") {
            onMain(scenario) { it.viewModel.state.value.let { s -> s.reopenFailed != null || s.sessionId != session } }
        }
        // A reopen that wrongly went ahead would also have replaced the session by now.
        SystemClock.sleep(1_000)
        assertEquals("Reopen must not replace the session", session, currentSession(scenario))
        requireNotNull(device.wait(Until.findObject(By.textContains("The reader was not reopened.")), 5_000)) {
            "Reopen failure dialog not shown"
        }
        onMain(scenario) { activity ->
            val state = activity.viewModel.state.value
            assertTrue(state.reopenFailed != null)
            assertEquals(session, state.sessionId)
            assertEquals(EpubRestorationPhase.Ready, state.phase)
            assertEquals(session, activity.viewModel.activeOwner?.sessionId)
            if (keepsAcceptedA) assertEquals(stored, activity.viewModel.acceptedCheckpoint)
            assertEquals(session, activity.readerFragment!!.boundSessionId)
        }
        assertTrue(BACK_PASSAGE in visiblePassages(scenario))
        assertEquals(stored, checkpointFile.readText())

        clearFailure()
        requireNotNull(device.wait(Until.findObject(By.text("Retry")), 5_000)).click()
        val reopened = awaitReady(scenario, afterSession = session)
        onMain(scenario) { assertEquals(null, it.viewModel.state.value.reopenFailed) }
        val visible = visiblePassages(scenario)
        assertTrue("Expected paragraph $BACK_PASSAGE after Retry, got $visible", BACK_PASSAGE in visible)
        val saved = checkpointFile.readText()
        assertTrue("File must name B after Retry: $saved", saved != stored)
        assertOwnedAndNavigable(scenario, reopened)
    }

    private fun noReaderActivities() =
        Stage.values().filter { it != Stage.DESTROYED }.all { readerActivities(it).isEmpty() }

    /** Runs [block] on the resumed reader, or returns `null` while none is resumed (recreation). */
    private fun <T> onResumed(block: (EpubReaderActivity) -> T): T? {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<EpubReaderActivity>()
                .singleOrNull()
                ?.let { result.set(block(it)) }
        }
        return result.get()
    }

    private val checkpointFile: File get() = File(context.cacheDir, "epub-diagnostic-checkpoints/epub3.json")

    /**
     * SavedStateHandle holds B (paragraph [BACK_PASSAGE]) while the file holds an older A (or
     * nothing). A new ViewModel restores B and, without any navigation, Back must store B.
     */
    private fun assertRestoredCheckpointPersists(deleteFile: Boolean) {
        launch("epub3") { scenario ->
            savedStateNewerThanFile(scenario, deleteFile)
            val restored = recreateViewModelFromSavedState(scenario) {}
            // The restored anchor is unchanged: no capture differs from it.
            assertEquals(restored, onMain(scenario) { it.viewModel.anchorSelector })
            device.pressBack()
            awaitCondition("Activity finished after saving", timeoutMs = 15_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    /** Stores checkpoint A, then accepts a newer B whose write fails. Returns A's file text. */
    private fun savedStateNewerThanFile(scenario: ActivityScenario<EpubReaderActivity>, deleteFile: Boolean): String {
        awaitReady(scenario)
        goToPassage(scenario, 25)
        checkpointPassage(scenario)
        val older = checkpointFile.readText()

        EpubReaderTestHooks.persist = { throw IOException("injected write failure") }
        goToPassage(scenario, BACK_PASSAGE)
        val activity = onMain(scenario) { it }
        val result = runBlocking { withContext(Dispatchers.Main) { activity.viewModel.checkpointNow() } }
        assertTrue("B must not reach the file: $result", result is EpubCheckpointWriter.FlushResult.Failure)
        val newer = onMain(scenario) { it.viewModel.acceptedCheckpoint }
        assertTrue("SavedStateHandle must hold the newer checkpoint", newer != null && newer != older)
        assertEquals(older, checkpointFile.readText())
        if (deleteFile) assertTrue(checkpointFile.delete())
        return older
    }

    /**
     * A new ViewModel whose SavedStateHandle comes from the saved instance state, as after process
     * death; [persist] becomes the write hook once the old ViewModel is cleared. Returns the anchor.
     */
    private fun recreateViewModelFromSavedState(
        scenario: ActivityScenario<EpubReaderActivity>,
        persist: suspend () -> Unit,
    ): String? {
        scenario.onActivity { it.viewModelStore.clear() }
        EpubReaderTestHooks.persist = persist
        scenario.recreate()
        awaitReady(scenario)
        val anchor = onMain(scenario) { it.viewModel.anchorSelector }
        val passage = anchor?.removePrefix("#long-p")?.toIntOrNull()
        assertTrue("New ViewModel must restore B from SavedStateHandle, got $anchor", passage != null && passage > 40)
        return anchor
    }

    /** Back competes with reopen requests; the exit must finish once, against the same session. */
    private fun assertCompetingExit(requests: (EpubReaderViewModel) -> Unit) {
        launch("epub3") { scenario ->
            val session = awaitReady(scenario)
            goToPassage(scenario, BACK_PASSAGE)
            val exits = recordExits(scenario)
            EpubReaderTestHooks.captureDelayMs = 1_500
            EpubReaderTestHooks.persist = { delay(1_500) }

            onMainNow { requests(it.viewModel) }
            awaitCondition("Activity finished after saving", timeoutMs = 15_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
            assertSingleExit(exits, session)
        }
        assertPassageInFreshActivity(BACK_PASSAGE)
    }

    private fun readerActivities(stage: Stage): List<EpubReaderActivity> {
        val result = AtomicReference<List<EpubReaderActivity>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result.set(
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                    .filterIsInstance<EpubReaderActivity>(),
            )
        }
        return result.get()
    }

    /** Every (exit, session) state the ViewModel publishes from now on; survives recreation. */
    private fun recordExits(
        scenario: ActivityScenario<EpubReaderActivity>,
    ): MutableList<Pair<EpubReaderViewModel.Exit, Long>> {
        val seen = Collections.synchronizedList(mutableListOf<Pair<EpubReaderViewModel.Exit, Long>>())
        scenario.onActivity { activity ->
            activity.viewModel.viewModelScope.launch(Dispatchers.Main.immediate) {
                activity.viewModel.state.collect { seen += it.exit to it.sessionId }
            }
        }
        return seen
    }

    private fun assertSingleExit(exits: MutableList<Pair<EpubReaderViewModel.Exit, Long>>, session: Long) {
        val snapshot = synchronized(exits) { exits.toList() }
        val transitions = snapshot.map { it.first }.fold(mutableListOf<EpubReaderViewModel.Exit>()) { acc, exit ->
            acc.apply { if (lastOrNull() != exit) add(exit) }
        }
        assertEquals(
            "Exit transitions $snapshot",
            listOf(EpubReaderViewModel.Exit.Idle, EpubReaderViewModel.Exit.Saving, EpubReaderViewModel.Exit.Done),
            transitions,
        )
        assertTrue("Session changed during exit: $snapshot", snapshot.all { it.second == session })
    }

    /** The page navigated to before Back (containing [expected]) is restored from the file. */
    private fun assertPassageInFreshActivity(expected: Int) {
        EpubReaderTestHooks.reset()
        assertTrue(File(context.cacheDir, "epub-diagnostic-checkpoints/epub3.json").isFile)
        launch("epub3") { scenario ->
            awaitReady(scenario)
            val anchor = onMain(scenario) { it.viewModel.anchorSelector }
            val anchored = anchor?.removePrefix("#long-p")?.toIntOrNull()
            val visible = visiblePassages(scenario)
            assertTrue("Restored anchor $anchor must be visible: $visible", anchored != null && anchored in visible)
            assertTrue("Expected paragraph $expected visible in a fresh Activity, got $visible", expected in visible)
        }
    }

    private inline fun <reified T : EpubReaderViewModel.Exit> awaitExit(
        scenario: ActivityScenario<EpubReaderActivity>,
        allowDestroyed: Boolean = false,
    ) {
        awaitCondition("exit state ${T::class.simpleName}") {
            (allowDestroyed && scenario.state == Lifecycle.State.DESTROYED) ||
                onMain(scenario) { it.viewModel.state.value.exit is T }
        }
    }

    /**
     * Like [onMain], but without `ActivityScenario`'s wait for an idle main thread, which never
     * comes on slow devices while the busy spinner animates.
     */
    private fun <T> onMainNow(block: (EpubReaderActivity) -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<EpubReaderActivity>()
                .single()
            result.set(block(activity))
        }
        return result.get()
    }

    private fun awaitPhase(scenario: ActivityScenario<EpubReaderActivity>, phase: EpubRestorationPhase) {
        awaitCondition("phase $phase with a bound navigator") {
            onMainNow {
                it.viewModel.state.value.phase == phase &&
                    it.viewModel.adapter?.owns(it.readerFragment?.navigatorFragment) == true
            }
        }
    }

    /** Buttons, ToC, tap, swipe and keyboard navigation while the reader is busy. */
    private fun attemptAllNavigation(scenario: ActivityScenario<EpubReaderActivity>) {
        onMainNow { activity ->
            val viewModel = activity.viewModel
            assertFalse(activity.viewModel.state.value.canNavigate)
            assertFalse(viewModel.next())
            assertFalse(viewModel.previous())
            assertFalse(viewModel.goToToc(viewModel.state.value.tableOfContents.first().id))
        }
        swipeForward(scenario)
        tapAndKeyForward(scenario)
        withoutIdleWait {
            device.pressKeyCode(KeyEvent.KEYCODE_PAGE_DOWN)
            device.pressKeyCode(KeyEvent.KEYCODE_SPACE)
        }
    }

    /** Right-edge tap and DPAD-right: both turn the page through `DirectionalNavigationAdapter`. */
    private fun tapAndKeyForward(scenario: ActivityScenario<EpubReaderActivity>) {
        val area = readerArea(scenario)
        withoutIdleWait {
            device.click(area.left + area.width() * 9 / 10, area.centerY())
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    private fun readerArea(scenario: ActivityScenario<EpubReaderActivity>): Rect = onMainNow { activity ->
        Rect().also { activity.findViewById<View>(R.id.epub_reader_container).getGlobalVisibleRect(it) }
    }

    /** Swipe inside the reader, away from the edges: an edge swipe is the system Back gesture. */
    private fun swipeForward(scenario: ActivityScenario<EpubReaderActivity>) {
        val area = readerArea(scenario)
        val y = area.centerY()
        withoutIdleWait { device.swipe(area.right - area.width() / 10, y, area.left + area.width() / 10, y, 5) }
    }

    /**
     * Every on-screen WebView of the navigator lies between the header and the footer, so its
     * `window.innerHeight` (used by the restoration visibility checks) is the readable area.
     */
    private fun assertUnobscured(scenario: ActivityScenario<EpubReaderActivity>, name: String) {
        val navigatorArea = Rect()
        scenario.onActivity { activity ->
            val header = Rect().also { activity.header.getGlobalVisibleRect(it) }
            val footer = Rect().also { activity.footer.getGlobalVisibleRect(it) }
            val navigator = requireNotNull(activity.readerFragment?.navigatorFragment?.view)
            assertTrue(navigator.getGlobalVisibleRect(navigatorArea))
            assertTrue("$name: navigator $navigatorArea below header $header", navigatorArea.top >= header.bottom)
            assertTrue("$name: navigator $navigatorArea above footer $footer", navigatorArea.bottom <= footer.top)
            val webViews = webViews(navigator).mapNotNull { web -> Rect().takeIf { web.getGlobalVisibleRect(it) } }
            assertTrue("$name: no visible WebView", webViews.isNotEmpty())
            webViews.forEach { rect ->
                assertFalse("$name: WebView $rect under header $header", Rect.intersects(rect, header))
                assertFalse("$name: WebView $rect under footer $footer", Rect.intersects(rect, footer))
                assertTrue("$name: WebView $rect outside $navigatorArea", navigatorArea.contains(rect))
            }
        }
        evidence("viewport-$name-api${Build.VERSION.SDK_INT}")
    }

    /** UiAutomator input otherwise waits for an idle UI and event acknowledgment; the spinner never idles. */
    private fun withoutIdleWait(block: () -> Unit) {
        val configurator = Configurator.getInstance()
        val idle = configurator.waitForIdleTimeout
        val acknowledgment = configurator.actionAcknowledgmentTimeout
        configurator.waitForIdleTimeout = 0
        configurator.actionAcknowledgmentTimeout = 0
        try {
            block()
        } finally {
            configurator.waitForIdleTimeout = idle
            configurator.actionAcknowledgmentTimeout = acknowledgment
        }
    }

    /** Screenshot kept outside the app (which is uninstalled after the run) for `adb pull`. */
    private fun evidence(name: String) {
        device.executeShellCommand("mkdir -p $EVIDENCE_DIR")
        device.executeShellCommand("screencap -p $EVIDENCE_DIR/$name.png")
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun launch(id: String, block: (ActivityScenario<EpubReaderActivity>) -> Unit) {
        ActivityScenario.launch<EpubReaderActivity>(EpubReaderActivity.newIntent(context, reference(id)))
            .use(block)
    }

    private fun <T> onMain(scenario: ActivityScenario<EpubReaderActivity>, block: (EpubReaderActivity) -> T): T {
        val result = AtomicReference<T>()
        scenario.onActivity { result.set(block(it)) }
        return result.get()
    }

    private fun awaitCondition(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    private fun currentSession(scenario: ActivityScenario<EpubReaderActivity>): Long =
        onMain(scenario) { it.viewModel.state.value.sessionId }

    /**
     * Rendered readiness: Ready, the gate's owner belongs to the active session, and the host's
     * child navigator is the instance created by that owner's binding.
     */
    private fun awaitReady(
        scenario: ActivityScenario<EpubReaderActivity>,
        afterSession: Long = 0,
        timeoutMs: Long = 20_000,
    ): Long {
        awaitCondition("rendered Ready state after session $afterSession", timeoutMs) {
            onMain(scenario) { activity ->
                val state = activity.viewModel.state.value
                check(state.phase != EpubRestorationPhase.Error) { "Reader failed: ${state.error}" }
                state.phase == EpubRestorationPhase.Ready &&
                    state.sessionId > afterSession &&
                    activity.viewModel.activeOwner?.sessionId == state.sessionId &&
                    activity.viewModel.adapter?.owns(activity.readerFragment?.navigatorFragment) == true
            }
        }
        return currentSession(scenario)
    }

    /** Runs [change] and waits for a new Activity instance to reach rendered readiness. */
    private fun awaitRecreatedReady(scenario: ActivityScenario<EpubReaderActivity>, change: () -> Unit) {
        val before = onMain(scenario) { System.identityHashCode(it) }
        change()
        awaitCondition("Activity recreation") { onMain(scenario) { System.identityHashCode(it) } != before }
        awaitReady(scenario)
    }

    private fun visibleAnchors(scenario: ActivityScenario<EpubReaderActivity>): List<String> {
        val activity = onMain(scenario) { it }
        return runBlocking {
            withContext(Dispatchers.Main) { activity.viewModel.adapter?.visibleAnchors().orEmpty() }
        }
    }

    private fun visiblePassages(scenario: ActivityScenario<EpubReaderActivity>): List<Int> =
        visibleAnchors(scenario).mapNotNull { it.removePrefix("long-p").takeIf { n -> n != it }?.toIntOrNull() }

    private fun goToPassage(scenario: ActivityScenario<EpubReaderActivity>, paragraph: Int) {
        val id = "long-p%03d".format(paragraph)
        scenario.onActivity { assertTrue(it.viewModel.adapter!!.goToElement("long.xhtml", id)) }
        awaitCondition("paragraph $id visible and reported by Readium") {
            visibleAnchors(scenario).contains(id) &&
                onMain(scenario) { it.viewModel.adapter?.currentLocator()?.href.toString().endsWith("long.xhtml") }
        }
    }

    /** Checkpoints and returns the anchored paragraph number of the long section. */
    private fun checkpointPassage(scenario: ActivityScenario<EpubReaderActivity>): Int {
        val activity = onMain(scenario) { it }
        val result = runBlocking { withContext(Dispatchers.Main) { activity.viewModel.checkpointNow() } }
        assertEquals(EpubCheckpointWriter.FlushResult.Success, result)
        val checkpoint = onMain(scenario) { it.viewModel.acceptedCheckpoint }.orEmpty()
        assertTrue("Checkpoint must name the long resource and an element: $checkpoint", "long.xhtml" in checkpoint)
        assertTrue("Checkpoint must carry an element anchor: $checkpoint", "cssSelector" in checkpoint)
        val anchor = onMain(scenario) { it.viewModel.anchorSelector }
        val passage = anchor?.removePrefix("#long-p")?.toIntOrNull()
        assertTrue("Expected an anchor far from the section start: $anchor", passage != null && passage > 10)
        assertTrue("Anchor $anchor must be visible", passage in visiblePassages(scenario))
        return passage!!
    }

    /**
     * The passage, not just the resource, must be on screen again: the exact element anchored by
     * the newest accepted checkpoint is visible, and it stays within a page of the original
     * passage (each relayout re-anchors to the first visible paragraph of the restored page).
     */
    private fun assertPassage(scenario: ActivityScenario<EpubReaderActivity>, expected: Int, phase: String) {
        val anchor = onMain(scenario) { it.viewModel.anchorSelector }
        val visible = visiblePassages(scenario)
        assertEquals("Restored anchor $phase", "#long-p%03d".format(expected), anchor)
        assertTrue("Expected paragraph $expected visible $phase, got $visible", expected in visible)
    }

    private fun assertOwnedAndNavigable(
        scenario: ActivityScenario<EpubReaderActivity>,
        session: Long,
        expectedPassage: Int? = null,
    ) {
        scenario.onActivity { activity ->
            val adapter = requireNotNull(activity.viewModel.adapter)
            assertEquals(session, adapter.sessionId)
            assertEquals(session, activity.readerFragment!!.boundSessionId)
            assertTrue(adapter.owns(activity.readerFragment!!.navigatorFragment))
        }
        expectedPassage?.let { assertPassage(scenario, it, "after process restoration") }
        // Navigation must actually work on the new navigator.
        val toc = onMain(scenario) { activity -> activity.viewModel.state.value.tableOfContents.last().id }
        scenario.onActivity { assertTrue(it.viewModel.goToToc(toc) == true) }
        awaitCondition("ToC navigation to the long section") { visibleAnchors(scenario).contains("long-p001") }
        scenario.onActivity { assertTrue(it.viewModel.next() == true) }
        awaitCondition("next page") { "long-p001" !in visibleAnchors(scenario) }
    }

    private fun assertStaleEventsIgnored(
        scenario: ActivityScenario<EpubReaderActivity>,
        staleOwner: EpubOwner,
        staleLocator: Locator,
    ) {
        scenario.onActivity { activity ->
            val viewModel = activity.viewModel
            val before = viewModel.state.value
            val checkpoint = viewModel.acceptedCheckpoint
            val current = viewModel.adapter!!.currentLocator()!!
            // Same href as the current location, but an obsolete owner.
            viewModel.onNavigatorEvent(staleOwner, ReadiumEpubReaderAdapter.Event.LocatorChanged(current))
            viewModel.onNavigatorEvent(staleOwner, ReadiumEpubReaderAdapter.Event.LocatorChanged(staleLocator))
            viewModel.onNavigatorEvent(staleOwner, ReadiumEpubReaderAdapter.Event.PageRendered)
            viewModel.onNavigatorEvent(staleOwner, ReadiumEpubReaderAdapter.Event.ResourceFailed("stale"))
            assertEquals(before, viewModel.state.value)
            assertEquals(checkpoint, viewModel.acceptedCheckpoint)
        }
    }

    private suspend fun open(id: String): EpubPublicationSession {
        val result = EpubPublicationOpener(context).open(reference(id))
        return (result as EpubPublicationOpener.Result.Success).session
    }

    private fun reference(id: String): EpubFixtureReference {
        val root = JSONObject(context.assets.open("epub-fixtures/fixtures.json").bufferedReader().use { it.readText() })
        val array = root.getJSONArray("fixtures")
        val item = (0 until array.length())
            .map(array::getJSONObject)
            .first { it.getString("id") == id }
        return EpubFixtureReference(
            id = id,
            assetPath = item.getString("assetPath"),
            expectedSha256 = item.getString("sha256"),
        )
    }

    // endregion

    private companion object {
        const val TIMING_TAG = "EpubOpenTiming"
        const val BACK_PASSAGE = 50

        /** Read after Stay on a failed Reopen of [BACK_PASSAGE]; far enough from it to tell apart. */
        const val STAY_PASSAGE = 35

        /** Restoration/reflow hold; UiAutomator input on the API 37 AVD takes about 12 s. */
        const val BUSY_WINDOW_MS = 20_000L
        const val EVIDENCE_DIR = "/data/local/tmp/epub-evidence"
    }
}

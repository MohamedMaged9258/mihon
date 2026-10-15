package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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

    // region Helpers

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
    private fun awaitReady(scenario: ActivityScenario<EpubReaderActivity>, afterSession: Long = 0): Long {
        awaitCondition("rendered Ready state after session $afterSession") {
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
    }
}

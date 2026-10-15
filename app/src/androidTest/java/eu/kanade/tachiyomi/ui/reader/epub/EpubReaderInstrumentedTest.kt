package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class EpubReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun opensEpub2AndEpub3WithExpectedToc() = runBlocking {
        val epub2 = open("epub2")
        assertEquals("Mihon EPUB 2 Fixture", epub2.publication.metadata.title)
        assertEquals(3, epub2.publication.tableOfContents.size)
        epub2.close()

        val epub3 = open("epub3")
        assertEquals("Mihon EPUB 3 Arabic Fixture", epub3.publication.metadata.title)
        assertEquals(2, epub3.publication.tableOfContents.size)
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
    fun activityRecreationReplacesNavigatorWithoutFactoryFailure() {
        ActivityScenario.launch<EpubReaderActivity>(
            EpubReaderActivity.newIntent(context, reference("epub3")),
        ).use { scenario ->
            SystemClock.sleep(1_500)
            scenario.recreate()
            SystemClock.sleep(1_500)
            scenario.onActivity { activity ->
                assertTrue(activity.viewModel.state.value.error == null)
            }
        }
    }

    @Test
    fun tocFontReflowAndRepeatedSessionRebuildStayOnRequestedResource() {
        ActivityScenario.launch<EpubReaderActivity>(
            EpubReaderActivity.newIntent(context, reference("epub3")),
        ).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val nested = activity.viewModel.state.value.tableOfContents.first { it.depth == 1 }
                assertTrue(activity.viewModel.goToToc(nested.id) == true)
            }
            awaitResource(scenario, "nested.xhtml")
            scenario.onActivity { it.viewModel.changeFontSize(0.1) }
            awaitReady(scenario)

            val href = AtomicReference<String>()
            scenario.onActivity { activity ->
                href.set(activity.viewModel.adapter?.currentLocator()?.href?.toString())
                assertEquals(1.1, activity.viewModel.state.value.preferences.fontSizeOverride ?: 0.0, 0.001)
                repeat(3) { activity.viewModel.reopen() }
            }
            assertTrue(href.get().contains("nested.xhtml"))
            awaitReady(scenario)
            scenario.onActivity { assertTrue(it.viewModel.state.value.sessionGeneration >= 2) }
        }
    }

    @Test
    fun externalFixtureNeverReachesLoopbackSocket() {
        ServerSocket(38080).use { trap ->
            trap.soTimeout = 2_000
            ActivityScenario.launch<EpubReaderActivity>(
                EpubReaderActivity.newIntent(context, reference("external")),
            ).use {
                assertThrows(SocketTimeoutException::class.java) { trap.accept().use { socket -> socket.close() } }
            }
        }
    }

    @Test
    fun measuresSingleFixtureOpen() {
        val startedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch<EpubReaderActivity>(
            EpubReaderActivity.newIntent(context, reference("epub3")),
        ).use { scenario ->
            awaitReady(scenario)
            Log.i(TIMING_TAG, "cold_open_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }

    @Test
    fun measuresWarmFixtureReopens() {
        ActivityScenario.launch<EpubReaderActivity>(
            EpubReaderActivity.newIntent(context, reference("epub3")),
        ).use { scenario ->
            awaitReady(scenario)
            repeat(5) { sample ->
                val generation = AtomicReference(0L)
                scenario.onActivity { activity ->
                    generation.set(activity.viewModel.state.value.sessionGeneration)
                }
                val startedAt = SystemClock.elapsedRealtime()
                scenario.onActivity { it.viewModel.reopen() }
                awaitGeneration(scenario, generation.get() + 1)
                Log.i(TIMING_TAG, "warm_open_${sample + 1}_ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
    }

    private suspend fun open(id: String): EpubPublicationSession {
        val result = EpubPublicationOpener(context).open(reference(id))
        return (result as EpubPublicationOpener.Result.Success).session
    }

    private fun awaitReady(scenario: ActivityScenario<EpubReaderActivity>) {
        repeat(30) {
            val ready = AtomicReference(false)
            scenario.onActivity { ready.set(it.viewModel.state.value.phase == EpubRestorationPhase.Ready) }
            if (ready.get()) return
            SystemClock.sleep(200)
        }
        scenario.onActivity { assertEquals(EpubRestorationPhase.Ready, it.viewModel.state.value.phase) }
    }

    private fun awaitResource(scenario: ActivityScenario<EpubReaderActivity>, expectedHref: String) {
        repeat(50) {
            val reached = AtomicReference(false)
            scenario.onActivity { activity ->
                val href = activity.viewModel.adapter?.currentLocator()?.href?.toString()
                reached.set(href?.contains(expectedHref) == true)
            }
            if (reached.get()) return
            SystemClock.sleep(100)
        }
        scenario.onActivity {
            val href = it.viewModel.adapter?.currentLocator()?.href?.toString()
            assertTrue("Expected $expectedHref, got $href", href?.contains(expectedHref) == true)
        }
    }

    private fun awaitGeneration(scenario: ActivityScenario<EpubReaderActivity>, expected: Long) {
        repeat(50) {
            val reached = AtomicReference(false)
            scenario.onActivity { activity ->
                val state = activity.viewModel.state.value
                reached.set(state.sessionGeneration >= expected && state.phase == EpubRestorationPhase.Ready)
            }
            if (reached.get()) return
            SystemClock.sleep(100)
        }
        scenario.onActivity {
            assertTrue(
                "Expected generation $expected in Ready, got ${it.viewModel.state.value}",
                it.viewModel.state.value.sessionGeneration >= expected &&
                    it.viewModel.state.value.phase == EpubRestorationPhase.Ready,
            )
        }
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

    private companion object {
        const val TIMING_TAG = "EpubOpenTiming"
    }
}

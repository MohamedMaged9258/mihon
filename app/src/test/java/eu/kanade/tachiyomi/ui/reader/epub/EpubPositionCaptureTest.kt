package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class EpubPositionCaptureTest {
    private val owner = EpubOwner(sessionId = 1, bindingId = 1)

    /**
     * Anchor lookups that ignore cancellation, like Readium's `suspendCoroutine`-based WebView
     * calls: each stays pending until the test resumes it, even after its job was cancelled.
     */
    private inner class Harness(scope: CoroutineScope) {
        val gate = EpubCheckpointGate().apply {
            beginSession(owner.sessionId)
            attach(owner)
            rendered(owner)
        }
        var shown: String? = "s1/p1"
        val accepted = mutableListOf<String>()
        private val pending = mutableListOf<Pair<String, Continuation<String?>>>()
        val capture = EpubPositionCapture<String>(
            scope = scope,
            gate = gate,
            lookup = { _, location -> suspendCoroutine { pending += location to it } },
            currentLocation = { shown },
            onAccepted = { accepted += it },
        )

        /** Navigator now shows [location] and reports it (an automatic capture request). */
        fun show(location: String) {
            shown = location
            capture.request(owner, location)
        }

        fun complete(index: Int) {
            val (location, continuation) = pending[index]
            continuation.resume("$location#anchor")
        }

        fun fail(index: Int) = pending[index].second.resumeWithException(IOException("lookup failed"))
    }

    private fun TestScope.harness() = Harness(backgroundScope)

    @Test
    fun `an older lookup finishing after a newer one does not overwrite it`() = runTest {
        val h = harness()
        h.show("s1/p1") // A
        runCurrent()
        h.show("s1/p2") // B
        runCurrent()
        h.complete(1)
        runCurrent()
        assertEquals(listOf("s1/p2#anchor"), h.accepted)

        h.complete(0) // A resumes despite its cancellation.
        runCurrent()
        assertEquals(listOf("s1/p2#anchor"), h.accepted)
    }

    @Test
    fun `a pending lookup for a section that is no longer shown is rejected`() = runTest {
        val h = harness()
        h.show("s1/p9")
        runCurrent()
        // Section changes while the previous section's WebView response is pending; the new
        // locator has not been delivered yet, so this capture is not superseded by a token.
        h.shown = "s2/p1"
        h.complete(0)
        runCurrent()
        assertEquals(emptyList<String>(), h.accepted)

        h.show("s2/p1")
        runCurrent()
        h.complete(1)
        runCurrent()
        assertEquals(listOf("s2/p1#anchor"), h.accepted)
    }

    @Test
    fun `a lookup started before a reflow cannot complete after it`() = runTest {
        val h = harness()
        h.show("s1/p1")
        runCurrent()
        h.complete(0)
        runCurrent()
        h.show("s1/p5")
        runCurrent()

        val reflow = requireNotNull(h.gate.beginOperation(owner, EpubRestorationPhase.Reflowing))
        assertTrue(h.gate.completeOperation(owner, reflow, success = true))
        // The job was not cancelled here: the token alone rejects it.
        h.complete(1)
        runCurrent()
        assertEquals(listOf("s1/p1#anchor"), h.accepted)
    }

    @Test
    fun `overlapping manual and automatic captures keep the newest and await it`() = runTest {
        val h = harness()
        h.capture.request(owner, "s1/p1") // manual checkpoint
        runCurrent()
        val awaited = async { h.capture.awaitLatest() }
        runCurrent()
        h.show("s1/p2") // automatic capture arrives while the manual one is pending
        runCurrent()

        h.complete(0)
        runCurrent()
        assertEquals(emptyList<String>(), h.accepted)
        assertFalse(awaited.isCompleted, "A checkpoint must wait for the capture that superseded it")

        h.complete(1)
        runCurrent()
        assertEquals(listOf("s1/p2#anchor"), h.accepted)
        assertTrue(awaited.isCompleted)

        // Reverse order: an automatic capture is superseded by a later manual one.
        h.show("s1/p3")
        runCurrent()
        h.capture.request(owner, "s1/p3")
        runCurrent()
        h.complete(3)
        runCurrent()
        h.complete(2)
        runCurrent()
        assertEquals(listOf("s1/p2#anchor", "s1/p3#anchor"), h.accepted)
    }

    @Test
    fun `rebinding or closing while a lookup is pending rejects it`() = runTest {
        val h = harness()
        h.show("s1/p1")
        runCurrent()
        val rebound = EpubOwner(sessionId = 1, bindingId = 2)
        assertTrue(h.gate.attach(rebound))
        assertTrue(h.gate.rendered(rebound))
        h.complete(0)
        runCurrent()

        h.capture.request(rebound, "s1/p1")
        runCurrent()
        h.gate.close()
        h.complete(1)
        runCurrent()
        assertEquals(emptyList<String>(), h.accepted)
    }

    @Test
    fun `a genuine lookup failure is a controlled miss`() = runTest {
        val h = harness()
        h.show("s1/p1")
        runCurrent()
        h.fail(0)
        runCurrent()
        assertEquals(emptyList<String>(), h.accepted)

        h.show("s1/p2")
        runCurrent()
        h.complete(1)
        runCurrent()
        assertEquals(listOf("s1/p2#anchor"), h.accepted)
    }

    @Test
    fun `captures are not started outside Ready`() = runTest {
        val h = harness()
        val operation = requireNotNull(h.gate.beginOperation(owner, EpubRestorationPhase.Restoring))
        h.show("s1/p1")
        runCurrent()
        h.capture.awaitLatest()
        assertTrue(h.gate.completeOperation(owner, operation, success = true))
        assertEquals(emptyList<String>(), h.accepted)
    }
}

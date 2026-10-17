package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class EpubCheckpointWriterTest {
    @Test
    fun `accepted state is immediate and disk writes are ordered and coalesced`() = runTest {
        val accepted = mutableListOf<String>()
        val persisted = mutableListOf<String>()
        val writer = EpubCheckpointWriter(
            scope = backgroundScope,
            onAccepted = { accepted.add(it) },
            persist = { persisted.add(it) },
            coalesceMillis = 100,
        )

        writer.accept("first")
        writer.accept("second")
        assertEquals(listOf("first", "second"), accepted)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("second"), persisted)
    }

    @Test
    fun `flush skips the delay and persists newest value once`() = runTest {
        val persisted = mutableListOf<String>()
        val writer = EpubCheckpointWriter(backgroundScope, {}, { persisted.add(it) }, coalesceMillis = 10_000)
        writer.accept("old")
        writer.accept("new")
        advanceTimeBy(1)
        assertEquals(EpubCheckpointWriter.FlushResult.Success, writer.flush())
        assertEquals(listOf("new"), persisted)
        assertEquals(EpubCheckpointWriter.FlushResult.Success, writer.flush())
        assertEquals(listOf("new"), persisted)
    }

    @Test
    fun `slow cancellation-resistant write cannot finish after a newer write`() = runTest {
        val release = CompletableDeferred<Unit>()
        var stored: String? = null
        var inFlight = 0
        var maxInFlight = 0
        val started = mutableListOf<String>()
        val writer = EpubCheckpointWriter(
            scope = backgroundScope,
            onAccepted = {},
            persist = { value ->
                started += value
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                // Simulates blocking I/O that ignores cancellation.
                withContext(NonCancellable) { if (value == "old") release.await() }
                stored = value
                inFlight--
            },
            coalesceMillis = 10,
        )

        writer.accept("old")
        advanceTimeBy(11)
        runCurrent()
        assertEquals(listOf("old"), started)

        writer.accept("new")
        val flush = async { writer.flush() }
        advanceTimeBy(1_000)
        runCurrent()
        // The newer value waits for the in-flight write instead of racing it.
        assertEquals(listOf("old"), started)
        assertFalse(flush.isCompleted)

        release.complete(Unit)
        assertEquals(EpubCheckpointWriter.FlushResult.Success, flush.await())
        assertEquals(listOf("old", "new"), started)
        assertEquals("new", stored)
        assertEquals(1, maxInFlight)
    }

    @Test
    fun `flush waits for values accepted while another write is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val writer = EpubCheckpointWriter(
            backgroundScope,
            {},
            { value ->
                if (value == "a") gate.await()
                persisted += value
            },
            coalesceMillis = 10_000,
        )

        writer.accept("a")
        val first = async { writer.flush() }
        runCurrent()
        writer.accept("b")
        val second = async { writer.flush() }
        runCurrent()
        assertFalse(first.isCompleted)

        gate.complete(Unit)
        assertEquals(EpubCheckpointWriter.FlushResult.Success, first.await())
        assertEquals(EpubCheckpointWriter.FlushResult.Success, second.await())
        assertEquals(listOf("a", "b"), persisted)
    }

    @Test
    fun `persistence failure is reported and retried without crashing the scope`() = runTest {
        var fail = true
        val persisted = mutableListOf<String>()
        val writer = EpubCheckpointWriter(
            backgroundScope,
            {},
            { value ->
                if (fail) throw IOException("disk full")
                persisted += value
            },
            coalesceMillis = 10,
        )

        writer.accept("value")
        val failed = writer.flush()
        assertInstanceOf(EpubCheckpointWriter.FlushResult.Failure::class.java, failed)
        // No timer-driven retry loop after a failure.
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(persisted.isEmpty())

        fail = false
        assertEquals(EpubCheckpointWriter.FlushResult.Success, writer.flush())
        assertEquals(listOf("value"), persisted)
    }
}

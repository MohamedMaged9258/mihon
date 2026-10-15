package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
        advanceUntilIdle()
        assertEquals(listOf("second"), persisted)
    }

    @Test
    fun `flush cancels delay and persists newest value once`() = runTest {
        val persisted = mutableListOf<String>()
        val writer = EpubCheckpointWriter(backgroundScope, {}, { persisted.add(it) }, coalesceMillis = 10_000)
        writer.accept("old")
        writer.accept("new")
        advanceTimeBy(1)
        writer.flush()
        assertEquals(listOf("new"), persisted)
    }
}

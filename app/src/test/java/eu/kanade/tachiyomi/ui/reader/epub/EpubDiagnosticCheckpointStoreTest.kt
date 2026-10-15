package eu.kanade.tachiyomi.ui.reader.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EpubDiagnosticCheckpointStoreTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `missing then atomically replaced checkpoint`() {
        val store = EpubDiagnosticCheckpointStore(directory)
        assertEquals(EpubDiagnosticCheckpointStore.ReadResult.Missing, store.read("epub3"))
        store.write("epub3", "first")
        store.write("epub3", "second")
        assertEquals(EpubDiagnosticCheckpointStore.ReadResult.Found("second"), store.read("epub3"))
        // No temporary files are left behind.
        assertEquals(listOf("epub3.json"), directory.list()!!.toList())
    }

    @Test
    fun `oversize file is rejected before reading`() {
        File(directory, "epub3.json").writeText("x".repeat(EpubLocatorCodec.MAX_ENVELOPE_BYTES + 1))
        assertEquals(
            EpubDiagnosticCheckpointStore.ReadResult.Unreadable(EpubRecoveryReason.OversizeEnvelope),
            EpubDiagnosticCheckpointStore(directory).read("epub3"),
        )
    }

    @Test
    fun `corrupt content is returned for codec validation`() {
        File(directory, "epub3.json").writeText("{not json")
        val found = EpubDiagnosticCheckpointStore(
            directory,
        ).read("epub3") as EpubDiagnosticCheckpointStore.ReadResult.Found
        assertEquals(
            EpubEnvelopeValidator.Result.Invalid(EpubRecoveryReason.MalformedEnvelope),
            EpubEnvelopeValidator.validate(found.encoded, "epub3", "abc"),
        )
    }

    @Test
    fun `target ids cannot escape the directory`() {
        val store = EpubDiagnosticCheckpointStore(directory)
        assertThrows(IllegalArgumentException::class.java) { store.read("../epub3") }
        assertThrows(IllegalArgumentException::class.java) { store.write("a/b", "x") }
    }
}

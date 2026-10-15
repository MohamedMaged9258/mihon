package eu.kanade.tachiyomi.ui.reader.epub

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Diagnostic close-and-open checkpoint storage for P01-01 fixtures only.
 *
 * This is deliberately not the durable production reading-state repository planned for P01-04:
 * it lives in the cache directory, is keyed by fixture id and may be discarded by the system.
 * Content validation (version, target, fingerprint, resource membership) belongs to
 * [EpubLocatorCodec.decode]; this class only bounds and atomically replaces the bytes.
 */
internal class EpubDiagnosticCheckpointStore(private val directory: File) {
    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Found(val encoded: String) : ReadResult
        data class Unreadable(val reason: EpubRecoveryReason) : ReadResult
    }

    fun read(targetId: String): ReadResult {
        val file = fileFor(targetId)
        if (!file.isFile) return ReadResult.Missing
        if (file.length() > EpubLocatorCodec.MAX_ENVELOPE_BYTES) {
            return ReadResult.Unreadable(EpubRecoveryReason.OversizeEnvelope)
        }
        return runCatching { ReadResult.Found(file.readText()) }
            .getOrElse { ReadResult.Unreadable(EpubRecoveryReason.MalformedEnvelope) }
    }

    /** Replaces the stored checkpoint atomically; a failed write keeps the previous valid file. */
    fun write(targetId: String, encoded: String) {
        val file = fileFor(targetId)
        directory.mkdirs()
        val temp = File.createTempFile("checkpoint-", ".tmp", directory)
        try {
            temp.outputStream().use { output ->
                output.write(encoded.toByteArray())
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }

    private fun fileFor(targetId: String): File {
        require(ID_PATTERN.matches(targetId)) { "Invalid diagnostic target id" }
        return File(directory, "$targetId.json")
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

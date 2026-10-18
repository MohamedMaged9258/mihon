package eu.kanade.tachiyomi.data.epub

import tachiyomi.domain.epub.model.AssetOwnership
import tachiyomi.domain.epub.model.PublicationMetadata

/**
 * Typed failures of the import, registration and opening services. Messages are for diagnostics and never
 * contain document URIs, publication text or locator context.
 */
sealed interface EpubImportError {
    val detail: String

    data class PermissionDenied(override val detail: String = "Access to the document was denied") : EpubImportError
    data class SourceMissing(override val detail: String = "The document no longer exists") : EpubImportError
    data class ReadFailed(override val detail: String) : EpubImportError
    data class StorageUnavailable(override val detail: String = "Library storage is unavailable") : EpubImportError
    data class StorageChanged(override val detail: String = "Library storage changed during the import") :
        EpubImportError
    data class InsufficientSpace(override val detail: String = "Not enough storage space") : EpubImportError
    data class TooLarge(override val detail: String) : EpubImportError
    data class Malformed(override val detail: String) : EpubImportError
    data class UnsupportedFormat(override val detail: String) : EpubImportError
    data class UnsupportedProtection(override val detail: String) : EpubImportError
    data class FingerprintMismatch(override val detail: String = "The file no longer matches its recorded bytes") :
        EpubImportError
    data class CommitFailed(override val detail: String) : EpubImportError
    data class RecoveryFailed(override val detail: String) : EpubImportError
}

/** Failure carried through the pipeline as an exception, so `finally` cleanup runs on every path. */
internal class EpubImportException(val error: EpubImportError) : Exception(error.detail)

internal fun fail(error: EpubImportError): Nothing = throw EpubImportException(error)

/** One result per input document, in input order by [index]. */
sealed interface EpubImportResult {
    val index: Int

    /**
     * A managed copy and its shared records were committed. [reusedKey] is true when an earlier, deliberately
     * removed import of the same bytes was registered again under its original key.
     */
    data class Imported(
        override val index: Int,
        val mangaId: Long,
        val chapterId: Long,
        val assetId: Long,
        val importKey: String,
        val fingerprint: String,
        val metadata: PublicationMetadata,
        val reusedKey: Boolean,
        val userOwnedCopies: Int,
    ) : EpubImportResult

    /** The same bytes are already a managed import; nothing was copied. */
    data class Duplicate(
        override val index: Int,
        val mangaId: Long,
        val chapterId: Long,
        val assetId: Long,
        val fingerprint: String,
        val ownership: AssetOwnership,
    ) : EpubImportResult

    /** The document is not an EPUB this build can import (malformed, too large, protected or another format). */
    data class Unsupported(override val index: Int, val error: EpubImportError) : EpubImportResult

    data class Failed(override val index: Int, val error: EpubImportError) : EpubImportResult

    /** Canceled before its file commit; nothing of this input is discoverable. */
    data class Canceled(override val index: Int) : EpubImportResult
}

/**
 * Baseline limits for untrusted EPUB input. Chosen to admit large illustrated books while bounding the work a
 * hostile archive can cause; broader hostile-corpus tuning is P01-06.
 */
data class EpubContentLimits(
    val maxInputBytes: Long = 512L * MIB,
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 64L * MIB,
    val maxTotalBytes: Long = 1024L * MIB,
    val maxCompressionRatio: Long = 200,
    val compressionRatioFloorBytes: Long = MIB,
    val maxTitleChars: Int = 1024,
    val maxDescriptionChars: Int = 16 * 1024,
    val maxFieldChars: Int = 512,
    val maxAuthors: Int = 32,
    val maxCoverBytes: Long = 10L * MIB,
    val maxCoverSidePixels: Int = 10_000,
    val coverTargetPixels: Int = 1024,
    val maxSampledDocuments: Int = 10,
    val maxSampledDocumentBytes: Long = 64L * 1024,
) {
    companion object {
        const val MIB = 1024L * 1024
        val DEFAULT = EpubContentLimits()
    }
}

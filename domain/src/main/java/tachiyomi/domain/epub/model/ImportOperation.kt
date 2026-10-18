package tachiyomi.domain.epub.model

/** Journal phases of a managed import; see docs/epub/CONTRACTS.md for the recovery rule of each. */
enum class ImportPhase(val code: Long) {
    CLAIMED(1),
    FILE_COMMITTED(2),
    DATABASE_COMMITTED(3),
    FINALIZED(4),
    ABORTED(5),

    /** Rolled back, but the operation's files are not yet confirmed removed; recovery retries the cleanup. */
    ABORTING(6),
    ;

    companion object {
        fun of(code: Long) = entries.first { it.code == code }
    }
}

data class ImportOperation(
    val id: Long,
    val importKey: String,
    val fingerprint: String,
    val byteSize: Long,
    val storageRoot: String,
    val fileName: String,
    val phase: ImportPhase,
    /** The operation reopened a finished import under its existing key and did not write its book file. */
    val reused: Boolean,
    val mangaId: Long?,
    val chapterId: Long?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Shared library records created or reused by an import commit. */
data class CommittedImport(val mangaId: Long, val chapterId: Long, val assetId: Long)

/** What a managed import commit writes into the shared title and item records. */
data class ImportedTitle(
    val title: String,
    val author: String?,
    val description: String?,
    val coverUri: String?,
    val chapterName: String,
    val chapterUploadedAt: Long?,
)

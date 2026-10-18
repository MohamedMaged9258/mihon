package tachiyomi.domain.epub.model

/**
 * Enumerations persisted by stable code, never by ordinal or name, so they survive reordering and renaming.
 * Unknown codes from a newer schema decode to the safest value instead of failing a whole query.
 */
enum class PayloadFormat(val code: Long) {
    EPUB(1),
    ;

    companion object {
        fun of(code: Long) = entries.first { it.code == code }
    }
}

/** Rendition layout declared by the publication. */
enum class PublicationLayout(val code: Long) {
    UNKNOWN(0),
    REFLOWABLE(1),
    FIXED(2),
    ;

    companion object {
        fun of(code: Long) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Semantic content, independent of payload format and layout: a comic can be packaged as an EPUB. */
enum class ContentKind(val code: Long) {
    UNKNOWN(0),
    TEXT(1),
    IMAGE(2),
    ;

    companion object {
        fun of(code: Long) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class AssetOwnership(val code: Long) {
    /** A durable copy Mihon made into its configured library storage. */
    MANAGED(1),

    /** A file the user placed in a Local folder. Mihon never copies, renames or deletes it. */
    USER(2),
    ;

    companion object {
        fun of(code: Long) = entries.first { it.code == code }
    }
}

enum class AssetAvailability(val code: Long) {
    AVAILABLE(1),
    MISSING(2),

    /** The storage tree or permission is unavailable, so whether the file exists is unknown. */
    INACCESSIBLE(3),

    /** The configured storage root differs from the one the asset was committed under. */
    ROOT_CHANGED(4),
    ;

    companion object {
        fun of(code: Long) = entries.firstOrNull { it.code == code } ?: INACCESSIBLE
    }
}

/** Bounded, declared publication metadata. Strings keep their Unicode content as declared. */
data class PublicationMetadata(
    val title: String,
    val authors: List<String>,
    val description: String?,
    val language: String?,
    val publisher: String?,
    val identifier: String?,
    val published: String?,
)

/**
 * The publication bytes behind one readable item ([chapterId]). [relativePath] is resolved only against
 * [storageRoot], the storage preference value it was committed under, never against a later root.
 */
data class PublicationAsset(
    val id: Long,
    val chapterId: Long,
    val assetKey: String,
    val format: PayloadFormat,
    val layout: PublicationLayout,
    val contentKind: ContentKind,
    val fingerprint: String,
    val byteSize: Long,
    val storageRoot: String,
    val relativePath: String,
    val ownership: AssetOwnership,
    val availability: AssetAvailability,
    val availabilityCheckedAt: Long,
    val metadata: PublicationMetadata,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Values a new asset row is created from. */
data class NewPublicationAsset(
    val assetKey: String,
    val format: PayloadFormat,
    val layout: PublicationLayout,
    val contentKind: ContentKind,
    val fingerprint: String,
    val byteSize: Long,
    val storageRoot: String,
    val relativePath: String,
    val ownership: AssetOwnership,
    val metadata: PublicationMetadata,
)

/**
 * Layout of a managed import: `<root>/local/<key>/book.epub`. The key is random and immutable, so it is a safe
 * path component and a stable title URL; neither the user's filename nor the title ever becomes a path.
 */
object ManagedPublicationLayout {
    const val LOCAL_DIRECTORY = "local"
    const val BOOK_FILE = "book.epub"
    const val PARTIAL_FILE = ".book.epub.part"
    const val COVER_FILE = "cover.jpg"
    private val KEY = Regex("epub-[a-z2-7]{26}")

    fun isManagedKey(folder: String) = KEY.matches(folder)

    fun chapterUrl(key: String) = "$key/$BOOK_FILE"

    fun relativePath(key: String) = "$LOCAL_DIRECTORY/$key/$BOOK_FILE"
}

/** A Local title folder registered with at least one publication asset. */
data class LocalTitleIndexEntry(
    val folder: String,
    val title: String,
    val managed: Boolean,
)

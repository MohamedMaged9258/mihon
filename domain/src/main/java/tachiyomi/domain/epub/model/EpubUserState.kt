package tachiyomi.domain.epub.model

/**
 * An engine locator serialized at the reader-adapter boundary. [json] is opaque here; [version] says how to
 * decode it, and it is only comparable with locators of the same publication fingerprint.
 */
data class StoredLocator(val version: Int, val json: String) {
    init {
        require(version in SUPPORTED_VERSIONS) { "Unsupported locator version $version" }
        require(json.length <= MAX_LENGTH) { "Locator exceeds $MAX_LENGTH characters" }
    }

    companion object {
        const val CURRENT_VERSION = 1
        val SUPPORTED_VERSIONS = setOf(CURRENT_VERSION)
        const val MAX_LENGTH = 65_536
    }
}

/**
 * Reading state of one item for one publication revision ([fingerprint]). [resume] and [furthest] are separate:
 * reading backwards moves Resume, not the furthest point. [resetAt] records an explicit progress reset.
 */
data class EpubReadingState(
    val chapterId: Long,
    val fingerprint: String,
    val resume: StoredLocator?,
    val furthest: StoredLocator?,
    val furthestProgression: Double?,
    val revision: Long,
    val updatedAt: Long,
    val resetAt: Long?,
) {
    init {
        require(furthestProgression == null || furthestProgression in 0.0..1.0)
        require(resume == null || furthest == null || resume.version == furthest.version)
    }
}

enum class EpubReaderMode(val code: Long) {
    IMAGE(1),
    BOOK(2),
    ;

    companion object {
        fun of(code: Long?) = code?.let { c -> entries.firstOrNull { it.code == c } }
    }
}

enum class EpubTextAlign(val code: Long) {
    START(1),
    JUSTIFY(2),
    ;

    companion object {
        fun of(code: Long?) = code?.let { c -> entries.firstOrNull { it.code == c } }
    }
}

enum class EpubTheme(val code: Long) {
    LIGHT(1),
    DARK(2),
    SEPIA(3),
    ;

    companion object {
        fun of(code: Long?) = code?.let { c -> entries.firstOrNull { it.code == c } }
    }
}

/** Explicit per-title overrides. `null` means inherit the global default; it is not a stored value. */
data class EpubTitlePreferences(
    val readerMode: EpubReaderMode? = null,
    val fontScale: Double? = null,
    val lineHeight: Double? = null,
    val pageMargins: Double? = null,
    val textAlign: EpubTextAlign? = null,
    val theme: EpubTheme? = null,
    val scroll: Boolean? = null,
    val publisherStyles: Boolean? = null,
)

data class EpubLocationBookmark(
    val key: String,
    val chapterId: Long,
    val fingerprint: String,
    val locator: StoredLocator,
    val label: String?,
    val context: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

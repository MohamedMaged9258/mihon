package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.serialization.Serializable

@Serializable
data class EpubFixtureReference(
    val id: String,
    val assetPath: String,
    val expectedSha256: String,
)

@Serializable
data class EpubLocatorEnvelope(
    val version: Int = 1,
    val targetId: String,
    val publicationFingerprint: String,
    val locatorJson: String,
)

@Serializable
data class EpubReaderPreferences(
    val fontSizeOverride: Double? = null,
)

sealed interface EpubOpenError {
    val detail: String

    data class Missing(override val detail: String) : EpubOpenError
    data class Corrupt(override val detail: String) : EpubOpenError
    data class UnsupportedFormat(override val detail: String) : EpubOpenError
    data class ProtectedPublication(override val detail: String) : EpubOpenError
    data class FingerprintMismatch(override val detail: String) : EpubOpenError
    data class ResourceUnavailable(override val detail: String) : EpubOpenError
}
enum class EpubRestorationPhase {
    Opening,
    Attaching,
    Restoring,
    Ready,
    Reflowing,
    Closing,
    Error,
}

enum class EpubRecoveryReason {
    OversizeEnvelope,
    MalformedEnvelope,
    UnsupportedVersion,
    TargetMismatch,
    FingerprintMismatch,
    InvalidLocator,
    MissingResource,
}

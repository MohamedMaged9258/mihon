package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.nio.charset.StandardCharsets

object EpubLocatorCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_ENVELOPE_BYTES = 64 * 1024

    sealed interface DecodeResult {
        data class Restored(val locator: Locator) : DecodeResult
        data class Start(val reason: EpubRecoveryReason) : DecodeResult
    }

    fun encode(
        target: EpubFixtureReference,
        publicationFingerprint: String,
        locator: Locator,
    ): String = envelopeJson.encodeToString(
        EpubLocatorEnvelope(
            targetId = target.id,
            publicationFingerprint = publicationFingerprint,
            // Store the complete Readium object as an opaque string. This prevents a serialization
            // layer from normalizing hrefs, fragments or extension members.
            locatorJson = locator.toJSON().toString(),
        ),
    )

    fun decode(
        encoded: String,
        expectedTarget: EpubFixtureReference,
        publicationFingerprint: String,
        publication: Publication,
    ): DecodeResult {
        val envelope = when (
            val result = EpubEnvelopeValidator.validate(
                encoded,
                expectedTarget.id,
                publicationFingerprint,
            )
        ) {
            is EpubEnvelopeValidator.Result.Invalid -> return DecodeResult.Start(result.reason)
            is EpubEnvelopeValidator.Result.Valid -> result.envelope
        }
        val locatorJson = envelope.locatorJson.takeIf { it.isNotBlank() }
            ?: return DecodeResult.Start(EpubRecoveryReason.InvalidLocator)
        val locator = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }
            .getOrNull()
            ?: return DecodeResult.Start(EpubRecoveryReason.InvalidLocator)

        // Readium's URL model deliberately keeps the encoded href. linkWithHref resolves against
        // the publication manifest and ignores only query/fragment on its documented fallback.
        if (publication.linkWithHref(locator.href) == null) {
            return DecodeResult.Start(EpubRecoveryReason.MissingResource)
        }
        return DecodeResult.Restored(locator)
    }

    private val envelopeJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
}
internal object EpubEnvelopeValidator {
    sealed interface Result {
        data class Valid(val envelope: EpubLocatorEnvelope) : Result
        data class Invalid(val reason: EpubRecoveryReason) : Result
    }

    private val json = Json { ignoreUnknownKeys = false }

    fun validate(encoded: String, targetId: String, fingerprint: String): Result {
        if (encoded.toByteArray(StandardCharsets.UTF_8).size > EpubLocatorCodec.MAX_ENVELOPE_BYTES) {
            return Result.Invalid(EpubRecoveryReason.OversizeEnvelope)
        }
        val envelope = runCatching { json.decodeFromString<EpubLocatorEnvelope>(encoded) }
            .getOrElse { return Result.Invalid(EpubRecoveryReason.MalformedEnvelope) }
        if (envelope.version != EpubLocatorCodec.SCHEMA_VERSION) {
            return Result.Invalid(EpubRecoveryReason.UnsupportedVersion)
        }
        if (envelope.targetId != targetId) {
            return Result.Invalid(EpubRecoveryReason.TargetMismatch)
        }
        if (envelope.publicationFingerprint != fingerprint) {
            return Result.Invalid(EpubRecoveryReason.FingerprintMismatch)
        }
        return Result.Valid(envelope)
    }
}

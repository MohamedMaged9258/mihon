package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class EpubLocatorEnvelopeTest {
    private val locatorJson = """
        {
          "href":"text/chapter%201.xhtml?mode=test#passage%202",
          "type":"application/xhtml+xml",
          "locations":{
            "fragments":["passage%202"],
            "progression":0.42,
            "cssSelector":"#passage\\ 2"
          },
          "text":{"before":"before","highlight":"marker","after":"after"}
        }
    """.trimIndent()

    @Test
    fun `complete locator JSON including encoded href round trips verbatim`() {
        val original = EpubLocatorEnvelope(
            targetId = "book",
            publicationFingerprint = "abc",
            locatorJson = locatorJson,
        )
        val decoded = Json.decodeFromString<EpubLocatorEnvelope>(Json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(locatorJson, decoded.locatorJson)
    }

    @Test
    fun `oversize malformed version target and fingerprint are typed`() {
        assertInvalid(
            "x".repeat(EpubLocatorCodec.MAX_ENVELOPE_BYTES + 1),
            EpubRecoveryReason.OversizeEnvelope,
        )
        assertInvalid("{", EpubRecoveryReason.MalformedEnvelope)
        assertInvalid(envelope(version = 2), EpubRecoveryReason.UnsupportedVersion)
        assertInvalid(envelope(targetId = "other"), EpubRecoveryReason.TargetMismatch)
        assertInvalid(envelope(fingerprint = "def"), EpubRecoveryReason.FingerprintMismatch)
    }

    private fun envelope(
        version: Int = 1,
        targetId: String = "book",
        fingerprint: String = "abc",
    ): String = Json.encodeToString(
        EpubLocatorEnvelope(version, targetId, fingerprint, locatorJson),
    )

    private fun assertInvalid(encoded: String, reason: EpubRecoveryReason) {
        val result = EpubEnvelopeValidator.validate(encoded, "book", "abc")
        val invalid = assertInstanceOf(EpubEnvelopeValidator.Result.Invalid::class.java, result)
        assertEquals(reason, invalid.reason)
    }
}

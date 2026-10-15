package eu.kanade.tachiyomi.ui.reader.epub

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpubFingerprintVerifierTest {
    @Test
    fun `fingerprint matching is case insensitive and mismatch is typed`() {
        assertNull(EpubFingerprintVerifier.error("aabb", "AABB"))
        assertInstanceOf(
            EpubOpenError.FingerprintMismatch::class.java,
            EpubFingerprintVerifier.error("aabb", "ccdd"),
        )
    }
}

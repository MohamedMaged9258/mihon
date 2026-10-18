package eu.kanade.tachiyomi.data.epub

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class EpubArchiveGuardTest {

    @TempDir
    lateinit var dir: File

    private fun check(bytes: ByteArray, limits: EpubContentLimits = EpubContentLimits.DEFAULT): EpubImportError? {
        val file = File(dir, "input.epub").apply { writeBytes(bytes) }
        return EpubArchiveGuard.check(file, limits)
    }

    @Test
    fun `valid EPUB 2 and EPUB 3 packages pass`() {
        assertNull(check(TestEpubs.book(version = 3)))
        assertNull(check(TestEpubs.book(version = 2)))
    }

    @Test
    fun `entry names that could escape a root are rejected`() {
        listOf("../evil.xhtml", "OEBPS/../../evil", "/abs/path", "C:/x", "OEBPS\\x", "a/./b").forEach { name ->
            val error = check(TestEpubs.book(extra = listOf(name to "x".toByteArray())))
            assertInstanceOf(EpubImportError.Malformed::class.java, error, name)
        }
        assertTrue(EpubArchiveGuard.isSafeName("OEBPS/text/chapter..1.xhtml"))
        assertFalse(EpubArchiveGuard.isSafeName(""))
    }

    @Test
    fun `missing container and non-EPUB mimetype are rejected`() {
        val noContainer = TestEpubs.zip(listOf("mimetype" to "application/epub+zip".toByteArray()))
        assertInstanceOf(EpubImportError.Malformed::class.java, check(noContainer))

        val zipNotEpub = TestEpubs.zip(
            listOf(
                "mimetype" to "application/zip".toByteArray(),
                "META-INF/container.xml" to TestEpubs.CONTAINER.toByteArray(),
            ),
        )
        assertInstanceOf(EpubImportError.UnsupportedFormat::class.java, check(zipNotEpub))
    }

    @Test
    fun `truncated archives are malformed rather than crashes`() {
        val bytes = TestEpubs.book()
        assertInstanceOf(EpubImportError.Malformed::class.java, check(bytes.copyOf(bytes.size / 2)))
        assertInstanceOf(EpubImportError.Malformed::class.java, check("not a zip".toByteArray()))
    }

    @Test
    fun `limits apply to actual decompressed bytes`() {
        val big = "OEBPS/big.txt" to ByteArray(300_000) // compresses to a few hundred bytes
        val epub = TestEpubs.book(extra = listOf(big))

        val entryLimit = EpubContentLimits(maxEntryBytes = 200_000)
        assertInstanceOf(EpubImportError.TooLarge::class.java, check(epub, entryLimit))

        val totalLimit = EpubContentLimits(maxTotalBytes = 250_000)
        assertInstanceOf(EpubImportError.TooLarge::class.java, check(epub, totalLimit))

        val ratioLimit = EpubContentLimits(compressionRatioFloorBytes = 100_000, maxCompressionRatio = 50)
        assertInstanceOf(EpubImportError.TooLarge::class.java, check(epub, ratioLimit))

        val entries = EpubContentLimits(maxEntries = 3)
        assertInstanceOf(EpubImportError.TooLarge::class.java, check(epub, entries))

        val input = EpubContentLimits(maxInputBytes = 100)
        assertInstanceOf(EpubImportError.TooLarge::class.java, check(epub, input))

        assertNull(check(epub))
    }

    @Test
    fun `font obfuscation is accepted and real encryption or rights files are protection`() {
        assertNull(check(TestEpubs.book(extra = TestEpubs.encryption("http://www.idpf.org/2008/embedding"))))
        assertNull(check(TestEpubs.book(extra = TestEpubs.encryption("http://ns.adobe.com/pdf/enc#RC"))))
        assertInstanceOf(
            EpubImportError.UnsupportedProtection::class.java,
            check(TestEpubs.book(extra = TestEpubs.encryption("http://www.w3.org/2001/04/xmlenc#aes256-cbc"))),
        )
        assertInstanceOf(
            EpubImportError.UnsupportedProtection::class.java,
            check(TestEpubs.book(extra = listOf("META-INF/rights.xml" to "<rights/>".toByteArray()))),
        )
        assertInstanceOf(
            EpubImportError.UnsupportedProtection::class.java,
            check(TestEpubs.book(extra = listOf("META-INF/license.lcpl" to "{}".toByteArray()))),
        )
    }

    @Test
    fun `hashing copy bounds the bytes read and reports SHA-256`() = runBlocking {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val hashed = PublicationStorage.copyHashing(ByteArrayInputStream(data), out)
        assertEquals(data.size.toLong(), hashed.size)
        assertEquals(sha256(data), hashed.sha256)
        assertTrue(data.contentEquals(out.toByteArray()))

        // An unknown-size stream that never ends is cut off at the limit
        val endless = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = len
        }
        val error = assertThrows(EpubImportException::class.java) {
            runBlocking { PublicationStorage.copyHashing(endless, null, maxBytes = 1_000_000) }
        }
        assertInstanceOf(EpubImportError.TooLarge::class.java, error.error)
    }

    @Test
    fun `import keys are random, path-safe and recognized`() {
        val keys = List(200) { PublicationStorage.newImportKey() }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach {
            assertTrue(ManagedPublicationLayout.isManagedKey(it), it)
            assertTrue(EpubArchiveGuard.isSafeName(it))
        }
        assertFalse(ManagedPublicationLayout.isManagedKey("My Novel"))
        assertFalse(ManagedPublicationLayout.isManagedKey("epub-short"))
        assertEquals("${keys[0]}/book.epub", ManagedPublicationLayout.chapterUrl(keys[0]))
        assertEquals("local/${keys[0]}/book.epub", ManagedPublicationLayout.relativePath(keys[0]))
    }

    @Test
    fun `metadata normalization keeps Unicode and bounds length`() {
        val arabic = "  رواية\nالاختبار   العربية  "
        assertEquals("رواية الاختبار العربية", arabic.normalized(100))
        val emoji = "a\uD83D\uDE00b"
        assertEquals("a", emoji.normalized(2)) // never splits a surrogate pair
        assertEquals("abc", "abcdef".normalized(3))
    }

    @Test
    fun `IO failures are classified without leaking messages`() {
        assertInstanceOf(
            EpubImportError.InsufficientSpace::class.java,
            PublicationStorage.classify(java.io.IOException("write failed: ENOSPC (No space left on device)")) {
                EpubImportError.CommitFailed(it)
            },
        )
        assertInstanceOf(
            EpubImportError.PermissionDenied::class.java,
            PublicationStorage.classify(SecurityException("content://secret/path")) { EpubImportError.ReadFailed(it) },
        )
        val generic = PublicationStorage.classify(java.io.IOException("content://secret/path")) {
            EpubImportError.CommitFailed(it)
        }
        assertFalse(generic.detail.contains("secret"))
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

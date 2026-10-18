package eu.kanade.tachiyomi.data.epub

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Structural checks applied to a staged file before the EPUB parser sees it. Every entry is decompressed once
 * through a counting stream, so limits apply to bytes actually produced rather than to declared sizes, and a
 * CRC failure surfaces as a malformed archive. Nothing is ever extracted to the filesystem.
 */
internal object EpubArchiveGuard {

    // Font obfuscation is not DRM; these are the only encryption algorithms an unprotected EPUB may declare.
    private val FONT_OBFUSCATION = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )
    private val ALGORITHM = Regex("""Algorithm\s*=\s*["']([^"']*)["']""")
    private val PROTECTION_FILES = setOf("META-INF/rights.xml", "META-INF/license.lcpl")
    private const val CONTAINER = "META-INF/container.xml"
    private const val ENCRYPTION = "META-INF/encryption.xml"
    private const val MIMETYPE = "mimetype"
    private const val EPUB_MIME = "application/epub+zip"

    fun check(file: File, limits: EpubContentLimits = EpubContentLimits.DEFAULT): EpubImportError? {
        if (file.length() > limits.maxInputBytes) {
            return EpubImportError.TooLarge("File exceeds ${limits.maxInputBytes} bytes")
        }
        return try {
            ZipFile(file).use { zip -> checkEntries(zip, limits) }
        } catch (e: ZipException) {
            // Includes the platform's own rejection of unsafe entry names on newer Android versions
            EpubImportError.Malformed("Invalid ZIP container: ${e.message}")
        } catch (e: IOException) {
            EpubImportError.Malformed("Unreadable ZIP container: ${e.message}")
        } catch (e: IllegalArgumentException) {
            EpubImportError.Malformed("Invalid ZIP entry name")
        }
    }

    private fun checkEntries(zip: ZipFile, limits: EpubContentLimits): EpubImportError? {
        val names = HashSet<String>()
        var total = 0L
        var encryption: String? = null
        var mimetype: String? = null
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (names.size >= limits.maxEntries) {
                return EpubImportError.TooLarge("More than ${limits.maxEntries} archive entries")
            }
            val name = entry.name
            if (!isSafeName(name)) return EpubImportError.Malformed("Unsafe archive entry path")
            if (!names.add(name)) return EpubImportError.Malformed("Duplicate archive entry")
            if (entry.isDirectory) continue
            if (name in PROTECTION_FILES) {
                return EpubImportError.UnsupportedProtection("Rights-managed publication")
            }

            val keep = name == ENCRYPTION || name == MIMETYPE
            val read = zip.getInputStream(entry).use { input ->
                drain(input, limits.maxEntryBytes, keep)
            } ?: return EpubImportError.TooLarge("An archive entry exceeds ${limits.maxEntryBytes} bytes")

            total += read.size
            if (total > limits.maxTotalBytes) {
                return EpubImportError.TooLarge("Uncompressed content exceeds ${limits.maxTotalBytes} bytes")
            }
            val compressed = entry.compressedSize.coerceAtLeast(1)
            if (read.size > limits.compressionRatioFloorBytes && read.size / compressed > limits.maxCompressionRatio) {
                return EpubImportError.TooLarge("Archive entry compression ratio is implausible")
            }
            when (name) {
                ENCRYPTION -> encryption = read.text
                MIMETYPE -> mimetype = read.text
            }
        }

        if (CONTAINER !in names) return EpubImportError.Malformed("Missing $CONTAINER")
        if (mimetype != null && mimetype.trim() != EPUB_MIME) {
            return EpubImportError.UnsupportedFormat("Declared container type is not EPUB")
        }
        encryption?.let { xml ->
            val algorithms = ALGORITHM.findAll(xml).map { it.groupValues[1].trim() }.toSet()
            if ((algorithms - FONT_OBFUSCATION).isNotEmpty()) {
                return EpubImportError.UnsupportedProtection("Encrypted publication resources")
            }
        }
        return null
    }

    /** Rejects names that could escape a container root if anything ever resolved them as paths. */
    internal fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 1024) return false
        if (name.startsWith('/') || '\\' in name || '\u0000' in name) return false
        if (name.length >= 2 && name[1] == ':') return false
        return name.split('/').none { it == ".." || it == "." }
    }

    private class Drained(val size: Long, val text: String?)

    private fun drain(input: InputStream, limit: Long, keep: Boolean): Drained? {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val kept = if (keep) java.io.ByteArrayOutputStream() else null
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > limit) return null
            // Only small control files are kept, bounded well below the entry limit
            if (kept != null && kept.size() < 1024 * 1024) kept.write(buffer, 0, count)
        }
        return Drained(size, kept?.toString(Charsets.UTF_8.name()))
    }
}

package eu.kanade.tachiyomi.data.epub

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deterministic EPUB inputs generated in tests, so hostile cases need no checked-in binaries. */
object TestEpubs {

    /** 32×32 PNG (the P01-01 fixture illustration). */
    val PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAA" +
            "DsMAAA7DAcdvqGQAAAB9SURBVFhH7c6hEQNBEMTAjetjc24O46NwDGcuIdXRBU0GqGbOO+fG5/ldGQYrBqs9sAf2wB7YA3tg" +
            "DwyHar7nDoOVghWDlYIVg5WCFYOVghWDlYIVg5WCFYOVghWDlYIVg5WCFYOVghWDlYIVg5WCFYOVghWDlYIVg5WC0R/lftaX" +
            "Ul37QQAAAABJRU5ErkJggg==",
    )

    const val CONTAINER = "<?xml version=\"1.0\"?><container version=\"1.0\" " +
        "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile " +
        "full-path=\"OEBPS/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"

    fun zip(entries: List<Pair<String, ByteArray>>, storeMimetype: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                val entry = ZipEntry(name)
                if (storeMimetype && name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun xhtml(body: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>
    """.trimIndent()

    /**
     * An EPUB 3 publication. [version] 2 writes an EPUB 2 package with an NCX and a `meta name="cover"` cover.
     * [extra] entries are appended verbatim, after the regular ones.
     */
    fun book(
        title: String? = "Test Book",
        authors: List<String> = listOf("Test Author"),
        language: String = "en",
        description: String? = null,
        publisher: String? = null,
        identifier: String = "urn:mihon:test:book",
        cover: ByteArray? = PNG,
        version: Int = 3,
        fixedLayout: Boolean = false,
        imagePages: Boolean = false,
        bodyText: String = "Plain test text.",
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val meta = buildString {
            append("""<dc:identifier id="id">$identifier</dc:identifier>""")
            title?.let { append("<dc:title>$it</dc:title>") }
            authors.forEach { append("<dc:creator>$it</dc:creator>") }
            append("<dc:language>$language</dc:language>")
            description?.let { append("<dc:description>$it</dc:description>") }
            publisher?.let { append("<dc:publisher>$it</dc:publisher>") }
            if (version == 3) append("""<meta property="dcterms:modified">2000-01-01T00:00:00Z</meta>""")
            if (fixedLayout) append("""<meta property="rendition:layout">pre-paginated</meta>""")
            if (version == 2 && cover != null) append("""<meta name="cover" content="cover"/>""")
        }
        val coverItem = when {
            cover == null -> ""
            version == 3 -> """<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>"""
            else -> """<item id="cover" href="cover.png" media-type="image/png"/>"""
        }
        val nav = if (version == 3) {
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
        } else {
            """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        }
        val page = if (imagePages) """<img src="cover.png" alt=""/>""" else "<p>$bodyText</p>"
        val toc = if (version == 2) """ toc="ncx"""" else ""
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="$version.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$meta</metadata>
            <manifest>$nav$coverItem
            <item id="image" href="page.png" media-type="image/png"/>
            <item id="s1" href="s1.xhtml" media-type="application/xhtml+xml"/>
            <item id="s2" href="s2.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine$toc><itemref idref="s1"/><itemref idref="s2"/></spine>
            </package>
        """.trimIndent()
        val entries = mutableListOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to CONTAINER.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/s1.xhtml" to xhtml(page).toByteArray(),
            "OEBPS/s2.xhtml" to xhtml(page).toByteArray(),
            "OEBPS/page.png" to PNG,
        )
        if (version == 3) {
            entries += "OEBPS/nav.xhtml" to xhtml(
                """
                <nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc">
                <ol><li><a href="s1.xhtml">One</a></li><li><a href="s2.xhtml">Two</a></li></ol>
                </nav>
                """.trimIndent(),
            ).toByteArray()
        } else {
            entries += "OEBPS/toc.ncx" to """
                <?xml version="1.0"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/>
                <docTitle><text>t</text></docTitle>
                <navMap><navPoint id="n1" playOrder="1"><navLabel><text>One</text></navLabel>
                <content src="s1.xhtml"/></navPoint></navMap>
                </ncx>
            """.trimIndent().toByteArray()
        }
        if (cover != null) entries += "OEBPS/cover.png" to cover
        entries += extra
        return zip(entries)
    }

    /** An `encryption.xml` declaring [algorithm] for an embedded font, plus that font. */
    fun encryption(algorithm: String) = listOf(
        "OEBPS/font.otf" to ByteArray(2048) { it.toByte() },
        "META-INF/encryption.xml" to """
            <?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>
            <enc:CipherData><enc:CipherReference URI="OEBPS/font.otf"/></enc:CipherData></enc:EncryptedData>
            </encryption>
        """.trimIndent().toByteArray(),
    )
}

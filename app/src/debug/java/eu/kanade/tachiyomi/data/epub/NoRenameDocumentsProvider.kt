package eu.kanade.tachiyomi.data.epub

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only document provider over app-private files. It deliberately lacks rename (no
 * [Document.FLAG_SUPPORTS_RENAME]), like many cloud and vendor providers, and can inject faults, so import
 * commit and recovery are exercised through the real Storage Access Framework rather than a fake filesystem.
 * Protected by MANAGE_DOCUMENTS as Android requires; only the system picker and this app can reach it.
 */
class NoRenameDocumentsProvider : DocumentsProvider() {

    /** Faults applied to subsequent calls; reset by tests. */
    object Faults {
        @Volatile var revoked = false

        /** The provider refuses every byte as a full volume would. */
        @Volatile var noSpace = false

        /** Fail any write once this many bytes have been written to one document. */
        @Volatile var failWriteAfterBytes: Long? = null

        /** Limits [failWriteAfterBytes] to documents of this name. */
        @Volatile var failWriteName: String? = null

        @Volatile var deletes = DeleteFault.NORMAL

        /** Documents of this name read as an endless stream of unknown size. */
        @Volatile var endlessReadName: String? = null

        /** Bytes handed out by endless reads. */
        val bytesServed = AtomicLong()

        fun reset() {
            revoked = false
            noSpace = false
            failWriteAfterBytes = null
            failWriteName = null
            deletes = DeleteFault.NORMAL
            endlessReadName = null
            bytesServed.set(0)
        }
    }

    enum class DeleteFault {
        NORMAL,

        /** Reports success but keeps the document, as a lagging remote provider can. */
        IGNORE,

        /** Fails with an I/O error, which clients see as a false result. */
        FAIL,

        /** Fails with a permission error, which clients see thrown. */
        DENY,
    }

    private val base: File get() = File(context!!.filesDir, "no-rename-provider").apply { mkdirs() }

    override fun onCreate() = true

    private fun check() {
        if (Faults.revoked) throw SecurityException("Permission revoked")
    }

    private fun fileOf(documentId: String): File {
        val file = File(base, documentId.removePrefix(ROOT_ID).trimStart('/'))
        // Document ids are relative paths below the base; never resolve one outside it
        if (!file.canonicalPath.startsWith(base.canonicalPath)) throw FileNotFoundException()
        return file
    }

    private fun idOf(file: File): String {
        val relative = file.canonicalPath.removePrefix(base.canonicalPath).trimStart(File.separatorChar)
        return if (relative.isEmpty()) ROOT_ID else "$ROOT_ID/${relative.replace(File.separatorChar, '/')}"
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Mihon no-rename test storage")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        check()
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addRow(this, fileOf(documentId)) }
    }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        check()
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            fileOf(parentId).listFiles()?.forEach { addRow(this, it) }
        }
    }

    private fun addRow(cursor: MatrixCursor, file: File) {
        if (!file.exists()) throw FileNotFoundException()
        val directory = file.isDirectory
        val flags = if (directory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(file))
            .add(Document.COLUMN_DISPLAY_NAME, if (file == base) "root" else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (directory) Document.MIME_TYPE_DIR else mimeOf(file.name))
            .add(Document.COLUMN_SIZE, if (directory || file.name == Faults.endlessReadName) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return fileOf(documentId).canonicalPath.startsWith(fileOf(parentDocumentId).canonicalPath)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check()
        val parent = fileOf(parentDocumentId)
        // Like the platform's file-system providers: the MIME type's extension is appended when missing, and an
        // existing name gets a " (n)" suffix rather than being replaced
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeUnless { mimeType == "application/octet-stream" }
        val name = if (mimeType != Document.MIME_TYPE_DIR && extension != null &&
            !displayName.endsWith(".$extension", ignoreCase = true)
        ) {
            "$displayName.$extension"
        } else {
            displayName
        }
        var file = File(parent, name)
        var n = 1
        val base = name.substringBeforeLast('.', name)
        val suffix = name.removePrefix(base)
        while (file.exists()) file = File(parent, "$base (${n++})$suffix")
        val created = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!created) throw IOException("Unable to create document")
        return idOf(file)
    }

    override fun deleteDocument(documentId: String) {
        check()
        val file = fileOf(documentId)
        when (Faults.deletes) {
            DeleteFault.NORMAL -> Unit
            DeleteFault.IGNORE -> return
            DeleteFault.FAIL -> throw IOException("Provider delete failed")
            DeleteFault.DENY -> throw SecurityException("Delete denied")
        }
        if (!file.deleteRecursively()) throw IOException("Unable to delete document")
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        check()
        val file = fileOf(documentId)
        val writing = 'w' in mode
        if (!writing && file.name == Faults.endlessReadName) return endless()
        // A full remote volume: the provider accepts nothing and reports ENOSPC on its side of the pipe
        val limit = when {
            writing && Faults.noSpace -> 0L
            Faults.failWriteName.let { it == null || it == file.name } -> Faults.failWriteAfterBytes
            else -> null
        }
        if (!writing || limit == null) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        // A pipe lets the provider stop accepting bytes part-way, as a failing remote provider would
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (total + count > limit) {
                            output.write(buffer, 0, (limit - total).toInt())
                            read.closeWithError(
                                if (Faults.noSpace) "ENOSPC (No space left on device)" else "Provider write failed",
                            )
                            return@Thread
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                }
            }
        }.start()
        return write
    }

    /** A stream that never ends; it stops only when the reader closes its side. */
    private fun endless(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        output.write(buffer)
                        Faults.bytesServed.addAndGet(buffer.size.toLong())
                    }
                }
            }
        }.start()
        return read
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".epubtestdocuments"
        const val ROOT_ID = "root"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

package eu.kanade.tachiyomi.data.epub

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.SqlDriver
import com.hippo.unifile.TestProviderTreeHandler
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.filter.OrderBy
import java.io.File
import java.util.UUID

/**
 * A disposable library storage root, of one of two kinds:
 * - [Kind.FILE]: a plain directory (atomic `File.renameTo`), as with the default storage location;
 * - [Kind.PROVIDER]: a Storage Access Framework tree served by [NoRenameDocumentsProvider], which has no rename.
 * The app's storage preference points at it until [close]; database rows created through it are deleted.
 */
class EpubTestEnvironment(val kind: Kind) : AutoCloseable {
    enum class Kind { FILE, PROVIDER }

    val context: Context = ApplicationProvider.getApplicationContext()
    val graph: AppGraph = context.appGraph
    private val originalRoot = graph.storagePreferences.baseStorageDirectory.get()
    private val name = "epub-test-${UUID.randomUUID().toString().take(8)}"
    private val createdMangaIds = mutableSetOf<Long>()

    val authority = "${context.packageName}${NoRenameDocumentsProvider.AUTHORITY_SUFFIX}"
    private val providerBase = File(context.filesDir, "no-rename-provider")

    /** Backing directory of the root, for assertions. */
    val directory: File = when (kind) {
        Kind.FILE -> File(context.getExternalFilesDir(null), name)
        Kind.PROVIDER -> File(providerBase, name)
    }.apply { mkdirs() }

    val root: String = when (kind) {
        Kind.FILE -> Uri.fromFile(directory).toString()
        Kind.PROVIDER -> DocumentsContract.buildTreeDocumentUri(authority, "${NoRenameDocumentsProvider.ROOT_ID}/$name")
            .toString()
    }

    val localDirectory: File get() = File(directory, "local")

    /** A second connection to the app database, for test setup and cleanup that has no domain API. */
    val sql: SqlDriver by lazy {
        DatabaseBindings.createSqlDriver(context, DatabaseBindings.DATABASE_NAME, MutableStateFlow(false))
    }

    fun deleteManga(id: Long) = runBlocking {
        sql.execute(null, "DELETE FROM manga WHERE id = ?", 1) { bindLong(0, id) }.await()
    }

    val localSource: LocalSource by lazy { runBlocking { graph.sourceManager.get(LocalSource.ID) as LocalSource } }

    init {
        leaveStoppedState()
        NoRenameDocumentsProvider.Faults.reset()
        useRoot(root)
    }

    companion object {
        @Volatile private var launched = false

        /**
         * Brings the freshly installed package out of the "stopped" state, as for an app the user runs, and lets
         * UniFile recognize the test provider's trees (see [TestProviderTreeHandler]).
         */
        private fun leaveStoppedState() {
            if (launched) return
            ActivityScenario.launch(EpubImportDiagnosticsActivity::class.java).close()
            val context = ApplicationProvider.getApplicationContext<Context>()
            UniFile.addUriHandler(
                TestProviderTreeHandler("${context.packageName}${NoRenameDocumentsProvider.AUTHORITY_SUFFIX}"),
            )
            launched = true
        }
    }

    /** Points the storage preference at [value] and waits until the storage manager has switched away. */
    fun useRoot(value: String) {
        // Created first: it observes preference changes only from its creation on
        graph.storageManager
        graph.storagePreferences.baseStorageDirectory.set(value)
        val expected = if (value == root) localUri() else null
        val deadline = SystemClock.uptimeMillis() + 10_000
        var current: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            current = graph.storageManager.getLocalSourceDirectory()?.uri?.toString()
            if (expected != null && current == expected) return
            if (expected == null && current?.contains(name) != true) return
            Thread.sleep(50)
        }
        error("Storage manager did not switch roots: expected $expected, got $current")
    }

    private fun localUri(): String = when (kind) {
        Kind.FILE -> Uri.fromFile(File(directory, "local")).toString()
        Kind.PROVIDER -> DocumentsContract.buildDocumentUriUsingTree(
            Uri.parse(root),
            "${NoRenameDocumentsProvider.ROOT_ID}/$name/local",
        ).toString()
    }

    /** Places [bytes] as a document of the test provider, returning its `content://` URI. */
    fun document(bytes: ByteArray, displayName: String = "${UUID.randomUUID()}.epub"): Uri {
        val inputs = File(providerBase, "inputs").apply { mkdirs() }
        File(inputs, displayName).writeBytes(bytes)
        return DocumentsContract.buildDocumentUri(authority, "${NoRenameDocumentsProvider.ROOT_ID}/inputs/$displayName")
    }

    fun deleteDocument(uri: Uri) {
        val id = DocumentsContract.getDocumentId(uri).removePrefix("${NoRenameDocumentsProvider.ROOT_ID}/")
        File(providerBase, id).delete()
    }

    fun track(mangaId: Long) {
        createdMangaIds += mangaId
    }

    suspend fun importAll(vararg documents: Uri): List<EpubImportResult> {
        return graph.epubImportService.importAll(documents.toList()).onEach { result ->
            when (result) {
                is EpubImportResult.Imported -> track(result.mangaId)
                is EpubImportResult.Duplicate -> track(result.mangaId)
                else -> Unit
            }
        }
    }

    fun keyFolders(): List<File> =
        localDirectory.listFiles().orEmpty().filter { ManagedPublicationLayout.isManagedKey(it.name) }

    suspend fun browseTitles(query: String = ""): Map<String, String> {
        val filters = FilterList(OrderBy.Popular(context))
        return localSource.getSearchManga(1, query, filters).mangas.associate { it.url to it.title }
    }

    override fun close() {
        NoRenameDocumentsProvider.Faults.reset()
        graph.epubImportService.checkpointHook = null
        graph.epubImportService.limits = EpubContentLimits.DEFAULT
        createdMangaIds.forEach(::deleteManga)
        runBlocking {
            sql.execute(null, "DELETE FROM epub_import_operation WHERE storage_root = ?", 1) {
                bindString(0, root)
            }.await()
        }
        sql.close()
        useRoot(originalRoot)
        directory.deleteRecursively()
    }
}

package eu.kanade.tachiyomi.data.epub

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.epub.model.AssetOwnership
import tachiyomi.domain.epub.model.CommittedImport
import tachiyomi.domain.epub.model.ImportOperation
import tachiyomi.domain.epub.model.ImportPhase
import tachiyomi.domain.epub.model.ImportedTitle
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import tachiyomi.domain.epub.model.NewPublicationAsset
import tachiyomi.domain.epub.model.PayloadFormat
import tachiyomi.domain.epub.repository.EpubImportJournal
import tachiyomi.domain.epub.repository.PublicationAssetRepository
import tachiyomi.i18n.MR
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Points at which tests inject faults or simulate process death. */
internal enum class ImportCheckpoint { STAGED, CLAIMED, PART_WRITTEN, FILE_COMMITTED, DATABASE_COMMITTED }

/**
 * Test seam: thrown from a checkpoint hook to stop an import the way process death would, leaving files, staging
 * and journal exactly as they are for recovery to handle. Never thrown by production code.
 */
internal class SimulatedInterruption : Error("Simulated process death")

/** Outcome counts of one recovery pass over the import journal. */
data class EpubRecoveryReport(val aborted: Int, val completed: Int, val finalized: Int, val failed: Int)

/**
 * Managed EPUB import into the configured library storage. Each input is staged privately while hashed,
 * validated, de-duplicated by SHA-256 against managed copies, written under an immutable key, verified, and only
 * then committed to the database, which is the point it becomes discoverable. A durable journal makes every
 * interruption recoverable; see docs/epub/CONTRACTS.md for the phase rules.
 */
@Inject
@SingleIn(AppScope::class)
class EpubImportService(
    private val context: Context,
    private val storage: PublicationStorage,
    private val inspector: LocalPublicationInspector,
    private val journal: EpubImportJournal,
    private val assets: PublicationAssetRepository,
    private val chapters: ChapterRepository,
    private val xml: XML,
) {
    private val permits = Semaphore(MAX_CONCURRENT_IMPORTS)

    // Identical bytes import one at a time, so the second deterministically reports the first as its duplicate.
    // ponytail: one Mutex per fingerprint seen this process lifetime; prune if imports ever reach the thousands
    private val fingerprintLocks = ConcurrentHashMap<String, Mutex>()
    private val recoveryLock = Mutex()
    private val activeStaging = ConcurrentHashMap.newKeySet<File>()

    internal var limits = EpubContentLimits.DEFAULT

    /** Test seam: called at each checkpoint with the import key, if one exists yet. */
    internal var checkpointHook: (suspend (ImportCheckpoint, String?) -> Unit)? = null

    private class Canceled : Exception()

    /**
     * Imports [documents] with bounded concurrency and emits one result per input as it finishes. Setting
     * [cancel] stops inputs that have not reached their file commit, which report [EpubImportResult.Canceled];
     * inputs past that point complete. Cancelling the collecting coroutine instead stops without results for
     * unfinished inputs, which are cleaned up or recovered by the journal.
     */
    fun import(
        documents: List<Uri>,
        cancel: StateFlow<Boolean> = MutableStateFlow(false),
    ): Flow<EpubImportResult> = channelFlow {
        recover()
        documents.forEachIndexed { index, uri ->
            launch { send(permits.withPermit { importOne(index, uri, cancel) }) }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun importAll(
        documents: List<Uri>,
        cancel: StateFlow<Boolean> = MutableStateFlow(false),
    ): List<EpubImportResult> = import(documents, cancel).toList().sortedBy { it.index }

    private suspend fun importOne(index: Int, uri: Uri, cancel: StateFlow<Boolean>): EpubImportResult {
        fun checkCancel() {
            if (cancel.value) throw Canceled()
        }

        val staged = File(storage.stagingDirectory.apply { mkdirs() }, "${PublicationStorage.newImportKey()}.part")
        activeStaging += staged
        try {
            checkCancel()
            val pinned = storage.pin() ?: fail(EpubImportError.StorageUnavailable())
            val displayName = displayName(uri)

            val hashed = openDocument(uri).use { input ->
                staged.outputStream().use { PublicationStorage.copyHashing(input, it, limits.maxInputBytes) }
            }
            checkpointHook?.invoke(ImportCheckpoint.STAGED, null)
            checkCancel()
            val inspected = inspector.inspect(staged, displayName?.substringBeforeLast('.'), limits)
            checkCancel()

            return lockFor(hashed.sha256).withLock {
                assets.getManagedByFingerprint(hashed.sha256)?.let {
                    return@withLock EpubImportResult.Duplicate(
                        index = index,
                        mangaId = mangaIdOf(it.chapterId),
                        chapterId = it.chapterId,
                        assetId = it.id,
                        fingerprint = it.fingerprint,
                        ownership = it.ownership,
                    )
                }
                commit(index, pinned, staged, hashed, inspected, ::checkCancel)
            }
        } catch (_: Canceled) {
            return EpubImportResult.Canceled(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpubImportException) {
            return e.error.toResult(index)
        } catch (e: FileNotFoundException) {
            return EpubImportResult.Failed(index, EpubImportError.SourceMissing())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "EPUB import failed: ${e.javaClass.simpleName}" }
            return PublicationStorage.classify(e) { EpubImportError.ReadFailed(it) }.toResult(index)
        } catch (e: SimulatedInterruption) {
            activeStaging -= staged
            throw e
        } finally {
            if (staged in activeStaging) {
                activeStaging -= staged
                staged.delete()
            }
        }
    }

    private suspend fun mangaIdOf(chapterId: Long): Long = chapters.getChapterById(chapterId)?.mangaId ?: -1

    private suspend fun commit(
        index: Int,
        pinned: PinnedRoot,
        staged: File,
        hashed: HashedBytes,
        inspected: InspectedPublication,
        checkCancel: () -> Unit,
    ): EpubImportResult {
        val operation = claim(pinned, hashed)
        var fileCommitted = false
        try {
            checkpointHook?.invoke(ImportCheckpoint.CLAIMED, operation.importKey)
            checkCancel()
            if (!storage.isCurrent(pinned)) fail(EpubImportError.StorageChanged())

            val directory = pinned.local.createDirectory(operation.importKey)
                ?.takeIf { it.isDirectory && it.name == operation.importKey }
                ?: fail(EpubImportError.CommitFailed("Unable to create the title folder"))
            val cover = writeSidecars(directory, inspected)
            if (!operation.reused) writeBook(directory, staged, hashed, operation.importKey, checkCancel)

            checkCancel()
            if (!storage.isCurrent(pinned)) fail(EpubImportError.StorageChanged())
            journal.markFileCommitted(operation.id)
            fileCommitted = true
        } catch (e: Throwable) {
            if (e is SimulatedInterruption) throw e
            if (!fileCommitted) withContext(NonCancellable) { discard(operation, pinned.local, e.javaClass.simpleName) }
            // Destination failures are commit failures (or lack of space), not failures to read the source
            if (e is IOException || e is SecurityException) {
                throw EpubImportException(PublicationStorage.classify(e) { EpubImportError.CommitFailed(it) })
            }
            throw e
        }

        // Past the file commit an import always completes: cancellation no longer applies and storage changes
        // keep the original root, which the asset records.
        return withContext(NonCancellable) {
            checkpointHook?.invoke(ImportCheckpoint.FILE_COMMITTED, operation.importKey)
            val committed = try {
                commitDatabase(operation, pinned.local, inspected, hashed)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "EPUB database commit failed: ${e.javaClass.simpleName}" }
                discard(operation, pinned.local, "Database commit failed")
                fail(EpubImportError.CommitFailed("Database commit failed"))
            }
            checkpointHook?.invoke(ImportCheckpoint.DATABASE_COMMITTED, operation.importKey)
            journal.markFinalized(operation.id)
            logcat { "Imported EPUB ${operation.importKey} (${hashed.sha256.take(12)})" }
            EpubImportResult.Imported(
                index = index,
                mangaId = committed.mangaId,
                chapterId = committed.chapterId,
                assetId = committed.assetId,
                importKey = operation.importKey,
                fingerprint = hashed.sha256,
                metadata = inspected.metadata,
                reusedKey = operation.reused,
                userOwnedCopies = assets.getUserOwnedByFingerprint(hashed.sha256).size,
            )
        }
    }

    /**
     * Claims the bytes in the journal. A finished import whose asset was deliberately removed but whose verified
     * file is still in place is reopened under its key, so importing it again creates no second managed copy.
     */
    private suspend fun claim(pinned: PinnedRoot, hashed: HashedBytes): ImportOperation {
        journal.getLatestFinalizedByFingerprint(hashed.sha256)
            ?.takeIf { it.storageRoot == pinned.root }
            ?.takeIf { previous ->
                val file = (
                    storage.locate(previous.storageRoot, ManagedPublicationLayout.relativePath(previous.importKey))
                        as? AssetLocation.Found
                    )?.file
                file != null && PublicationStorage.hash(file).sha256 == hashed.sha256
            }
            ?.let { journal.reclaim(it.id, pinned.root) }
            ?.let { return it }

        return journal.claim(
            importKey = PublicationStorage.newImportKey(),
            fingerprint = hashed.sha256,
            byteSize = hashed.size,
            storageRoot = pinned.root,
            fileName = ManagedPublicationLayout.BOOK_FILE,
        ) ?: fail(EpubImportError.CommitFailed("Another unfinished import holds the same bytes"))
    }

    /** Sidecars go in first, so the title is complete the moment its book file appears. */
    private fun writeSidecars(directory: UniFile, inspected: InspectedPublication): UniFile? {
        val comicInfo = SManga.create().apply {
            title = inspected.metadata.title
            author = inspected.metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(", ")
            description = inspected.metadata.description
        }.getComicInfo()
        replaceFile(directory, COMIC_INFO_FILE, xml.encodeToString(ComicInfo.serializer(), comicInfo).toByteArray())
        directory.findFile(".nomedia") ?: directory.createFile(".nomedia")
        return inspected.cover?.let { replaceFile(directory, ManagedPublicationLayout.COVER_FILE, it) }
    }

    private fun replaceFile(directory: UniFile, name: String, bytes: ByteArray): UniFile {
        directory.findFile(name)?.delete()
        val file = directory.createFile(name)?.takeIf { it.name == name }
            ?: fail(EpubImportError.CommitFailed("Unable to create $name"))
        file.openOutputStream().use { it.write(bytes) }
        return file
    }

    /**
     * Writes the book under a dot-name the Local source ignores, then renames it into place. Providers without
     * rename get a second copy under the final name. Either way the final file is re-read and must match the
     * fingerprint before the file commit is recorded.
     */
    private suspend fun writeBook(
        directory: UniFile,
        staged: File,
        hashed: HashedBytes,
        key: String,
        checkCancel: () -> Unit,
    ) {
        directory.findFile(ManagedPublicationLayout.PARTIAL_FILE)?.delete()
        directory.findFile(ManagedPublicationLayout.BOOK_FILE)?.delete()
        val partial = directory.createFile(ManagedPublicationLayout.PARTIAL_FILE)
            ?.takeIf { it.name == ManagedPublicationLayout.PARTIAL_FILE }
            ?: fail(EpubImportError.CommitFailed("Unable to create the book file"))
        copyInto(staged, partial)
        checkpointHook?.invoke(ImportCheckpoint.PART_WRITTEN, key)
        checkCancel()

        val renamed = runCatching { partial.renameTo(ManagedPublicationLayout.BOOK_FILE) }.getOrDefault(false) &&
            directory.findFile(ManagedPublicationLayout.BOOK_FILE) != null
        if (!renamed) {
            val book = directory.createFile(ManagedPublicationLayout.BOOK_FILE)
                ?.takeIf { it.name == ManagedPublicationLayout.BOOK_FILE }
                ?: fail(EpubImportError.CommitFailed("Unable to create the book file"))
            copyInto(staged, book)
            directory.findFile(ManagedPublicationLayout.PARTIAL_FILE)?.delete()
        }

        val book = directory.findFile(ManagedPublicationLayout.BOOK_FILE)
            ?: fail(EpubImportError.CommitFailed("The book file disappeared"))
        val verified = PublicationStorage.hash(book)
        if (verified.sha256 != hashed.sha256 || verified.size != hashed.size) {
            fail(EpubImportError.CommitFailed("The stored book does not match the imported bytes"))
        }
    }

    private suspend fun copyInto(staged: File, target: UniFile) {
        val output = target.openOutputStream()
        staged.inputStream().use { input -> output.use { PublicationStorage.copyHashing(input, it) } }
    }

    private suspend fun commitDatabase(
        operation: ImportOperation,
        local: UniFile,
        inspected: InspectedPublication,
        hashed: HashedBytes,
    ): CommittedImport {
        val directory = local.findFile(operation.importKey)
        val book = directory?.findFile(ManagedPublicationLayout.BOOK_FILE)
        return journal.commitDatabase(
            operationId = operation.id,
            chapterUrl = ManagedPublicationLayout.chapterUrl(operation.importKey),
            title = ImportedTitle(
                title = inspected.metadata.title,
                author = inspected.metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(", "),
                description = inspected.metadata.description,
                coverUri = directory?.findFile(ManagedPublicationLayout.COVER_FILE)?.uri?.toString(),
                // Same label the Local source gives managed items; chapter sync strips the title from item names
                chapterName = context.stringResource(MR.strings.local_epub_book),
                chapterUploadedAt = book?.lastModified()?.takeIf { it > 0 },
            ),
            asset = NewPublicationAsset(
                assetKey = operation.importKey,
                format = PayloadFormat.EPUB,
                layout = inspected.layout,
                contentKind = inspected.contentKind,
                fingerprint = hashed.sha256,
                byteSize = hashed.size,
                storageRoot = operation.storageRoot,
                relativePath = ManagedPublicationLayout.relativePath(operation.importKey),
                ownership = AssetOwnership.MANAGED,
                metadata = inspected.metadata,
            ),
        )
    }

    /**
     * Removes only what this operation wrote under its own key folder: nothing outside it, and never a verified
     * book file of a reopened import. Then records the abort.
     */
    private suspend fun discard(operation: ImportOperation, local: UniFile?, reason: String) {
        runCatching {
            val directory = local?.findFile(operation.importKey)
            if (directory != null && directory.isDirectory) {
                val names = buildList {
                    add(ManagedPublicationLayout.PARTIAL_FILE)
                    if (!operation.reused) {
                        addAll(
                            listOf(
                                ManagedPublicationLayout.BOOK_FILE,
                                ManagedPublicationLayout.COVER_FILE,
                                COMIC_INFO_FILE,
                                ".nomedia",
                            ),
                        )
                    }
                }
                names.forEach { directory.findFile(it)?.delete() }
                if (!operation.reused && directory.listFiles().isNullOrEmpty()) directory.delete()
            }
        }
        journal.markAborted(operation.id, reason)
    }

    /**
     * Brings every unfinished operation to a final phase; safe to run any number of times:
     * - claimed: its partial files are removed and the operation aborted;
     * - file committed: if the stored book still matches, the database commit is completed, otherwise discarded;
     * - database committed: finalized. A deliberately removed title is not recreated.
     * Private staging files no running import owns are deleted.
     */
    suspend fun recover(): EpubRecoveryReport = withContext(Dispatchers.IO) {
        recoveryLock.withLock {
            var aborted = 0
            var completed = 0
            var finalized = 0
            var failed = 0
            for (operation in journal.getActive()) {
                if (fingerprintLocks[operation.fingerprint]?.isLocked == true) continue
                try {
                    val local = UniFile.fromUri(context, Uri.parse(operation.storageRoot))
                        ?.takeIf { it.exists() }
                        ?.findFile(ManagedPublicationLayout.LOCAL_DIRECTORY)
                    when (operation.phase) {
                        ImportPhase.CLAIMED -> {
                            discard(operation, local, "Interrupted before the file commit")
                            aborted++
                        }
                        ImportPhase.FILE_COMMITTED -> {
                            if (completeForward(operation, local)) completed++ else aborted++
                        }
                        ImportPhase.DATABASE_COMMITTED -> {
                            journal.markFinalized(operation.id)
                            finalized++
                        }
                        else -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "EPUB import recovery failed: ${e.javaClass.simpleName}" }
                    failed++
                }
            }
            storage.stagingDirectory.listFiles()?.filter { it !in activeStaging }?.forEach { it.delete() }
            EpubRecoveryReport(aborted, completed, finalized, failed)
        }
    }

    private suspend fun completeForward(operation: ImportOperation, local: UniFile?): Boolean {
        // Storage that cannot be reached is left for a later pass rather than guessed about
        local ?: throw IOException("Storage unavailable")
        val book = local.findFile(operation.importKey)?.findFile(ManagedPublicationLayout.BOOK_FILE)
        val copy = File.createTempFile("recover-", ".epub", storage.workingDirectory.apply { mkdirs() })
        try {
            val hashed = book?.openInputStream()?.use { input ->
                copy.outputStream().use { PublicationStorage.copyHashing(input, it) }
            }
            if (hashed == null || hashed.sha256 != operation.fingerprint) {
                discard(operation, local, "Stored book did not verify during recovery")
                return false
            }
            val inspected = inspector.inspect(copy, null, limits)
            commitDatabase(operation, local, inspected, hashed)
            journal.markFinalized(operation.id)
            return true
        } finally {
            copy.delete()
        }
    }

    private fun lockFor(fingerprint: String) = fingerprintLocks.computeIfAbsent(fingerprint) { Mutex() }

    private fun openDocument(uri: Uri) = try {
        context.contentResolver.openInputStream(uri)
            ?: fail(EpubImportError.ReadFailed("The provider returned no data"))
    } catch (e: SecurityException) {
        fail(EpubImportError.PermissionDenied())
    }

    /** The provider's display name: a hint for a missing title, never a path component. */
    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun EpubImportError.toResult(index: Int): EpubImportResult = when (this) {
        is EpubImportError.Malformed,
        is EpubImportError.UnsupportedFormat,
        is EpubImportError.UnsupportedProtection,
        is EpubImportError.TooLarge,
        -> EpubImportResult.Unsupported(index, this)
        else -> EpubImportResult.Failed(index, this)
    }

    companion object {
        const val MAX_CONCURRENT_IMPORTS = 2
    }
}

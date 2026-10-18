package eu.kanade.tachiyomi.data.epub

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.epub.EpubParseResult
import eu.kanade.tachiyomi.ui.reader.epub.handOff
import eu.kanade.tachiyomi.ui.reader.epub.parseEpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.Asset
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.epub.model.AssetAvailability
import tachiyomi.domain.epub.model.AssetOwnership
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import tachiyomi.domain.epub.model.NewPublicationAsset
import tachiyomi.domain.epub.model.PayloadFormat
import tachiyomi.domain.epub.model.PublicationAsset
import tachiyomi.domain.epub.repository.PublicationAssetRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** How an existing item resolves for reading. The image path stays the default for unregistered items. */
sealed interface PublicationResolution {
    /** No publication is registered; existing image-page behavior applies. */
    data object NotPublication : PublicationResolution

    data class Epub(val asset: PublicationAsset, val file: UniFile) : PublicationResolution

    /** Registered, but its file cannot be used now. Identity and reading state are kept. */
    data class Unavailable(val asset: PublicationAsset, val availability: AssetAvailability) : PublicationResolution
}

@Inject
@SingleIn(AppScope::class)
class PublicationAssetResolver(
    private val assets: PublicationAssetRepository,
    private val storage: PublicationStorage,
) {

    /** Resolves [chapterId] and records the availability it observed. */
    suspend fun resolve(chapterId: Long): PublicationResolution = withContext(Dispatchers.IO) {
        val asset = assets.getByChapterId(chapterId) ?: return@withContext PublicationResolution.NotPublication
        val (resolution, availability) = when (val location = storage.locate(asset.storageRoot, asset.relativePath)) {
            is AssetLocation.Found -> PublicationResolution.Epub(asset, location.file) to AssetAvailability.AVAILABLE
            AssetLocation.Missing -> unavailable(asset, AssetAvailability.MISSING)
            AssetLocation.Inaccessible -> unavailable(asset, AssetAvailability.INACCESSIBLE)
            AssetLocation.RootChanged -> unavailable(asset, AssetAvailability.ROOT_CHANGED)
        }
        if (availability != asset.availability) assets.updateAvailability(listOf(chapterId), availability)
        resolution
    }

    private fun unavailable(asset: PublicationAsset, availability: AssetAvailability) =
        PublicationResolution.Unavailable(asset, availability) to availability
}

/**
 * An open production publication. It owns the Readium objects and, only when the durable original could not be
 * opened in place, a disposable working copy. Closing never touches the managed or user-owned original.
 */
class LocalPublicationSession internal constructor(
    val asset: PublicationAsset,
    val publication: Publication,
    private val readiumAsset: Asset,
    private val workingCopy: File?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val navigatorFactory: EpubNavigatorFactory by lazy { EpubNavigatorFactory(publication) }

    val usesWorkingCopy: Boolean get() = workingCopy != null

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { publication.close() }
        runCatching { readiumAsset.close() }
        workingCopy?.let { runCatching { it.delete() } }
    }
}

@Inject
@SingleIn(AppScope::class)
class LocalPublicationOpener(
    private val context: Context,
    private val resolver: PublicationAssetResolver,
    private val storage: PublicationStorage,
) {
    sealed interface Result {
        data class Success(val session: LocalPublicationSession) : Result
        data class Failure(val error: EpubImportError, val availability: AssetAvailability? = null) : Result
    }

    /**
     * Opens the publication of [chapterId] after checking its bytes still match the recorded fingerprint, so a
     * replaced file never inherits locators of another revision. Ownership of a returned session passes to the
     * caller only when this function returns; cancellation during the return closes it here.
     */
    suspend fun open(chapterId: Long): Result =
        handOff(Dispatchers.IO, release = { (it as? Result.Success)?.session?.close() }) { openOnIo(chapterId) }

    private suspend fun openOnIo(chapterId: Long): Result {
        val (asset, file) = when (val resolution = resolver.resolve(chapterId)) {
            PublicationResolution.NotPublication ->
                return Result.Failure(EpubImportError.UnsupportedFormat("Item has no registered publication"))
            is PublicationResolution.Unavailable -> return Result.Failure(
                when (resolution.availability) {
                    AssetAvailability.MISSING -> EpubImportError.SourceMissing()
                    AssetAvailability.ROOT_CHANGED -> EpubImportError.StorageChanged()
                    else -> EpubImportError.StorageUnavailable()
                },
                resolution.availability,
            )
            is PublicationResolution.Epub -> resolution.asset to resolution.file
        }

        var workingCopy: File? = null
        var transferred = false
        try {
            val local = file.filePath?.let(::File)?.takeIf { file.uri.scheme == "file" && it.canRead() }
            // Reading stops past the recorded size: a longer file is a replacement, never the registered bytes
            val hashed = if (local != null) {
                local.inputStream().use { PublicationStorage.copyHashing(it, null, asset.byteSize) }
            } else {
                // Providers give only streams; the ZIP container needs random access, so a private copy is used
                val directory = storage.workingDirectory.apply { mkdirs() }
                val copy = File.createTempFile("open-", ".epub", directory).also { workingCopy = it }
                val input = file.openInputStream()
                input.use { source ->
                    copy.outputStream().use { PublicationStorage.copyHashing(source, it, asset.byteSize) }
                }
            }
            if (hashed.sha256 != asset.fingerprint) return Result.Failure(EpubImportError.FingerprintMismatch())

            val parsed = when (val result = parseEpub(context, workingCopy ?: local!!, DenyAllHttpClient)) {
                is EpubParseResult.Failure -> return Result.Failure(result.error.toImportError())
                is EpubParseResult.Success -> result
            }
            val session = LocalPublicationSession(asset, parsed.publication, parsed.asset, workingCopy)
            transferred = true
            return Result.Success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpubImportException) {
            val error = if (e.error is EpubImportError.TooLarge) EpubImportError.FingerprintMismatch() else e.error
            return Result.Failure(error)
        } catch (_: FileNotFoundException) {
            return Result.Failure(EpubImportError.SourceMissing())
        } catch (e: SecurityException) {
            return Result.Failure(EpubImportError.PermissionDenied())
        } catch (e: IOException) {
            return Result.Failure(PublicationStorage.classify(e) { EpubImportError.ReadFailed(it) })
        } finally {
            if (!transferred) workingCopy?.let { runCatching { it.delete() } }
        }
    }
}

/**
 * Registers EPUBs that already sit in Local title folders. The user's file is only read: never copied into
 * managed storage, renamed, rewritten or deleted, and the existing title/item metadata is left as the Local
 * source produced it.
 */
@Inject
@SingleIn(AppScope::class)
class LocalEpubRegistrar(
    private val assets: PublicationAssetRepository,
    private val chapters: ChapterRepository,
    private val mangas: MangaRepository,
    private val storage: PublicationStorage,
    private val inspector: LocalPublicationInspector,
) {
    internal var limits = EpubContentLimits.DEFAULT

    sealed interface Result {
        data class Registered(val asset: PublicationAsset, val existing: Boolean) : Result
        data class Failed(val chapterId: Long, val error: EpubImportError) : Result
    }

    suspend fun register(chapterId: Long): Result = withContext(Dispatchers.IO) {
        assets.getByChapterId(chapterId)?.let { return@withContext Result.Registered(it, existing = true) }
        val chapter = chapters.getChapterById(chapterId)
            ?: return@withContext Result.Failed(chapterId, EpubImportError.SourceMissing("Unknown item"))
        val manga = mangas.getMangaById(chapter.mangaId)
        if (manga.source != LocalSource.ID || !chapter.url.endsWith(".epub", ignoreCase = true)) {
            return@withContext Result.Failed(chapterId, EpubImportError.UnsupportedFormat("Not a Local EPUB item"))
        }
        val relativePath = "${ManagedPublicationLayout.LOCAL_DIRECTORY}/${chapter.url}"
        val root = storage.currentRoot()
        val file = when (val location = storage.locate(root, relativePath)) {
            is AssetLocation.Found -> location.file
            AssetLocation.Missing -> return@withContext Result.Failed(chapterId, EpubImportError.SourceMissing())
            else -> return@withContext Result.Failed(chapterId, EpubImportError.StorageUnavailable())
        }

        val directory = storage.workingDirectory.apply { mkdirs() }
        val copy = File.createTempFile("register-", ".epub", directory)
        try {
            val input = file.openInputStream()
            val hashed = input.use { source ->
                copy.outputStream().use { PublicationStorage.copyHashing(source, it, limits.maxInputBytes) }
            }
            val inspected = inspector.inspect(copy, fallbackTitle = chapter.name, limits)
            val id = assets.insert(
                chapterId,
                NewPublicationAsset(
                    assetKey = "user-${UUID.randomUUID()}",
                    format = PayloadFormat.EPUB,
                    layout = inspected.layout,
                    contentKind = inspected.contentKind,
                    fingerprint = hashed.sha256,
                    byteSize = hashed.size,
                    storageRoot = root,
                    relativePath = relativePath,
                    ownership = AssetOwnership.USER,
                    metadata = inspected.metadata,
                ),
            )
            logcat { "Registered user-owned EPUB asset $id (${hashed.sha256.take(12)})" }
            Result.Registered(assets.getByChapterId(chapterId)!!, existing = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpubImportException) {
            Result.Failed(chapterId, e.error)
        } catch (e: FileNotFoundException) {
            Result.Failed(chapterId, EpubImportError.SourceMissing())
        } catch (e: Exception) {
            // A concurrent registration of the same item loses on the item's unique constraint
            assets.getByChapterId(chapterId)?.let { Result.Registered(it, existing = true) }
                ?: Result.Failed(chapterId, PublicationStorage.classify(e) { EpubImportError.ReadFailed(it) }).also {
                    logcat(LogPriority.ERROR) { "EPUB registration failed: ${e.javaClass.simpleName}" }
                }
        } finally {
            copy.delete()
        }
    }

    /** Registers every Local `.epub` item of a title, one result per item. */
    suspend fun registerAll(mangaId: Long): List<Result> {
        return chapters.getChapterByMangaId(mangaId)
            .filter { it.url.endsWith(".epub", ignoreCase = true) }
            .map { register(it.id) }
    }
}

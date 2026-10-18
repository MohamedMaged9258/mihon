package tachiyomi.data.epub

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.data.Epub_asset
import tachiyomi.data.Epub_import_operation
import tachiyomi.data.Epub_location_bookmark
import tachiyomi.data.Epub_reading_state
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.epub.model.AssetAvailability
import tachiyomi.domain.epub.model.AssetOwnership
import tachiyomi.domain.epub.model.CommittedImport
import tachiyomi.domain.epub.model.ContentKind
import tachiyomi.domain.epub.model.EpubLocationBookmark
import tachiyomi.domain.epub.model.EpubReaderMode
import tachiyomi.domain.epub.model.EpubReadingState
import tachiyomi.domain.epub.model.EpubTextAlign
import tachiyomi.domain.epub.model.EpubTheme
import tachiyomi.domain.epub.model.EpubTitlePreferences
import tachiyomi.domain.epub.model.ImportOperation
import tachiyomi.domain.epub.model.ImportPhase
import tachiyomi.domain.epub.model.ImportedTitle
import tachiyomi.domain.epub.model.LocalTitleIndexEntry
import tachiyomi.domain.epub.model.NewPublicationAsset
import tachiyomi.domain.epub.model.PayloadFormat
import tachiyomi.domain.epub.model.PublicationAsset
import tachiyomi.domain.epub.model.PublicationLayout
import tachiyomi.domain.epub.model.PublicationMetadata
import tachiyomi.domain.epub.model.StoredLocator
import tachiyomi.domain.epub.repository.EpubBookmarkRepository
import tachiyomi.domain.epub.repository.EpubImportJournal
import tachiyomi.domain.epub.repository.EpubReadingStateRepository
import tachiyomi.domain.epub.repository.EpubTitlePreferencesRepository
import tachiyomi.domain.epub.repository.PublicationAssetRepository
import tachiyomi.domain.manga.model.Manga
import java.util.UUID
import kotlin.time.Instant

private const val LOCAL_SOURCE_ID = 0L

// Authors cannot contain a newline once their metadata is normalized, so it separates them losslessly.
private const val AUTHOR_SEPARATOR = "\n"

private fun now() = System.currentTimeMillis()

private fun Long.toBoolean() = this != 0L

private fun Boolean.toLong() = if (this) 1L else 0L

internal fun Epub_asset.toDomain() = PublicationAsset(
    id = id,
    chapterId = chapter_id,
    assetKey = asset_key,
    format = PayloadFormat.of(payload_format),
    layout = PublicationLayout.of(layout),
    contentKind = ContentKind.of(content_kind),
    fingerprint = fingerprint,
    byteSize = byte_size,
    storageRoot = storage_root,
    relativePath = relative_path,
    ownership = AssetOwnership.of(ownership),
    availability = AssetAvailability.of(availability),
    availabilityCheckedAt = availability_checked_at,
    metadata = PublicationMetadata(
        title = meta_title,
        authors = meta_authors?.split(AUTHOR_SEPARATOR).orEmpty(),
        description = null,
        language = meta_language,
        publisher = meta_publisher,
        identifier = meta_identifier,
        published = meta_published,
    ),
    createdAt = created_at,
    updatedAt = updated_at,
)

private fun Epub_import_operation.toDomain() = ImportOperation(
    id = id,
    importKey = import_key,
    fingerprint = fingerprint,
    byteSize = byte_size,
    storageRoot = storage_root,
    fileName = file_name,
    phase = ImportPhase.of(phase),
    reused = reused.toBoolean(),
    mangaId = manga_id,
    chapterId = chapter_id,
    error = error,
    createdAt = created_at,
    updatedAt = updated_at,
)

private fun Epub_reading_state.toDomain() = EpubReadingState(
    chapterId = chapter_id,
    fingerprint = fingerprint,
    resume = resume_locator?.let { StoredLocator(locator_version.toInt(), it) },
    furthest = furthest_locator?.let { StoredLocator(locator_version.toInt(), it) },
    furthestProgression = furthest_progression,
    revision = revision,
    updatedAt = updated_at,
    resetAt = reset_at,
)

private fun Epub_location_bookmark.toDomain() = EpubLocationBookmark(
    key = bookmark_key,
    chapterId = chapter_id,
    fingerprint = fingerprint,
    locator = StoredLocator(locator_version.toInt(), locator),
    label = label,
    context = context,
    createdAt = created_at,
    updatedAt = updated_at,
)

private suspend fun Database.insertAsset(chapterId: Long, asset: NewPublicationAsset): Long {
    return epub_assetQueries.insert(
        chapterId = chapterId,
        assetKey = asset.assetKey,
        payloadFormat = asset.format.code,
        layout = asset.layout.code,
        contentKind = asset.contentKind.code,
        fingerprint = asset.fingerprint,
        byteSize = asset.byteSize,
        storageRoot = asset.storageRoot,
        relativePath = asset.relativePath,
        ownership = asset.ownership.code,
        availability = AssetAvailability.AVAILABLE.code,
        now = now(),
        metaTitle = asset.metadata.title,
        metaAuthors = asset.metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(AUTHOR_SEPARATOR),
        metaLanguage = asset.metadata.language,
        metaPublisher = asset.metadata.publisher,
        metaIdentifier = asset.metadata.identifier,
        metaPublished = asset.metadata.published,
    ).awaitAsOne()
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PublicationAssetRepositoryImpl(
    private val database: Database,
) : PublicationAssetRepository {

    override suspend fun getByChapterId(chapterId: Long): PublicationAsset? {
        return database.epub_assetQueries.getByChapterId(chapterId).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun getByMangaId(mangaId: Long): List<PublicationAsset> {
        return database.epub_assetQueries.getByMangaId(mangaId).awaitAsList().map { it.toDomain() }
    }

    override suspend fun getAll(): List<PublicationAsset> {
        return database.epub_assetQueries.getAll().awaitAsList().map { it.toDomain() }
    }

    override suspend fun getManagedByFingerprint(fingerprint: String): PublicationAsset? {
        return database.epub_assetQueries.getManagedByFingerprint(fingerprint).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun getUserOwnedByFingerprint(fingerprint: String): List<PublicationAsset> {
        return database.epub_assetQueries.getUserOwnedByFingerprint(fingerprint).awaitAsList().map { it.toDomain() }
    }

    override suspend fun insert(chapterId: Long, asset: NewPublicationAsset): Long {
        return database.insertAsset(chapterId, asset)
    }

    override suspend fun updateAvailability(chapterIds: Collection<Long>, availability: AssetAvailability) {
        if (chapterIds.isEmpty()) return
        database.epub_assetQueries.updateAvailability(availability.code, now(), chapterIds)
    }

    override suspend fun unregister(chapterId: Long) {
        database.epub_assetQueries.deleteByChapterId(chapterId)
    }

    override suspend fun getLocalTitleIndex(): List<LocalTitleIndexEntry> {
        return database.epub_assetQueries.localTitleIndex().awaitAsList()
            .groupBy { it.remote_url }
            .map { (folder, rows) ->
                LocalTitleIndexEntry(
                    folder = folder,
                    title = rows.first().remote_title,
                    managed = rows.any { it.ownership == AssetOwnership.MANAGED.code },
                )
            }
    }

    override suspend fun getUncommittedImportKeys(): Set<String> {
        return database.epub_import_operationQueries.getUncommittedKeys().awaitAsList().toSet()
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EpubImportJournalImpl(
    private val database: Database,
) : EpubImportJournal {

    private val queries get() = database.epub_import_operationQueries

    override suspend fun claim(
        importKey: String,
        fingerprint: String,
        byteSize: Long,
        storageRoot: String,
        fileName: String,
    ): ImportOperation? {
        val id = queries.claim(importKey, fingerprint, byteSize, storageRoot, fileName, now()).awaitAsOneOrNull()
        return id?.let { get(it) }
    }

    override suspend fun reclaim(operationId: Long, storageRoot: String): ImportOperation? {
        // The active-fingerprint index rejects a reopen while another import holds the bytes
        val id = try {
            queries.reclaim(storageRoot, now(), operationId).awaitAsOneOrNull()
        } catch (_: Exception) {
            null
        }
        return id?.let { get(it) }
    }

    override suspend fun get(operationId: Long): ImportOperation? {
        return queries.getById(operationId).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun getActive(): List<ImportOperation> {
        return queries.getActive().awaitAsList().map { it.toDomain() }
    }

    override suspend fun getActiveByFingerprint(fingerprint: String): ImportOperation? {
        return queries.getActiveByFingerprint(fingerprint).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun getLatestFinalizedByFingerprint(fingerprint: String): ImportOperation? {
        return queries.getLatestFinalizedByFingerprint(fingerprint).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun getAll(): List<ImportOperation> {
        return queries.getAll().awaitAsList().map { it.toDomain() }
    }

    override suspend fun markFileCommitted(operationId: Long) {
        queries.setFileCommitted(now(), operationId)
    }

    override suspend fun markFinalized(operationId: Long) {
        queries.setFinalized(now(), operationId)
    }

    override suspend fun markAborted(operationId: Long, error: String) {
        queries.setAborted(error, now(), operationId)
    }

    override suspend fun commitDatabase(
        operationId: Long,
        chapterUrl: String,
        title: ImportedTitle,
        asset: NewPublicationAsset,
    ): CommittedImport {
        return database.transactionWithResult {
            val operation = queries.getById(operationId).awaitAsOne().toDomain()
            check(operation.phase == ImportPhase.CLAIMED || operation.phase == ImportPhase.FILE_COMMITTED) {
                "Import operation is ${operation.phase}"
            }
            val nowMillis = now()

            // Reuses an existing title by (source, url); an entry in the library keeps its details
            val manga = database.mangaQueries.insertNetworkManga(
                sourceId = LOCAL_SOURCE_ID,
                remoteUrl = operation.importKey,
                remoteTitle = title.title,
                remoteAuthor = title.author,
                remoteArtist = null,
                remoteDescription = title.description,
                remoteGenre = null,
                remoteStatus = 0,
                remoteCover = title.coverUri,
                remoteUpdateStrategy = Manga.create().updateStrategy,
                remoteMemo = Manga.create().memo,
                userFavoriteAt = null,
                userReaderFlags = 0,
                userChapterFlags = 0,
                stateChapterLastUpdate = null,
                stateChapterNextUpdate = null,
                stateChapterFetchInterval = 0,
                stateCoverLastModified = Instant.fromEpochMilliseconds(nowMillis),
                stateInitialized = true,
                updateTitle = true,
                updateCover = title.coverUri != null,
                updateDetails = true,
                mapper = MangaMapper::mapManga,
            ).awaitAsOne()

            // Reuses an existing item by (title, url); importing is not a reading event
            val chapterId = database.chapterQueries.getChapterByUrlAndMangaId(manga.id, chapterUrl)
                .awaitAsOneOrNull()?.id
                ?: database.chapterQueries.insertReturningId(
                    mangaId = manga.id,
                    remoteUrl = chapterUrl,
                    remoteName = title.chapterName,
                    remoteScanlator = null,
                    remoteChapterNumber = Chapter.create().chapterNumber,
                    remoteDateUpload = title.chapterUploadedAt?.let(Instant::fromEpochMilliseconds),
                    remoteOrder = 0,
                    remoteMemo = Chapter.create().memo,
                    userRead = false,
                    userBookmark = false,
                    userLastPageRead = 0,
                    stateDateFetch = Instant.fromEpochMilliseconds(nowMillis),
                ).awaitAsOne()

            val existing = database.epub_assetQueries.getByChapterId(chapterId).awaitAsOneOrNull()
            val assetId = when {
                existing == null -> database.insertAsset(chapterId, asset)
                existing.fingerprint == asset.fingerprint && existing.ownership == AssetOwnership.MANAGED.code ->
                    existing.id
                else -> error("Item already has a different publication")
            }

            queries.setDatabaseCommitted(manga.id, chapterId, nowMillis, operationId)
            CommittedImport(mangaId = manga.id, chapterId = chapterId, assetId = assetId)
        }
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EpubReadingStateRepositoryImpl(
    private val database: Database,
) : EpubReadingStateRepository {

    override suspend fun get(chapterId: Long, fingerprint: String): EpubReadingState? {
        return database.epub_reading_stateQueries.get(chapterId, fingerprint).awaitAsOneOrNull()?.toDomain()
    }

    override suspend fun getByChapterId(chapterId: Long): List<EpubReadingState> {
        return database.epub_reading_stateQueries.getByChapterId(chapterId).awaitAsList().map { it.toDomain() }
    }

    override suspend fun save(state: EpubReadingState): Boolean {
        val version = (state.resume ?: state.furthest)?.version ?: StoredLocator.CURRENT_VERSION
        return database.epub_reading_stateQueries.upsert(
            chapterId = state.chapterId,
            fingerprint = state.fingerprint,
            locatorVersion = version.toLong(),
            resumeLocator = state.resume?.json,
            furthestLocator = state.furthest?.json,
            furthestProgression = state.furthestProgression,
            revision = state.revision,
            updatedAt = state.updatedAt,
            resetAt = state.resetAt,
        ).awaitAsOneOrNull() != null
    }

    override suspend fun reset(chapterId: Long, fingerprint: String): Long {
        return database.epub_reading_stateQueries.reset(chapterId, fingerprint, now()).awaitAsOne()
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EpubTitlePreferencesRepositoryImpl(
    private val database: Database,
) : EpubTitlePreferencesRepository {

    override suspend fun get(mangaId: Long): EpubTitlePreferences {
        val row = database.epub_title_preferencesQueries.get(mangaId).awaitAsOneOrNull()
            ?: return EpubTitlePreferences()
        return EpubTitlePreferences(
            readerMode = EpubReaderMode.of(row.reader_mode),
            fontScale = row.font_scale,
            lineHeight = row.line_height,
            pageMargins = row.page_margins,
            textAlign = EpubTextAlign.of(row.text_align),
            theme = EpubTheme.of(row.theme),
            scroll = row.scroll?.toBoolean(),
            publisherStyles = row.publisher_styles?.toBoolean(),
        )
    }

    override suspend fun set(mangaId: Long, preferences: EpubTitlePreferences) {
        if (preferences == EpubTitlePreferences()) return clear(mangaId)
        database.epub_title_preferencesQueries.set(
            mangaId = mangaId,
            readerMode = preferences.readerMode?.code,
            fontScale = preferences.fontScale,
            lineHeight = preferences.lineHeight,
            pageMargins = preferences.pageMargins,
            textAlign = preferences.textAlign?.code,
            theme = preferences.theme?.code,
            scroll = preferences.scroll?.toLong(),
            publisherStyles = preferences.publisherStyles?.toLong(),
            now = now(),
        )
    }

    override suspend fun clear(mangaId: Long) {
        database.epub_title_preferencesQueries.clear(mangaId)
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EpubBookmarkRepositoryImpl(
    private val database: Database,
) : EpubBookmarkRepository {

    override suspend fun getByChapterId(chapterId: Long): List<EpubLocationBookmark> {
        return database.epub_location_bookmarkQueries.getByChapterId(chapterId).awaitAsList().map { it.toDomain() }
    }

    override suspend fun add(
        chapterId: Long,
        fingerprint: String,
        locator: StoredLocator,
        label: String?,
        context: String?,
    ): EpubLocationBookmark {
        val key = UUID.randomUUID().toString()
        database.epub_location_bookmarkQueries.insert(
            bookmarkKey = key,
            chapterId = chapterId,
            fingerprint = fingerprint,
            locatorVersion = locator.version.toLong(),
            locator = locator.json,
            label = label,
            context = context,
            now = now(),
        )
        return database.epub_location_bookmarkQueries.getByKey(key).awaitAsOne().toDomain()
    }

    override suspend fun updateLabel(key: String, label: String?) {
        database.epub_location_bookmarkQueries.updateLabel(label, now(), key)
    }

    override suspend fun delete(key: String) {
        database.epub_location_bookmarkQueries.delete(key)
    }
}

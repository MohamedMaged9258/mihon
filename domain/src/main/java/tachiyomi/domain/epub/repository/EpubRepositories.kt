package tachiyomi.domain.epub.repository

import tachiyomi.domain.epub.model.AssetAvailability
import tachiyomi.domain.epub.model.CommittedImport
import tachiyomi.domain.epub.model.EpubLocationBookmark
import tachiyomi.domain.epub.model.EpubReadingState
import tachiyomi.domain.epub.model.EpubTitlePreferences
import tachiyomi.domain.epub.model.ImportOperation
import tachiyomi.domain.epub.model.ImportedTitle
import tachiyomi.domain.epub.model.LocalTitleIndexEntry
import tachiyomi.domain.epub.model.NewPublicationAsset
import tachiyomi.domain.epub.model.PublicationAsset
import tachiyomi.domain.epub.model.StoredLocator

interface PublicationAssetRepository {

    suspend fun getByChapterId(chapterId: Long): PublicationAsset?

    suspend fun getByMangaId(mangaId: Long): List<PublicationAsset>

    suspend fun getAll(): List<PublicationAsset>

    suspend fun getManagedByFingerprint(fingerprint: String): PublicationAsset?

    suspend fun getUserOwnedByFingerprint(fingerprint: String): List<PublicationAsset>

    /** Associates an existing item with its publication; fails if the item already has one. */
    suspend fun insert(chapterId: Long, asset: NewPublicationAsset): Long

    suspend fun updateAvailability(chapterIds: Collection<Long>, availability: AssetAvailability)

    /** Deliberately removes the association only. Reading state, bookmarks and every file are kept. */
    suspend fun unregister(chapterId: Long)

    /** Registered Local folders and their titles, from one indexed query. */
    suspend fun getLocalTitleIndex(): List<LocalTitleIndexEntry>

    /** Managed folders whose import has not reached its database commit and must not be discoverable. */
    suspend fun getUncommittedImportKeys(): Set<String>
}

interface EpubImportJournal {

    /** Records a new import, or returns null while another unfinished import holds the same bytes. */
    suspend fun claim(
        importKey: String,
        fingerprint: String,
        byteSize: Long,
        storageRoot: String,
        fileName: String,
    ): ImportOperation?

    /** Reopens a finished operation so a deliberately removed import can be registered again under its key. */
    suspend fun reclaim(operationId: Long, storageRoot: String): ImportOperation?

    suspend fun get(operationId: Long): ImportOperation?

    suspend fun getActive(): List<ImportOperation>

    suspend fun getActiveByFingerprint(fingerprint: String): ImportOperation?

    suspend fun getLatestFinalizedByFingerprint(fingerprint: String): ImportOperation?

    suspend fun getAll(): List<ImportOperation>

    suspend fun markFileCommitted(operationId: Long)

    suspend fun markFinalized(operationId: Long)

    suspend fun markAborted(operationId: Long, error: String)

    /**
     * In one transaction: creates or reuses the Local title (source 0, url = import key) and its item, adds the
     * managed asset and moves the operation to [tachiyomi.domain.epub.model.ImportPhase.DATABASE_COMMITTED].
     * Existing library state (favorite, categories, read flags, timestamps) is kept.
     */
    suspend fun commitDatabase(
        operationId: Long,
        chapterUrl: String,
        title: ImportedTitle,
        asset: NewPublicationAsset,
    ): CommittedImport
}

interface EpubReadingStateRepository {

    suspend fun get(chapterId: Long, fingerprint: String): EpubReadingState?

    /** Every revision of an item's state, newest first; older fingerprints are kept, not reinterpreted. */
    suspend fun getByChapterId(chapterId: Long): List<EpubReadingState>

    /** Stores [state] only if its revision is newer than the stored one. Returns whether it was applied. */
    suspend fun save(state: EpubReadingState): Boolean

    /** Clears Resume and furthest progress and records the reset time. Returns the new revision. */
    suspend fun reset(chapterId: Long, fingerprint: String): Long
}

interface EpubTitlePreferencesRepository {

    suspend fun get(mangaId: Long): EpubTitlePreferences

    /** Replaces every override; a null property clears that override. */
    suspend fun set(mangaId: Long, preferences: EpubTitlePreferences)

    suspend fun clear(mangaId: Long)
}

interface EpubBookmarkRepository {

    suspend fun getByChapterId(chapterId: Long): List<EpubLocationBookmark>

    suspend fun add(
        chapterId: Long,
        fingerprint: String,
        locator: StoredLocator,
        label: String?,
        context: String?,
    ): EpubLocationBookmark

    suspend fun updateLabel(key: String, label: String?)

    suspend fun delete(key: String)
}

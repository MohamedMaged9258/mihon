package eu.kanade.tachiyomi.data.epub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.epub.EpubBookmarkRepositoryImpl
import tachiyomi.data.epub.EpubImportJournalImpl
import tachiyomi.data.epub.EpubReadingStateRepositoryImpl
import tachiyomi.data.epub.EpubTitlePreferencesRepositoryImpl
import tachiyomi.data.epub.PublicationAssetRepositoryImpl
import tachiyomi.domain.epub.model.AssetAvailability
import tachiyomi.domain.epub.model.AssetOwnership
import tachiyomi.domain.epub.model.ContentKind
import tachiyomi.domain.epub.model.EpubReaderMode
import tachiyomi.domain.epub.model.EpubReadingState
import tachiyomi.domain.epub.model.EpubTheme
import tachiyomi.domain.epub.model.EpubTitlePreferences
import tachiyomi.domain.epub.model.ImportPhase
import tachiyomi.domain.epub.model.ImportedTitle
import tachiyomi.domain.epub.model.NewPublicationAsset
import tachiyomi.domain.epub.model.PayloadFormat
import tachiyomi.domain.epub.model.PublicationLayout
import tachiyomi.domain.epub.model.PublicationMetadata
import tachiyomi.domain.epub.model.StoredLocator

/**
 * Schema evidence on the application's real driver configuration (bundled SQLite, WAL, foreign keys on), using
 * separate database files so the app's own database is never touched.
 */
@RunWith(AndroidJUnit4::class)
class EpubDatabaseInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val drivers = mutableListOf<SqlDriver>()
    private val names = listOf("epub-upgrade-test.db", "epub-fresh-test.db")

    private val fp1 = "a".repeat(64)
    private val fp2 = "b".repeat(64)

    init {
        names.forEach { context.deleteDatabase(it) }
    }

    @After
    fun tearDown() {
        drivers.forEach { runCatching { it.close() } }
        names.forEach { context.deleteDatabase(it) }
    }

    private fun open(name: String, migrating: MutableStateFlow<Boolean> = MutableStateFlow(false)): SqlDriver {
        return DatabaseBindings.createSqlDriver(context, name, migrating).also {
            drivers += it
            it.query("SELECT 1") // forces creation or migration
        }
    }

    // The application driver is asynchronous, so every statement is awaited
    private fun SqlDriver.exec(sql: String) = runBlocking { execute(null, sql, 0).await() }

    private fun SqlDriver.query(sql: String, columns: Int = 1): List<List<String?>> = runBlocking {
        executeQuery(null, sql, { cursor ->
            QueryResult.AsyncValue {
                val rows = mutableListOf<List<String?>>()
                while (cursor.next().await()) rows += List(columns) { cursor.getString(it) }
                rows
            }
        }, 0).await()
    }

    private fun SqlDriver.version() = query("PRAGMA user_version").single().single()!!.toLong()

    private val epubTables = listOf(
        "epub_asset",
        "epub_import_operation",
        "epub_location_bookmark",
        "epub_reading_state",
        "epub_title_preferences",
    )

    private fun SqlDriver.epubSchema(): List<String> = query(
        "SELECT type || ':' || name || ':' || coalesce(sql, '') FROM sqlite_master WHERE tbl_name LIKE 'epub_%' " +
            "ORDER BY name",
    ).map { it.single()!!.replace(Regex("\\s+"), " ") }

    private fun SqlDriver.dumpUserData(): List<List<String?>> =
        query("SELECT * FROM manga ORDER BY id", 21) +
            query("SELECT * FROM chapter ORDER BY id", 13) +
            query("SELECT * FROM history ORDER BY id", 5) +
            query("SELECT * FROM category ORDER BY id", 4) +
            query("SELECT * FROM manga_category ORDER BY manga_id, category_id", 2)

    @Test
    fun upgradeFromP0101SchemaKeepsDataAndMatchesFreshSchema() {
        val current = Database.Schema.version
        assertEquals("P01-02 adds exactly one migration (20.sqm)", 21L, current)

        // Build the P01-01 schema (version 20): the current schema without the additive EPUB tables
        val old = open(names[0])
        epubTables.forEach { old.exec("DROP TABLE $it") }
        old.exec("PRAGMA user_version = 20")
        assertTrue(old.epubSchema().isEmpty())
        old.exec(
            """INSERT INTO manga(id, source_id, remote_url, remote_title, remote_author, remote_status,
               remote_update_strategy, remote_memo, user_favorite_at, user_notes, user_reader_flags,
               user_chapter_flags, state_chapter_fetch_interval, state_initialized)
               VALUES (10, 0, 'My Folder', 'My Folder', 'Author', 1, 0, X'7B7D', 1700000000000, 'note', 6, 3, 0, 1),
                      (11, 12345, '/remote/1', 'Remote Manga', NULL, 2, 1, X'7B7D', NULL, '', 0, 0, 7, 0)""",
        )
        old.exec(
            """INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, remote_chapter_number,
               remote_date_upload, remote_order, remote_memo, user_read, user_bookmark, user_last_page_read,
               state_date_fetch)
               VALUES (20, 10, 'My Folder/book.epub', 'Book', 'Publisher', 1.0, 1600000000000, 0, X'7B7D',
                       1, 1, 37, 1700000000000),
                      (21, 11, '/remote/1/c1', 'Chapter 1', NULL, 1.0, NULL, 0, X'7B7D', 0, 0, 5, 1700000000001)""",
        )
        old.exec(
            "INSERT INTO history(id, manga_id, chapter_id, read_at, read_duration) " +
                "VALUES (30, 10, 20, 1700000000002, 4000)",
        )
        old.exec("INSERT INTO category(id, name, `order`, flags) VALUES (100, 'Books', 100, 0)")
        old.exec("INSERT INTO manga_category(manga_id, category_id) VALUES (10, 100)")
        val before = old.dumpUserData()
        old.close()
        drivers.remove(old)

        val migrating = MutableStateFlow(true)
        val upgraded = open(names[0], migrating)
        assertEquals(current, upgraded.version())
        assertFalse("onOpen must clear the migrating flag", migrating.value)
        assertEquals("Existing rows, flags and timestamps are unchanged", before, upgraded.dumpUserData())
        assertEquals(listOf(listOf("1")), upgraded.query("PRAGMA foreign_keys"))
        assertEquals(listOf(listOf("ok")), upgraded.query("PRAGMA integrity_check"))
        assertTrue(upgraded.query("PRAGMA foreign_key_check").isEmpty())

        val fresh = open(names[1])
        assertEquals(current, fresh.version())
        assertEquals("Migrated and fresh EPUB schema are identical", fresh.epubSchema(), upgraded.epubSchema())
        assertEquals(
            epubTables,
            fresh.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'epub_%' ORDER BY name",
            ).map {
                it.single()
            },
        )
    }

    private fun database(driver: SqlDriver) = DatabaseBindings.providesDatabase(driver)

    private fun SqlDriver.seedTitle(mangaId: Long, chapterIds: List<Long>) {
        exec(
            """INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, remote_update_strategy,
               remote_memo, user_notes, user_reader_flags, user_chapter_flags, state_chapter_fetch_interval,
               state_initialized) VALUES ($mangaId, 0, 'folder-$mangaId', 'T', 0, 0, X'7B7D', '', 0, 0, 0, 1)""",
        )
        chapterIds.forEach { id ->
            exec(
                """INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_order,
                   remote_memo, user_read, user_bookmark, user_last_page_read, state_date_fetch)
                   VALUES ($id, $mangaId, 'folder-$mangaId/$id.epub', 'c', 1, 0, X'7B7D', 1, 0, 0, 0)""",
            )
        }
    }

    private fun asset(key: String, fingerprint: String, ownership: AssetOwnership) = NewPublicationAsset(
        assetKey = key,
        format = PayloadFormat.EPUB,
        layout = PublicationLayout.REFLOWABLE,
        contentKind = ContentKind.TEXT,
        fingerprint = fingerprint,
        byteSize = 10,
        storageRoot = "file:///root",
        relativePath = "local/$key/book.epub",
        ownership = ownership,
        metadata = PublicationMetadata("عنوان", listOf("A", "B"), null, "ar", "P", "urn:x", null),
    )

    private fun expectConstraintFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected a constraint failure")
        } catch (e: AssertionError) {
            throw e
        } catch (_: Exception) {
        }
    }

    @Test
    fun repositoriesRoundTripAndEnforceConstraints() = runBlocking<Unit> {
        val driver = open(names[1])
        val db = database(driver)
        driver.seedTitle(1, listOf(11, 12, 13))
        val assets = PublicationAssetRepositoryImpl(db)
        val states = EpubReadingStateRepositoryImpl(db)
        val prefs = EpubTitlePreferencesRepositoryImpl(db)
        val bookmarks = EpubBookmarkRepositoryImpl(db)

        // Assets: one per item, managed duplicates refused, user-owned duplicates allowed
        assets.insert(11, asset("epub-a", fp1, AssetOwnership.MANAGED))
        expectConstraintFailure { runBlocking { assets.insert(12, asset("epub-b", fp1, AssetOwnership.MANAGED)) } }
        expectConstraintFailure { runBlocking { assets.insert(11, asset("epub-c", fp2, AssetOwnership.MANAGED)) } }
        assets.insert(12, asset("user-1", fp1, AssetOwnership.USER))
        assets.insert(13, asset("user-2", fp1, AssetOwnership.USER))
        val stored = assets.getByChapterId(11)!!
        assertEquals(listOf("A", "B"), stored.metadata.authors)
        assertEquals("عنوان", stored.metadata.title)
        assertEquals(stored.id, assets.getManagedByFingerprint(fp1)!!.id)
        assertEquals(2, assets.getUserOwnedByFingerprint(fp1).size)

        // Invalid codes and versions are rejected by the schema itself
        expectConstraintFailure { driver.exec("UPDATE epub_asset SET availability = 9 WHERE chapter_id = 11") }
        expectConstraintFailure { driver.exec("UPDATE epub_asset SET fingerprint = 'short' WHERE chapter_id = 11") }
        expectConstraintFailure {
            driver.exec(
                "INSERT INTO epub_reading_state(chapter_id, fingerprint, locator_version, revision, updated_at) " +
                    "VALUES (11, '$fp1', 2, 1, 0)",
            )
        }
        expectConstraintFailure {
            driver.exec(
                "INSERT INTO epub_title_preferences(manga_id, schema_version, theme, updated_at) VALUES (1, 1, 7, 0)",
            )
        }
        expectConstraintFailure {
            driver.exec(
                "INSERT INTO epub_reading_state(chapter_id, fingerprint, locator_version, revision, updated_at) " +
                    "VALUES (999, '$fp1', 1, 1, 0)",
            )
        }

        // Reading state: separate Resume/furthest, revision guard, fingerprint separation, reset marker
        val resume = StoredLocator(1, """{"href":"s2.xhtml","locations":{"progression":0.35}}""")
        val furthest = StoredLocator(1, """{"href":"s2.xhtml","locations":{"progression":0.7}}""")
        assertTrue(states.save(EpubReadingState(11, fp1, resume, furthest, 0.7, 2, 100, null)))
        assertFalse(
            "An older revision cannot replace a newer one",
            states.save(EpubReadingState(11, fp1, furthest, furthest, 0.7, 1, 200, null)),
        )
        assertTrue(states.save(EpubReadingState(11, fp2, furthest, furthest, 0.9, 1, 300, null)))
        assertEquals(resume, states.get(11, fp1)!!.resume)
        assertEquals(furthest, states.get(11, fp1)!!.furthest)
        assertEquals(0.9, states.get(11, fp2)!!.furthestProgression!!, 0.0)
        assertEquals(2, states.getByChapterId(11).size)
        val revision = states.reset(11, fp1)
        assertEquals(3, revision)
        states.get(11, fp1)!!.let {
            assertNull(it.resume)
            assertNull(it.furthest)
            assertNotNull(it.resetAt)
        }
        assertEquals("Reset is per fingerprint", furthest, states.get(11, fp2)!!.resume)

        // Preferences: explicit set, clearing one override, clearing all
        prefs.set(
            1,
            EpubTitlePreferences(
                readerMode = EpubReaderMode.BOOK,
                fontScale = 1.4,
                theme = EpubTheme.SEPIA,
                scroll = true,
            ),
        )
        assertEquals(
            EpubTitlePreferences(
                readerMode = EpubReaderMode.BOOK,
                fontScale = 1.4,
                theme = EpubTheme.SEPIA,
                scroll = true,
            ),
            prefs.get(1),
        )
        prefs.set(1, prefs.get(1).copy(fontScale = null))
        assertNull("Null clears the override", prefs.get(1).fontScale)
        assertEquals(EpubTheme.SEPIA, prefs.get(1).theme)
        prefs.clear(1)
        assertEquals(EpubTitlePreferences(), prefs.get(1))

        // Bookmarks
        val mark = bookmarks.add(11, fp1, resume, "Arabic passage", "…context…")
        bookmarks.updateLabel(mark.key, null)
        assertNull(bookmarks.getByChapterId(11).single().label)

        // Losing the asset association keeps reading state and bookmarks
        assets.unregister(11)
        assertNull(assets.getByChapterId(11))
        assertEquals(2, states.getByChapterId(11).size)
        assertEquals(1, bookmarks.getByChapterId(11).size)
        assets.updateAvailability(listOf(12), AssetAvailability.MISSING)
        assertEquals(AssetAvailability.MISSING, assets.getByChapterId(12)!!.availability)

        // Deliberately deleting the item cascades its auxiliary records; the title's preferences go with the title
        prefs.set(1, EpubTitlePreferences(theme = EpubTheme.DARK))
        driver.exec("DELETE FROM chapter WHERE id = 11")
        assertTrue(states.getByChapterId(11).isEmpty())
        assertTrue(bookmarks.getByChapterId(11).isEmpty())
        driver.exec("DELETE FROM manga WHERE id = 1")
        assertNull(assets.getByChapterId(12))
        assertEquals(EpubTitlePreferences(), prefs.get(1))
    }

    @Test
    fun importJournalClaimsCommitsAndKeepsHistoryAfterRemoval() = runBlocking<Unit> {
        val driver = open(names[1])
        val db = database(driver)
        val journal = EpubImportJournalImpl(db)
        val assets = PublicationAssetRepositoryImpl(db)

        val op = journal.claim("epub-aaaaaaaaaaaaaaaaaaaaaaaaaa", fp1, 10, "file:///root", "book.epub")!!
        assertEquals(ImportPhase.CLAIMED, op.phase)
        assertNull(
            "One unfinished import per byte set",
            journal.claim("epub-bbbbbbbbbbbbbbbbbbbbbbbbbb", fp1, 10, "file:///root", "book.epub"),
        )
        assertEquals(setOf(op.importKey), assets.getUncommittedImportKeys())

        journal.markFileCommitted(op.id)
        val title = ImportedTitle("Title", "Author", "Desc", null, "Title", 5)
        val committed = journal.commitDatabase(
            op.id,
            "${op.importKey}/book.epub",
            title,
            asset(op.importKey, fp1, AssetOwnership.MANAGED),
        )
        assertEquals(ImportPhase.DATABASE_COMMITTED, journal.get(op.id)!!.phase)
        assertTrue(assets.getUncommittedImportKeys().isEmpty())
        journal.markFinalized(op.id)
        assertEquals(
            "Title",
            driver.query("SELECT remote_title FROM manga WHERE id = ${committed.mangaId}").single().single(),
        )
        assertEquals(
            "Importing is not a reading event",
            listOf(listOf("0", "0", "0")),
            driver.query(
                "SELECT user_read, user_bookmark, user_last_page_read FROM chapter WHERE id = ${committed.chapterId}",
                3,
            ),
        )
        assertTrue(driver.query("SELECT * FROM history").isEmpty())
        assertEquals(op.importKey, assets.getLocalTitleIndex().single().folder)

        // Removing the title deliberately keeps the finished journal entry, with its references cleared
        driver.exec("DELETE FROM manga WHERE id = ${committed.mangaId}")
        val after = journal.get(op.id)!!
        assertEquals(ImportPhase.FINALIZED, after.phase)
        assertNull(after.mangaId)
        assertNull(after.chapterId)
        assertEquals(after.id, journal.getLatestFinalizedByFingerprint(fp1)!!.id)
        assertEquals(op.id, journal.reclaim(op.id, "file:///root")!!.id)
        assertTrue(journal.get(op.id)!!.reused)
    }
}

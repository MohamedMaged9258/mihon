package eu.kanade.tachiyomi.data.epub

import android.net.Uri
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.epub.model.AssetAvailability
import tachiyomi.domain.epub.model.EpubReadingState
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import tachiyomi.domain.epub.model.StoredLocator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.LocalStorageUnavailableException
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File

/**
 * Managed imports through the real Local source and chapter synchronization: discovery, search by metadata,
 * refresh and "restart" keep identities and metadata, legacy folders keep working, and an unavailable file or
 * storage tree never deletes a registered item or its state.
 */
@RunWith(Parameterized::class)
class EpubLocalDiscoveryInstrumentedTest(private val kind: EpubTestEnvironment.Kind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds() = EpubTestEnvironment.Kind.entries.toList()
    }

    private val env = EpubTestEnvironment(kind)

    @After
    fun tearDown() = env.close()

    /** Refreshes a title the way the manga screen and library update do, returning its stored items. */
    private suspend fun refresh(source: LocalSource, manga: Manga): List<Chapter> {
        val update = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = true, fetchChapters = true)
        env.graph.syncChaptersWithSource.await(update.chapters, manga, source)
        return env.graph.chapterRepository.getChapterByMangaId(manga.id)
    }

    private fun Manga.toSManga() = SManga.create().also {
        it.url = url
        it.title = title
        it.author = author
        it.description = description
        it.thumbnail_url = thumbnailUrl
    }

    /** A fresh Local source over the same storage and database, as after an app restart. */
    private fun restartedSource(): LocalSource {
        val fileSystem = LocalSourceFileSystem(env.graph.storageManager)
        return LocalSource(
            env.context,
            fileSystem,
            LocalCoverManager(env.context, fileSystem),
            env.graph.publicationAssetRepository,
        )
    }

    @Test
    fun managedImportIsDiscoverableSearchableAndStableAcrossRefreshAndRestart() = runBlocking<Unit> {
        val title = "حكاية المدينة — City Tale"
        val imported = env.importAll(env.document(TestEpubs.book(title = title, authors = listOf("كاتب")))).single()
            as EpubImportResult.Imported

        // Legacy content next to it: a folder of images and a user EPUB with image pages
        File(env.localDirectory, "Legacy Comic/Chapter 1").apply { mkdirs() }
            .let { File(it, "001.png").writeBytes(TestEpubs.PNG) }
        File(
            env.localDirectory,
            "Legacy Comic/Image Book.epub",
        ).writeBytes(TestEpubs.book(title = "Pages", imagePages = true))

        val titles = env.browseTitles()
        assertEquals(title, titles[imported.importKey])
        assertEquals("Legacy Comic", titles["Legacy Comic"])
        assertEquals(setOf(imported.importKey), env.browseTitles("المدينة").keys)
        assertEquals(setOf(imported.importKey), env.browseTitles("city tale").keys)
        assertTrue("The internal key is not the display title", env.browseTitles("epub-").isEmpty())

        val manga = env.graph.mangaRepository.getMangaById(imported.mangaId)
        repeat(2) { round ->
            val source = if (round == 0) env.localSource else restartedSource()
            val chapters = refresh(source, env.graph.mangaRepository.getMangaById(manga.id))
            val chapter = chapters.single()
            assertEquals(imported.chapterId, chapter.id)
            assertEquals(ManagedPublicationLayout.chapterUrl(imported.importKey), chapter.url)
            assertEquals("Book", chapter.name)
            assertEquals(null, chapter.scanlator)
            val update = source.getMangaUpdate(
                manga.toSManga(),
                emptyList(),
                fetchDetails = true,
                fetchChapters = false,
            )
            assertEquals("Details keep the declared title, not the folder key", title, update.manga.title)
            assertEquals("كاتب", update.manga.author)
            assertEquals(
                title,
                source.getSearchManga(1, "", source.getFilterList()).mangas.single {
                    it.url ==
                        imported.importKey
                }.title,
            )
        }
        assertFalse(
            "No legacy JSON is created or consumed",
            File(env.localDirectory, imported.importKey).list()!!.any {
                it.endsWith(".json")
            },
        )

        // Legacy discovery is unchanged: a directory chapter and an image EPUB chapter
        val legacy = env.graph.mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = 0, url = "Legacy Comic", title = "Legacy Comic")),
        ).single().also { env.track(it.id) }
        val legacyChapters = refresh(env.localSource, legacy)
        assertEquals(
            setOf("Legacy Comic/Chapter 1", "Legacy Comic/Image Book.epub"),
            legacyChapters.map {
                it.url
            }.toSet(),
        )
        val format = env.localSource.getFormat(
            eu.kanade.tachiyomi.source.model.SChapter.create().apply { url = "Legacy Comic/Image Book.epub" },
        )
        assertTrue(
            "Image EPUBs still open through the image reader path",
            format is tachiyomi.source.local.io.Format.Epub,
        )
    }

    @Test
    fun missingFileAndUnavailableStorageKeepIdentityStateAndFlags() = runBlocking<Unit> {
        val imported = env.importAll(env.document(TestEpubs.book(title = "Kept"))).single() as EpubImportResult.Imported
        val chapterId = imported.chapterId
        env.graph.chapterRepository.update(
            ChapterUpdate(chapterId) {
                read = true
                bookmark = true
                lastPageRead = 7
            },
        )
        val locator = StoredLocator(1, """{"href":"s1.xhtml","locations":{"progression":0.5}}""")
        env.graph.epubReadingStateRepository.save(
            EpubReadingState(chapterId, imported.fingerprint, locator, locator, 0.5, 1, 1, null),
        )
        env.graph.epubBookmarkRepository.add(chapterId, imported.fingerprint, locator, "mark", null)
        val manga = env.graph.mangaRepository.getMangaById(imported.mangaId)

        fun assertKept(availability: AssetAvailability) = runBlocking<Unit> {
            val chapter = env.graph.chapterRepository.getChapterById(chapterId)
            assertNotNull("The item is kept", chapter)
            assertTrue(chapter!!.read)
            assertTrue(chapter.bookmark)
            assertEquals(7, chapter.lastPageRead)
            assertEquals(availability, env.graph.publicationAssetRepository.getByChapterId(chapterId)!!.availability)
            assertEquals(locator, env.graph.epubReadingStateRepository.get(chapterId, imported.fingerprint)!!.resume)
            assertEquals(1, env.graph.epubBookmarkRepository.getByChapterId(chapterId).size)
        }

        // The book file is deleted outside the app
        val book = File(env.localDirectory, "${imported.importKey}/${ManagedPublicationLayout.BOOK_FILE}")
        val bytes = book.readBytes()
        book.delete()
        refresh(env.localSource, manga)
        assertKept(AssetAvailability.MISSING)
        val resolution = env.graph.publicationAssetResolver.resolve(chapterId)
        assertTrue(resolution is PublicationResolution.Unavailable)

        // It comes back
        book.writeBytes(bytes)
        refresh(env.localSource, manga)
        assertKept(AssetAvailability.AVAILABLE)

        // The whole storage tree becomes unreachable (unmounted, deleted or no longer granted)
        if (kind == EpubTestEnvironment.Kind.PROVIDER) {
            NoRenameDocumentsProvider.Faults.revoked = true
        } else {
            env.useRoot(Uri.fromFile(File(env.context.getExternalFilesDir(null), "epub-missing-root")).toString())
        }
        try {
            refresh(env.localSource, manga)
            fail("An unreachable tree must not look like an empty title")
        } catch (_: LocalStorageUnavailableException) {
        }
        assertKept(AssetAvailability.AVAILABLE)
        val unavailable = env.graph.publicationAssetResolver.resolve(chapterId) as PublicationResolution.Unavailable
        assertTrue(
            unavailable.availability == AssetAvailability.INACCESSIBLE ||
                unavailable.availability == AssetAvailability.ROOT_CHANGED,
        )
        assertKept(unavailable.availability)
        NoRenameDocumentsProvider.Faults.revoked = false
        env.useRoot(env.root)
        assertTrue(env.graph.publicationAssetResolver.resolve(chapterId) is PublicationResolution.Epub)
    }

    @Test
    fun unregisteredLegacyItemsKeepTheirRemovalSemantics() = runBlocking<Unit> {
        val folder = File(env.localDirectory, "Plain Folder").apply { mkdirs() }
        File(folder, "Chapter 1").apply { mkdirs() }.let { File(it, "001.png").writeBytes(TestEpubs.PNG) }
        val manga = env.graph.mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = 0, url = "Plain Folder", title = "Plain Folder")),
        ).single().also { env.track(it.id) }
        assertEquals(1, refresh(env.localSource, manga).size)
        File(folder, "Chapter 1").deleteRecursively()
        assertTrue("A removed unregistered chapter is still removed", refresh(env.localSource, manga).isEmpty())
    }
}

package eu.kanade.tachiyomi.data.epub

import eu.kanade.tachiyomi.data.epub.NoRenameDocumentsProvider.DeleteFault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import tachiyomi.domain.epub.model.AssetAvailability
import tachiyomi.domain.epub.model.AssetOwnership
import tachiyomi.domain.epub.model.ContentKind
import tachiyomi.domain.epub.model.EpubReadingState
import tachiyomi.domain.epub.model.ImportPhase
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import tachiyomi.domain.epub.model.PublicationLayout
import tachiyomi.domain.epub.model.StoredLocator
import java.io.File
import java.security.MessageDigest

/**
 * The production import service against two kinds of library storage: a plain directory, and a Storage Access
 * Framework provider without rename. Inputs are `content://` documents of a real provider.
 */
@RunWith(Parameterized::class)
class EpubImportInstrumentedTest(private val kind: EpubTestEnvironment.Kind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds() = EpubTestEnvironment.Kind.entries.toList()

        const val ARABIC_TITLE = "رواية الاختبار: Mixed عربي English ١٢٣"
        const val ARABIC_AUTHOR = "مؤلف الاختبار"
    }

    private val env = EpubTestEnvironment(kind)
    private val service get() = env.graph.epubImportService
    private val assets get() = env.graph.publicationAssetRepository
    private val journal get() = env.graph.epubImportJournal

    @After
    fun tearDown() = env.close()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bookFile(key: String) = File(env.localDirectory, "$key/${ManagedPublicationLayout.BOOK_FILE}")

    private fun EpubImportResult.imported() = this as? EpubImportResult.Imported
        ?: throw AssertionError("Expected Imported, got $this")

    @Test
    fun batchImportsMetadataCoversAndPerFileOutcomes() = runBlocking<Unit> {
        val epub2 = TestEpubs.book(
            title = "EPUB Two",
            version = 2,
            publisher = "Pub",
            identifier = "urn:isbn:9780000000001",
        )
        val epub3 = TestEpubs.book(
            title = ARABIC_TITLE,
            authors = listOf(ARABIC_AUTHOR, "Second Author"),
            language = "ar",
            description = "&lt;p&gt;وصف &lt;b&gt;قصير&lt;/b&gt;&lt;/p&gt;",
        )
        val fixed = TestEpubs.book(title = "Fixed Comic", fixedLayout = true, imagePages = true, cover = null)
        val corrupt = TestEpubs.book().let { it.copyOf(it.size / 2) }
        val notEpub = "plain text".toByteArray()

        val results = env.importAll(
            env.document(epub2),
            env.document(epub3),
            env.document(fixed),
            env.document(corrupt),
            env.document(notEpub, "renamed.epub"),
        )
        assertEquals(listOf(0, 1, 2, 3, 4), results.map { it.index })
        val (r2, r3, rFixed) = results.take(3).map { it.imported() }
        assertTrue(results[3] is EpubImportResult.Unsupported)
        assertTrue(results[4] is EpubImportResult.Unsupported)

        // Shared records and durable files
        assertEquals("EPUB Two", r2.metadata.title)
        assertEquals("Pub", r2.metadata.publisher)
        assertEquals("urn:isbn:9780000000001", r2.metadata.identifier)
        assertEquals(ARABIC_TITLE, r3.metadata.title)
        assertEquals(listOf(ARABIC_AUTHOR, "Second Author"), r3.metadata.authors)
        assertEquals("ar", r3.metadata.language)
        assertEquals("وصف قصير", r3.metadata.description)

        val manga = env.graph.mangaRepository.getMangaById(r3.mangaId)
        assertEquals(0L, manga.source)
        assertEquals(r3.importKey, manga.url)
        assertEquals(ARABIC_TITLE, manga.title)
        assertEquals("$ARABIC_AUTHOR, Second Author", manga.author)
        assertNull("Importing does not add to the library by itself", manga.favoriteAt)
        val chapter = env.graph.chapterRepository.getChapterById(r3.chapterId)!!
        assertEquals(ManagedPublicationLayout.chapterUrl(r3.importKey), chapter.url)
        assertFalse(chapter.read)
        assertEquals(0, chapter.lastPageRead)
        assertNull("Publication metadata is not stored in the scanlator field", chapter.scanlator)

        assertTrue(bookFile(r3.importKey).isFile)
        assertEquals(sha(epub3), sha(bookFile(r3.importKey).readBytes()))
        assertTrue("Declared EPUB 3 cover", File(env.localDirectory, "${r3.importKey}/cover.jpg").length() > 0)
        assertTrue("Declared EPUB 2 cover", File(env.localDirectory, "${r2.importKey}/cover.jpg").length() > 0)
        assertFalse(File(env.localDirectory, "${rFixed.importKey}/cover.jpg").exists())
        assertFalse(File(env.localDirectory, "${r3.importKey}/${ManagedPublicationLayout.PARTIAL_FILE}").exists())
        assertEquals(3, env.keyFolders().size)

        val asset = assets.getByChapterId(r3.chapterId)!!
        assertEquals(AssetOwnership.MANAGED, asset.ownership)
        assertEquals(AssetAvailability.AVAILABLE, asset.availability)
        assertEquals(env.root, asset.storageRoot)
        assertEquals(epub3.size.toLong(), asset.byteSize)
        assertEquals(ContentKind.TEXT, asset.contentKind)
        assertEquals(PublicationLayout.REFLOWABLE, asset.layout)
        assertEquals(PublicationLayout.FIXED, assets.getByChapterId(rFixed.chapterId)!!.layout)
        assertEquals(ContentKind.IMAGE, assets.getByChapterId(rFixed.chapterId)!!.contentKind)
        assertTrue(journal.getActive().isEmpty())
        assertTrue("Staging is removed", env.graph.publicationStorage.stagingDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun exactAndConcurrentDuplicatesKeepOneManagedCopy() = runBlocking<Unit> {
        val bytes = TestEpubs.book(title = "Same")
        val results = env.importAll(env.document(bytes), env.document(bytes), env.document(bytes))
        val imported = results.filterIsInstance<EpubImportResult.Imported>().single()
        val duplicates = results.filterIsInstance<EpubImportResult.Duplicate>()
        assertEquals(2, duplicates.size)
        duplicates.forEach {
            assertEquals(imported.mangaId, it.mangaId)
            assertEquals(imported.chapterId, it.chapterId)
            assertEquals(AssetOwnership.MANAGED, it.ownership)
        }
        // Retrying the batch returns the committed identity instead of a second copy
        val retry = env.importAll(env.document(bytes)).single() as EpubImportResult.Duplicate
        assertEquals(imported.assetId, retry.assetId)
        assertEquals(1, env.keyFolders().size)

        // Same title, different bytes: a distinct edition, no replacement
        val edition = env.importAll(
            env.document(TestEpubs.book(title = "Same", bodyText = "Revised")),
        ).single().imported()
        assertTrue(edition.mangaId != imported.mangaId)
        assertEquals(2, env.keyFolders().size)
        assertEquals(imported.fingerprint, assets.getByChapterId(imported.chapterId)!!.fingerprint)
    }

    @Test
    fun managedCopySurvivesSourceDeletionAndCloseNeverDeletesIt() = runBlocking<Unit> {
        val bytes = TestEpubs.book(title = "Durable")
        val source = env.document(bytes)
        val imported = env.importAll(source).single().imported()
        env.deleteDocument(source)

        val opened = env.graph.localPublicationOpener.open(imported.chapterId) as LocalPublicationOpener.Result.Success
        val session = opened.session
        assertEquals(2, session.publication.readingOrder.size)
        assertEquals(kind == EpubTestEnvironment.Kind.PROVIDER, session.usesWorkingCopy)
        session.close()
        session.close() // idempotent
        assertTrue("The managed original stays", bookFile(imported.importKey).isFile)
        assertEquals(sha(bytes), sha(bookFile(imported.importKey).readBytes()))
        assertTrue(
            "Working copies are removed",
            env.graph.publicationStorage.workingDirectory.listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun openingCanceledAtAnyPointLeaksNothingAndKeepsTheOriginal() = runBlocking<Unit> {
        val bytes = TestEpubs.book(
            title = "Cancel",
            extra = listOf(
                "OEBPS/filler.bin" to ByteArray(2_000_000).also {
                    java.util.Random(1).nextBytes(it)
                },
            ),
        )
        val imported = env.importAll(env.document(bytes)).single().imported()
        val opener = env.graph.localPublicationOpener
        repeat(12) { attempt ->
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                (opener.open(imported.chapterId) as? LocalPublicationOpener.Result.Success)?.session?.close()
            }
            kotlinx.coroutines.delay(attempt * 5L)
            job.cancelAndJoin()
        }
        assertTrue(
            "Canceled opens leave no working copies",
            env.graph.publicationStorage.workingDirectory.listFiles().orEmpty().isEmpty(),
        )
        assertEquals(sha(bytes), sha(bookFile(imported.importKey).readBytes()))
    }

    @Test
    fun replacedBytesAreReportedAndNotOpened() = runBlocking<Unit> {
        val imported = env.importAll(env.document(TestEpubs.book(title = "Changed"))).single().imported()
        bookFile(imported.importKey).writeBytes(TestEpubs.book(title = "Changed", bodyText = "other"))
        val result = env.graph.localPublicationOpener.open(imported.chapterId)
        assertTrue((result as LocalPublicationOpener.Result.Failure).error is EpubImportError.FingerprintMismatch)
    }

    @Test
    fun hostileAndProtectedInputsAreRejectedWithoutFetching() = runBlocking<Unit> {
        val before = DenyAllHttpClient.attemptedRequestCount
        service.limits = EpubContentLimits(maxEntryBytes = 200_000)
        val results = env.importAll(
            env.document(TestEpubs.book(extra = listOf("../../escape.xhtml" to "x".toByteArray()))),
            env.document(TestEpubs.book(extra = listOf("OEBPS/bomb.bin" to ByteArray(1_000_000)))),
            env.document(TestEpubs.book(extra = TestEpubs.encryption("http://www.w3.org/2001/04/xmlenc#aes256-cbc"))),
            env.document(
                TestEpubs.book(title = "Fonts", extra = TestEpubs.encryption("http://www.idpf.org/2008/embedding")),
            ),
            env.document(
                TestEpubs.book(
                    title = "External",
                    bodyText = "<img src=\"http://127.0.0.1:9/x.png\"/>remote",
                    extra = listOf("OEBPS/style.css" to "@import url(http://127.0.0.1:9/a.css);".toByteArray()),
                ),
            ),
        )
        assertTrue((results[0] as EpubImportResult.Unsupported).error is EpubImportError.Malformed)
        assertTrue((results[1] as EpubImportResult.Unsupported).error is EpubImportError.TooLarge)
        assertTrue((results[2] as EpubImportResult.Unsupported).error is EpubImportError.UnsupportedProtection)
        assertEquals("Font obfuscation is not DRM", "Fonts", results[3].imported().metadata.title)
        val external = results[4].imported()
        val session = (
            env.graph.localPublicationOpener.open(
                external.chapterId,
            ) as LocalPublicationOpener.Result.Success
            ).session
        session.close()
        assertEquals("Inspection and opening fetch nothing", before, DenyAllHttpClient.attemptedRequestCount)
        assertEquals(2, env.keyFolders().size)
    }

    @Test
    fun missingRevokedAndFailingSourcesFailWithTypedErrors() = runBlocking<Unit> {
        val gone = env.document(TestEpubs.book())
        env.deleteDocument(gone)
        assertTrue((env.importAll(gone).single() as EpubImportResult.Failed).error is EpubImportError.SourceMissing)

        val doc = env.document(TestEpubs.book())
        NoRenameDocumentsProvider.Faults.revoked = true
        val revoked = env.importAll(doc).single()
        NoRenameDocumentsProvider.Faults.revoked = false
        if (kind == EpubTestEnvironment.Kind.PROVIDER) {
            // The library tree itself is behind the revoked provider
            assertTrue(
                revoked.toString(),
                (revoked as EpubImportResult.Failed).error is EpubImportError.StorageUnavailable,
            )
        } else {
            assertTrue(
                revoked.toString(),
                (revoked as EpubImportResult.Failed).error is EpubImportError.PermissionDenied,
            )
        }
        assertTrue(env.keyFolders().isEmpty())
    }

    @Test
    fun providerWriteFailureAndFullStorageLeaveNothingVisible() = runBlocking<Unit> {
        if (kind != EpubTestEnvironment.Kind.PROVIDER) return@runBlocking
        NoRenameDocumentsProvider.Faults.failWriteAfterBytes = 1_000
        val failed = env.importAll(env.document(TestEpubs.book(title = "Partial"))).single()
        assertTrue(failed.toString(), (failed as EpubImportResult.Failed).error is EpubImportError.CommitFailed)

        NoRenameDocumentsProvider.Faults.reset()
        NoRenameDocumentsProvider.Faults.noSpace = true
        val full = env.importAll(env.document(TestEpubs.book(title = "Full"))).single()
        // Across a provider pipe the writer only sees the pipe close, so a remote full volume is a commit failure;
        // ENOSPC from a local volume maps to InsufficientSpace (EpubArchiveGuardTest)
        val error = (full as EpubImportResult.Failed).error
        assertTrue(full.toString(), error is EpubImportError.CommitFailed || error is EpubImportError.InsufficientSpace)
        NoRenameDocumentsProvider.Faults.reset()

        assertTrue("No partial title folder remains", env.keyFolders().isEmpty())
        assertTrue(env.browseTitles().isEmpty())
        assertTrue(journal.getActive().isEmpty())
        assertTrue(journal.getAll().filter { it.storageRoot == env.root }.all { it.phase == ImportPhase.ABORTED })

        // The same bytes import cleanly afterwards
        env.importAll(env.document(TestEpubs.book(title = "Partial"))).single().imported()
    }

    @Test
    fun storageChangeBeforeFileCommitAbortsOnTheOriginalRoot() = runBlocking<Unit> {
        val other = File(env.context.getExternalFilesDir(null), "epub-test-other").apply { mkdirs() }
        try {
            service.checkpointHook = { checkpoint, _ ->
                if (checkpoint == ImportCheckpoint.CLAIMED) {
                    env.graph.storagePreferences.baseStorageDirectory.set(android.net.Uri.fromFile(other).toString())
                }
            }
            val result = env.importAll(env.document(TestEpubs.book())).single()
            assertTrue(result.toString(), (result as EpubImportResult.Failed).error is EpubImportError.StorageChanged)
            assertTrue(env.keyFolders().isEmpty())
            assertTrue(File(other, "local").listFiles().orEmpty().isEmpty())
        } finally {
            service.checkpointHook = null
            env.useRoot(env.root)
            other.deleteRecursively()
        }
    }

    @Test
    fun cancellationReportsUnfinishedInputsAndKeepsCommittedOnes() = runBlocking<Unit> {
        val cancel = MutableStateFlow(false)
        val first = TestEpubs.book(title = "First")
        val second = TestEpubs.book(title = "Second")
        service.checkpointHook = { checkpoint, _ ->
            // The first input reaches its file commit; the batch is then canceled
            if (checkpoint == ImportCheckpoint.FILE_COMMITTED) cancel.value = true
        }
        val firstResult = service.importAll(listOf(env.document(first)), cancel).single()
        val imported = firstResult.imported().also { env.track(it.mangaId) }
        val secondResult = service.importAll(listOf(env.document(second)), cancel).single()
        assertTrue(secondResult is EpubImportResult.Canceled)
        assertEquals(1, env.keyFolders().size)
        assertTrue(journal.getActive().isEmpty())

        // Retrying the batch does not duplicate the committed input
        service.checkpointHook = null
        val retry = env.importAll(env.document(first), env.document(second))
        assertEquals(imported.assetId, (retry[0] as EpubImportResult.Duplicate).assetId)
        retry[1].imported()
    }

    /** Simulates process death at [checkpoint], then recovers twice. */
    private fun interruptAt(checkpoint: ImportCheckpoint, bytes: ByteArray): String? = runBlocking {
        var key: String? = null
        service.checkpointHook = { reached, k ->
            if (reached == checkpoint) {
                key = k
                throw SimulatedInterruption()
            }
        }
        val thrown = runCatching { service.importAll(listOf(env.document(bytes))) }.exceptionOrNull()
        service.checkpointHook = null
        assertTrue("Interrupted at $checkpoint", thrown is SimulatedInterruption)
        key
    }

    private val sentinel get() = File(env.localDirectory, "User Folder/keep.txt")

    private fun placeSentinel() {
        sentinel.parentFile!!.mkdirs()
        sentinel.writeText("user data")
    }

    @Test
    fun interruptionBeforeTheFileCommitIsRolledBack() = runBlocking<Unit> {
        placeSentinel()
        for (checkpoint in listOf(ImportCheckpoint.STAGED, ImportCheckpoint.CLAIMED, ImportCheckpoint.PART_WRITTEN)) {
            val bytes = TestEpubs.book(title = "Interrupted $checkpoint")
            val key = interruptAt(checkpoint, bytes)
            if (key != null) {
                assertFalse("Hidden while unfinished", key in env.browseTitles().keys)
            }
            val report = service.recover()
            assertEquals(if (key == null) 0 else 1, report.aborted)
            assertEquals(EpubRecoveryReport(0, 0, 0, 0), service.recover())
            assertTrue(env.keyFolders().isEmpty())
            assertTrue(env.graph.publicationStorage.stagingDirectory.listFiles().orEmpty().isEmpty())
            assertTrue(journal.getActive().isEmpty())

            val again = env.importAll(env.document(bytes)).single().imported()
            assertTrue(again.importKey != key)
            env.deleteManga(again.mangaId)
            File(env.localDirectory, again.importKey).deleteRecursively()
        }
        assertEquals("Unrelated files are never touched", "user data", sentinel.readText())
    }

    @Test
    fun interruptionAfterTheFileCommitCompletesOnce() = runBlocking<Unit> {
        placeSentinel()
        val bytes = TestEpubs.book(title = "Committed file")
        val key = interruptAt(ImportCheckpoint.FILE_COMMITTED, bytes)!!
        assertTrue(bookFile(key).isFile)
        assertFalse("Hidden until the database commit", key in env.browseTitles().keys)

        assertEquals(EpubRecoveryReport(0, 1, 0, 0), service.recover())
        assertEquals(EpubRecoveryReport(0, 0, 0, 0), service.recover())
        assertEquals("Committed file", env.browseTitles()[key])
        val duplicate = env.importAll(env.document(bytes)).single() as EpubImportResult.Duplicate
        assertEquals(1, env.keyFolders().size)
        assertEquals(key, env.graph.mangaRepository.getMangaById(duplicate.mangaId).url)
        assertEquals("user data", sentinel.readText())
    }

    @Test
    fun interruptionAfterTheDatabaseCommitIsFinalizedAndRemovalIsRespected() = runBlocking<Unit> {
        val bytes = TestEpubs.book(title = "Database committed")
        val key = interruptAt(ImportCheckpoint.DATABASE_COMMITTED, bytes)!!
        val operation = journal.getActive().single { it.importKey == key }
        assertEquals(ImportPhase.DATABASE_COMMITTED, operation.phase)
        env.track(operation.mangaId!!)
        assertEquals("Visible once committed", "Database committed", env.browseTitles()[key])

        // The user deliberately removes the title before recovery runs: it must not be recreated
        env.deleteManga(operation.mangaId!!)
        assertEquals(EpubRecoveryReport(0, 0, 1, 0), service.recover())
        assertNull(env.graph.mangaRepository.getMangaByUrlAndSourceId(key, 0))
        assertTrue(bookFile(key).isFile)

        // Importing the same bytes again re-registers the existing verified copy under its key
        val again = env.importAll(env.document(bytes)).single().imported()
        assertEquals(key, again.importKey)
        assertTrue(again.reusedKey)
        assertEquals(1, env.keyFolders().size)
    }

    @Test
    fun existingLocalEpubIsRegisteredWithoutTouchingTheFile() = runBlocking<Unit> {
        val bytes = TestEpubs.book(title = "User Book", publisher = "User Publisher")
        val folder = File(env.localDirectory, "My Series").apply { mkdirs() }
        val file = File(folder, "Volume 1.epub").apply { writeBytes(bytes) }
        val copy = File(folder, "Volume 1 copy.epub").apply { writeBytes(bytes) }
        val modified = file.lastModified()

        val local = env.localSource
        val manga = local.getSearchManga(1, "My Series", local.getFilterList()).mangas.single()
        assertEquals("My Series", manga.title)
        val stored = env.graph.mangaRepository.insertNetworkManga(
            listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = 0, url = manga.url, title = manga.title)),
        ).single().also { env.track(it.id) }
        val update = local.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        env.graph.syncChaptersWithSource.await(update.chapters, stored, local)
        val chapters = env.graph.chapterRepository.getChapterByMangaId(stored.id)
        assertEquals("Legacy metadata behavior is unchanged", "User Publisher", chapters.first().scanlator)

        val registered = env.graph.localEpubRegistrar.registerAll(stored.id)
        assertEquals(2, registered.size)
        registered.forEach { result ->
            val asset = (result as LocalEpubRegistrar.Result.Registered).asset
            assertEquals(AssetOwnership.USER, asset.ownership)
            assertEquals(sha(bytes), asset.fingerprint)
            assertTrue(asset.relativePath.startsWith("local/My Series/"))
        }
        assertTrue(
            "Repeated registration reuses the record",
            (
                env.graph.localEpubRegistrar.register(
                    chapters.first().id,
                ) as LocalEpubRegistrar.Result.Registered
                ).existing,
        )
        assertEquals(sha(bytes), sha(file.readBytes()))
        assertEquals(modified, file.lastModified())
        assertTrue(copy.isFile)
        assertEquals(listOf("My Series"), env.localDirectory.list()!!.filterNot { it.startsWith(".") })

        // A managed import of the same bytes still makes its own durable copy and reports the user-owned ones
        val imported = env.importAll(env.document(bytes)).single().imported()
        assertEquals(2, imported.userOwnedCopies)
        val opened = env.graph.localPublicationOpener.open(chapters.first().id) as LocalPublicationOpener.Result.Success
        opened.session.close()
        assertTrue("Closing never deletes a user-owned file", file.isFile)
        assertNotNull(assets.getByChapterId(chapters.first().id))
    }

    private val faults = NoRenameDocumentsProvider.Faults

    private fun filler(size: Int) =
        listOf("OEBPS/filler.bin" to ByteArray(size).also { java.util.Random(7).nextBytes(it) })

    private fun noWorkingFiles() {
        assertTrue(
            "No working copies remain",
            env.graph.publicationStorage.workingDirectory.listFiles().orEmpty().isEmpty(),
        )
        assertTrue(
            "No staging files remain",
            env.graph.publicationStorage.stagingDirectory.listFiles().orEmpty().isEmpty(),
        )
    }

    private suspend fun operationOf(key: String) = journal.getAll().single { it.importKey == key }

    @Test
    fun recoveryRacingAFinishingImportKeepsTheBook() = runBlocking<Unit> {
        val bytes = TestEpubs.book(title = "Racing")
        val snapshotTaken = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var claimedKey: String? = null
        var recovery: Deferred<EpubRecoveryReport>? = null
        service.checkpointHook = { checkpoint, key ->
            if (checkpoint == ImportCheckpoint.CLAIMED) {
                claimedKey = key
                // Recovery reads the journal while this import is CLAIMED, then waits until the import is done
                recovery = async(Dispatchers.IO) { service.recover() }
                snapshotTaken.await()
            } else if (checkpoint == ImportCheckpoint.RECOVERY_SNAPSHOT && key == claimedKey) {
                snapshotTaken.complete(Unit)
                resume.await()
            }
        }
        val imported = env.importAll(env.document(bytes)).single().imported()
        assertEquals("The import finished first", ImportPhase.FINALIZED, operationOf(imported.importKey).phase)
        resume.complete(Unit)
        val report = recovery!!.await()
        service.checkpointHook = null

        assertEquals(EpubRecoveryReport(0, 0, 0, 0), report)
        assertEquals(ImportPhase.FINALIZED, operationOf(imported.importKey).phase)
        assertEquals(sha(bytes), sha(bookFile(imported.importKey).readBytes()))
        val asset = assets.getByChapterId(imported.chapterId)!!
        assertEquals(imported.assetId, asset.id)
        assertEquals(imported.importKey, asset.assetKey)
        val chapter = env.graph.chapterRepository.getChapterById(imported.chapterId)!!
        assertEquals(imported.mangaId, chapter.mangaId)
        assertEquals(imported.importKey, env.graph.mangaRepository.getMangaById(imported.mangaId).url)
        assertEquals("Racing", env.browseTitles()[imported.importKey])
    }

    @Test
    fun failedRollbackStaysHiddenAndIsRetriedUntilConfirmed() = runBlocking<Unit> {
        if (kind != EpubTestEnvironment.Kind.PROVIDER) return@runBlocking
        placeSentinel()
        val cases = listOf<Pair<DeleteFault, () -> Unit>>(
            DeleteFault.IGNORE to { faults.deletes = DeleteFault.IGNORE },
            DeleteFault.FAIL to { faults.deletes = DeleteFault.FAIL },
            DeleteFault.DENY to { faults.deletes = DeleteFault.DENY },
            // Storage that cannot be inspected at recovery time is not taken as clean
            DeleteFault.IGNORE to { faults.revoked = true },
        )
        for ((index, case) in cases.withIndex()) {
            val (importFault, recoveryFault) = case
            val bytes = TestEpubs.book(title = "Rollback $index", extra = filler(200_000))
            var key: String? = null
            service.checkpointHook = { checkpoint, k -> if (checkpoint == ImportCheckpoint.CLAIMED) key = k }
            // Without rename the book is copied a second time under its final name; that copy fails part-way
            faults.failWriteName = ManagedPublicationLayout.BOOK_FILE
            faults.failWriteAfterBytes = 1_000
            faults.deletes = importFault
            val result = env.importAll(env.document(bytes)).single()
            service.checkpointHook = null
            assertTrue(result.toString(), (result as EpubImportResult.Failed).error is EpubImportError.CommitFailed)
            val partial = bookFile(key!!)
            assertTrue("A partial final-name copy exists", partial.length() in 1 until bytes.size)
            assertEquals(ImportPhase.ABORTING, operationOf(key!!).phase)
            assertTrue(key in assets.getUncommittedImportKeys())
            assertFalse("Hidden while its rollback is pending", key in env.browseTitles().keys)

            faults.reset()
            recoveryFault()
            assertEquals("Retained for a later pass", 1, service.recover().failed)
            faults.reset()
            assertEquals(ImportPhase.ABORTING, operationOf(key!!).phase)
            assertTrue(partial.exists())
            assertFalse(key in env.browseTitles().keys)

            assertEquals(EpubRecoveryReport(1, 0, 0, 0), service.recover())
            assertEquals(EpubRecoveryReport(0, 0, 0, 0), service.recover())
            assertEquals(ImportPhase.ABORTED, operationOf(key!!).phase)
            assertFalse("Its folder is removed", File(env.localDirectory, key!!).exists())
            assertFalse(key in assets.getUncommittedImportKeys())
        }

        // A reopened import whose rollback cannot reach storage keeps its verified book once access returns
        val bytes = TestEpubs.book(title = "Reused rollback")
        val first = env.importAll(env.document(bytes)).single().imported()
        env.deleteManga(first.mangaId)
        val key = interruptAt(ImportCheckpoint.CLAIMED, bytes)
        assertEquals(first.importKey, key)
        assertTrue(operationOf(first.importKey).reused)
        faults.revoked = true
        assertEquals(1, service.recover().failed)
        faults.reset()
        assertEquals(ImportPhase.ABORTING, operationOf(first.importKey).phase)
        assertEquals(EpubRecoveryReport(1, 0, 0, 0), service.recover())
        assertEquals(sha(bytes), sha(bookFile(first.importKey).readBytes()))

        assertTrue(env.keyFolders().all { it.name == first.importKey })
        assertEquals("Unrelated files are never touched", "user data", sentinel.readText())
        noWorkingFiles()
    }

    @Test
    fun oversizedAndEndlessInputsStopAtTheLimit() = runBlocking<Unit> {
        val limit = 300_000L
        service.limits = EpubContentLimits(maxInputBytes = limit)
        faults.endlessReadName = "endless.epub"
        val results = env.importAll(
            env.document(TestEpubs.book(title = "Endless"), displayName = "endless.epub"),
            env.document(TestEpubs.book(title = "Big", extra = filler(400_000))),
        )
        results.forEach {
            assertTrue(it.toString(), (it as EpubImportResult.Unsupported).error is EpubImportError.TooLarge)
        }
        assertTrue("Reads stop near the limit", faults.bytesServed.get() < limit + 1_048_576)
        assertTrue(env.keyFolders().isEmpty())
        assertTrue(journal.getActive().isEmpty())
        noWorkingFiles()
    }

    @Test
    fun registeringOversizedOrEndlessUserFilesFailsAndKeepsThem() = runBlocking<Unit> {
        val folder = File(env.localDirectory, "Big Series").apply { mkdirs() }
        val big = TestEpubs.book(title = "Big", extra = filler(400_000))
        val files = buildList {
            add(File(folder, "Big.epub").apply { writeBytes(big) })
            if (kind == EpubTestEnvironment.Kind.PROVIDER) {
                add(File(folder, "endless.epub").apply { writeBytes(TestEpubs.book(title = "Endless")) })
            }
        }
        val before = files.associateWith { sha(it.readBytes()) to it.lastModified() }

        val local = env.localSource
        val manga = local.getSearchManga(1, "Big Series", local.getFilterList()).mangas.single()
        val stored = env.graph.mangaRepository.insertNetworkManga(
            listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = 0, url = manga.url, title = manga.title)),
        ).single().also { env.track(it.id) }
        val update = local.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        env.graph.syncChaptersWithSource.await(update.chapters, stored, local)

        val limit = 300_000L
        env.graph.localEpubRegistrar.limits = EpubContentLimits(maxInputBytes = limit)
        faults.endlessReadName = "endless.epub"
        val results = env.graph.localEpubRegistrar.registerAll(stored.id)
        faults.reset()
        assertEquals(files.size, results.size)
        results.forEach { result ->
            val failed = result as LocalEpubRegistrar.Result.Failed
            assertTrue(result.toString(), failed.error is EpubImportError.TooLarge)
            assertNull(assets.getByChapterId(failed.chapterId))
        }
        assertTrue(faults.bytesServed.get() < limit + 1_048_576)
        before.forEach { (file, state) -> assertEquals(state, sha(file.readBytes()) to file.lastModified()) }
        noWorkingFiles()
    }

    @Test
    fun openingOversizedOrEndlessReplacementsFailsAndKeepsReadingState() = runBlocking<Unit> {
        val bytes = TestEpubs.book(title = "Replaced")
        val imported = env.importAll(env.document(bytes)).single().imported()
        val states = env.graph.epubReadingStateRepository
        val locator = StoredLocator(1, """{"href":"s1.xhtml","locations":{"progression":0.5}}""")
        val state = EpubReadingState(imported.chapterId, imported.fingerprint, locator, locator, 0.5, 1, 1, null)
        assertTrue(states.save(state))
        val saved = states.getByChapterId(imported.chapterId)
        val asset = assets.getByChapterId(imported.chapterId)!!
        val opener = env.graph.localPublicationOpener

        bookFile(imported.importKey).writeBytes(TestEpubs.book(title = "Replaced", extra = filler(400_000)))
        val oversized = opener.open(imported.chapterId) as LocalPublicationOpener.Result.Failure
        assertTrue(oversized.toString(), oversized.error is EpubImportError.FingerprintMismatch)

        if (kind == EpubTestEnvironment.Kind.PROVIDER) {
            faults.endlessReadName = ManagedPublicationLayout.BOOK_FILE
            val endless = opener.open(imported.chapterId) as LocalPublicationOpener.Result.Failure
            assertTrue(endless.toString(), endless.error is EpubImportError.FingerprintMismatch)
            assertTrue(faults.bytesServed.get() < asset.byteSize + 1_048_576)
            faults.reset()
        }
        assertEquals(saved, states.getByChapterId(imported.chapterId))
        val after = assets.getByChapterId(imported.chapterId)!!
        assertEquals(asset.fingerprint to asset.byteSize, after.fingerprint to after.byteSize)
        noWorkingFiles()
    }

    @Test
    fun recoveryRejectsAnOversizedOrEndlessCommittedBook() = runBlocking<Unit> {
        val oversizedKey = interruptAt(ImportCheckpoint.FILE_COMMITTED, TestEpubs.book(title = "Grown"))!!
        bookFile(oversizedKey).writeBytes(TestEpubs.book(title = "Grown", extra = filler(400_000)))
        assertEquals(EpubRecoveryReport(1, 0, 0, 0), service.recover())
        assertNull(env.graph.mangaRepository.getMangaByUrlAndSourceId(oversizedKey, 0))
        assertFalse(File(env.localDirectory, oversizedKey).exists())

        if (kind == EpubTestEnvironment.Kind.PROVIDER) {
            val bytes = TestEpubs.book(title = "Endless book")
            val key = interruptAt(ImportCheckpoint.FILE_COMMITTED, bytes)!!
            val byteSize = operationOf(key).byteSize
            faults.endlessReadName = ManagedPublicationLayout.BOOK_FILE
            assertEquals(EpubRecoveryReport(1, 0, 0, 0), service.recover())
            assertTrue(faults.bytesServed.get() < byteSize + 1_048_576)
            faults.reset()
            assertNull(env.graph.mangaRepository.getMangaByUrlAndSourceId(key, 0))
            assertFalse(File(env.localDirectory, key).exists())
        }
        assertTrue(journal.getActive().isEmpty())
        noWorkingFiles()
    }
}

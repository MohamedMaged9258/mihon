package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class EpubPublicationSession internal constructor(
    val target: EpubFixtureReference,
    val fingerprint: String,
    private val privateFile: File,
    private val asset: Asset,
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
) : AutoCloseable {
    override fun close() {
        // Reverse acquisition order. All calls are idempotent in Readium's resource layer.
        runCatching { publication.close() }
        runCatching { asset.close() }
        runCatching { privateFile.delete() }
    }
}

class EpubPublicationOpener(private val context: Context) {
    sealed interface Result {
        data class Success(val session: EpubPublicationSession) : Result
        data class Failure(val error: EpubOpenError) : Result
    }

    suspend fun open(target: EpubFixtureReference): Result = withContext(Dispatchers.IO) {
        val privateDir = File(context.cacheDir, "epub-fixtures").apply { mkdirs() }
        val file = File.createTempFile("${target.id}-", ".epub", privateDir)
        var asset: Asset? = null
        var publication: Publication? = null
        var transferred = false

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(target.assetPath).use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
            EpubFingerprintVerifier.error(target.expectedSha256, fingerprint)?.let {
                return@withContext Result.Failure(it)
            }

            val httpClient = RecordingDenyHttpClient
            val retrieved = AssetRetriever(context.contentResolver, httpClient).retrieve(file)
            val openedAsset = retrieved.getOrNull()
                ?: return@withContext Result.Failure(mapRetrieveError(retrieved.failureOrNull()))
            asset = openedAsset

            val opened = PublicationOpener(EpubParser(httpClient)).open(
                asset = openedAsset,
                allowUserInteraction = false,
            )
            val openedPublication = opened.getOrNull()
                ?: return@withContext Result.Failure(mapOpenError(opened.failureOrNull()))
            publication = openedPublication

            if (!openedPublication.conformsTo(Publication.Profile.EPUB)) {
                return@withContext Result.Failure(
                    EpubOpenError.UnsupportedFormat("Readium did not identify an EPUB profile."),
                )
            }
            if (openedPublication.isRestricted) {
                return@withContext Result.Failure(
                    EpubOpenError.ProtectedPublication("Protected fixtures are outside P01-01."),
                )
            }

            Result.Success(
                EpubPublicationSession(
                    target = target,
                    fingerprint = fingerprint,
                    privateFile = file,
                    asset = openedAsset,
                    publication = openedPublication,
                    navigatorFactory = EpubNavigatorFactory(openedPublication),
                ),
            ).also {
                transferred = true
                asset = null
                publication = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: FileNotFoundException) {
            Result.Failure(EpubOpenError.Missing("Fixture asset is missing."))
        } catch (e: Exception) {
            Result.Failure(EpubOpenError.Corrupt(e.message ?: "Unable to open fixture."))
        } finally {
            runCatching { publication?.close() }
            runCatching { asset?.close() }
            if (!transferred) runCatching { file.delete() }
        }
    }

    private fun mapRetrieveError(error: Any?): EpubOpenError = when (error) {
        is AssetRetriever.RetrieveError.FormatNotSupported ->
            EpubOpenError.UnsupportedFormat(error.message)
        else -> EpubOpenError.Corrupt(error?.toString() ?: "Asset retrieval failed.")
    }

    private fun mapOpenError(error: Any?): EpubOpenError = when (error) {
        is PublicationOpener.OpenError.FormatNotSupported ->
            EpubOpenError.UnsupportedFormat(error.message)
        is PublicationOpener.OpenError.Reading -> EpubOpenError.Corrupt(error.message)
        else -> EpubOpenError.Corrupt(error?.toString() ?: "Publication opening failed.")
    }
}

internal object EpubFingerprintVerifier {
    fun error(expected: String, actual: String): EpubOpenError.FingerprintMismatch? =
        if (expected.equals(actual, ignoreCase = true)) {
            null
        } else {
            EpubOpenError.FingerprintMismatch("Fixture fingerprint did not match its manifest.")
        }
}

/** Network policy for diagnostic fixtures: count attempts, disclose no requested URI, deny all. */
internal object RecordingDenyHttpClient : HttpClient {
    private val attempts = AtomicInteger()

    val attemptedRequestCount: Int get() = attempts.get()

    fun reset() {
        attempts.set(0)
    }

    override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> {
        attempts.incrementAndGet()
        return Try.failure(HttpError.Unreachable(org.readium.r2.shared.util.DebugError("Blocked by fixture policy")))
    }
}

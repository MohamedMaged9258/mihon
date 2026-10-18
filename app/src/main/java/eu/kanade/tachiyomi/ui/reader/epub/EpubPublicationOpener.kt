package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Runs [produce] on [context] and keeps cleanup ownership of its result until this function has
 * actually returned it. Cancellation during the return dispatch closes the abandoned resource.
 */
internal suspend fun <T> handOff(
    context: CoroutineContext,
    release: (T) -> Unit,
    produce: suspend () -> T,
): T {
    var produced: Result<T>? = null
    try {
        withContext(context) { produced = Result.success(produce()) }
        return produced!!.getOrThrow()
    } catch (e: Throwable) {
        produced?.getOrNull()?.let { runCatching { release(it) } }
        throw e
    }
}

class EpubPublicationSession internal constructor(
    val target: EpubFixtureReference,
    val fingerprint: String,
    private val privateFile: File,
    private val asset: Asset,
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Reverse acquisition order.
        runCatching { publication.close() }
        runCatching { asset.close() }
        runCatching { privateFile.delete() }
    }
}

class EpubPublicationOpener(
    private val context: Context,
    private val workDirectory: File = File(context.cacheDir, "epub-fixtures"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Result {
        data class Success(val session: EpubPublicationSession) : Result
        data class Failure(val error: EpubOpenError) : Result
    }

    /**
     * Ownership of a successful session passes to the caller only when this function returns.
     * If cancellation strikes while the result is dispatched back, the session is closed here.
     */
    suspend fun open(target: EpubFixtureReference): Result =
        handOff(ioDispatcher, release = { (it as? Result.Success)?.session?.close() }) { openOnIo(target) }

    private suspend fun openOnIo(target: EpubFixtureReference): Result {
        var file: File? = null
        var asset: Asset? = null
        var publication: Publication? = null
        var transferred = false

        fun fail(error: EpubOpenError) = Result.Failure(error)

        try {
            if (!workDirectory.isDirectory && !workDirectory.mkdirs()) {
                return fail(EpubOpenError.ResourceUnavailable("Unable to create the private fixture directory."))
            }
            val privateFile = File.createTempFile("${target.id}-", ".epub", workDirectory)
            file = privateFile
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(target.assetPath).use { input ->
                privateFile.outputStream().buffered().use { output ->
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
            EpubFingerprintVerifier.error(target.expectedSha256, fingerprint)?.let { return fail(it) }

            val parsed = when (val result = parseEpub(context, privateFile, RecordingDenyHttpClient)) {
                is EpubParseResult.Failure -> return fail(result.error)
                is EpubParseResult.Success -> result
            }
            asset = parsed.asset
            publication = parsed.publication

            val session = EpubPublicationSession(
                target = target,
                fingerprint = fingerprint,
                privateFile = privateFile,
                asset = parsed.asset,
                publication = parsed.publication,
                navigatorFactory = EpubNavigatorFactory(parsed.publication),
            )
            // From here the session owns every acquisition; handOff owns the session.
            transferred = true
            return Result.Success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (_: FileNotFoundException) {
            return fail(EpubOpenError.Missing("Fixture asset is missing."))
        } catch (e: IOException) {
            return fail(EpubOpenError.ResourceUnavailable(e.message ?: "Unable to prepare fixture."))
        } catch (e: Exception) {
            return fail(EpubOpenError.Corrupt(e.message ?: "Unable to open fixture."))
        } finally {
            if (!transferred) {
                runCatching { publication?.close() }
                runCatching { asset?.close() }
                runCatching { file?.delete() }
            }
        }
    }
}

internal sealed interface EpubParseResult {
    /** The caller owns [asset] and [publication] and closes them in reverse order. */
    class Success(val asset: Asset, val publication: Publication) : EpubParseResult
    class Failure(val error: EpubOpenError) : EpubParseResult
}

/**
 * Opens a local file with the EPUB parser only: no LCP/DRM support installed and every HTTP request handled by
 * [httpClient]. Shared by the fixture reader and the production inspector/opener; nothing leaks on failure.
 */
internal suspend fun parseEpub(context: Context, file: File, httpClient: HttpClient): EpubParseResult {
    var asset: Asset? = null
    var publication: Publication? = null
    var transferred = false
    try {
        val retrieved = AssetRetriever(context.contentResolver, httpClient).retrieve(file)
        asset = retrieved.getOrNull()
            ?: return EpubParseResult.Failure(mapRetrieveError(retrieved.failureOrNull()))

        val opened = PublicationOpener(EpubParser(httpClient)).open(asset = asset, allowUserInteraction = false)
        publication = opened.getOrNull()
            ?: return EpubParseResult.Failure(mapOpenError(opened.failureOrNull()))

        if (!publication.conformsTo(Publication.Profile.EPUB)) {
            return EpubParseResult.Failure(EpubOpenError.UnsupportedFormat("Readium did not identify an EPUB profile."))
        }
        if (publication.isRestricted) {
            return EpubParseResult.Failure(
                EpubOpenError.ProtectedPublication("Protected publications are unsupported."),
            )
        }
        transferred = true
        return EpubParseResult.Success(asset, publication)
    } finally {
        if (!transferred) {
            runCatching { publication?.close() }
            runCatching { asset?.close() }
        }
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

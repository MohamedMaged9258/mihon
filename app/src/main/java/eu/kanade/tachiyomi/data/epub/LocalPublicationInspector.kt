package eu.kanade.tachiyomi.data.epub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.epub.EpubOpenError
import eu.kanade.tachiyomi.ui.reader.epub.EpubParseResult
import eu.kanade.tachiyomi.ui.reader.epub.parseEpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import tachiyomi.domain.epub.model.ContentKind
import tachiyomi.domain.epub.model.PublicationLayout
import tachiyomi.domain.epub.model.PublicationMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** What import and registration need from a publication, read without an Activity or navigator. */
class InspectedPublication(
    val metadata: PublicationMetadata,
    val layout: PublicationLayout,
    val contentKind: ContentKind,
    val readingOrderSize: Int,
    /** A re-encoded JPEG of the declared cover, or null if there is none or it could not be used. */
    val cover: ByteArray?,
    val coverProblem: String?,
)

/** Production network policy for local publications: every request is refused; attempts are only counted. */
internal object DenyAllHttpClient : HttpClient {
    private val attempts = AtomicInteger()

    val attemptedRequestCount: Int get() = attempts.get()

    override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> {
        attempts.incrementAndGet()
        return Try.failure(HttpError.Unreachable(org.readium.r2.shared.util.DebugError("Blocked by local policy")))
    }
}

internal fun EpubOpenError.toImportError(): EpubImportError = when (this) {
    is EpubOpenError.UnsupportedFormat -> EpubImportError.UnsupportedFormat(detail)
    is EpubOpenError.ProtectedPublication -> EpubImportError.UnsupportedProtection(detail)
    is EpubOpenError.Missing -> EpubImportError.SourceMissing(detail)
    is EpubOpenError.FingerprintMismatch -> EpubImportError.FingerprintMismatch(detail)
    is EpubOpenError.ResourceUnavailable -> EpubImportError.ReadFailed(detail)
    else -> EpubImportError.Malformed(detail)
}

@Inject
@SingleIn(AppScope::class)
class LocalPublicationInspector(
    private val context: Context,
) {

    /**
     * Validates [file] (a private, complete copy) and extracts bounded metadata. Fails with
     * [EpubImportException]. [fallbackTitle] is used only for display when the publication declares no title.
     */
    suspend fun inspect(
        file: File,
        fallbackTitle: String?,
        limits: EpubContentLimits = EpubContentLimits.DEFAULT,
    ): InspectedPublication = withContext(Dispatchers.IO) {
        EpubArchiveGuard.check(file, limits)?.let(::fail)
        val parsed = when (val result = parseEpub(context, file, DenyAllHttpClient)) {
            is EpubParseResult.Failure -> fail(result.error.toImportError())
            is EpubParseResult.Success -> result
        }
        try {
            inspect(parsed.publication, fallbackTitle, limits)
        } finally {
            runCatching { parsed.publication.close() }
            runCatching { parsed.asset.close() }
        }
    }

    private suspend fun inspect(
        publication: Publication,
        fallbackTitle: String?,
        limits: EpubContentLimits,
    ): InspectedPublication {
        if (publication.readingOrder.isEmpty()) fail(EpubImportError.Malformed("The publication has no reading order"))
        val metadata = extractMetadata(publication, fallbackTitle, limits)
        val layout = when (publication.metadata.layout) {
            Layout.FIXED -> PublicationLayout.FIXED
            else -> PublicationLayout.REFLOWABLE
        }
        val contentKind = if (layout == PublicationLayout.FIXED || isImageOriented(publication, limits)) {
            ContentKind.IMAGE
        } else {
            ContentKind.TEXT
        }
        val (cover, problem) = try {
            readCover(publication, limits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to "Cover could not be read"
        }
        return InspectedPublication(metadata, layout, contentKind, publication.readingOrder.size, cover, problem)
    }

    /**
     * A reflowable publication counts as image-oriented when every sampled reading-order document is a bitmap or
     * an XHTML page with images and no text. Bounded to the first few documents and bytes.
     */
    private suspend fun isImageOriented(publication: Publication, limits: EpubContentLimits): Boolean {
        val sample = publication.readingOrder.take(limits.maxSampledDocuments)
        return sample.all { link ->
            val type = link.mediaType
            when {
                type?.isBitmap == true -> true
                type?.isHtml == true -> {
                    val bytes = read(publication, link, limits.maxSampledDocumentBytes, truncate = true)
                        ?: return@all false
                    val body = Jsoup.parse(String(bytes, Charsets.UTF_8)).body()
                    body.text().isBlank() && body.select("img, svg, image").isNotEmpty()
                }
                else -> false
            }
        }
    }

    private suspend fun readCover(publication: Publication, limits: EpubContentLimits): Pair<ByteArray?, String?> {
        val link = publication.linkWithRel("cover") ?: return null to null
        val bytes = read(publication, link, limits.maxCoverBytes, truncate = false)
            ?: return null to "Declared cover is missing or too large"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null to "Declared cover is not a supported image"
        if (side > limits.maxCoverSidePixels) return null to "Declared cover dimensions are too large"
        var sample = 1
        while (side / (sample * 2) >= limits.coverTargetPixels) sample *= 2
        val bitmap =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null to "Declared cover could not be decoded"
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray() to null
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads at most [max] bytes of a publication resource; a longer resource is truncated or rejected. */
    private suspend fun read(publication: Publication, link: Link, max: Long, truncate: Boolean): ByteArray? {
        val resource = publication.get(link) ?: return null
        try {
            val length = resource.length().getOrNull() ?: return null
            if (length > max && !truncate) return null
            return resource.read(0 until minOf(length, max)).getOrNull()
        } finally {
            resource.close()
        }
    }

    private fun extractMetadata(
        publication: Publication,
        fallbackTitle: String?,
        limits: EpubContentLimits,
    ): PublicationMetadata {
        val m = publication.metadata
        val title = m.title?.normalized(limits.maxTitleChars)?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle?.normalized(limits.maxTitleChars)?.takeIf { it.isNotEmpty() }
            ?: UNTITLED
        return PublicationMetadata(
            title = title,
            authors = m.authors.mapNotNull { it.name.normalized(limits.maxFieldChars).takeIf(String::isNotEmpty) }
                .distinct()
                .take(limits.maxAuthors),
            description = m.description
                ?.let { Jsoup.parse(it).text() }
                ?.normalized(limits.maxDescriptionChars)
                ?.takeIf { it.isNotEmpty() },
            language = m.languages.firstOrNull()?.normalized(64)?.takeIf { it.isNotEmpty() },
            publisher = m.publishers.firstOrNull()?.name?.normalized(limits.maxFieldChars)?.takeIf { it.isNotEmpty() },
            identifier = m.identifier?.normalized(limits.maxFieldChars)?.takeIf { it.isNotEmpty() },
            published = m.published?.toString(),
        )
    }

    companion object {
        const val UNTITLED = "Untitled EPUB"
    }
}

/** Collapses whitespace (including newlines) and bounds length without splitting a surrogate pair. */
internal fun String.normalized(max: Int): String {
    val collapsed = replace(Regex("\\s+"), " ").trim()
    if (collapsed.length <= max) return collapsed
    val end = if (Character.isHighSurrogate(collapsed[max - 1])) max - 1 else max
    return collapsed.substring(0, end)
}

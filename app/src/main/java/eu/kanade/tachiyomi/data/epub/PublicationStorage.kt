package eu.kanade.tachiyomi.data.epub

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.epub.model.ManagedPublicationLayout
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** The storage root an operation committed to, held for the whole operation even if the preference changes. */
class PinnedRoot(val root: String, val base: UniFile, val local: UniFile)

class HashedBytes(val size: Long, val sha256: String)

/** Where a recorded asset is now, relative to the root it was committed under. */
sealed interface AssetLocation {
    data class Found(val file: UniFile) : AssetLocation
    data object Missing : AssetLocation

    /** The storage tree cannot be reached (unmounted, deleted or no longer granted). */
    data object Inaccessible : AssetLocation

    /** The configured root is no longer the one this asset was committed under. */
    data object RootChanged : AssetLocation
}

/**
 * Durable library storage and private scratch space. Managed originals live under the configured root; staging
 * and reader working copies are app-private and disposable; nothing here ever deletes a user-owned file.
 */
@Inject
@SingleIn(AppScope::class)
class PublicationStorage(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    /** App-private, excluded from backup, never inside the Local tree. */
    val stagingDirectory: File get() = File(context.noBackupFilesDir, "epub-import")

    /** Disposable copies a reader session may need; deleted when the session closes. */
    val workingDirectory: File get() = File(context.cacheDir, "epub-working")

    fun currentRoot(): String = storagePreferences.baseStorageDirectory.get()

    /** Pins the configured root, creating `local` if needed; null when storage is unavailable. */
    fun pin(): PinnedRoot? {
        val root = currentRoot()
        val base = resolveRoot(root) ?: return null
        val local = base.createDirectory(ManagedPublicationLayout.LOCAL_DIRECTORY)?.takeIf { it.isDirectory }
            ?: return null
        return PinnedRoot(root, base, local)
    }

    fun isCurrent(pinned: PinnedRoot) = currentRoot() == pinned.root

    fun locate(storageRoot: String, relativePath: String): AssetLocation {
        if (storageRoot != currentRoot()) return AssetLocation.RootChanged
        var file = resolveRoot(storageRoot) ?: return AssetLocation.Inaccessible
        for (segment in relativePath.split('/')) {
            file = file.findFile(segment) ?: return AssetLocation.Missing
        }
        return if (file.isFile) AssetLocation.Found(file) else AssetLocation.Missing
    }

    private fun resolveRoot(root: String): UniFile? {
        return try {
            UniFile.fromUri(context, root.toUri())?.takeIf { it.exists() && it.isDirectory }
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private val random = SecureRandom()
        private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

        /** 130 random bits as `epub-` plus 26 lowercase base32 characters. */
        fun newImportKey(): String {
            return "epub-" + String(CharArray(26) { BASE32[random.nextInt(32)] })
        }

        /**
         * Copies at most [maxBytes] while hashing; checks cancellation between buffers. Never reads more than
         * `maxBytes + 1` bytes nor writes more than [maxBytes], whatever the stream's length or reported size, and
         * fails with [EpubImportException] ([EpubImportError.TooLarge]) as soon as the limit is passed.
         */
        suspend fun copyHashing(input: InputStream, output: OutputStream?, maxBytes: Long): HashedBytes {
            require(maxBytes in 0..<Long.MAX_VALUE)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                // One byte past the limit is enough to know it was passed
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - size + 1).toInt())
                if (count < 0) break
                size += count
                if (size > maxBytes) fail(EpubImportError.TooLarge("File exceeds $maxBytes bytes"))
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
            output?.flush()
            return HashedBytes(size, digest.digest().joinToString("") { "%02x".format(it) })
        }

        suspend fun hash(file: UniFile, maxBytes: Long): HashedBytes {
            return file.openInputStream().use { copyHashing(it, null, maxBytes) }
        }

        /** Maps an I/O failure to its typed meaning; ENOSPC is the only space signal providers give. */
        fun classify(e: Throwable, fallback: (String) -> EpubImportError): EpubImportError {
            val message = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            return when {
                e is EpubImportException -> e.error
                "ENOSPC" in message || "No space left" in message -> EpubImportError.InsufficientSpace()
                e is SecurityException -> EpubImportError.PermissionDenied()
                else -> fallback(e.javaClass.simpleName)
            }
        }
    }
}

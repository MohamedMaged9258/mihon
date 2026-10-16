package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Orders asynchronous position captures (automatic locator changes and manual checkpoints).
 *
 * Cancelling a superseded capture is not enough: the anchor lookup waits on a WebView callback
 * through `suspendCoroutine`, which resumes cancelled work. Every capture therefore holds a gate
 * token, and its result is accepted only if, after the lookup, the coroutine is active, the token
 * is still the newest gate revision (same owner, Ready, no newer capture, operation or rebinding),
 * and the navigator still reports the location the capture started from.
 *
 * Generic so that ordering is unit-testable without Readium's Android-backed locator types.
 */
internal class EpubPositionCapture<L : Any>(
    private val scope: CoroutineScope,
    private val gate: EpubCheckpointGate,
    private val lookup: suspend (EpubOwner, L) -> L?,
    private val currentLocation: () -> L?,
    private val onAccepted: (L) -> Unit,
) {
    private var job: Job? = null

    /** Starts a capture of [location] that supersedes any earlier one. */
    fun request(owner: EpubOwner, location: L) {
        val token = gate.beginCapture(owner) ?: return
        job?.cancel()
        job = scope.launch { run(owner, token, location) }
    }

    private suspend fun run(owner: EpubOwner, token: Long, location: L) {
        val anchored = try {
            lookup(owner, location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "EPUB position lookup failed" }
            null
        } ?: return
        currentCoroutineContext().ensureActive()
        if (!gate.acceptCapture(owner, token)) return
        if (currentLocation() != location) return
        onAccepted(anchored)
    }

    /** Waits until the newest capture, including any that superseded an awaited one, finished. */
    suspend fun awaitLatest() {
        while (true) {
            val current = job ?: return
            current.join()
            if (current === job) return
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

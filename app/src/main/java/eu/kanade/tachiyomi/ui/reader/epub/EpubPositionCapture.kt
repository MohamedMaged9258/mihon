package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
 * Every capture also has an explicit [Outcome]. Only [Outcome.Accepted] means the requested
 * location was taken; a cancelled or superseded capture completes its outcome at once, without
 * waiting for a callback-style lookup that may resume much later (or never).
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
    enum class Outcome {
        /** The anchored location was passed to `onAccepted` (possibly the unchanged held anchor). */
        Accepted,

        /** A newer capture request replaced this one. */
        Superseded,

        /** Cancelled, or invalidated by an operation, rebinding, failure or close. */
        Canceled,

        /** No anchor: no navigator, a transitional location, or the navigator moved meanwhile. */
        Unavailable,

        /** The lookup threw. */
        Failed,
    }

    private class Pending(val result: CompletableDeferred<Outcome>, var job: Job? = null)

    private var latest: Pending? = null

    /**
     * Starts a capture of [location] that supersedes any earlier one. Returns false (and captures
     * nothing) unless [owner] may currently persist.
     */
    fun request(owner: EpubOwner, location: L): Boolean {
        val token = gate.beginCapture(owner) ?: return false
        latest?.finish(Outcome.Superseded)
        val pending = Pending(CompletableDeferred())
        latest = pending
        pending.job = scope.launch { pending.result.complete(run(pending, owner, token, location)) }
        return true
    }

    private suspend fun run(pending: Pending, owner: EpubOwner, token: Long, location: L): Outcome {
        val anchored = try {
            lookup(owner, location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "EPUB position lookup failed" }
            return Outcome.Failed
        }
        if (!currentCoroutineContext().isActive || !gate.acceptCapture(owner, token)) {
            return if (pending === latest) Outcome.Canceled else Outcome.Superseded
        }
        if (anchored == null || currentLocation() != location) return Outcome.Unavailable
        onAccepted(anchored)
        return Outcome.Accepted
    }

    /**
     * Outcome of the newest capture, following any request that superseded an awaited one;
     * `null` if nothing was ever requested.
     */
    suspend fun awaitLatest(): Outcome? {
        while (true) {
            val current = latest ?: return null
            val outcome = current.result.await()
            if (current === latest) return outcome
        }
    }

    fun cancel() {
        latest?.finish(Outcome.Canceled)
    }

    private fun Pending.finish(outcome: Outcome) {
        job?.cancel()
        result.complete(outcome)
    }
}

package eu.kanade.tachiyomi.ui.reader.epub

/**
 * Pure lifecycle and ownership gate used by the ViewModel and unit tests.
 *
 * Only events from the active [EpubOwner] are honoured, and checkpoints are accepted only in
 * [EpubRestorationPhase.Ready]. Restoration and reflow are operations with their own ids, so a
 * stale completion from a superseded operation cannot reopen persistence.
 */
class EpubCheckpointGate {
    var phase: EpubRestorationPhase = EpubRestorationPhase.Opening
        private set
    var sessionId = 0L
        private set
    private var bindingId = 0L
    private var operationCounter = 0L
    private var pendingOperation: Long? = null

    /**
     * Bumped by every transition and every new capture request. A capture token is valid only
     * while nothing happened since it was issued, so a slower, older lookup cannot overwrite a
     * newer one, and a lookup started before an operation or rebinding cannot land after it.
     */
    var revision = 0L
        private set

    val activeOwner: EpubOwner?
        get() = EpubOwner(sessionId, bindingId).takeIf { sessionId != 0L && bindingId != 0L }

    fun beginOpening() {
        revision += 1
        phase = EpubRestorationPhase.Opening
        sessionId = 0L
        bindingId = 0L
        pendingOperation = null
    }

    fun beginSession(sessionId: Long) {
        revision += 1
        this.sessionId = sessionId
        bindingId = 0L
        pendingOperation = null
        phase = EpubRestorationPhase.Attaching
    }

    /** A navigator of the active session was bound; it must render before anything is accepted. */
    fun attach(owner: EpubOwner): Boolean {
        if (owner.sessionId != sessionId || sessionId == 0L || isClosed()) return false
        revision += 1
        bindingId = owner.bindingId
        pendingOperation = null
        phase = EpubRestorationPhase.Rendering
        return true
    }

    fun isCurrent(owner: EpubOwner): Boolean = owner == activeOwner

    /** First rendered page from the current binding. Returns false for stale or repeated signals. */
    fun rendered(owner: EpubOwner): Boolean {
        if (!isCurrent(owner) || phase != EpubRestorationPhase.Rendering) return false
        phase = EpubRestorationPhase.Ready
        return true
    }

    fun beginOperation(owner: EpubOwner, kind: EpubRestorationPhase): Long? {
        require(kind == EpubRestorationPhase.Restoring || kind == EpubRestorationPhase.Reflowing)
        if (!isCurrent(owner) || isClosed() || phase == EpubRestorationPhase.Rendering) return null
        revision += 1
        operationCounter += 1
        pendingOperation = operationCounter
        phase = kind
        return operationCounter
    }

    /** Completes the pending operation; returns false if the owner or operation was superseded. */
    fun completeOperation(owner: EpubOwner, operationId: Long, success: Boolean): Boolean {
        if (!isCurrent(owner) || pendingOperation != operationId) return false
        revision += 1
        pendingOperation = null
        phase = if (success) EpubRestorationPhase.Ready else EpubRestorationPhase.Error
        return true
    }

    fun accept(owner: EpubOwner): Boolean = isCurrent(owner) && phase == EpubRestorationPhase.Ready

    /** Starts a position capture, superseding any earlier one; `null` unless [owner] may persist. */
    fun beginCapture(owner: EpubOwner): Long? = if (accept(owner)) ++revision else null

    /** Whether the capture [token] of [owner] is still the newest event since it was issued. */
    fun acceptCapture(owner: EpubOwner, token: Long): Boolean = accept(owner) && token == revision

    /** A failure reported by the current binding. Obsolete owners cannot change the state. */
    fun fail(owner: EpubOwner): Boolean {
        if (!isCurrent(owner) || isClosed()) return false
        revision += 1
        pendingOperation = null
        phase = EpubRestorationPhase.Error
        return true
    }

    fun failOpening() {
        revision += 1
        pendingOperation = null
        phase = EpubRestorationPhase.Error
    }

    fun close() {
        revision += 1
        bindingId = 0L
        pendingOperation = null
        phase = EpubRestorationPhase.Closing
    }

    private fun isClosed() = phase == EpubRestorationPhase.Closing || phase == EpubRestorationPhase.Error
}

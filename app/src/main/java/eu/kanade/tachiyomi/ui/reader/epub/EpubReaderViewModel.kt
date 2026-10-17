package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.cssSelector
import tachiyomi.core.common.util.system.logcat
import java.io.File

@AssistedInject
class EpubReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
) : ViewModel() {
    data class State(
        val phase: EpubRestorationPhase = EpubRestorationPhase.Opening,
        val title: String = "Mihon EPUB Dev",
        val tableOfContents: List<ReadiumEpubReaderAdapter.TocEntry> = emptyList(),
        val preferences: EpubReaderPreferences = EpubReaderPreferences(),
        val recoveryReason: EpubRecoveryReason? = null,
        val error: EpubOpenError? = null,
        /** Id of the active publication session; `0` while none is installed. */
        val sessionId: Long = 0,
        val remoteAttempts: Int = 0,
        val exit: Exit = Exit.Idle,
    ) {
        /** User navigation is honoured only when nothing else may move the navigator. */
        val canNavigate: Boolean get() = phase == EpubRestorationPhase.Ready && exit == Exit.Idle
    }

    /** Back saves the latest eligible checkpoint before the reader finishes. */
    sealed interface Exit {
        data object Idle : Exit
        data object Saving : Exit
        data object Done : Exit
        data class Failed(val detail: String) : Exit
    }

    @AssistedFactory
    @ViewModelAssistedFactoryKey(EpubReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): EpubReaderViewModel =
            create(extras.createSavedStateHandle())

        fun create(@Assisted savedState: SavedStateHandle): EpubReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val gate = EpubCheckpointGate()
    private var openJob: Job? = null
    private var reopenJob: Job? = null
    private var operationJob: Job? = null
    private var exitJob: Job? = null
    private var exitCounter = 0L
    private var sessionCounter = 0L
    private var session: EpubPublicationSession? = null

    /** Sessions whose navigator may still exist; closed once the host released that navigator. */
    private val retiring = mutableMapOf<Long, EpubPublicationSession>()
    private val sessionsWithNavigator = mutableSetOf<Long>()

    /** Locator to restore and verify once the current binding has rendered. */
    private var restoreTarget: Locator? = null
    private var lastAccepted: Locator? = null

    /**
     * Position Back must save. It outlives a rebinding while its capture is pending: the
     * replacement navigator restores it and the exit captures it again through the new owner.
     */
    private var exitTarget: Locator? = null

    var adapter: ReadiumEpubReaderAdapter? = null
        private set

    /** Newest accepted checkpoint envelope, for diagnostics and instrumented assertions. */
    val acceptedCheckpoint: String? get() = savedState[KEY_CHECKPOINT]

    /** Element anchor of the authoritative reading position, for diagnostics and assertions. */
    val anchorSelector: String? get() = lastAccepted?.locations?.cssSelector

    /** Owner whose navigator is bound and rendering for the active session, if any. */
    val activeOwner: EpubOwner? get() = gate.activeOwner

    private val target: EpubFixtureReference? = savedState.get<String>(KEY_TARGET)
        ?.let { runCatching { json.decodeFromString<EpubFixtureReference>(it) }.getOrNull() }
    private var preferences: EpubReaderPreferences = savedState.get<String>(KEY_PREFERENCES)
        ?.let { runCatching { json.decodeFromString<EpubReaderPreferences>(it) }.getOrNull() }
        ?: EpubReaderPreferences()
    private val diagnosticStore = EpubDiagnosticCheckpointStore(File(context.cacheDir, "epub-diagnostic-checkpoints"))
    private val checkpointWriter = EpubCheckpointWriter(
        scope = viewModelScope,
        onAccepted = { savedState[KEY_CHECKPOINT] = it },
        persist = ::writeCheckpoint,
    )
    private val capture = EpubPositionCapture<Locator>(
        scope = viewModelScope,
        gate = gate,
        lookup = { owner, locator ->
            EpubReaderTestHooks.captureDelayMs.takeIf { it > 0 }?.let { delay(it) }
            EpubReaderTestHooks.lookup(owner)
            adapter?.let { currentAnchor(it, owner, locator) }
        },
        currentLocation = { adapter?.currentLocator() },
        onAccepted = ::acceptAnchored,
    )

    init {
        if (target == null) {
            gate.failOpening()
            mutableState.value = State(
                phase = EpubRestorationPhase.Error,
                error = EpubOpenError.Missing("No diagnostic fixture target was supplied."),
            )
        } else {
            open(target)
        }
    }

    private fun open(target: EpubFixtureReference) {
        openJob?.cancel()
        closeSession()
        gate.beginOpening()
        mutableState.value = mutableState.value.copy(
            phase = EpubRestorationPhase.Opening,
            error = null,
            sessionId = 0,
        )
        openJob = viewModelScope.launch {
            RecordingDenyHttpClient.reset()
            val stored = withContext(Dispatchers.IO) { diagnosticStore.read(target.id) }
            // No suspension point between a successful return and install(): the session is
            // adopted before this coroutine can observe cancellation.
            when (val result = EpubPublicationOpener(context).open(target)) {
                is EpubPublicationOpener.Result.Failure -> {
                    gate.failOpening()
                    mutableState.value = mutableState.value.copy(
                        phase = EpubRestorationPhase.Error,
                        error = result.error,
                    )
                }
                is EpubPublicationOpener.Result.Success -> install(result.session, stored)
            }
        }
    }

    private fun install(newSession: EpubPublicationSession, stored: EpubDiagnosticCheckpointStore.ReadResult) {
        val sessionId = ++sessionCounter
        session = newSession
        val (initialLocator, recovery) = initialLocation(newSession, stored)
        restoreTarget = initialLocator
        lastAccepted = initialLocator
        // The restored checkpoint is authoritative until a newer one is accepted. Unless the file
        // already holds it, it is submitted to the writer: capture dedups on [lastAccepted], so an
        // unchanged anchor would otherwise let a later flush succeed without storing it.
        initialLocator?.let {
            val encoded = EpubLocatorCodec.encode(newSession.target, newSession.fingerprint, it)
            if ((stored as? EpubDiagnosticCheckpointStore.ReadResult.Found)?.encoded == encoded) {
                savedState[KEY_CHECKPOINT] = encoded
            } else {
                checkpointWriter.accept(encoded)
            }
        }
        gate.beginSession(sessionId)
        adapter = ReadiumEpubReaderAdapter(sessionId, newSession, preferences, ::onNavigatorEvent)
        mutableState.value = State(
            phase = gate.phase,
            title = adapter?.title.orEmpty(),
            tableOfContents = adapter?.tableOfContents.orEmpty(),
            preferences = preferences,
            recoveryReason = recovery,
            sessionId = sessionId,
            remoteAttempts = RecordingDenyHttpClient.attemptedRequestCount,
            // An exit in progress (or awaiting Retry/Stay) outlives a session transition.
            exit = mutableState.value.exit,
        )
    }

    /**
     * Precedence: the SavedStateHandle checkpoint (same task or process restoration, always the
     * newest accepted value) → the diagnostic close-and-open file → publication start. Every
     * candidate passes the codec's version, target, fingerprint, size and membership checks.
     */
    private fun initialLocation(
        session: EpubPublicationSession,
        stored: EpubDiagnosticCheckpointStore.ReadResult,
    ): Pair<Locator?, EpubRecoveryReason?> {
        var recovery: EpubRecoveryReason? = null
        val candidates = listOf(
            savedState.get<String>(KEY_CHECKPOINT)?.let { EpubDiagnosticCheckpointStore.ReadResult.Found(it) },
            stored,
        )
        for (candidate in candidates) {
            when (candidate) {
                null, EpubDiagnosticCheckpointStore.ReadResult.Missing -> continue
                is EpubDiagnosticCheckpointStore.ReadResult.Unreadable -> recovery = recovery ?: candidate.reason
                is EpubDiagnosticCheckpointStore.ReadResult.Found -> {
                    when (
                        val decoded = EpubLocatorCodec.decode(
                            candidate.encoded,
                            session.target,
                            session.fingerprint,
                            session.publication,
                        )
                    ) {
                        is EpubLocatorCodec.DecodeResult.Restored -> return decoded.locator to recovery
                        is EpubLocatorCodec.DecodeResult.Start -> recovery = recovery ?: decoded.reason
                    }
                }
            }
        }
        return null to recovery
    }

    /** Creates a navigator binding for the active session; `null` while no session is installed. */
    fun newBinding(): ReadiumEpubReaderAdapter.Binding? {
        val adapter = adapter ?: return null
        val initial = exitTarget ?: lastAccepted ?: restoreTarget
        // A rebinding (recreation/rotation) must land on the accepted passage, or on the pending
        // exit destination, again before Ready.
        restoreTarget = initial
        sessionsWithNavigator += adapter.sessionId
        return adapter.newBinding(initial)
    }

    fun bindNavigator(binding: ReadiumEpubReaderAdapter.Binding, lifecycleOwner: LifecycleOwner) {
        val adapter = adapter ?: return
        if (!adapter.bind(binding, lifecycleOwner)) return
        if (!gate.attach(binding.owner)) return
        operationJob?.cancel()
        capture.cancel()
        publishPhase()
        // The page may have rendered between instantiation and binding.
        if (binding.renderSignals.value > 0) onRendered(binding.owner)
    }

    fun detachBinding(binding: ReadiumEpubReaderAdapter.Binding?) {
        adapter?.detach(binding)
    }

    /** The host removed or destroyed the navigator that used [sessionId]'s publication. */
    fun navigatorReleased(sessionId: Long) {
        retiring.remove(sessionId)?.close()
    }

    /**
     * Main thread only: the binding moves off-main callbacks to the main looper. Ownership is
     * checked here, at delivery, so a queued event of a replaced binding or session is ignored.
     */
    @VisibleForTesting
    internal fun onNavigatorEvent(owner: EpubOwner, event: ReadiumEpubReaderAdapter.Event) {
        check(Looper.getMainLooper().isCurrentThread) { "EPUB reader events must be handled on the main thread" }
        if (!gate.isCurrent(owner)) return
        when (event) {
            ReadiumEpubReaderAdapter.Event.PageRendered -> onRendered(owner)
            is ReadiumEpubReaderAdapter.Event.LocatorChanged -> onLocator(owner, event.locator)
            is ReadiumEpubReaderAdapter.Event.ResourceFailed -> {
                if (!gate.fail(owner)) return
                operationJob?.cancel()
                capture.cancel()
                mutableState.value = mutableState.value.copy(
                    phase = EpubRestorationPhase.Error,
                    error = EpubOpenError.ResourceUnavailable(event.detail),
                )
            }
        }
    }

    private fun onRendered(owner: EpubOwner) {
        if (!gate.rendered(owner)) return
        val target = restoreTarget
        if (target == null) {
            publishPhase()
        } else {
            runOperation(owner, EpubRestorationPhase.Restoring) { adapter -> adapter.restore(owner, target) }
        }
    }

    private fun onLocator(owner: EpubOwner, locator: Locator) {
        // Readium may report the location only after Back (its locator lags a jump), so the newest
        // location reported in Ready during an exit is the destination Back saves.
        if (capture.request(owner, locator) && mutableState.value.exit == Exit.Saving) exitTarget = locator
    }

    /** Called by [capture] only for a current, unsuperseded capture of a still-shown location. */
    private fun acceptAnchored(anchored: Locator) {
        val session = session ?: return
        if (anchored === lastAccepted) return
        lastAccepted = anchored
        checkpointWriter.accept(EpubLocatorCodec.encode(session.target, session.fingerprint, anchored))
    }

    /**
     * While the previously accepted anchor element is still on screen the reader has not moved,
     * so it stays authoritative. Re-anchoring to the first visible element of a restored page
     * would otherwise drift the passage backwards by up to a page per relayout.
     */
    private suspend fun currentAnchor(adapter: ReadiumEpubReaderAdapter, owner: EpubOwner, locator: Locator): Locator? {
        val held = lastAccepted
        if (held != null && held.href == locator.href && held.locations.cssSelector != null &&
            adapter.isShowing(owner, held)
        ) {
            return held
        }
        return adapter.anchored(owner, locator)
    }

    /**
     * Runs a restoration/reflow operation. Persistence stays closed until the operation of this
     * owner completes; a failed restore is a controlled error that keeps the last checkpoint.
     */
    private fun runOperation(
        owner: EpubOwner,
        kind: EpubRestorationPhase,
        block: suspend (ReadiumEpubReaderAdapter) -> Boolean,
    ) {
        val adapter = adapter ?: return
        val operation = gate.beginOperation(owner, kind) ?: return
        capture.cancel()
        operationJob?.cancel()
        publishPhase()
        operationJob = viewModelScope.launch {
            EpubReaderTestHooks.operationDelayMs.takeIf { it > 0 }?.let { delay(it) }
            val success = block(adapter)
            if (!gate.completeOperation(owner, operation, success)) return@launch
            if (success) {
                restoreTarget = null
                publishPhase()
            } else {
                mutableState.value = mutableState.value.copy(
                    phase = EpubRestorationPhase.Error,
                    error = EpubOpenError.RestorationFailed("The reading position could not be restored."),
                )
            }
        }
    }

    private fun publishPhase() {
        mutableState.value = mutableState.value.copy(phase = gate.phase, preferences = preferences)
    }

    /**
     * Navigation policy: user navigation is ignored unless the reader is Ready and not exiting.
     * Restoration and reflow therefore never compete with a user destination. The host also
     * blocks navigator touch and key input while [State.canNavigate] is false.
     */
    private fun navigationAdapter(): ReadiumEpubReaderAdapter? {
        val owner = gate.activeOwner ?: return null
        return adapter?.takeIf { gate.accept(owner) && mutableState.value.exit == Exit.Idle }
    }

    fun previous(): Boolean = navigationAdapter()?.previous() == true
    fun next(): Boolean = navigationAdapter()?.next() == true
    fun goToToc(id: String): Boolean = navigationAdapter()?.goToToc(id) == true

    fun changeFontSize(delta: Double) {
        if (mutableState.value.exit != Exit.Idle) return
        val owner = gate.activeOwner?.takeIf { gate.accept(it) } ?: return
        val next = ((preferences.fontSizeOverride ?: 1.0) + delta).coerceIn(0.5, 2.5)
        runOperation(owner, EpubRestorationPhase.Reflowing) { adapter ->
            val current = adapter.currentLocator() ?: return@runOperation false
            // Capture the passage before the reflow changes what is visible.
            val anchor = currentAnchor(adapter, owner, current) ?: lastAccepted ?: current
            val applied = adapter.setFontSize(owner, next) ?: return@runOperation false
            preferences = applied
            savedState[KEY_PREFERENCES] = json.encodeToString(applied)
            adapter.restore(owner, anchor)
        }
    }

    /**
     * Captures the current location (only in Ready) through the same ordered capture path as
     * automatic locator changes, waits for the newest capture, and flushes. Manual checkpoints
     * during opening, restoration or reflow capture nothing, so they cannot reopen persistence.
     * A requested capture that is not accepted is a failure: flushing an older value would
     * otherwise report the requested position as saved.
     */
    internal suspend fun checkpointNow(): EpubCheckpointWriter.FlushResult {
        val outcome = captureCurrent()
        if (outcome != null && outcome != EpubPositionCapture.Outcome.Accepted) return notCaptured(outcome)
        return checkpointWriter.flush()
    }

    /** Outcome of a capture of the current location; `null` when none could be requested. */
    private suspend fun captureCurrent(): EpubPositionCapture.Outcome? {
        val owner = gate.activeOwner ?: return null
        val locator = adapter?.currentLocator() ?: return null
        if (!capture.request(owner, locator)) return null
        return capture.awaitLatest()
    }

    private fun notCaptured(outcome: Any) =
        EpubCheckpointWriter.FlushResult.Failure(IllegalStateException("Reading position not captured: $outcome"))

    /**
     * Saves [exitTarget] for Back. Without one (Back during opening or restoration, before the
     * reader was Ready) the authoritative accepted checkpoint is flushed. Otherwise the target is
     * captured through the current owner; if that owner's binding is replaced meanwhile (rotation,
     * recreation), the capture is retried once the replacement restored the target and is Ready.
     * Callbacks of the replaced navigator are never consulted.
     */
    private suspend fun saveExitPosition(): EpubCheckpointWriter.FlushResult {
        if (exitTarget == null) return checkpointWriter.flush()
        while (true) {
            // The gate, not the published state, is checked: a rebinding cancels the capture
            // before its new phase is published. Every gate transition publishes a state.
            mutableState.first {
                gate.phase == EpubRestorationPhase.Error ||
                    (gate.phase == EpubRestorationPhase.Ready && adapter?.currentBinding?.owner == gate.activeOwner)
            }
            if (gate.phase == EpubRestorationPhase.Error) return notCaptured(EpubRestorationPhase.Error)
            val owner = gate.activeOwner
            when (val outcome = captureCurrent()) {
                EpubPositionCapture.Outcome.Accepted -> {
                    exitTarget = lastAccepted
                    return checkpointWriter.flush()
                }
                else -> if (adapter?.currentBinding?.owner == owner) return notCaptured(outcome ?: "no navigator")
            }
        }
    }

    fun checkpoint() {
        viewModelScope.launch { logFailure(checkpointNow()) }
    }

    /**
     * Back: capture the displayed position ([exitTarget], see [saveExitPosition]) and flush it,
     * then let the host finish. Done is reported only for an accepted capture. Runs in
     * [viewModelScope], which outlives configuration changes and is cleared only after the host
     * finished. Bounded by [EXIT_TIMEOUT_MS]; repeated requests while saving are ignored.
     *
     * Back supersedes a pending [reopen]: one still capturing is cancelled before it opens, and a
     * session it already started installs without resetting the exit. The flush covers every
     * checkpoint accepted so far. Only the newest exit attempt may publish its result.
     */
    fun exit() {
        if (exitJob?.isActive == true || mutableState.value.exit == Exit.Done) return
        reopenJob?.cancel()
        val attempt = ++exitCounter
        // While not Ready (opening, restoring a rebinding) any earlier target is kept.
        gate.activeOwner?.takeIf(gate::accept)?.let { adapter?.currentLocator() }?.let { exitTarget = it }
        mutableState.value = mutableState.value.copy(exit = Exit.Saving)
        exitJob = viewModelScope.launch {
            val exit = when (val result = withTimeoutOrNull(EXIT_TIMEOUT_MS) { saveExitPosition() }) {
                EpubCheckpointWriter.FlushResult.Success -> Exit.Done
                is EpubCheckpointWriter.FlushResult.Failure ->
                    Exit.Failed(result.cause.message ?: result.cause::class.java.simpleName)
                null -> Exit.Failed("Saving timed out")
            }
            if (attempt != exitCounter || mutableState.value.exit != Exit.Saving) return@launch
            if (exit == Exit.Done) exitTarget = null
            mutableState.value = mutableState.value.copy(exit = exit)
        }
    }

    /** Dismisses a failed exit and returns to reading. */
    fun stayAfterFailedExit() {
        if (mutableState.value.exit is Exit.Failed) {
            exitTarget = null
            mutableState.value = mutableState.value.copy(exit = Exit.Idle)
        }
    }

    /** Rejected while an exit is saving, failed (awaiting Retry/Stay) or done. */
    fun reopen() {
        if (mutableState.value.exit != Exit.Idle) return
        val target = target ?: return
        reopenJob?.cancel()
        reopenJob = viewModelScope.launch {
            checkpointNow()
            open(target)
        }
    }

    /**
     * Best effort (pause/stop): queues a flush and logs a failure. It is not a persistence
     * guarantee; only [exit] and [checkpointNow] report a result.
     */
    fun flushCheckpoint() {
        viewModelScope.launch { logFailure(checkpointWriter.flush()) }
    }

    private fun logFailure(result: EpubCheckpointWriter.FlushResult) {
        if (result is EpubCheckpointWriter.FlushResult.Failure) {
            logcat(LogPriority.WARN, result.cause) { "EPUB checkpoint flush failed" }
        }
    }

    private suspend fun writeCheckpoint(encoded: String) = withContext(Dispatchers.IO) {
        val id = target?.id ?: return@withContext
        try {
            EpubReaderTestHooks.persist()
            diagnosticStore.write(id, encoded)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Diagnostic EPUB checkpoint write failed" }
            throw e
        }
    }

    /**
     * Teardown order: close the gate, detach the binding (stops locator/pagination/error/input
     * callbacks), then close the publication only once no navigator can still use it.
     */
    private fun closeSession() {
        operationJob?.cancel()
        capture.cancel()
        gate.close()
        adapter?.detachAll()
        adapter = null
        val old = session ?: return
        session = null
        val oldId = sessionCounter
        if (sessionsWithNavigator.remove(oldId)) retiring[oldId] = old else old.close()
    }

    override fun onCleared() {
        closeSession()
        // ViewModels are cleared before the Activity's fragments are destroyed. Close after the
        // current destroy dispatch so no navigator still renders from a closed publication.
        val abandoned = retiring.values.toList()
        retiring.clear()
        Handler(Looper.getMainLooper()).post { abandoned.forEach { it.close() } }
        super.onCleared()
    }

    companion object {
        const val KEY_TARGET = "epub_fixture_target"
        private const val KEY_CHECKPOINT = "epub_locator_checkpoint"
        private const val KEY_PREFERENCES = "epub_reader_preferences"
        val json = Json { ignoreUnknownKeys = false }
        private const val EXIT_TIMEOUT_MS = 5_000L
    }
}

/** Delays and failures injected by instrumented tests; no-ops otherwise. */
@VisibleForTesting
internal object EpubReaderTestHooks {
    @Volatile var captureDelayMs = 0L

    @Volatile var operationDelayMs = 0L

    /** Runs inside every anchor lookup, with the capturing owner. */
    @Volatile var lookup: suspend (EpubOwner) -> Unit = {}

    @Volatile var persist: suspend () -> Unit = {}

    fun reset() {
        captureDelayMs = 0L
        operationDelayMs = 0L
        lookup = {}
        persist = {}
    }
}

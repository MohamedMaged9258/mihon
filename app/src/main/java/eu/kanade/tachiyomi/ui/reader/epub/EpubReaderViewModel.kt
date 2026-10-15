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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    )

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
    private var anchorJob: Job? = null
    private var sessionCounter = 0L
    private var session: EpubPublicationSession? = null

    /** Sessions whose navigator may still exist; closed once the host released that navigator. */
    private val retiring = mutableMapOf<Long, EpubPublicationSession>()
    private val sessionsWithNavigator = mutableSetOf<Long>()

    /** Locator to restore and verify once the current binding has rendered. */
    private var restoreTarget: Locator? = null
    private var lastAccepted: Locator? = null

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
        val initial = lastAccepted ?: restoreTarget
        // A rebinding (recreation/rotation) must land on the accepted passage again before Ready.
        restoreTarget = initial
        sessionsWithNavigator += adapter.sessionId
        return adapter.newBinding(initial)
    }

    fun bindNavigator(binding: ReadiumEpubReaderAdapter.Binding, lifecycleOwner: LifecycleOwner) {
        val adapter = adapter ?: return
        if (!adapter.bind(binding, lifecycleOwner)) return
        if (!gate.attach(binding.owner)) return
        operationJob?.cancel()
        anchorJob?.cancel()
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

    @VisibleForTesting
    internal fun onNavigatorEvent(owner: EpubOwner, event: ReadiumEpubReaderAdapter.Event) {
        if (!gate.isCurrent(owner)) return
        when (event) {
            ReadiumEpubReaderAdapter.Event.PageRendered -> onRendered(owner)
            is ReadiumEpubReaderAdapter.Event.LocatorChanged -> onLocator(owner, event.locator)
            is ReadiumEpubReaderAdapter.Event.ResourceFailed -> {
                if (!gate.fail(owner)) return
                operationJob?.cancel()
                anchorJob?.cancel()
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
        if (!gate.accept(owner)) return
        anchorJob?.cancel()
        anchorJob = viewModelScope.launch { acceptAnchored(owner, locator) }
    }

    private suspend fun acceptAnchored(owner: EpubOwner, locator: Locator): Boolean {
        val adapter = adapter ?: return false
        val session = session ?: return false
        val anchored = currentAnchor(adapter, owner, locator) ?: return false
        if (anchored === lastAccepted) return gate.accept(owner)
        // Re-check: the anchor lookup suspends and the owner or phase may have changed.
        if (!gate.accept(owner)) return false
        lastAccepted = anchored
        checkpointWriter.accept(EpubLocatorCodec.encode(session.target, session.fingerprint, anchored))
        return true
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
        anchorJob?.cancel()
        operationJob?.cancel()
        publishPhase()
        operationJob = viewModelScope.launch {
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

    fun previous() = adapter?.previous()
    fun next() = adapter?.next()
    fun goToToc(id: String) = adapter?.goToToc(id)

    fun changeFontSize(delta: Double) {
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
     * Accepts the current anchored location (only in Ready) and flushes it. Manual checkpoints
     * during opening, restoration or reflow are ignored so they cannot reopen persistence.
     */
    internal suspend fun checkpointNow(): EpubCheckpointWriter.FlushResult {
        val owner = gate.activeOwner
        val locator = adapter?.currentLocator()
        if (owner != null && locator != null && gate.accept(owner)) {
            anchorJob?.cancel()
            acceptAnchored(owner, locator)
        }
        return checkpointWriter.flush()
    }

    fun checkpoint() {
        viewModelScope.launch { checkpointNow() }
    }

    fun reopen() {
        val target = target ?: return
        reopenJob?.cancel()
        reopenJob = viewModelScope.launch {
            checkpointNow()
            open(target)
        }
    }

    fun flushCheckpoint() {
        viewModelScope.launch { checkpointWriter.flush() }
    }

    private suspend fun writeCheckpoint(encoded: String) = withContext(Dispatchers.IO) {
        val id = target?.id ?: return@withContext
        try {
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
        anchorJob?.cancel()
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
    }
}

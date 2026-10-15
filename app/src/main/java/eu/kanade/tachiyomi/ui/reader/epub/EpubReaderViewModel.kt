package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readium.r2.shared.publication.Locator

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
        val sessionGeneration: Long = 0,
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
    private var generation = 0L
    private var session: EpubPublicationSession? = null
    var adapter: ReadiumEpubReaderAdapter? = null
        private set

    private val target: EpubFixtureReference? = savedState.get<String>(KEY_TARGET)
        ?.let { runCatching { json.decodeFromString<EpubFixtureReference>(it) }.getOrNull() }
    private var preferences: EpubReaderPreferences = savedState.get<String>(KEY_PREFERENCES)
        ?.let { runCatching { json.decodeFromString<EpubReaderPreferences>(it) }.getOrNull() }
        ?: EpubReaderPreferences()
    private val checkpointWriter = EpubCheckpointWriter(
        scope = viewModelScope,
        onAccepted = { savedState[KEY_CHECKPOINT] = it },
        persist = ::writeCheckpoint,
    )

    init {
        if (target == null) {
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
        openJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                phase = EpubRestorationPhase.Opening,
                error = null,
            )
            gate.setPhase(EpubRestorationPhase.Opening)
            closeSession()
            RecordingDenyHttpClient.reset()
            when (val result = EpubPublicationOpener(context).open(target)) {
                is EpubPublicationOpener.Result.Failure -> {
                    gate.setPhase(EpubRestorationPhase.Error)
                    mutableState.value = mutableState.value.copy(
                        phase = EpubRestorationPhase.Error,
                        error = result.error,
                    )
                }
                is EpubPublicationOpener.Result.Success -> install(result.session)
            }
        }
    }

    private fun install(newSession: EpubPublicationSession) {
        session = newSession
        val checkpoint = savedState.get<String>(KEY_CHECKPOINT)
        val decoded = checkpoint?.let {
            EpubLocatorCodec.decode(it, newSession.target, newSession.fingerprint, newSession.publication)
        }
        val initialLocator = (decoded as? EpubLocatorCodec.DecodeResult.Restored)?.locator
        val recovery = (decoded as? EpubLocatorCodec.DecodeResult.Start)?.reason
        generation = gate.begin(
            if (initialLocator == null) EpubRestorationPhase.Attaching else EpubRestorationPhase.Restoring,
            initialLocator?.href?.toString(),
        )
        adapter = ReadiumEpubReaderAdapter(
            session = newSession,
            initialLocator = initialLocator,
            preferences = preferences,
            onLocator = ::onLocator,
            onResourceError = {
                mutableState.value = mutableState.value.copy(
                    phase = EpubRestorationPhase.Error,
                    error = EpubOpenError.ResourceUnavailable(it),
                )
            },
        )
        mutableState.value = State(
            phase = gate.phase,
            title = adapter?.title.orEmpty(),
            tableOfContents = adapter?.tableOfContents.orEmpty(),
            preferences = preferences,
            recoveryReason = recovery,
            sessionGeneration = mutableState.value.sessionGeneration + 1,
            remoteAttempts = RecordingDenyHttpClient.attemptedRequestCount,
        )
    }

    fun navigatorAttached() {
        if (gate.phase == EpubRestorationPhase.Attaching) {
            gate.setPhase(EpubRestorationPhase.Ready)
            mutableState.value = mutableState.value.copy(phase = EpubRestorationPhase.Ready)
        }
    }

    private fun onLocator(locator: Locator) {
        val session = session ?: return
        if (!gate.observe(generation, locator.href.toString())) return
        mutableState.value = mutableState.value.copy(phase = EpubRestorationPhase.Ready)
        checkpointWriter.accept(EpubLocatorCodec.encode(session.target, session.fingerprint, locator))
    }

    fun previous() = adapter?.previous()
    fun next() = adapter?.next()
    fun goToToc(id: String) = adapter?.goToToc(id)

    fun changeFontSize(delta: Double) {
        val adapter = adapter ?: return
        val locator = adapter.currentLocator() ?: return
        val next = ((preferences.fontSizeOverride ?: 1.0) + delta).coerceIn(0.5, 2.5)
        generation = gate.begin(EpubRestorationPhase.Reflowing, locator.href.toString())
        mutableState.value = mutableState.value.copy(phase = EpubRestorationPhase.Reflowing)
        preferences = adapter.setFontSize(next)
        savedState[KEY_PREFERENCES] = json.encodeToString(preferences)
        mutableState.value = mutableState.value.copy(preferences = preferences)
        adapter.go(locator)
    }

    fun checkpoint() {
        adapter?.currentLocator()?.let(::onLocator)
        flushCheckpoint()
    }

    fun reopen() {
        checkpoint()
        target?.let(::open)
    }

    fun flushCheckpoint() {
        viewModelScope.launch { checkpointWriter.flush() }
    }

    private suspend fun writeCheckpoint(encoded: String) = withContext(Dispatchers.IO) {
        val id = target?.id ?: return@withContext
        File(context.cacheDir, "epub-checkpoint-$id.json").writeText(encoded)
    }

    private fun closeSession() {
        gate.setPhase(EpubRestorationPhase.Closing)
        adapter?.unbind()
        adapter = null
        session?.close()
        session = null
    }

    override fun onCleared() {
        closeSession()
        super.onCleared()
    }

    companion object {
        const val KEY_TARGET = "epub_fixture_target"
        private const val KEY_CHECKPOINT = "epub_locator_checkpoint"
        private const val KEY_PREFERENCES = "epub_reader_preferences"
        val json = Json { ignoreUnknownKeys = false }
    }
}

package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Orders accepted checkpoints and coalesces their diagnostic disk writes. */
internal class EpubCheckpointWriter(
    scope: CoroutineScope,
    private val onAccepted: (String) -> Unit,
    private val persist: suspend (String) -> Unit,
    private val coalesceMillis: Long = 300,
) {
    private sealed interface Command {
        data class Save(val value: String) : Command
        data class Flush(val done: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var latest: String? = null
    private var diskJob: Job? = null

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Save -> {
                        latest = command.value
                        diskJob?.cancel()
                        diskJob = launch {
                            delay(coalesceMillis)
                            persist(command.value)
                        }
                    }
                    is Command.Flush -> {
                        diskJob?.cancelAndJoin()
                        latest?.let { persist(it) }
                        command.done.complete(Unit)
                    }
                }
            }
        }
    }

    fun accept(value: String) {
        // SavedStateHandle is updated synchronously before the coalesced actor write.
        onAccepted(value)
        commands.trySend(Command.Save(value))
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Flush(done))
        done.await()
    }
}

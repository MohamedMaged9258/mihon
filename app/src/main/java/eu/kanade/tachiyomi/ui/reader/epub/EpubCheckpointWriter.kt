package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orders accepted checkpoints and coalesces their diagnostic disk writes.
 *
 * A single actor coroutine performs every physical write inline, so at most one write runs at a
 * time and a write is never abandoned while a newer one starts. Pending values are conflated to
 * the newest accepted value.
 */
internal class EpubCheckpointWriter(
    scope: CoroutineScope,
    private val onAccepted: (String) -> Unit,
    private val persist: suspend (String) -> Unit,
    private val coalesceMillis: Long = 300,
) {
    sealed interface FlushResult {
        data object Success : FlushResult
        data class Failure(val cause: Throwable) : FlushResult
    }

    private sealed interface Command {
        data class Save(val sequence: Long, val value: String) : Command
        data class Flush(val done: CompletableDeferred<FlushResult>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var acceptedSequence = 0L

    init {
        scope.launch {
            var latest: Command.Save? = null
            var writtenSequence = 0L
            // A failed value is retried by the next save or flush, not by a timer loop.
            var failedSequence = 0L
            val waiters = mutableListOf<CompletableDeferred<FlushResult>>()

            while (true) {
                val dirty = latest != null && latest.sequence > writtenSequence && latest.sequence != failedSequence
                val command = if (dirty) {
                    withTimeoutOrNull(coalesceMillis) { commands.receive() }
                } else {
                    commands.receive()
                }
                when (command) {
                    is Command.Save -> {
                        latest = command
                        continue
                    }
                    is Command.Flush -> waiters += command.done
                    // Coalescing delay elapsed.
                    null -> Unit
                }

                val result = latest
                    ?.takeIf { it.sequence > writtenSequence }
                    ?.let { save ->
                        try {
                            persist(save.value)
                            writtenSequence = save.sequence
                            FlushResult.Success
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedSequence = save.sequence
                            FlushResult.Failure(e)
                        }
                    }
                    ?: FlushResult.Success
                // The channel is FIFO, so every waiter's value was received before its Flush.
                waiters.forEach { it.complete(result) }
                waiters.clear()
            }
        }
    }

    fun accept(value: String) {
        // SavedStateHandle is updated synchronously before the coalesced actor write.
        onAccepted(value)
        commands.trySend(Command.Save(++acceptedSequence, value))
    }

    /** Completes after the newest value accepted before this call is stored, or its write failed. */
    suspend fun flush(): FlushResult {
        val done = CompletableDeferred<FlushResult>()
        commands.send(Command.Flush(done))
        return done.await()
    }
}

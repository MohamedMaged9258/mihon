package eu.kanade.tachiyomi.ui.reader.epub

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class EpubPublicationHandOffTest {
    private class Resource : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `cancellation at the return dispatch closes the produced resource`() = runTest {
        val callerDispatcher = StandardTestDispatcher(testScheduler, "caller")
        val ioDispatcher = StandardTestDispatcher(testScheduler, "io")
        val resource = Resource()
        var adopted: Resource? = null

        lateinit var job: Job
        job = launch(callerDispatcher) {
            adopted = handOff(ioDispatcher, Resource::close) {
                // The caller is cancelled after the resource exists but before withContext
                // resumes it: the prompt-cancellation guarantee discards the returned value.
                job.cancel()
                resource
            }
        }
        testScheduler.advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(null, adopted)
        assertTrue(resource.closed)
    }

    @Test
    fun `successful handoff transfers ownership`() = runTest {
        val resource = Resource()
        val adopted = handOff(StandardTestDispatcher(testScheduler), Resource::close) { resource }
        assertTrue(adopted === resource)
        assertTrue(!resource.closed)
    }

    @Test
    fun `production failure propagates without a resource`() = runTest {
        var released = false
        val error = runCatching {
            handOff<Resource>(StandardTestDispatcher(testScheduler), { released = true }) {
                throw IOException("cannot create temp file")
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(!released)
    }
}

package eu.kanade.tachiyomi.ui.reader.epub

/** Pure lifecycle gate used by the ViewModel and unit tests. */
class EpubCheckpointGate {
    private var generation = 0L
    private var requestedResource: String? = null
    var phase: EpubRestorationPhase = EpubRestorationPhase.Opening
        private set

    fun begin(phase: EpubRestorationPhase, resource: String?): Long {
        generation += 1
        this.phase = phase
        requestedResource = resource
        return generation
    }

    fun observe(generation: Long, resource: String): Boolean {
        if (generation != this.generation) return false
        val expected = requestedResource
        if (phase == EpubRestorationPhase.Restoring || phase == EpubRestorationPhase.Reflowing) {
            if (expected != null && expected != resource) return false
            phase = EpubRestorationPhase.Ready
        }
        return phase == EpubRestorationPhase.Ready
    }

    fun setPhase(phase: EpubRestorationPhase) {
        this.phase = phase
    }
}

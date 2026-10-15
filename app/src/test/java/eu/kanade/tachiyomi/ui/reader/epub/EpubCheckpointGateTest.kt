package eu.kanade.tachiyomi.ui.reader.epub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubCheckpointGateTest {
    @Test
    fun `restoration ignores defaults and stale generations`() {
        val gate = EpubCheckpointGate()
        val first = gate.begin(EpubRestorationPhase.Restoring, "chapter-2.xhtml")
        assertFalse(gate.observe(first, "chapter-1.xhtml"))
        val second = gate.begin(EpubRestorationPhase.Restoring, "chapter-3.xhtml")
        assertFalse(gate.observe(first, "chapter-2.xhtml"))
        assertTrue(gate.observe(second, "chapter-3.xhtml"))
        assertTrue(gate.observe(second, "chapter-3.xhtml"))
    }

    @Test
    fun `reflow reopens writes only at captured resource`() {
        val gate = EpubCheckpointGate()
        val generation = gate.begin(EpubRestorationPhase.Reflowing, "nested.xhtml")
        assertFalse(gate.observe(generation, "intro.xhtml"))
        assertTrue(gate.observe(generation, "nested.xhtml"))
    }
}

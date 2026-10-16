package eu.kanade.tachiyomi.ui.reader.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubCheckpointGateTest {
    private fun readyGate(owner: EpubOwner) = EpubCheckpointGate().apply {
        beginSession(owner.sessionId)
        assertTrue(attach(owner))
        assertTrue(rendered(owner))
    }

    @Test
    fun `persistence opens only after the current binding rendered`() {
        val owner = EpubOwner(sessionId = 1, bindingId = 1)
        val gate = EpubCheckpointGate()
        gate.beginSession(1)
        assertFalse(gate.accept(owner))
        gate.attach(owner)
        assertEquals(EpubRestorationPhase.Rendering, gate.phase)
        assertFalse(gate.accept(owner))
        assertTrue(gate.rendered(owner))
        assertTrue(gate.accept(owner))
        // Repeated render signals do not re-trigger the transition.
        assertFalse(gate.rendered(owner))
    }

    @Test
    fun `old session events after replacement change nothing, even for the same href`() {
        val old = EpubOwner(sessionId = 1, bindingId = 1)
        val gate = readyGate(old)

        gate.close()
        gate.beginOpening()
        val current = EpubOwner(sessionId = 2, bindingId = 2)
        gate.beginSession(2)
        gate.attach(current)
        gate.rendered(current)
        val operation = requireNotNull(gate.beginOperation(current, EpubRestorationPhase.Restoring))

        // Locator, pagination, resource-error and attachment callbacks from the old session.
        assertFalse(gate.accept(old))
        assertFalse(gate.rendered(old))
        assertFalse(gate.fail(old))
        assertFalse(gate.attach(old))
        assertFalse(gate.completeOperation(old, operation, success = true))
        assertEquals(EpubRestorationPhase.Restoring, gate.phase)
        assertEquals(current, gate.activeOwner)

        assertTrue(gate.completeOperation(current, operation, success = true))
        assertTrue(gate.accept(current))
    }

    @Test
    fun `old binding of the same session is rejected after rebinding`() {
        val first = EpubOwner(sessionId = 3, bindingId = 1)
        val gate = readyGate(first)
        val second = EpubOwner(sessionId = 3, bindingId = 2)
        assertTrue(gate.attach(second))

        assertFalse(gate.accept(first))
        assertFalse(gate.rendered(first))
        assertFalse(gate.fail(first))
        assertEquals(EpubRestorationPhase.Rendering, gate.phase)
        assertTrue(gate.rendered(second))
        assertTrue(gate.accept(second))
    }

    @Test
    fun `transitional operations keep persistence closed and stale operations cannot complete`() {
        val owner = EpubOwner(1, 1)
        val gate = readyGate(owner)
        val reflow = requireNotNull(gate.beginOperation(owner, EpubRestorationPhase.Reflowing))
        assertFalse(gate.accept(owner))
        val restore = requireNotNull(gate.beginOperation(owner, EpubRestorationPhase.Restoring))
        assertFalse(gate.completeOperation(owner, reflow, success = true))
        assertFalse(gate.accept(owner))
        assertTrue(gate.completeOperation(owner, restore, success = true))
        assertTrue(gate.accept(owner))
    }

    @Test
    fun `error and closing keep the gate closed`() {
        val owner = EpubOwner(1, 1)
        val gate = readyGate(owner)
        assertTrue(gate.fail(owner))
        assertFalse(gate.accept(owner))
        assertFalse(gate.rendered(owner))
        assertNull(gate.beginOperation(owner, EpubRestorationPhase.Restoring))
        assertFalse(gate.attach(owner))

        val other = readyGate(owner)
        val operation = requireNotNull(other.beginOperation(owner, EpubRestorationPhase.Restoring))
        other.close()
        assertFalse(other.accept(owner))
        assertFalse(other.completeOperation(owner, operation, success = true))
        assertEquals(EpubRestorationPhase.Closing, other.phase)
    }

    @Test
    fun `capture tokens are issued only in Ready and invalidated by any later event`() {
        val owner = EpubOwner(1, 1)
        val gate = EpubCheckpointGate()
        gate.beginSession(1)
        gate.attach(owner)
        assertNull(gate.beginCapture(owner))
        gate.rendered(owner)

        val first = requireNotNull(gate.beginCapture(owner))
        assertTrue(gate.acceptCapture(owner, first))
        val second = requireNotNull(gate.beginCapture(owner))
        assertFalse(gate.acceptCapture(owner, first))
        assertTrue(gate.acceptCapture(owner, second))

        val operation = requireNotNull(gate.beginOperation(owner, EpubRestorationPhase.Reflowing))
        assertNull(gate.beginCapture(owner))
        assertTrue(gate.completeOperation(owner, operation, success = true))
        assertFalse(gate.acceptCapture(owner, second))

        val third = requireNotNull(gate.beginCapture(owner))
        assertTrue(gate.attach(EpubOwner(1, 2)))
        assertFalse(gate.acceptCapture(owner, third))
    }

    @Test
    fun `failed restoration is a controlled error`() {
        val owner = EpubOwner(1, 1)
        val gate = readyGate(owner)
        val operation = requireNotNull(gate.beginOperation(owner, EpubRestorationPhase.Restoring))
        assertTrue(gate.completeOperation(owner, operation, success = false))
        assertEquals(EpubRestorationPhase.Error, gate.phase)
        assertFalse(gate.accept(owner))
    }
}

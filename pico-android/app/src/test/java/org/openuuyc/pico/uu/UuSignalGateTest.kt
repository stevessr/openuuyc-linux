package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuSignalGateTest {
    @Test fun standardHandshakeAllocatesExactlyOneController() {
        val gate = UuSignalGate()
        assertFalse(gate.roomAcknowledged())
        assertFalse(gate.controlAcknowledged())
        assertTrue(gate.namespaceConnected())
        assertFalse(gate.namespaceConnected())
        assertTrue(gate.roomAcknowledged())
        assertFalse(gate.roomAcknowledged())
        assertTrue(gate.controlAcknowledged())
        assertFalse(gate.controlAcknowledged())
        assertTrue(gate.mayProcessMedia())
    }

    @Test fun staleAndReorderedControlAckCannotAuthorizePeer() {
        val gate = UuSignalGate()
        assertFalse(gate.controlAcknowledged())
        gate.namespaceConnected()
        assertFalse(gate.controlAcknowledged())
        assertFalse(gate.mayProcessMedia())
    }

    @Test fun oldRoomReplyAfterCloseNeverRestartsControl() {
        val gate = UuSignalGate()
        gate.namespaceConnected()
        gate.close()
        assertFalse(gate.roomAcknowledged())
        assertFalse(gate.controlAcknowledged())
        assertFalse(gate.namespaceConnected())
        assertFalse(gate.mayProcessMedia())
    }

    @Test fun roomAckAndServerDuplicateCannotRecreateSession() {
        val gate = UuSignalGate()
        gate.namespaceConnected()
        assertTrue(gate.roomAcknowledged())
        assertFalse(gate.roomAcknowledged())
        assertEquals(UuSignalGate.Phase.WAIT_CONTROL_ACK, gate.phase)
        gate.controlAcknowledged()
        assertFalse(gate.roomAcknowledged())
        assertFalse(gate.controlAcknowledged())
    }
}

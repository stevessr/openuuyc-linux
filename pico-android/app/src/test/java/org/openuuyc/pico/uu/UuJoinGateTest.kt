package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuJoinGateTest {
    @Test fun duplicateDeviceTapsCannotStartConcurrentSessions() {
        val gate = UuJoinGate()
        val first = requireNotNull(gate.begin())
        assertNull(gate.begin())
        assertTrue(gate.adopt(first))
        assertNull(gate.begin())
    }

    @Test fun disconnectDuringNrdNetworkJoinInvalidatesLateSuccess() {
        val gate = UuJoinGate()
        val first = requireNotNull(gate.begin())
        gate.stop()
        assertFalse(gate.adopt(first))
        val second = requireNotNull(gate.begin())
        assertFalse(gate.adopt(first))
        assertTrue(gate.adopt(second))
    }

    @Test fun staleFailureCannotClearNewInFlightJoin() {
        val gate = UuJoinGate()
        val first = requireNotNull(gate.begin())
        gate.stop()
        val second = requireNotNull(gate.begin())
        gate.failed(first)
        assertNull(gate.begin())
        gate.failed(second)
        assertNotNull(gate.begin())
    }

    @Test fun destroyedActivityCannotLaunchXrFromPendingCallback() {
        val gate = UuJoinGate()
        val ticket = requireNotNull(gate.begin())
        gate.destroy()
        assertFalse(gate.adopt(ticket))
        assertNull(gate.begin())
    }
}

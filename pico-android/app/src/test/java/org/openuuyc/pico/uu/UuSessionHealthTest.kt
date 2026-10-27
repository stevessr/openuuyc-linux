package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuSessionHealthTest {
    private class Clock(var time: Long = 0) {
        val health = UuSessionHealth { time }
        fun advance(ms: Long) { time += ms }
    }

    @Test fun signalDeadlineDoesNotTriggerEarly() {
        val clock = Clock()
        clock.health.markStarted()
        clock.advance(29_999)
        assertEquals(UuSessionHealth.Warning.NONE, clock.health.check())
        clock.advance(1)
        assertEquals(UuSessionHealth.Warning.SIGNAL_TIMEOUT, clock.health.check())
    }

    @Test fun controlAckClosesSignalDeadlineAndIceGetsIndependentWindow() {
        val clock = Clock()
        clock.health.markStarted()
        clock.advance(19_000)
        clock.health.markControlAccepted()
        clock.health.markPeerCreated()
        clock.advance(20_000)
        assertEquals(UuSessionHealth.Warning.NONE, clock.health.check())
        clock.advance(15_000)
        assertEquals(UuSessionHealth.Warning.ICE_TIMEOUT, clock.health.check())
    }

    @Test fun iceSuccessDisarmsPeerTimeoutEvenAfterLongSession() {
        val clock = Clock()
        clock.health.markStarted()
        clock.health.markControlAccepted()
        clock.health.markPeerCreated()
        clock.advance(3_000)
        clock.health.markIceConnected()
        clock.advance(60_000)
        assertEquals(UuSessionHealth.Warning.NONE, clock.health.check())
    }

    @Test fun missingFirstFrameIsWarnOnceNotTerminal() {
        val clock = Clock()
        clock.health.markStarted()
        clock.health.markControlAccepted()
        clock.health.markPeerCreated()
        clock.health.markIceConnected()
        clock.health.markCaptureRequested()
        clock.advance(24_999)
        assertEquals(UuSessionHealth.Warning.NONE, clock.health.check())
        clock.advance(1)
        assertEquals(UuSessionHealth.Warning.FIRST_FRAME_TIMEOUT, clock.health.check())
        assertEquals(UuSessionHealth.Warning.NONE, clock.health.check())
    }

    @Test fun frameArrivalSuppressesBlackScreenTimeout() {
        val clock = Clock()
        clock.health.markStarted()
        clock.health.markControlAccepted()
        clock.health.markPeerCreated()
        clock.health.markIceConnected()
        clock.health.markCaptureRequested()
        clock.advance(5_000)
        clock.health.markFirstFrame()
        clock.advance(60_000)
        assertEquals(UuSessionHealth.Warning.NONE, clock.health.check())
    }
}

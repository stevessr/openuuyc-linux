package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuCaptureGateTest {
    private fun frame(
        screen: Int? = null, track: Int? = null, echo: Boolean = false,
        response: Boolean = false, ack: Long? = null, error: Int? = null
    ) = UuWire.Envelope(
        seq = 0, timestamp = 0,
        echoRequest = echo, screenId = screen,
        videoTrackIndex = track, echoResponse = response,
        trackAckId = ack, trackAckError = error
    )

    private fun registered(): UuCaptureGate {
        val gate = UuCaptureGate()
        gate.observe(frame(echo = true), onControlChannel = true)
        gate.observe(frame(screen = 4, track = 2), onControlChannel = false)
        assertEquals(UuCaptureGate.Action.REGISTER_TRACK, gate.next(true, true))
        gate.onSubmitted(UuCaptureGate.Action.REGISTER_TRACK, 9)
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        assertEquals(UuCaptureGate.Ack.REGISTERED, gate.observe(frame(ack = 9, error = 0), false))
        return gate
    }

    @Test fun echoOnTextCannotAuthorizeRemoteControl() {
        val gate = UuCaptureGate()
        gate.observe(frame(echo = true), onControlChannel = false)
        gate.observe(frame(screen = 4, track = 2), onControlChannel = false)
        assertFalse(gate.pbConnected)
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        gate.observe(frame(response = true), onControlChannel = true)
        assertEquals(UuCaptureGate.Action.REGISTER_TRACK, gate.next(true, true))
    }

    @Test fun missingChannelOrDisplayBlocksTrackRegistration() {
        val gate = UuCaptureGate()
        gate.observe(frame(echo = true), true)
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        gate.observe(frame(screen = 4), false)
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        gate.observe(frame(screen = 4, track = 2), false)
        assertEquals(UuCaptureGate.Action.NONE, gate.next(false, true))
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, false))
        assertEquals(UuCaptureGate.Action.REGISTER_TRACK, gate.next(true, true))
    }

    @Test fun videoStartAndSettingsOnlyFollowMatchingSuccessfulAck() {
        val gate = registered()
        assertFalse(gate.inputReady(true, true))
        assertEquals(UuCaptureGate.Action.START_VIDEO, gate.next(true, true))
        gate.onSubmitted(UuCaptureGate.Action.START_VIDEO, 10)
        assertEquals(UuCaptureGate.Action.CAPTURE_SETTING, gate.next(true, true))
        assertFalse(gate.inputReady(true, true))
        gate.onSubmitted(UuCaptureGate.Action.CAPTURE_SETTING, 11)
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        assertTrue(gate.inputReady(true, true))
        assertFalse(gate.inputReady(false, true))
        assertFalse(gate.inputReady(true, false))
    }

    @Test fun staleAckAndDuplicateAckDoNotStartCapture() {
        val gate = UuCaptureGate()
        gate.observe(frame(echo = true), true)
        gate.observe(frame(screen = 4, track = 2), false)
        gate.onSubmitted(UuCaptureGate.Action.REGISTER_TRACK, 19)
        assertEquals(UuCaptureGate.Ack.NONE, gate.observe(frame(ack = 18, error = 0), false))
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        assertEquals(UuCaptureGate.Ack.REGISTERED, gate.observe(frame(ack = 19, error = 0), false))
        assertEquals(UuCaptureGate.Ack.NONE, gate.observe(frame(ack = 19, error = 0), false))
        assertEquals(UuCaptureGate.Action.START_VIDEO, gate.next(true, true))
    }

    @Test fun negativeTrackAckBlocksUnsafeRetryUntilNewTrack() {
        val gate = UuCaptureGate()
        gate.observe(frame(echo = true), true)
        gate.observe(frame(screen = 4, track = 2), false)
        gate.onSubmitted(UuCaptureGate.Action.REGISTER_TRACK, 21)
        assertEquals(UuCaptureGate.Ack.REJECTED, gate.observe(frame(ack = 21, error = 6), false))
        assertEquals(UuCaptureGate.Action.NONE, gate.next(true, true))
        assertFalse(gate.inputReady(true, true))
        gate.observe(frame(screen = 4, track = 3), false)
        assertEquals(UuCaptureGate.Action.REGISTER_TRACK, gate.next(true, true))
    }

    @Test fun screenSwitchDiscardsPendingAckAndRevokesInput() {
        val gate = registered()
        gate.onSubmitted(UuCaptureGate.Action.START_VIDEO, 10)
        gate.onSubmitted(UuCaptureGate.Action.CAPTURE_SETTING, 11)
        assertTrue(gate.inputReady(true, true))
        gate.observe(frame(screen = 8, track = 5), false)
        assertFalse(gate.inputReady(true, true))
        assertEquals(8, gate.selectedScreen)
        assertEquals(5, gate.selectedTrack)
        assertEquals(UuCaptureGate.Ack.NONE, gate.observe(frame(ack = 9, error = 0), false))
        assertEquals(UuCaptureGate.Action.REGISTER_TRACK, gate.next(true, true))
    }

    @Test fun channelClosureInvalidatesGenerationAndLateAck() {
        val gate = registered()
        gate.onSubmitted(UuCaptureGate.Action.START_VIDEO, 10)
        gate.onSubmitted(UuCaptureGate.Action.CAPTURE_SETTING, 11)
        gate.onTransportClosed()
        assertFalse(gate.inputReady(true, true))
        assertNull(gate.selectedScreen)
        assertFalse(gate.pbConnected)
        assertEquals(UuCaptureGate.Ack.NONE, gate.observe(frame(ack = 9, error = 0), false))
    }

    @Test fun illegalTransitionsAreRejected() {
        val gate = UuCaptureGate()
        assertThrows(IllegalStateException::class.java) {
            gate.onSubmitted(UuCaptureGate.Action.START_VIDEO, 1)
        }
        assertThrows(IllegalStateException::class.java) {
            gate.onSubmitted(UuCaptureGate.Action.NONE, 1)
        }
    }
}

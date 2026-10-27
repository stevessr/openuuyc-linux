package org.openuuyc.pico.uu

/**
 * Pure, deterministic UU capture handshake gate. This never contacts the network.
 *
 * Session generations and track requests are deliberately independent of video
 * frame rendering. Late responses and reordered ScreenSources must not enable
 * remote input on a different screen or track.
 */
internal class UuCaptureGate {
    enum class Action { NONE, REGISTER_TRACK, START_VIDEO, CAPTURE_SETTING }
    enum class Ack { NONE, REGISTERED, REJECTED }

    var pbConnected: Boolean = false
        private set
    var selectedScreen: Int? = null
        private set
    var selectedTrack: Int? = null
        private set
    private var pendingTrackSequence: Long? = null
    private var trackRegistered = false
    private var trackRejected = false
    private var startSent = false
    private var settingsSent = false

    /** Only CONTROL_DATA_CHANNEL may complete the PB ECHO handshake. */
    fun observe(envelope: UuWire.Envelope, onControlChannel: Boolean): Ack {
        if (onControlChannel && (envelope.echoRequest || envelope.echoResponse)) pbConnected = true
        val screen = envelope.screenId
        val track = envelope.videoTrackIndex
        if (screen != null && screen >= 0) {
            // A screen change invalidates the old track and every in-flight ACK.
            if (selectedScreen != screen) {
                selectedScreen = screen
                selectedTrack = null
                clearCapture()
            }
            if (track != null && track >= 0 && selectedTrack != track) {
                selectedTrack = track
                clearCapture()
            }
        }
        if (pendingTrackSequence == null || envelope.trackAckId != pendingTrackSequence) {
            return Ack.NONE
        }
        pendingTrackSequence = null
        if (envelope.trackAckError == 0) {
            trackRegistered = true
            return Ack.REGISTERED
        }
        trackRejected = true
        return Ack.REJECTED
    }

    fun next(controlOpen: Boolean, textOpen: Boolean): Action {
        if (!pbConnected || !controlOpen || !textOpen ||
            selectedScreen == null || selectedTrack == null) return Action.NONE
        if (trackRejected) return Action.NONE
        if (!trackRegistered) return if (pendingTrackSequence == null) Action.REGISTER_TRACK else Action.NONE
        if (!startSent) return Action.START_VIDEO
        if (!settingsSent) return Action.CAPTURE_SETTING
        return Action.NONE
    }

    fun onSubmitted(action: Action, sequence: Long) {
        check(sequence > 0)
        when (action) {
            Action.NONE -> error("cannot submit an empty UU action")
            Action.REGISTER_TRACK -> {
                check(pbConnected && selectedTrack != null && !trackRegistered && !trackRejected)
                check(pendingTrackSequence == null)
                pendingTrackSequence = sequence
            }
            Action.START_VIDEO -> {
                check(trackRegistered)
                startSent = true
            }
            Action.CAPTURE_SETTING -> {
                check(startSent && trackRegistered)
                settingsSent = true
            }
        }
    }

    fun inputReady(controlOpen: Boolean, textOpen: Boolean): Boolean =
        !trackRejected && pbConnected && trackRegistered && startSent && settingsSent &&
            selectedScreen != null && selectedTrack != null && controlOpen && textOpen

    fun onTransportClosed() {
        pbConnected = false
        selectedScreen = null
        selectedTrack = null
        clearCapture()
    }

    private fun clearCapture() {
        pendingTrackSequence = null
        trackRegistered = false
        trackRejected = false
        startSent = false
        settingsSent = false
    }
}

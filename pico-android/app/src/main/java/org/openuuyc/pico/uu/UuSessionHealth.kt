package org.openuuyc.pico.uu

/**
 * Monotonic media connection deadlines, separated from Android/WebRTC callbacks
 * so timeout decisions can be tested without a PICO headset.
 *
 * A missing first frame warns but does not unilaterally close a valid UU
 * session: remote hosts may temporarily pause capture or be locked.
 */
internal class UuSessionHealth(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    enum class Warning { NONE, SIGNAL_TIMEOUT, ICE_TIMEOUT, TRACK_ACK_TIMEOUT, FIRST_FRAME_TIMEOUT }
    private var started: Long? = null
    private var peerStarted: Long? = null
    private var captureStarted: Long? = null
    private var iceDeadlineStart: Long? = null
    private var iceHasConnectedBefore = false
    private var trackRegistrationStarted: Long? = null
    private var signalReady = false
    private var iceReady = false
    private var firstFrame = false
    private var frameWarningSent = false

    @Synchronized fun markStarted() {
        started = nowMs()
    }

    @Synchronized fun markControlAccepted() {
        signalReady = true
    }

    @Synchronized fun markPeerCreated() {
        peerStarted = nowMs()
        iceDeadlineStart = nowMs()
    }

    @Synchronized fun markIceConnected() {
        iceReady = true
        iceHasConnectedBefore = true
        iceDeadlineStart = null
    }

    @Synchronized fun markIceDisconnected() {
        if (peerStarted == null || !iceReady) return
        iceReady = false
        iceDeadlineStart = nowMs()
    }

    @Synchronized fun markTrackRegistrationRequested() {
        trackRegistrationStarted = nowMs()
    }

    @Synchronized fun markTrackRegistrationFinished() {
        trackRegistrationStarted = null
    }

    @Synchronized fun markCaptureRequested() {
        if (captureStarted == null) captureStarted = nowMs()
    }

    @Synchronized fun markFirstFrame() {
        firstFrame = true
    }

    @Synchronized fun check(): Warning {
        val now = nowMs()
        if (!signalReady && started != null && now - started!! >= 30_000L)
            return Warning.SIGNAL_TIMEOUT
        val iceTimeoutMs = if (iceHasConnectedBefore) 15_000L else 35_000L
        if (!iceReady && iceDeadlineStart != null &&
            now - iceDeadlineStart!! >= iceTimeoutMs)
            return Warning.ICE_TIMEOUT
        if (trackRegistrationStarted != null &&
            now - trackRegistrationStarted!! >= 5_000L)
            return Warning.TRACK_ACK_TIMEOUT
        if (!firstFrame && !frameWarningSent &&
            captureStarted != null && now - captureStarted!! >= 25_000L) {
            frameWarningSent = true
            return Warning.FIRST_FRAME_TIMEOUT
        }
        return Warning.NONE
    }
}

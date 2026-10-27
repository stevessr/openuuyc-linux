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
    enum class Warning { NONE, SIGNAL_TIMEOUT, ICE_TIMEOUT, FIRST_FRAME_TIMEOUT }
    private var started: Long? = null
    private var peerStarted: Long? = null
    private var captureStarted: Long? = null
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
    }

    @Synchronized fun markIceConnected() {
        iceReady = true
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
        if (!iceReady && peerStarted != null && now - peerStarted!! >= 35_000L)
            return Warning.ICE_TIMEOUT
        if (!firstFrame && !frameWarningSent &&
            captureStarted != null && now - captureStarted!! >= 25_000L) {
            frameWarningSent = true
            return Warning.FIRST_FRAME_TIMEOUT
        }
        return Warning.NONE
    }
}

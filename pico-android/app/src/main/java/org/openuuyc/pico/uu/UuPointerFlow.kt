package org.openuuyc.pico.uu

/**
 * UU mouse delivery ordering. Releasing a held button takes priority over
 * motion, so SCTP backpressure cannot strand a remote drag.
 *
 * The session must terminate on RELEASE_FAILED: no safe retry guarantee
 * exists after an interrupted reliable control channel.
 */
internal class UuPointerFlow {
    enum class Result { SENT, IGNORED, RELEASE_FAILED }

    var pressed: Boolean = false
        private set

    fun pointer(
        screen: Int?,
        u: Float,
        v: Float,
        down: Boolean,
        inputReady: Boolean,
        send: (ByteArray) -> Boolean
    ): Result {
        // A release is always eligible while CONTROL is open, even if the
        // target monitor or TEXT channel disappeared between pointer events.
        if (pressed && !down && !release(send)) return Result.RELEASE_FAILED
        if (!inputReady || screen == null || !UuPointerWire.valid(u, v))
            return Result.IGNORED
        if (!send(UuPointerWire.move(screen, u, v))) return Result.IGNORED
        if (down && !pressed) {
            if (!send(UuPointerWire.button(true))) return Result.IGNORED
            pressed = true
        }
        return Result.SENT
    }

    fun release(send: (ByteArray) -> Boolean): Boolean {
        if (!pressed) return true
        if (!send(UuPointerWire.button(false))) return false
        pressed = false
        return true
    }

    fun reset() {
        pressed = false
    }
}

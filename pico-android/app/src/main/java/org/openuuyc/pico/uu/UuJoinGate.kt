package org.openuuyc.pico.uu

/**
 * One join attempt owns one generation. Cancelling a pending NRD HTTP request
 * must not launch a late OpenXR session after the user pressed Disconnect.
 */
internal class UuJoinGate {
    private var generation = 0L
    private var joining = false
    private var active = false
    private var destroyed = false

    @Synchronized fun begin(): Long? {
        if (joining || active || destroyed) return null
        generation++
        joining = true
        return generation
    }

    @Synchronized fun adopt(ticket: Long): Boolean {
        if (!joining || active || destroyed || ticket != generation) return false
        joining = false
        active = true
        return true
    }

    @Synchronized fun failed(ticket: Long) {
        if (ticket == generation) joining = false
    }

    @Synchronized fun stop() {
        generation++
        joining = false
        active = false
    }

    @Synchronized fun destroy() {
        stop()
        destroyed = true
    }
}

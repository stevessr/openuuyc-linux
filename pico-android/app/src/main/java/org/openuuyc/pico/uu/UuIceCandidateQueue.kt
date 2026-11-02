package org.openuuyc.pico.uu

/**
 * ICE trickle gate: local candidates must not overtake the SDP offer, and
 * remote candidates must not overtake setRemoteDescription success.
 *
 * The gate holds at most [limit] distinct pending entries. A send failure
 * leaves the candidate queued for explicit retry or connection teardown.
 */
internal class UuIceCandidateQueue<T>(private val limit: Int = 128) {
    private val pending = LinkedHashMap<String, T>()
    private var ready = false
    private var closed = false

    init { require(limit in 1..1024) }

    @Synchronized fun submit(key: String, candidate: T, sender: (T) -> Boolean): Boolean {
        if (closed || key.isBlank() || key.length > 8192) return false
        if (ready) return sender(candidate)
        if (pending.containsKey(key)) return true
        if (pending.size >= limit) return false
        pending[key] = candidate
        return true
    }

    @Synchronized fun activate(sender: (T) -> Boolean): Boolean {
        if (closed) return false
        for ((key, value) in pending.toList()) {
            if (!sender(value)) return false
            pending.remove(key)
        }
        ready = true
        return true
    }

    @Synchronized fun resetForRenegotiation() {
        if (closed) return
        ready = false
        pending.clear()
    }

    @Synchronized fun close() {
        closed = true
        ready = false
        pending.clear()
    }

    @Synchronized fun size(): Int = pending.size
    @Synchronized fun isReady(): Boolean = ready && !closed
}

package org.openuuyc.pico.uu

/**
 * UU's Socket.IO room handshake owns one generation. Duplicate or late ACK
 * frames must never allocate a second WebRTC PeerConnection.
 */
internal class UuSignalGate {
    enum class Phase { WAIT_NAMESPACE, WAIT_ROOM_ACK, WAIT_CONTROL_ACK, ESTABLISHED, CLOSED }
    var phase = Phase.WAIT_NAMESPACE
        private set

    fun namespaceConnected(): Boolean {
        if (phase != Phase.WAIT_NAMESPACE) return false
        phase = Phase.WAIT_ROOM_ACK
        return true
    }

    fun roomAcknowledged(): Boolean {
        if (phase != Phase.WAIT_ROOM_ACK) return false
        phase = Phase.WAIT_CONTROL_ACK
        return true
    }

    fun controlAcknowledged(): Boolean {
        if (phase != Phase.WAIT_CONTROL_ACK) return false
        phase = Phase.ESTABLISHED
        return true
    }

    fun mayProcessMedia(): Boolean = phase == Phase.ESTABLISHED

    fun close() { phase = Phase.CLOSED }
}

package org.openuuyc.pico.uu

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException

/**
 * Strictly recognizes the Engine.IO v4 / Socket.IO default-namespace frames
 * used by the UU signaling server. This decoder is deliberately network-free,
 * so malformed frames and ACK ordering can be regression tested on JVM.
 */
internal object UuSocketIoWire {
    sealed class Packet {
        data class Open(val sid: String, val pingIntervalMs: Long, val pingTimeoutMs: Long) : Packet()
        data class Ping(val payload: String) : Packet()
        data object NamespaceConnected : Packet()
        data class RoomAck(val data: JSONObject) : Packet()
        data class ControlAck(val data: String) : Packet()
        data class Event(val name: String, val payload: JSONObject?, val acknowledge: String?) : Packet()
        data class BinaryStart(val name: String, val payload: JSONObject?, val acknowledge: String?) : Packet()
        data object Disconnected : Packet()
        data class Rejected(val code: String) : Packet()
        data object Ignore : Packet()
    }

    fun closeFrames(namespaceConnected: Boolean): List<String> =
        if(namespaceConnected) listOf("42[\"leave\"]", "41") else emptyList()

    private const val MAX_SIGNAL_TEXT = 1024 * 1024

    fun parse(frame: String): Packet = try {
        parseChecked(frame)
    } catch (error: JSONException) {
        throw IllegalArgumentException("invalid UU signaling JSON", error)
    }

    private fun parseChecked(frame: String): Packet {
        require(frame.length <= MAX_SIGNAL_TEXT) { "signaling frame too long" }
        if (frame.isEmpty()) throw IllegalArgumentException("empty UU signaling frame")
        return when {
            frame.startsWith("0") -> {
                val data = JSONObject(frame.substring(1))
                val sid = data.optString("sid")
                require(sid.isNotBlank() && sid.length <= 128) { "invalid Engine.IO sid" }
                val interval = data.optLong("pingInterval", 25_000L)
                val timeout = data.optLong("pingTimeout", 60_000L)
                require(interval in 1..300_000 && timeout in 1..300_000)
                Packet.Open(sid, interval, timeout)
            }
            frame.startsWith("2") -> Packet.Ping(frame.drop(1))
            frame == "40" || frame.startsWith("40{") -> Packet.NamespaceConnected
            frame == "41" || frame == "1" -> Packet.Disconnected
            frame.startsWith("44") -> Packet.Rejected("namespace connection rejected")
            frame.startsWith("431") && frame.drop(3).startsWith("[") -> {
                val array = JSONArray(frame.drop(3))
                val info = array.optJSONObject(0)
                require(info != null && info.length() > 0) { "empty room_info ACK" }
                Packet.RoomAck(info)
            }
            frame.startsWith("432") && frame.drop(3).startsWith("[") -> {
                // Validate the complete control ACK with UuWire.parseControlAck.
                Packet.ControlAck(frame)
            }
            frame.startsWith("45") -> {
                val delimiter = frame.indexOf('-')
                require(delimiter in 3..16 && frame.substring(2, delimiter) == "1") {
                    "unsupported Socket.IO attachment count"
                }
                val rest = frame.substring(delimiter + 1)
                val (ack, items) = decodeEvent(rest)
                val payload = items.optJSONObject(1)
                    ?: throw IllegalArgumentException("UU binary event missing metadata")
                Packet.BinaryStart(items.getString(0), payload, ack)
            }
            frame.startsWith("42") -> {
                val (ack, items) = decodeEvent(frame.drop(2))
                Packet.Event(items.getString(0), items.optJSONObject(1), ack)
            }
            else -> Packet.Ignore
        }
    }

    private fun decodeEvent(frame: String): Pair<String?, JSONArray> {
        val firstArray = frame.indexOf('[')
        require(firstArray in 0..24) { "event payload missing JSON array" }
        val ack = frame.substring(0, firstArray)
        require(ack.isEmpty() || (ack.length <= 20 && ack.all(Char::isDigit))) {
            "invalid Socket.IO ACK id"
        }
        val items = JSONArray(frame.substring(firstArray))
        require(items.length() >= 1)
        require(items.opt(0) is String && items.optString(0).isNotBlank())
        return ack.takeIf(String::isNotEmpty) to items
    }

    fun eventAck(id: String): String {
        require(id.isNotEmpty() && id.length <= 20 && id.all(Char::isDigit))
        return "43${id}[]"
    }

    /** Binary Socket.IO attachments start with an Engine.IO message opcode. */
    fun binaryPayload(frame: ByteArray): ByteArray {
        require(frame.size in 2..MAX_SIGNAL_TEXT && frame[0] == 4.toByte()) {
            "invalid UU Socket.IO binary attachment"
        }
        return frame.copyOfRange(1, frame.size)
    }
}

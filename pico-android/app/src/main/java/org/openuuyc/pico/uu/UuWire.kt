package org.openuuyc.pico.uu

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Wire-compatible subset of UU GameViewer's controller protocol (no relay). */
internal object UuWire {
    private const val MAX_SDP = 1024 * 1024

    private fun varint(out: ByteArrayOutputStream, number: Long) {
        var value = number
        while ((value and -128L) != 0L) {
            out.write(((value and 127L) or 128L).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
    }
    private fun integer(out: ByteArrayOutputStream, tag: Int, value: Long) {
        varint(out, tag.toLong() shl 3)
        varint(out, value)
    }
    private fun bytes(out: ByteArrayOutputStream, tag: Int, data: ByteArray) {
        varint(out, (tag.toLong() shl 3) or 2L)
        varint(out, data.size.toLong())
        out.write(data)
    }
    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    private fun message(block: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(block).toByteArray()

    /**
     * Read-only feature flags from src/features/stream_control/wire.rs.
     * Protobuf zero-valued fields are omitted by prost.
     */
    fun featureFlags() = message { out ->
        integer(out, 1, 6) // capture_setting
        integer(out, 6, 2) // file_transfer_ftp
        integer(out, 7, 2) // file_transfer_ftp2
        integer(out, 8, 3) // clipboard
        integer(out, 9, 1) // qos_stat
    }

    fun connectOptions(controllerId: String): ByteArray {
        require(controllerId.matches(Regex("[a-z0-9]{16}")))
        val capture = message { out ->
            integer(out, 1, 2) // 60fps tier
            integer(out, 2, 5) // balanced quality
            integer(out, 4, 3) // follow remote resolution
            val resolution = message { r ->
                integer(r, 1, 1920)
                integer(r, 2, 1080)
            }
            bytes(out, 5, resolution)
            integer(out, 7, 1) // 8bit 4:2:0 SDR
            integer(out, 10, 2) // auto-quality
            integer(out, 11, 60)
        }
        return message { out ->
            integer(out, 1, 1) // viewing
            integer(out, 2, -1) // auto screen
            bytes(out, 3, capture)
            bytes(out, 4, message { decoder ->
                integer(decoder, 1, 60)
                integer(decoder, 2, 1) // H264
                integer(decoder, 3, 1920)
                integer(decoder, 4, 1080)
                integer(decoder, 5, 1) // 4:2:0
            })
            integer(out, 8, 3) // desktop controller ABI used by source
            bytes(out, 9, controllerId.utf8())
            integer(out, 10, 1) // normal connection
            bytes(out, 11, featureFlags())
            bytes(out, 12, UuContract.VERSION.utf8())
        }
    }

    fun controlEvent(controllerId: String, appControlId: String = UUID.randomUUID().toString()):
        Pair<String, ByteArray> {
        val capability = JSONObject()
            .put("ice_id", "")
            .put("display_info", JSONArray())
            .put("video_codec_capability", JSONArray().put(
                JSONObject()
                    .put("video_codec", 1)
                    .put("width", 1920)
                    .put("height", 1080)
                    .put("chroma_sampling", 1)
                    .put("bit_depth", 8)
                    .put("codec_impl", 37)
            ))
        val streamer = JSONObject()
            .put("control_id", appControlId)
            .put("openuuyc_audio_only", false)
            .put("openuuyc_audio_control", 1)
            .put("openuuyc_annotation", 0)
            .put("openuuyc_display_geometry", 0)
            .put("openuuyc_diagnostics", 0)
            .put("openuuyc_drag_drop", 0)
            .put("device_capability", capability)
        val payload = JSONObject()
            .put("app_control_id", appControlId)
            .put("app_data", JSONObject().put("_placeholder", true).put("num", 0))
            .put("streamer_data", streamer.toString())
        return "451-2" + JSONArray().put("control").put(payload).toString() to connectOptions(controllerId)
    }

    /** Socket.IO binary attachment MUST start with an Engine.IO message byte. */
    fun attachment(bytes: ByteArray): ByteArray = byteArrayOf(4) + bytes

    fun echoRequest(): ByteArray {
        // PbControlMessage.payload = SimpleAction(field3)
        val action = message { simple ->
            integer(simple, 1, 0) // ACTION_TYPE_ECHO_REQUEST
            bytes(simple, 4, featureFlags())
        }
        return message { out -> bytes(out, 3, action) }
    }

    fun echoResponse(sequence: Long, timestamp: Long): ByteArray {
        val action = message { simple ->
            integer(simple, 1, 1)
            bytes(simple, 2, """{ "seq" : $sequence }""".utf8())
            bytes(simple, 4, featureFlags())
        }
        return message { out ->
            integer(out, 1, sequence)
            integer(out, 2, timestamp)
            bytes(out, 3, action)
        }
    }

    data class Envelope(val seq: Long, val timestamp: Long, val echoRequest: Boolean, val screenId: Int?)
    private class Reader(private val data: ByteArray) {
        var offset = 0
        fun long(): Long {
            var bits = 0
            var result = 0L
            while (bits < 70) {
                if (offset >= data.size) throw IllegalArgumentException("truncated protobuf varint")
                val b = data[offset++].toInt() and 255
                if (bits == 63 && (b and 0x7e) != 0) throw IllegalArgumentException("varint overflow")
                result = result or ((b.toLong() and 127L) shl bits)
                if (b and 128 == 0) return result
                bits += 7
            }
            throw IllegalArgumentException("varint too long")
        }
        fun len(): ByteArray {
            val size = long()
            if (size < 0 || size > data.size - offset) throw IllegalArgumentException("invalid protobuf length")
            val out = data.copyOfRange(offset, offset + size.toInt())
            offset += size.toInt()
            return out
        }
        fun skip(wire: Int): ByteArray? = when(wire) {
            0 -> { long(); null }
            1 -> { if (data.size - offset < 8) throw IllegalArgumentException("truncated fixed64"); offset += 8; null }
            2 -> len()
            5 -> { if (data.size - offset < 4) throw IllegalArgumentException("truncated fixed32"); offset += 4; null }
            else -> throw IllegalArgumentException("unsupported protobuf wire type")
        }
        fun done(): Boolean = offset == data.size
    }

    fun decodeEnvelope(bytes: ByteArray): Envelope {
        require(bytes.size <= 64 * 1024) { "UU control message too large" }
        val top = Reader(bytes)
        var sequence = 0L
        var timestamp = 0L
        var echo = false
        var screen: Int? = null
        while(!top.done()) {
            val tag = top.long().toInt()
            require(tag > 0)
            when(tag ushr 3) {
                1 -> sequence = top.long()
                2 -> timestamp = top.long()
                3 -> {
                    val sub = Reader(top.len())
                    while(!sub.done()) {
                        val field = sub.long().toInt()
                        when(field ushr 3) {
                            1 -> echo = sub.long() == 0L
                            else -> sub.skip(field and 7)
                        }
                    }
                }
                7 -> {
                    val sources = Reader(top.len())
                    while(!sources.done()) {
                        val field = sources.long().toInt()
                        if (field ushr 3 == 2) screen = sources.long().toInt()
                        else if (field ushr 3 == 1) {
                            val entry = Reader(sources.len())
                            while (!entry.done()) {
                                val screenField = entry.long().toInt()
                                if (screenField ushr 3 == 1 && screen == null) screen = entry.long().toInt()
                                else entry.skip(screenField and 7)
                            }
                        } else sources.skip(field and 7)
                    }
                }
                else -> top.skip(tag and 7)
            }
        }
        return Envelope(sequence, timestamp, echo, screen)
    }

    fun signalEndpoint(endpoint: String): String {
        val normalized = if (endpoint.contains("://")) endpoint else "wss://$endpoint"
        val url = okhttp3.HttpUrl.Companion.toHttpUrlOrNull(
            normalized.replaceFirst("wss://", "https://")
        ) ?: throw IllegalArgumentException("invalid UU signal endpoint")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
        return url.newBuilder()
            .encodedPath(if (url.encodedPath == "/") "/socket.io/" else url.encodedPath)
            .query(null)
            .addQueryParameter("EIO", "4")
            .addQueryParameter("transport", "websocket")
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build().toString().replaceFirst("https://", "wss://")
    }

    fun event(name: String, payload: JSONObject) =
        "42" + JSONArray().put(name).put(payload).toString()

    fun offerHeader(info: ControlReply): String {
        val envelope = JSONObject()
            .put("client_id", info.clientId)
            .put("data", JSONObject()
                .put("app_control_id", info.appControlId)
                .put("gzip_sdp", JSONObject().put("_placeholder", true).put("num", 0))
                .put("ice_id", info.iceId)
                .put("ice_network_type", 3)
                .put("sdp", "")
                .put("type", "offer"))
        return "451-" + JSONArray().put("soac").put(envelope).toString()
    }

    fun candidate(info: ControlReply, candidate: String, mid: String, index: Int): String =
        event("soac", JSONObject().put("client_id", info.clientId)
            .put("data", JSONObject()
                .put("app_control_id", info.appControlId)
                .put("ice_id", info.iceId)
                .put("ice_network_type", 0)
                .put("type", "candidate")
                .put("candidate", JSONObject()
                    .put("candidate", candidate)
                    .put("sdpMid", mid)
                    .put("sdpMLineIndex", index))))

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun ungzip(bytes: ByteArray): String {
        val src = if (bytes.isNotEmpty() && bytes[0] == 4.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        GZIPInputStream(src.inputStream()).use { zipped ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(true) {
                val count = zipped.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_SDP) { "SDP over safety limit" }
                out.write(buffer, 0, count)
            }
            return out.toString("UTF-8")
        }
    }
}

internal data class ControlReply(
    val clientId: String, val iceId: String, val appControlId: String,
    val iceServers: List<ServerIce>
)

internal data class ServerIce(val urls: List<String>, val username: String, val credential: String)

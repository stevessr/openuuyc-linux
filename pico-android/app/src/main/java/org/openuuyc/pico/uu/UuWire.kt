package org.openuuyc.pico.uu

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    internal fun integer(out: ByteArrayOutputStream, tag: Int, value: Long) {
        varint(out, tag.toLong() shl 3)
        varint(out, value)
    }
    internal fun bytes(out: ByteArrayOutputStream, tag: Int, data: ByteArray) {
        varint(out, (tag.toLong() shl 3) or 2L)
        varint(out, data.size.toLong())
        out.write(data)
    }
    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)
    internal fun message(block: (ByteArrayOutputStream) -> Unit): ByteArray =
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

    fun connectOptions(controllerId: String, profiles: List<UuCodecProfile> = listOf(UuCodecProfile(1, 1920, 1080))): ByteArray {
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
            profiles.forEach { profile -> bytes(out, 4, message { decoder ->
                integer(decoder, 1, profile.fps.toLong())
                integer(decoder, 2, profile.codec.toLong())
                integer(decoder, 3, profile.width.toLong())
                integer(decoder, 4, profile.height.toLong())
                integer(decoder, 5, 1)
            }) }
            integer(out, 8, 3) // desktop controller ABI used by source
            bytes(out, 9, controllerId.utf8())
            integer(out, 10, 1) // normal connection
            bytes(out, 11, featureFlags())
            bytes(out, 12, UuContract.VERSION.utf8())
        }
    }

    fun controlEvent(controllerId: String, appControlId: String = UUID.randomUUID().toString(),
                     profiles: List<UuCodecProfile> = listOf(UuCodecProfile(1, 1920, 1080))):
        Pair<String, ByteArray> {
        val capability = JSONObject()
            .put("ice_id", "")
            .put("display_info", JSONArray())
            .put("video_codec_capability", JSONArray().apply {
                profiles.forEach { profile -> put(JSONObject()
                    .put("video_codec", profile.codec).put("width", profile.width).put("height", profile.height)
                    .put("chroma_sampling", 1).put("bit_depth", 8).put("codec_impl", 37)) }
            })
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
        return "451-2" + JSONArray().put("control").put(payload).toString() to connectOptions(controllerId, profiles)
    }

    /** Socket.IO binary attachment MUST start with an Engine.IO message byte. */
    fun attachment(bytes: ByteArray): ByteArray = byteArrayOf(4) + bytes

    /**
     * Runtime video CaptureSetting RPC, src/features/stream_control/settings.rs.
     * The request targets existing negotiated tracks (-2) instead of resizing
     * the controlled computer's physical display. Must use TEXT_DATA_CHANNEL
     * with a text PPID, not the CONTROL_DATA_CHANNEL.
     */
    fun captureSetting(sequence: Long = 1L, settings: UuCaptureSettings = UuCaptureSettings(), screen: Int = -2,
                       physical: UuDisplayMode? = null, dpi: Int = 0): ByteArray {
        settings.validate()
        require(screen >= 0 || screen == -2)
        require(dpi == 0 || dpi in 50..500)
        val capture = message { out ->
            val tier = when(settings.fps) { 30 -> 1; 60 -> 2; 90 -> 3; else -> 4 }
            integer(out, 1, tier.toLong())
            integer(out, 2, settings.quality.toLong())
            integer(out, 3, if (settings.cursor) 1 else 0)
            integer(out, 4, screen.toLong())
            integer(out, 5, physical?.width?.toLong() ?: -1)
            integer(out, 6, physical?.height?.toLong() ?: -1)
            integer(out, 7, 1) // Only formats supported by Android's I420/texture renderer.
            integer(out, 8, if (settings.quality == 6) settings.bitrateMbps.toLong() else 0)
            integer(out, 9, dpi.toLong())
            integer(out, 10, 1)
            integer(out, 12, 2)
            integer(out, 13, settings.codec.toLong())
            integer(out, 14, settings.maxWidth.toLong())
            integer(out, 15, settings.maxHeight.toLong())
            physical?.let { integer(out, 16, it.width.toLong()); integer(out, 17, it.height.toLong()) }
            integer(out, 18, settings.fps.toLong())
        }
        return rpc(sequence, 2, capture)
    }

    fun createVirtualDisplay(sequence: Long, width: Int, height: Int): ByteArray {
        require(width in 640..7680 && height in 480..4320)
        return rpc(sequence, 12, message { out ->
            bytes(out, 1, message { integer(it, 1, width.toLong()); integer(it, 2, height.toLong()) })
            integer(out, 2, 1)
        })
    }

    fun microphonePolicy(sequence: Long,enabled: Boolean)=rpc(sequence,21,message {integer(it,1,if(enabled) 1 else 0)})

    internal fun rpc(sequence: Long, field: Int, payload: ByteArray): ByteArray = message { out ->
        integer(out, 1, sequence); integer(out, 2, System.currentTimeMillis())
        bytes(out, 21, message { request ->
            bytes(request, 1, message { integer(it, 1, sequence) })
            bytes(request, field, payload)
        })
    }

    internal data class Field(val number: Long? = null, val data: ByteArray? = null)
    internal fun fields(data: ByteArray): Map<Int, List<Field>> {
        require(data.size <= 1024 * 1024)
        val reader = Reader(data)
        val result = linkedMapOf<Int, MutableList<Field>>()
        while (!reader.done()) {
            val (tag, wire) = reader.tag()
            val value = when(wire) { 0 -> Field(number = reader.long()); 2 -> Field(data = reader.len()); else -> { reader.skip(wire); Field() } }
            result.getOrPut(tag) { mutableListOf() }.add(value)
        }
        return result
    }
    internal fun Map<Int, List<Field>>.number(field: Int, default: Long = 0) = get(field)?.lastOrNull()?.number ?: default
    internal fun Map<Int, List<Field>>.data(field: Int) = get(field)?.lastOrNull()?.data


    /** Register *all* accepted m=video tracks, matching desktop controller. */
    fun registerVideoTrack(sequence: Long, index: Int): ByteArray =
        registerVideoTracks(sequence, listOf(index))

    fun registerVideoTracks(sequence: Long, indexes: List<Int>): ByteArray {
        require(indexes.isNotEmpty() && indexes.size <= 5 &&
            indexes.distinct().size == indexes.size &&
            indexes.all { it in 0..4 }) { "invalid or duplicate UU video-track index" }
        val tracks = message { out -> indexes.forEach { integer(out, 1, it.toLong()) } }
        val rpc = message { out ->
            bytes(out, 1, message { header -> integer(header, 1, sequence) })
            bytes(out, 15, tracks)
        }
        return message { out ->
            integer(out, 1, sequence)
            integer(out, 2, System.currentTimeMillis())
            bytes(out, 21, rpc)
        }
    }

    /** Request capture through UU SimpleAction 8, same as the desktop controller. */
    fun startVideoCapture(sequence: Long, screenId: Int): ByteArray {
        require(screenId >= 0) { "missing remote screen" }
        val action = message { out ->
            integer(out, 1, 8)
            bytes(out, 2, JSONObject().put("screen_id", screenId).toString().utf8())
        }
        return message { out ->
            integer(out, 1, sequence)
            bytes(out, 3, action)
        }
    }

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

    data class Envelope(
        val seq: Long,
        val timestamp: Long,
        val echoRequest: Boolean,
        val screenId: Int?,
        val videoTrackIndex: Int? = null,
        val echoResponse: Boolean = false,
        val trackAckId: Long? = null,
        val trackAckError: Int? = null,
        val screenTracks: Map<Int, Int> = emptyMap(),
        val screens: List<UuRemoteScreen> = emptyList(),
        val captureLevel: Int? = null,
        val cursorType: Int? = null,
        val cursorScreen: Int? = null,
        val topologyAck: Pair<Long, Int>? = null,
        val captureAck: Long? = null,
        val captureErrors: List<Int> = emptyList(),
        val microphoneAck: Pair<Long,Int>? = null,
        val simpleAction: Int? = null
    )
    private class Reader(private val data: ByteArray) {
        var offset = 0
        fun long(): Long {
            var result = 0L
            for (index in 0..9) {
                if (offset >= data.size) throw IllegalArgumentException("truncated protobuf varint")
                val b = data[offset++].toInt() and 255
                if (index == 9 && b > 1) throw IllegalArgumentException("protobuf varint overflow")
                result = result or ((b.toLong() and 127L) shl (index * 7))
                if (b and 128 == 0) return result
            }
            throw IllegalArgumentException("protobuf varint exceeds 10 bytes")
        }
        fun tag(): Pair<Int, Int> {
            val raw = long()
            val field = raw ushr 3
            val wire = (raw and 7).toInt()
            require(field in 1..536870911 && wire in listOf(0, 1, 2, 5)) {
                "invalid protobuf tag/wire type"
            }
            return field.toInt() to wire
        }
        fun requireWire(actual: Int, expected: Int) {
            require(actual == expected) { "wrong protobuf wire type" }
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
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024) { "UU control message empty or too large" }
        val top = Reader(bytes)
        var sequence = 0L
        var timestamp = 0L
        var echo = false
        var echoAck = false
        var screen: Int? = null
        val tracksByScreen = mutableMapOf<Int, Int>()
        var registeredRequest: Long? = null
        var registrationError: Int? = null
        while(!top.done()) {
            val (field, wire) = top.tag()
            when(field) {
                1 -> { top.requireWire(wire, 0); sequence = top.long() }
                2 -> { top.requireWire(wire, 0); timestamp = top.long() }
                3 -> {
                    top.requireWire(wire, 2)
                    val sub = Reader(top.len())
                    var action = 0L // proto3 default; ECHO_REQUEST is omitted on the wire.
                    while(!sub.done()) {
                        val (subField, subWire) = sub.tag()
                        when(subField) {
                            1 -> {
                                sub.requireWire(subWire, 0)
                                action = sub.long()
                            }
                            else -> sub.skip(subWire)
                        }
                    }
                    echo = action == 0L
                    echoAck = action == 1L
                }
                7 -> {
                    top.requireWire(wire, 2)
                    val sources = Reader(top.len())
                    while(!sources.done()) {
                        val (screenField, screenWire) = sources.tag()
                        if (screenField == 2) {
                            sources.requireWire(screenWire, 0)
                            screen = sources.long().toInt()
                        } else if (screenField == 1) {
                            sources.requireWire(screenWire, 2)
                            val entry = Reader(sources.len())
                            var entryId = 0 // PbScreen.id defaults to the primary screen.
                            var entryTrack = 0 // PbScreen.video_track_index defaults to video_0.
                            while (!entry.done()) {
                                val (entryField, entryWire) = entry.tag()
                                when(entryField) {
                                    1 -> { entry.requireWire(entryWire, 0); entryId = entry.long().toInt() }
                                    12 -> { entry.requireWire(entryWire, 0); entryTrack = entry.long().toInt() }
                                    else -> entry.skip(entryWire)
                                }
                            }
                            if (entryId >= 0 && entryTrack >= 0) {
                                tracksByScreen[entryId] = entryTrack
                                if (screen == null) screen = entryId
                            }
                        } else sources.skip(screenWire)
                    }
                }
                22 -> {
                    top.requireWire(wire, 2)
                    val rpc = Reader(top.len())
                    var requestId: Long? = null
                    var error: Int? = null
                    var hasTrackReply = false
                    while (!rpc.done()) {
                        val (rpcField, rpcWire) = rpc.tag()
                        when(rpcField) {
                            1 -> {
                                rpc.requireWire(rpcWire, 2)
                                val header = Reader(rpc.len())
                                while (!header.done()) {
                                    val (headerField, headerWire) = header.tag()
                                    if (headerField == 1) {
                                        header.requireWire(headerWire, 0)
                                        requestId = header.long()
                                    } else header.skip(headerWire)
                                }
                            }
                            11 -> {
                                rpc.requireWire(rpcWire, 2)
                                hasTrackReply = true
                                val reply = Reader(rpc.len())
                                error = 0
                                while (!reply.done()) {
                                    val (replyField, replyWire) = reply.tag()
                                    if (replyField == 1) {
                                        reply.requireWire(replyWire, 0)
                                        error = reply.long().toInt()
                                    } else reply.skip(replyWire)
                                }
                            }
                            else -> rpc.skip(rpcWire)
                        }
                    }
                    if (hasTrackReply) {
                        registeredRequest = requestId
                        registrationError = error
                    }
                }
                else -> top.skip(wire)
            }
        }
        // Initial publishers may report current=-1 before capture starts. Pick
        // only an explicitly listed source; never invent an absent screen.
        if (tracksByScreen.isNotEmpty() && (screen == null || screen !in tracksByScreen))
            screen = tracksByScreen.keys.first()
        val rootFields = fields(bytes)
        val sourceFields = rootFields.data(7)?.let(::fields)
        val remoteScreens = sourceFields?.get(1).orEmpty().mapNotNull { field -> field.data?.let { encoded ->
            val f = fields(encoded)
            fun mode(raw: ByteArray?): UuDisplayMode? = raw?.let(::fields)?.let { m ->
                val width = m.number(3).toInt(); val height = m.number(4).toInt()
                if (width > 0 && height > 0) UuDisplayMode(width, height) else null
            }
            val dpiInfo = f.data(9)?.let(::fields)
            val dpis = dpiInfo?.get(3).orEmpty().flatMap { value ->
                if (value.number != null) listOf(value.number.toInt()) else value.data?.let { packed ->
                    val r = Reader(packed); val numbers = mutableListOf<Int>(); while (!r.done()) numbers.add(r.long().toInt()); numbers
                }.orEmpty()
            }.filter { it in 50..500 }.distinct()
            UuRemoteScreen(f.number(1).toInt(), f.number(12).toInt(), f.data(10)?.toString(Charsets.UTF_8).orEmpty(),
                f.number(2, 60).toInt(), mode(f.data(4)), f[3].orEmpty().mapNotNull { mode(it.data) }.distinct(),
                dpiInfo?.number(1)?.toInt() ?: 0, dpis, f.number(5).toInt())
        }}.filter { it.id >= 0 && it.track in -1..4 }
        val simple = rootFields.data(3)?.let(::fields)
        val flags = simple?.data(4)?.let(::fields)
        val cursor = rootFields.data(15)?.let(::fields)?.data(2)?.let(::fields)
        val response = rootFields.data(22)?.let(::fields)
        val captureReply = response?.data(2)?.let(::fields)
        val captureRequest = response?.data(1)?.let(::fields)?.number(1)
        val errors = captureReply?.get(1).orEmpty().mapNotNull { it.data?.let(::fields)?.number(1)?.toInt() }
        return Envelope(sequence, timestamp, echo, screen, screen?.let(tracksByScreen::get), echoAck, registeredRequest,
            registrationError, tracksByScreen.toMap(), remoteScreens, flags?.number(1)?.toInt(), cursor?.number(6)?.toInt(), cursor?.number(9)?.toInt(),
            response?.data(8)?.let { captureRequest?.let { id -> id to fields(it).number(1).toInt() } },
            if (captureReply != null) captureRequest else null, errors,
            response?.data(17)?.let {captureRequest?.let {id -> id to fields(it).number(1).toInt()}},simple?.number(1)?.toInt())

    }

    fun signalEndpoint(endpoint: String): String {
        val normalized = if (endpoint.contains("://")) endpoint else "wss://$endpoint"
        val url = normalized.replaceFirst("wss://", "https://").toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid UU signal endpoint")
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

    /**
     * The desktop Rust client modifies only the signaling-copy of SDP.
     * UU's application section requires ordered SCTP message-size and optional
     * mix-KCP attributes; the local WebRTC SDP must stay unchanged.
     */
    /**
     * Map the peer's accepted video m-lines to UU 0..4 track indices.
     * A rejected m-line (port 0) must never be registered for capture.
     */
    fun acceptedVideoTrackIndices(sdp: String): List<Int> {
        val result = mutableListOf<Int>()
        var videoIndex = 0
        for (line in sdp.lineSequence()) {
            if (!line.trimEnd('\r').startsWith("m=video ")) continue
            require(videoIndex < 5) { "UU peer offered too many video sections" }
            val parts = line.trim().split(Regex("\\s+"))
            val port = parts.getOrNull(1)?.toIntOrNull()
                ?: throw IllegalArgumentException("invalid video SDP port")
            require(port in 0..65535) { "invalid SDP video port" }
            if (port != 0) result.add(videoIndex)
            videoIndex++
        }
        require(videoIndex > 0) { "no UU video m-lines in SDP answer" }
        return result
    }

    /** Video ordinal is the UU video_track_index; MID is the SDK receiver key.
     * Do not infer this from callback order or MediaStreamTrack.id (often UUID).
     */
    fun videoMidIndices(sdp: String): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        var index = -1
        var accepted = false
        for (raw in sdp.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("m=")) {
                val parts = line.split(Regex("\\s+"))
                val video = parts.firstOrNull() == "m=video"
                if (video) index++
                accepted = video && parts.getOrNull(1) != "0" && index in 0..4
            } else if (accepted && line.startsWith("a=mid:")) {
                val mid = line.removePrefix("a=mid:")
                require(mid.isNotBlank() && mid.length <= 64 && mid !in result)
                result[mid] = index
            }
        }
        return result
    }

    fun uuOfferSdp(sdp: String, streams: String = "audio_0"): String {
        val separator = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toMutableList()
        require(lines.any { it.startsWith("m=application ") }) { "UU SDP missing application channel" }
        lines.removeAll { it.startsWith("a=msid-semantic:") || it == "a=extmap-allow-mixed" }
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }
        require(firstMedia >= 0)
        val bundle = lines.subList(0, firstMedia).indexOfFirst { it.startsWith("a=group:BUNDLE") }
        val insertAt = if (bundle >= 0) bundle + 1 else firstMedia
        val attrs = listOf("a=extmap-allow-mixed", "a=msid-semantic: WMS $streams")
        attrs.asReversed().forEach { if (it !in lines.subList(0, firstMedia)) lines.add(insertAt, it) }
        val app = lines.indexOfFirst { it.startsWith("m=application ") }
        var end = (app + 1 until lines.size).firstOrNull { lines[it].startsWith("m=") } ?: lines.size
        for (i in end - 1 downTo app + 1) {
            if (lines[i] == "a=sendrecv" || lines[i].startsWith("a=x-uuremote-mix-kcp:")) {
                lines.removeAt(i)
            }
        }
        end = (app + 1 until lines.size).firstOrNull { lines[it].startsWith("m=") } ?: lines.size
        // Replace a peer-library default value rather than adding a competing
        // max-message-size line. Repeated normalization must be byte-stable.
        for (i in end - 1 downTo app + 1) {
            if (lines[i].startsWith("a=max-message-size:")) lines.removeAt(i)
        }
        end = (app + 1 until lines.size).firstOrNull { lines[it].startsWith("m=") } ?: lines.size
        val sctp = (app + 1 until end).firstOrNull { lines[it].startsWith("a=sctp-port:") }
        val attrAt = if (sctp != null) sctp + 1 else end
        lines.add(attrAt, "a=max-message-size:524288")
        // Disable mixed KCP until Android libwebrtc has a compatible transport.
        return lines.joinToString(separator, postfix = separator)
    }

    /** Desktop-compatible Socket.IO control ACK: ["success", ControlSessionInfo]. */
    fun parseControlAck(frame: String, appControlId: String): ControlReply {
        require(frame.startsWith("432")) { "incorrect UU control ACK id" }
        val arr = JSONArray(frame.removePrefix("432"))
        require(arr.length() >= 2 && arr.optString(0) == "success") { "UU controller was not accepted" }
        val response = arr.optJSONObject(1) ?: throw IllegalArgumentException("UU control config missing")
        require(response.optInt("code", -1) == 0) { "UU control rejected" }
        val clientId = response.optString("client_id")
        val iceId = response.optString("ice_id")
        require(clientId.isNotBlank() && iceId.isNotBlank()) { "UU control IDs missing" }
        val servers = mutableListOf<ServerIce>()
        val raw = response.optJSONArray("iceServers")
        if (raw != null) for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: continue
            val urls = when(val value = item.opt("urls")) {
                is String -> listOf(value)
                is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
                else -> emptyList()
            }
            if (urls.isNotEmpty()) servers.add(ServerIce(urls, item.optString("username"), item.optString("credential")))
        }
        require(servers.isNotEmpty()) { "UU control did not provide ICE servers" }
        return ControlReply(clientId, iceId, appControlId, servers)
    }

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

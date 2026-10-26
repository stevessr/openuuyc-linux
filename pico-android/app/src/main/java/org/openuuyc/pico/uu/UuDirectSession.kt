package org.openuuyc.pico.uu

import android.content.Context
import android.view.Surface
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native direct UU controller. No Linux bridge: NRD room -> UU Socket.IO ->
 * WebRTC/ICE -> ordered UU CONTROL/TEXT channels -> H264 decoder -> XR Surface.
 *
 * The Android libwebrtc codec/SDP dialect still requires on-device testing,
 * particularly against hosts requiring proprietary RTP and mixed-KCP.
 */
internal class UuDirectSession(
    private val context: Context,
    private val room: UuRoom,
    private val identity: UuIdentity,
    private val events: (String) -> Unit
) : AutoCloseable {
    private val http = OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val closed = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var peer: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var renderer: EglRenderer? = null
    private var surface: Surface? = null
    private var controlInfo: ControlReply? = null
    private var controlChannel: DataChannel? = null
    private var textChannel: DataChannel? = null
    private val channels = mutableListOf<DataChannel>()
    private var video: VideoTrack? = null
    private var pendingBinaryEvent: JSONObject? = null
    private var pendingBinaryName: String? = null
    private var currentScreen: Int? = null
    private var inputReady = false
    private var captureRequested = false
    private var pbConnected = false
    private var pressed = false
    private var signalingConnected = false
    private val appControlId = UUID.randomUUID().toString()

    @Synchronized fun start(xrSurface: Surface) {
        check(socket == null && !closed.get())
        surface = xrSurface
        val endpoint = UuWire.signalEndpoint(room.endpoints.first())
        val request = Request.Builder().url(endpoint)
            .header("X-NRD-AUTH", room.token)
            .header("X-NRD-CONTROLLING", "0")
            .header("streamer_version", "V4.7.3")
            .header("streamer_flag", """{"sdp_flags":{"gzip_sdp":true}}""")
            .build()
        emit("UU 信令服务器连接中")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send("40") // Socket.IO namespace CONNECT
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.length <= 1024 * 1024) synchronized(this@UuDirectSession) {
                    runCatching { parseMessage(text) }.onFailure {
                        emit("UU 信令帧解析失败")
                        ws.close(1002, "invalid UU protocol frame")
                    }
                }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (bytes.size <= 1024 * 1024) synchronized(this@UuDirectSession) {
                    runCatching { parseBinary(bytes.toByteArray()) }.onFailure {
                        emit("UU 二进制信令无效")
                        ws.close(1002, "invalid UU binary frame")
                    }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                emit("UU 信令连接失败：${t.javaClass.simpleName}")
                close()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                emit("UU 信令已关闭 ($code)")
                close()
            }
        })
    }

    private fun emit(text: String) { if (!closed.get()) events(text) }

    private fun parseMessage(frame: String) {
        if (closed.get()) return
        when {
            frame == "2" || frame.startsWith("2") -> socket?.send("3" + frame.drop(1))
            frame.startsWith("40") -> {
                if (!signalingConnected) {
                    signalingConnected = true
                    socket?.send("421[\"room_info\"]")
                }
            }
            frame.startsWith("431") -> {
                // room_info ACK id=1
                val array = JSONArray(frame.removePrefix("431"))
                if (array.length() == 0 || array.optJSONObject(0) == null)
                    throw IllegalArgumentException("UU room_info ACK missing")
                val (header, bytes) = UuWire.controlEvent(identity.deviceId, appControlId)
                socket?.send(header)
                socket?.send(ByteString.of(*UuWire.attachment(bytes)))
                emit("UU 房间已登录，发送原生控制握手")
            }
            frame.startsWith("432") -> {
                controlInfo = UuWire.parseControlAck(frame, appControlId)
                emit("UU 控制握手已确认，启动设备直连 WebRTC")
                createPeer(controlInfo!!)
            }
            frame.startsWith("451-") -> {
                val start = frame.indexOf('[')
                if (start < 0) return
                val args = JSONArray(frame.substring(start))
                pendingBinaryName = args.optString(0)
                pendingBinaryEvent = args.optJSONObject(1)
            }
            frame.startsWith("42") -> {
                val arrayStart = frame.indexOf('[')
                if (arrayStart < 0) return
                val packet = JSONArray(frame.substring(arrayStart))
                if (packet.optString(0) == "soac") {
                    parseSoac(packet.optJSONObject(1), null)
                }
            }
            frame == "41" || frame == "1" -> close()
        }
    }

    private fun parseBinary(frame: ByteArray) {
        val event = pendingBinaryEvent ?: return
        val type = pendingBinaryName
        pendingBinaryEvent = null
        pendingBinaryName = null
        if (type == "soac") parseSoac(event, frame)
    }

    private fun parseSoac(envelope: JSONObject?, compressed: ByteArray?) {
        val info = controlInfo ?: return
        val data = envelope?.optJSONObject("data") ?: return
        if (data.optString("ice_id") != info.iceId ||
            data.optString("app_control_id", info.appControlId) != info.appControlId) return
        when (data.optString("type")) {
            "answer", "restart_ice" -> {
                val sdp = if (compressed != null) UuWire.ungzip(compressed) else data.optString("sdp")
                if (sdp.isBlank()) return
                peer?.setRemoteDescription(sdpObserver(onSuccess = {
                    emit("UU WebRTC SDP 已接受")
                }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "candidate" -> {
                val ice = data.optJSONObject("candidate") ?: return
                val candidate = ice.optString("candidate")
                if (candidate.isNotBlank()) {
                    peer?.addIceCandidate(IceCandidate(ice.optString("sdpMid", "0"),
                        ice.optInt("sdpMLineIndex", 0), candidate))
                }
            }
        }
    }

    private fun sdpObserver(
        onCreated: ((SessionDescription) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { onCreated?.invoke(sdp) }
        override fun onSetSuccess() { onSuccess?.invoke() }
        override fun onCreateFailure(error: String) { emit("SDP offer error") }
        override fun onSetFailure(error: String) { emit("SDP negotiation error") }
    }

    private fun createPeer(info: ControlReply) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val localEgl = EglBase.create()
        egl = localEgl
        val output = EglRenderer("OpenUUYC-UU-Direct")
        output.init(localEgl.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        output.createEglSurface(surface)
        renderer = output
        val engine = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(localEgl.eglBaseContext))
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(localEgl.eglBaseContext, false, true))
            .createPeerConnectionFactory()
        factory = engine
        val config = PeerConnection.RTCConfiguration(info.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username).setPassword(server.credential).createIceServer()
        }).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = engine.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) {
                    emit("UU P2P/中转 WebRTC 已连接")
                }
            }
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                socket?.send(UuWire.candidate(info, candidate.sdp, candidate.sdpMid ?: "0", candidate.sdpMLineIndex))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel) { listenChannel(channel) }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                attachVideo(receiver?.track())
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                attachVideo(transceiver?.receiver?.track())
            }
        })
        val connection = peer ?: throw IllegalStateException("createPeerConnection failed")
        val options = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, options)
        connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, options)
        for (label in listOf(
            "CONTROL_DATA_CHANNEL", "TEXT_DATA_CHANNEL", "STREAMER_DATA_CHANNEL",
            "FILE_DATA_CHANNEL", "BINARY_DATA_CHANNEL"
        )) {
            val channel = connection.createDataChannel(label, DataChannel.Init().apply { ordered = true })
            if (channel != null) {
                channels.add(channel)
                listenChannel(channel)
            }
        }
        connection.createOffer(sdpObserver(onCreated = { desc ->
            connection.setLocalDescription(sdpObserver(onSuccess = {
                val wireSdp = UuWire.uuOfferSdp(desc.description)
                val zip = UuWire.gzip(wireSdp.toByteArray(StandardCharsets.UTF_8))
                socket?.send(UuWire.offerHeader(info))
                socket?.send(ByteString.of(*UuWire.attachment(zip)))
                emit("UU WebRTC offer 已通过原生 Socket.IO 发送")
            }), desc)
        }), MediaConstraints())
    }

    private fun attachVideo(track: MediaStreamTrack?) {
        if (track !is VideoTrack) return
        synchronized(this) {
            if (video === track) return
            renderer?.let { video?.removeSink(it) }
            video = track
            renderer?.let { track.addSink(it) }
        }
    }

    private fun listenChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    synchronized(this@UuDirectSession) {
                        when(channel.label()) {
                            "CONTROL_DATA_CHANNEL" -> {
                                controlChannel = channel
                                sendControl(UuWire.echoRequest())
                            }
                            "TEXT_DATA_CHANNEL" -> {
                                textChannel = channel
                                maybeStartCapture()
                            }
                        }
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.data.remaining() > 65536) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (channel.label() != "CONTROL_DATA_CHANNEL" &&
                    channel.label() != "TEXT_DATA_CHANNEL") return
                if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return
                try {
                    val payload = UuWire.decodeEnvelope(bytes)
                    if (channel.label() == "CONTROL_DATA_CHANNEL") {
                        pbConnected = true
                        if (payload.echoRequest) {
                            sendControl(UuWire.echoResponse(payload.seq, payload.timestamp))
                        }
                    }
                    if (payload.screenId != null && payload.screenId >= 0)
                        currentScreen = payload.screenId
                    maybeStartCapture()
                    inputReady = pbConnected && captureRequested &&
                        currentScreen != null &&
                        controlChannel?.state() == DataChannel.State.OPEN &&
                        textChannel?.state() == DataChannel.State.OPEN
                } catch (_: IllegalArgumentException) {
                    // Vendor extensions may be opaque; never reflect them back.
                }
            }
        })
    }

    @Synchronized private fun maybeStartCapture() {
        if (captureRequested || !pbConnected || currentScreen == null) return
        if (controlChannel?.state() != DataChannel.State.OPEN ||
            textChannel?.state() != DataChannel.State.OPEN) return
        // UU runtime CaptureSetting RPC is a protobuf payload with SCTP TEXT PPID.
        val payload = UuWire.captureSetting()
        captureRequested = textChannel?.send(
            DataChannel.Buffer(ByteBuffer.wrap(payload), false)
        ) == true
        if (captureRequested) emit("UU 屏幕控制已协商，申请远端 H.264 画面")
    }

    @Synchronized private fun sendControl(data: ByteArray): Boolean {
        val channel = controlChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN || channel.bufferedAmount() > 1024 * 1024) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    @Synchronized fun pointer(u: Float, v: Float, down: Boolean) {
        if (closed.get() || !inputReady || !u.isFinite() || !v.isFinite()) return
        val screen = currentScreen ?: return
        val x = u.toDouble().coerceIn(0.0, 0.999999)
        val y = v.toDouble().coerceIn(0.0, 0.999999)
        sendControl(JSONObject().put("action", "mouse_move_absolute")
            .put("screen_id", screen).put("abs_x", x).put("abs_y", y)
            .toString().toByteArray(StandardCharsets.UTF_8))
        if (pressed != down) {
            if (sendControl(JSONObject().put("action", if (down) "mouse_press" else "mouse_release")
                .put("button", 1).toString().toByteArray(StandardCharsets.UTF_8))) pressed = down
        }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (pressed) sendControl("""{"action":"mouse_release","button":1}""".toByteArray())
        pressed = false
        inputReady = false
        renderer?.let { video?.removeSink(it) }
        video = null
        channels.forEach { runCatching { it.close() } }
        channels.clear()
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        renderer?.release()
        renderer = null
        egl?.release()
        egl = null
        factory?.dispose()
        factory = null
        socket?.close(1000, "session ended")
        socket = null
        http.dispatcher.executorService.shutdown()
        // Surface lifetime is owned by the Android XR activity.
    }
}

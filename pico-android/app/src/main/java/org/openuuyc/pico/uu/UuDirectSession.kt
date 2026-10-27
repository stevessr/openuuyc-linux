package org.openuuyc.pico.uu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import org.webrtc.VideoSink
import org.webrtc.AudioTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
    private val events: (String) -> Unit,
    private val onUnexpectedDisconnect: () -> Unit = {}
) : AutoCloseable {
    private val http = OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val closed = AtomicBoolean(false)
    private val health = UuSessionHealth()
    private val firstFrameSeen = AtomicBoolean(false)
    private val firstFrameSink = VideoSink { _ ->
        if (firstFrameSeen.compareAndSet(false, true)) {
            health.markFirstFrame()
            emit("UU 远端 H.264 第一帧已到达头显")
        }
    }
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "OpenUUYC-UU-PB").apply { isDaemon = true }
    }
    private var echoRetry: ScheduledFuture<*>? = null
    private var echoAttempts = 0
    private var socket: WebSocket? = null
    private val signalEndpoints = UuSignalEndpoints(room.endpoints)
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
    private var remoteAudio: AudioTrack? = null
    private var audioFocusHeld = false
    private var audioFocusRequested = false
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        synchronized(this) {
            audioFocusHeld = focus == AudioManager.AUDIOFOCUS_GAIN && !closed.get()
            remoteAudio?.setEnabled(audioFocusHeld)
        }
    }
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build())
        .setOnAudioFocusChangeListener(audioFocusListener)
        .build()
    private var pendingBinaryEvent: JSONObject? = null
    private var pendingBinaryName: String? = null
    private val captureGate = UuCaptureGate()
    private var nextRequestSeq = 2L
    private var pressed = false
    private val signalGate = UuSignalGate()
    private var answerInstalled = false
    private val pendingRemoteCandidates = ArrayList<IceCandidate>()
    private val appControlId = UUID.randomUUID().toString()

    @Synchronized fun start(xrSurface: Surface) {
        check(socket == null && !closed.get())
        surface = xrSurface
        health.markStarted()
        heartbeatExecutor.scheduleAtFixedRate({
            synchronized(this) {
                if (closed.get()) return@synchronized
                when (health.check()) {
                    UuSessionHealth.Warning.SIGNAL_TIMEOUT -> {
                        emit("UU 信令握手超过 30 秒，终止无效连接")
                        terminateUnexpectedly()
                    }
                    UuSessionHealth.Warning.ICE_TIMEOUT -> {
                        emit("UU WebRTC/ICE 连接超过 35 秒，终止无效连接")
                        terminateUnexpectedly()
                    }
                    UuSessionHealth.Warning.FIRST_FRAME_TIMEOUT ->
                        emit("UU 视频采集已请求但 25 秒内无画面，请检查被控端及编解码器")
                    UuSessionHealth.Warning.NONE -> {}
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
        connectSignaling()
    }

    @Synchronized private fun connectSignaling() {
        if (closed.get()) return
        val request = Request.Builder().url(signalEndpoints.current)
            .header("X-NRD-AUTH", room.token)
            .header("X-NRD-CONTROLLING", "0")
            .header("streamer_version", "V4.7.3")
            .header("streamer_flag", """{"sdp_flags":{"gzip_sdp":true}}""")
            .build()
        emit("UU 信令服务器连接中 (${signalEndpoints.position}/${signalEndpoints.count})")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (!closed.get()) ws.send("40") // namespace CONNECT
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (text.length <= 1024 * 1024) synchronized(this@UuDirectSession) {
                    if (ws !== socket || closed.get()) return@synchronized
                    runCatching { parseMessage(text) }.onFailure {
                        emit("UU 信令帧解析失败")
                        ws.close(1002, "invalid UU protocol frame")
                    }
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (bytes.size <= 1024 * 1024) synchronized(this@UuDirectSession) {
                    if (ws !== socket || closed.get()) return@synchronized
                    runCatching { parseBinary(bytes.toByteArray()) }.onFailure {
                        emit("UU 二进制信令无效")
                        ws.close(1002, "invalid UU binary frame")
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onSocketEnded(ws, "连接失败：${t.javaClass.simpleName}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onSocketEnded(ws, "已关闭 ($code)")
            }
        })
    }

    @Synchronized private fun onSocketEnded(ws: WebSocket, reason: String) {
        if (closed.get() || ws !== socket) return
        if (signalGate.phase == UuSignalGate.Phase.WAIT_NAMESPACE &&
            signalEndpoints.nextBeforeNamespace()) {
            socket = null
            emit("UU 首选信令节点不可用，切换下一个节点")
            connectSignaling()
        } else {
            emit("UU 信令 $reason")
            terminateUnexpectedly()
        }
    }

    private fun emit(text: String) { if (!closed.get()) events(text) }

    /** Exit headset mode if the authorized UU signaling connection dies. */
    private fun terminateUnexpectedly() {
        if (closed.get()) return
        close()
        onUnexpectedDisconnect()
    }

    private fun parseMessage(frame: String) {
        if (closed.get()) return
        when (val packet = UuSocketIoWire.parse(frame)) {
            is UuSocketIoWire.Packet.Open -> emit("UU Engine.IO 会话已建立")
            is UuSocketIoWire.Packet.Ping -> socket?.send("3" + packet.payload)
            UuSocketIoWire.Packet.NamespaceConnected -> {
                if (signalGate.namespaceConnected())
                    socket?.send("421[\"room_info\"]")
            }
            is UuSocketIoWire.Packet.RoomAck -> {
                if (!signalGate.roomAcknowledged()) return
                val (header, bytes) = UuWire.controlEvent(identity.deviceId, appControlId)
                socket?.send(header)
                socket?.send(ByteString.of(*UuWire.attachment(bytes)))
                emit("UU 房间已登录，发送原生控制握手")
            }
            is UuSocketIoWire.Packet.ControlAck -> {
                if (signalGate.phase != UuSignalGate.Phase.WAIT_CONTROL_ACK) return
                val verified = UuWire.parseControlAck(packet.data, appControlId)
                if (!signalGate.controlAcknowledged()) return
                controlInfo = verified
                health.markControlAccepted()
                emit("UU 控制握手已确认，启动设备直连 WebRTC")
                createPeer(verified)
            }
            is UuSocketIoWire.Packet.Event -> {
                packet.acknowledge?.let { socket?.send(UuSocketIoWire.eventAck(it)) }
                when (packet.name) {
                    "soac" -> if (signalGate.mayProcessMedia()) parseSoac(packet.payload, null)
                    "leave", "publisher_disconnect" -> {
                        emit("UU 远端已结束控制会话")
                        terminateUnexpectedly()
                    }
                }
            }
            is UuSocketIoWire.Packet.BinaryStart -> {
                packet.acknowledge?.let { socket?.send(UuSocketIoWire.eventAck(it)) }
                if (!signalGate.mayProcessMedia()) return
                require(pendingBinaryEvent == null) { "interleaved binary UU events" }
                pendingBinaryName = packet.name
                pendingBinaryEvent = packet.payload
                require(pendingBinaryEvent != null) { "binary UU event missing metadata" }
            }
            is UuSocketIoWire.Packet.Rejected -> {
                emit("UU 信令服务拒绝连接")
                terminateUnexpectedly()
            }
            UuSocketIoWire.Packet.Disconnected -> terminateUnexpectedly()
            UuSocketIoWire.Packet.Ignore -> {}
        }
    }

    private fun parseBinary(frame: ByteArray) {
        val pending = pendingBinaryEvent ?: return
        val type = pendingBinaryName
        pendingBinaryEvent = null
        pendingBinaryName = null
        val payload = UuSocketIoWire.binaryPayload(frame)
        if (type == "soac") parseSoac(pending, UuWire.attachment(payload))
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
                answerInstalled = false
                peer?.setRemoteDescription(sdpObserver(onSuccess = {
                    synchronized(this@UuDirectSession) {
                        answerInstalled = true
                        pendingRemoteCandidates.forEach { peer?.addIceCandidate(it) }
                        pendingRemoteCandidates.clear()
                    }
                    emit("UU WebRTC SDP 已接受")
                }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "candidate" -> {
                val ice = data.optJSONObject("candidate") ?: return
                val candidate = ice.optString("candidate")
                if (candidate.isNotBlank()) {
                    val next = IceCandidate(ice.optString("sdpMid", "0"),
                        ice.optInt("sdpMLineIndex", 0), candidate)
                    if (answerInstalled) peer?.addIceCandidate(next)
                    else if (pendingRemoteCandidates.size < 128) pendingRemoteCandidates.add(next)
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
        health.markPeerCreated()
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
                    health.markIceConnected()
                    emit("UU P2P/中转 WebRTC 已连接")
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    emit("UU WebRTC ICE 连接失败")
                    terminateUnexpectedly()
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
                attachAudio(receiver?.track())
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                attachVideo(transceiver?.receiver?.track())
                attachAudio(transceiver?.receiver?.track())
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
            video?.removeSink(firstFrameSink)
            video = track
            renderer?.let { track.addSink(it) }
            track.addSink(firstFrameSink)
        }
    }

    private fun attachAudio(track: MediaStreamTrack?) {
        if (track !is AudioTrack) return
        synchronized(this) {
            if (closed.get() || remoteAudio === track) return
            remoteAudio?.setEnabled(false)
            remoteAudio = track
            val granted = runCatching {
                audioFocusRequested = true
                audioManager.requestAudioFocus(audioFocusRequest) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
            audioFocusHeld = granted
            track.setEnabled(granted)
            emit(if (granted) "UU WebRTC 远端音轨已接入头显音频输出"
                 else "UU 远端音轨已接入，但 Android 音频焦点不可用")
        }
    }

    private fun listenChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                synchronized(this@UuDirectSession) {
                    if (channel.state() == DataChannel.State.OPEN) {
                        when (channel.label()) {
                            "CONTROL_DATA_CHANNEL" -> {
                                controlChannel = channel
                                startPbHandshake()
                            }
                            "TEXT_DATA_CHANNEL" -> {
                                textChannel = channel
                                maybeStartCapture()
                            }
                        }
                    } else if (channel.state() == DataChannel.State.CLOSED ||
                               channel.state() == DataChannel.State.CLOSING) {
                        if (channel === controlChannel || channel === textChannel) {
                            // Revoke the button while CONTROL is still writable.
                            if (pressed && controlChannel?.state() == DataChannel.State.OPEN) {
                                sendControl(UuPointerWire.button(false))
                            }
                            captureGate.onTransportClosed()
                            pressed = false
                        }
                        if (channel === controlChannel) controlChannel = null
                        if (channel === textChannel) textChannel = null
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                synchronized(this@UuDirectSession) {
                    if (closed.get() || buffer.data.remaining() > 65536) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val isControl = channel.label() == "CONTROL_DATA_CHANNEL"
                    if (!isControl && channel.label() != "TEXT_DATA_CHANNEL") return
                    if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return
                    try {
                        val payload = UuWire.decodeEnvelope(bytes)
                        val beforeScreen = captureGate.selectedScreen
                        val beforeTrack = captureGate.selectedTrack
                        if (isControl && payload.echoRequest) {
                            sendControl(UuWire.echoResponse(payload.seq, payload.timestamp))
                        }
                        val acknowledgment = captureGate.observe(payload, isControl)
                        // A host display/track change revokes the held drag. Do
                        // not allow a fresh DOWN until the old UP was sent.
                        if (pressed && (beforeScreen != captureGate.selectedScreen ||
                                beforeTrack != captureGate.selectedTrack)) {
                            if (!sendControl(UuPointerWire.button(false))) {
                                emit("UU 显示器切换时按键释放失败，已断开远控")
                                close()
                                return
                            }
                            pressed = false
                        }
                        when (acknowledgment) {
                            UuCaptureGate.Ack.REGISTERED -> emit("UU 视频轨道注册确认成功")
                            UuCaptureGate.Ack.REJECTED -> emit("UU 视频轨道注册被拒绝（错误码 ${payload.trackAckError}）")
                            UuCaptureGate.Ack.NONE -> {}
                        }
                        if (captureGate.pbConnected) echoRetry?.cancel(false)
                        maybeStartCapture()
                    } catch (_: IllegalArgumentException) {
                        // Vendor extensions may be opaque; never reflect them back.
                    }
                }
            }
        })
    }

    /** Official desktop PB bootstrap retransmits ECHO every 500ms (at most 30). */
    @Synchronized private fun startPbHandshake() {
        echoRetry?.cancel(false)
        echoAttempts = 0
        echoRetry = heartbeatExecutor.scheduleAtFixedRate({
            synchronized(this) {
                if (closed.get() || captureGate.pbConnected) {
                    echoRetry?.cancel(false)
                    return@synchronized
                }
                if (echoAttempts >= 30) {
                    echoRetry?.cancel(false)
                    emit("UU PB 握手超时，无法建立控制")
                    return@synchronized
                }
                echoAttempts++
                sendControl(UuWire.echoRequest())
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun maybeStartCapture() {
        val controlOpen = controlChannel?.state() == DataChannel.State.OPEN
        val textOpen = textChannel?.state() == DataChannel.State.OPEN
        val action = captureGate.next(controlOpen, textOpen)
        if (action == UuCaptureGate.Action.NONE) return
        val text = textChannel ?: return
        val sequence = nextRequestSeq++
        val packet = when (action) {
            UuCaptureGate.Action.REGISTER_TRACK ->
                UuWire.registerVideoTrack(sequence, captureGate.selectedTrack ?: return)
            UuCaptureGate.Action.START_VIDEO ->
                UuWire.startVideoCapture(sequence, captureGate.selectedScreen ?: return)
            UuCaptureGate.Action.CAPTURE_SETTING -> UuWire.captureSetting(sequence)
            UuCaptureGate.Action.NONE -> return
        }
        if (text.send(DataChannel.Buffer(ByteBuffer.wrap(packet), false))) {
            captureGate.onSubmitted(action, sequence)
            if (action == UuCaptureGate.Action.CAPTURE_SETTING) {
                health.markCaptureRequested()
                emit("UU 视频轨道注册确认，远端 H.264 画面已请求")
            }
        }
        // Never issue dependent UU RPCs in the same turn as SendVideoTrack.
        // Wait for real ACK, then invoke again. For the two post-ACK steps,
        // the second call is safe since the first is already locally admitted.
        if (action == UuCaptureGate.Action.START_VIDEO) maybeStartCapture()
    }

    @Synchronized private fun sendControl(data: ByteArray): Boolean {
        val channel = controlChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN || channel.bufferedAmount() > 1024 * 1024) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    @Synchronized fun pointer(u: Float, v: Float, down: Boolean) {
        if (closed.get() || !captureGate.inputReady(
            controlChannel?.state() == DataChannel.State.OPEN,
            textChannel?.state() == DataChannel.State.OPEN
        )) return
        val screen = captureGate.selectedScreen ?: return
        if (!UuPointerWire.valid(u, v)) return
        if (!sendControl(UuPointerWire.move(screen, u, v))) return
        if (pressed != down) {
            if (sendControl(UuPointerWire.button(down))) pressed = down
        }
    }

    @Synchronized fun scroll(delta: Int) {
        if (closed.get() || !captureGate.inputReady(
            controlChannel?.state() == DataChannel.State.OPEN,
            textChannel?.state() == DataChannel.State.OPEN
        )) return
        if (delta !in -1200..1200 || delta == 0) return
        sendControl(UuPointerWire.scroll(delta))
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        signalGate.close()
        pendingBinaryEvent = null
        pendingBinaryName = null
        echoRetry?.cancel(false)
        heartbeatExecutor.shutdownNow()
        if (pressed) sendControl(UuPointerWire.button(false))
        pressed = false
        captureGate.onTransportClosed()
        answerInstalled = false
        pendingRemoteCandidates.clear()
        renderer?.let { video?.removeSink(it) }
        video?.removeSink(firstFrameSink)
        video = null
        remoteAudio?.setEnabled(false)
        remoteAudio = null
        if (audioFocusRequested) {
            runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
        }
        audioFocusRequested = false
        audioFocusHeld = false
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

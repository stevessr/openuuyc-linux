package org.openuuyc.pico.uu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
    private val onWindowVisibility: (Int, Boolean) -> Unit = { _, _ -> },
    private val onUnexpectedDisconnect: () -> Unit = {}
) : AutoCloseable {
    var applicationsOnly=false
    var immersiveRendering=false
    companion object {
        private val appFocused=AtomicBoolean(false)
        private val live=java.util.concurrent.ConcurrentHashMap.newKeySet<UuDirectSession>()
        fun vrTrackingTarget()=live.firstOrNull {it.vr.active}
        fun setApplicationFocused(value: Boolean) {
            appFocused.set(value)
            live.forEach {it.clipboardBinding.focusChanged();if(!value) it.stopMicrophoneForFocus() else it.postRtc {it.updateMicrophone()}}
        }
    }
    private val clipboardBinding: UuClipboardBinding=UuClipboardBinding(context,
        permitted={appFocused.get() && !closed.get()},publish={clipboardWire.text(it)})
    private val clipboardWire: UuClipboardWire=UuClipboardWire(
        next={synchronized(this) {nextRequestSeq++}},
        send={payload -> !closed.get() && rtcCallbacks.post {synchronized(this) {if(!closed.get() && inputReady()) sendControl(payload)}}},
        write={text,done -> clipboardBinding.write(text,done)},rejected={clipboardBinding.enable(false);emit("远端拒绝文本剪贴板，已关闭同步")})
    val clipboardEnabled: Boolean get()=clipboardBinding.enabled
    fun setClipboardEnabled(value: Boolean) {
        if(!value) clipboardBinding.enable(false)
        postRtc {clipboardWire.reset();clipboardBinding.enable(value);emit(if(value) "文本剪贴板同步已开启（仅前台会话）" else "剪贴板同步已关闭")}
    }
    private val micState=UuMicrophoneState()
    @Volatile private var audioModule: org.webrtc.audio.JavaAudioDeviceModule?=null
    private var microphoneTransceiver: RtpTransceiver?=null
    private var microphoneTrack: AudioTrack?=null
    private var microphoneSource: org.webrtc.AudioSource?=null
    @Volatile var microphoneActive=false;private set
    @Volatile var microphoneNegotiated=false;private set
    val microphoneEnabled: Boolean get()=micState.requested
    fun setMicrophoneEnabled(value: Boolean) {
        if(!value) {audioModule?.setMicrophoneMute(true);micState.stop()}
        postRtc {
            if(value) {
                if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {emit("麦克风未授权，未开启");return@postRtc}
                val direction=microphoneTransceiver?.currentDirection
                if(direction!=RtpTransceiver.RtpTransceiverDirection.SEND_RECV && direction!=RtpTransceiver.RtpTransceiverDirection.SEND_ONLY) {emit("本次会话未协商可发送的麦克风轨道");return@postRtc}
            }
            val id=synchronized(this) {nextRequestSeq++}
            micState.request(id,value)
            val generation=micState.generation
            val sent=synchronized(this) {if(inputReady()) textChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(UuWire.microphonePolicy(id,value)),false))==true else false}
            if(!sent) {micState.stop();emit("麦克风控制通道未就绪，未开启")}
            else emit(if(value) "麦克风等待远端虚拟声卡确认" else "麦克风已关闭")
            updateMicrophone()
            if(value && sent) cleanupHandler.postDelayed({postRtc {if(micState.generation==generation && micState.requested && !microphoneActive) {setMicrophoneEnabled(false);emit("远端未打开麦克风，已安全关闭")}}},8000)
        }
    }
    private fun stopMicrophoneForFocus() {audioModule?.setMicrophoneMute(true);if(micState.requested) setMicrophoneEnabled(false)}
    private fun updateMicrophone() {
        val permission=context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val direction=microphoneTransceiver?.currentDirection
        val send=direction==RtpTransceiver.RtpTransceiverDirection.SEND_RECV || direction==RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        if(!closed.get() && micState.mayCapture(permission,appFocused.get(),send)) {
            if(microphoneTrack==null) {
                val engine=factory ?: return
                val source=engine.createAudioSource(MediaConstraints())
                val track=engine.createAudioTrack("audio_0",source)
                track.setEnabled(false)
                if(microphoneTransceiver?.sender?.setTrack(track,false)!=true) {track.dispose();source.dispose();micState.stop();emit("麦克风音轨绑定失败");return}
                microphoneSource=source;microphoneTrack=track
            }
            microphoneTrack?.setEnabled(true);audioModule?.setMicrophoneMute(false);microphoneActive=true
            emit("麦克风回传已开启")
        } else {
            audioModule?.setMicrophoneMute(true);microphoneActive=false
            val track=microphoneTrack;val source=microphoneSource;microphoneTrack=null;microphoneSource=null
            if(track!=null) {runCatching {microphoneTransceiver?.sender?.setTrack(null,false)};runCatching {track.dispose()};runCatching {source?.dispose()}}
        }
    }
    val transfers=UuFileTransfers(next={synchronized(this) {nextRequestSeq++}},send={packet,bulk->
        synchronized(this) {
            val channel=if(bulk) channels.firstOrNull {it.label()=="FILE_DATA_CHANNEL" && it.state()==DataChannel.State.OPEN} ?: textChannel else textChannel
            !closed.get() && channel?.state()==DataChannel.State.OPEN && channel.bufferedAmount()<1024*1024 && channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet),false))
        }
    })
    val filesReady: Boolean get()=textChannel?.state()==DataChannel.State.OPEN
    val files=UuFileManager(
        next={synchronized(this) {nextRequestSeq++}},
        send={packet->synchronized(this) {!closed.get() && textChannel?.state()==DataChannel.State.OPEN && textChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(packet),false))==true}})
    private var lastVrStatus=""
    private var lastVrActive=false
    val quickApps=UuQuickApps()
    val vr=UuPcvrSession()
    private val http = OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val diagnostics = UuSessionDiagnostics(context)
    private val screenSettings=mutableMapOf<Int,UuCaptureSettings>()
    private val userConfiguredScreens=mutableSetOf<Int>()
    private val portraitRequests=mutableSetOf<Int>()
    private val decodedScreenSizes=mutableMapOf<Int,Pair<Int,Int>>()
    private fun improvePortraitQuality() {
        if(closed.get() || !inputReady() || remoteSettings.pending!=null) return
        for(screen in remoteSettings.screens) {
            if(screen.id in portraitRequests) continue
            val current=screenSettings[screen.id] ?: remoteSettings.current
            val next=UuPortraitQuality.plan(screen.current,decodedScreenSizes[screen.id],current,remoteSettings.profiles,
                screen.id in userConfiguredScreens || -2 in userConfiguredScreens) ?: continue
            if(runCatching { applySettings(next,targetScreen=screen.id,automatic=true) }.getOrDefault(false)) {
                portraitRequests.add(screen.id)
                emit("竖屏清晰度：已请求屏幕 ${screen.id} 的 ${next.maxWidth}×${next.maxHeight} 像素上限，等待远端确认")
            }
            return
        }
    }
    private var pendingSettingsScreen: Pair<Long,Int?>?=null
    private val remoteSettings = UuRemoteSettingsState()
    private var topologyPending: Long? = null
    private var topologyOriginal: Set<Int> = emptySet()
    private var localProfiles = listOf(UuCodecProfile(1, 1920, 1080))
    private var capabilitySnapshot: JSONObject? = null
    @Volatile var textCursorListener: ((Boolean) -> Unit)? = null
    @Volatile var textCursorVisible = false
        private set
    private val heldKeys = mutableSetOf<Int>()
    private var touchActive = false
    private var requestedScreen: Int? = null
    private var diagnosticTicks = 0
    private val closed = AtomicBoolean(false)
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val deferredClose = UuDeferredClose(closed) { action -> cleanupHandler.post { action() } }
    private val rtcCallbacks=UuRtcCallbackQueue {error ->
        emit("WebRTC 回调处理失败：${error.javaClass.simpleName}");terminateUnexpectedly()
    }
    private fun postRtc(action: ()->Unit) {
        if(!closed.get() && !rtcCallbacks.post {if(!closed.get()) action()}) terminateUnexpectedly()
    }
    private val health = UuSessionHealth()
    @Volatile var onDisposed: (() -> Unit)?=null
    @Volatile var windowInfoListener: ((Int,Int?,String,Int,Int)->Unit)? = null
    @Volatile var videoSizeListener: ((Int, Int) -> Unit)? = null
    private var lastVideoSize: Pair<Int, Int>? = null
    private var started = false
    private val firstFrameSeen = AtomicBoolean(false)
    private val firstFrameSink = VideoSink { frame ->
        val size = frame.rotatedWidth to frame.rotatedHeight
        if (size != lastVideoSize) { lastVideoSize = size; videoSizeListener?.invoke(size.first, size.second) }
        if (firstFrameSeen.compareAndSet(false, true)) {
            health.markFirstFrame()
            emit("UU 远端 H.264 第一帧已到达客户端")
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
    private val renderers = mutableMapOf<Int, EglRenderer>()
    private val windowSurfaces = mutableMapOf<Int, Surface>()
    private val windowVideos = mutableMapOf<Int, VideoTrack>()
    private val paneFrameSinks = mutableMapOf<Int, VideoSink>()
    private val hiddenPanes=mutableSetOf<Int>()
    private val panesWithFrames = mutableSetOf<Int>()
    private var screenTracks: Map<Int, Int> = emptyMap()
    private var windowPlan: List<UuWindowLayout.Pane?> = List(6) { null }
    private var pointerScreen: Int? = null
    private var viewerSelectedScreen: Int? = null
    private var controlInfo: ControlReply? = null
    private var controlChannel: DataChannel? = null
    private var textChannel: DataChannel? = null
    private val channels = mutableListOf<DataChannel>()
    private val videoTrackRouter = UuVideoTrackRouter<VideoTrack>()
    private var negotiatedVideoTracks: List<Int> = emptyList()
    private var videoMidIndices: Map<String, Int> = emptyMap()
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
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build())
        // WebRTC onTrack callbacks arrive on native worker threads without
        // necessarily having a prepared Looper.
        .setOnAudioFocusChangeListener(audioFocusListener, Handler(Looper.getMainLooper()))
        .build()
    private var pendingBinaryEvent: JSONObject? = null
    private var pendingBinaryName: String? = null
    private val captureGate = UuCaptureGate()
    private var nextRequestSeq = 2L
    private val pointerFlow = UuPointerFlow()
    private val signalGate = UuSignalGate()
    private val localIce = UuIceCandidateQueue<IceCandidate>()
    private val remoteIce = UuIceCandidateQueue<IceCandidate>()
    private val appControlId = UUID.randomUUID().toString()

    @Synchronized fun start(xrSurface: Surface?) {
        check(!started && !closed.get())
        started = true;live.add(this)
        if(xrSurface!=null) windowSurfaces[0] = xrSurface
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        localProfiles = runCatching { UuLocalDecoders.profiles() }.getOrDefault(localProfiles)
        remoteSettings.localCodecs = localProfiles.map { it.codec }.toSet()
        health.markStarted()
        heartbeatExecutor.scheduleAtFixedRate({
            synchronized(this) {
                if (closed.get()) return@synchronized
                if (heldKeys.isNotEmpty()) sendControl("{\"heartbeat\":\"kbd_heartbeat\"}".toByteArray(Charsets.UTF_8))
                diagnosticTicks++
                if (diagnosticTicks in listOf(3, 8, 15, 30) || diagnosticTicks>30 && diagnosticTicks%15==0) reportMediaStats()
                improvePortraitQuality()
                when (health.check()) {
                    UuSessionHealth.Warning.SIGNAL_TIMEOUT -> {
                        emit("UU 信令握手超过 30 秒，终止无效连接")
                        terminateUnexpectedly()
                    }
                    UuSessionHealth.Warning.ICE_TIMEOUT -> {
                        emit("UU WebRTC ICE 建立或断线恢复超时，终止失效连接")
                        terminateUnexpectedly()
                    }
                    UuSessionHealth.Warning.TRACK_ACK_TIMEOUT -> {
                        emit("UU 视频轨道注册 5 秒未收到远端确认")
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

    private fun emit(text: String) {
        if (!closed.get()) { diagnostics.record(text); events(text) }
    }

    private fun reportMediaStats() {
        peer?.getStats { report ->
            if (closed.get()) return@getStats
            val summaries = report.statsMap.values.sortedBy {if(it.type=="inbound-rtp" && it.members["kind"]=="audio") 0 else 1}.mapNotNull { stat ->
                val m = stat.members
                when (stat.type) {
                    "transport" -> "transport(dtls=${m["dtlsState"]},ice=${m["iceState"]},sent=${m["bytesSent"]},recv=${m["bytesReceived"]})"
                    "candidate-pair" -> if (m["nominated"] == true || m["state"] == "succeeded")
                        "pair(state=${m["state"]},nominated=${m["nominated"]},sent=${m["bytesSent"]},recv=${m["bytesReceived"]})" else null
                    "inbound-rtp" -> if(m["kind"]=="audio") UuAudioLatency.summary(m) else "rtp(kind=${m["kind"]},bytes=${m["bytesReceived"]},frames=${m["framesReceived"]},decoded=${m["framesDecoded"]})"
                    "data-channel" -> "sctp(state=${m["state"]},sent=${m["messagesSent"]},recv=${m["messagesReceived"]})"
                    else -> null
                }
            }
            emit("媒体诊断：" + summaries.joinToString("; ").take(1200))
        }
    }

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
                val (header, bytes) = UuWire.controlEvent(identity.deviceId, appControlId, localProfiles)
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
                    "forward_setting" -> {
                        val data = packet.payload?.optJSONObject("data")
                        if (data?.optString("type") == "device_capability") {
                            data.optJSONObject("device_capability")?.let { cap ->
                                capabilitySnapshot = cap
                                remoteSettings.receiveCapabilities(cap)
                                remoteSettings.profiles = remoteSettings.profiles.mapNotNull { p ->
                                    localProfiles.firstOrNull { it.codec == p.codec }?.let { local ->
                                        p.copy(width = minOf(p.width, local.width), height = minOf(p.height, local.height), fps = local.fps)
                                    }
                                }
                            }
                        }
                    }
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
                // Trickle candidates may arrive before the initial SDP.
                // Preserve that queue on first answer; only discard an
                // installed generation when the host explicitly restarts ICE.
                val restart = data.optString("type") == "restart_ice"
                if (remoteIce.isReady()) {
                    if (!restart) return // Duplicate answer, do not reset ICE.
                    remoteIce.resetForRenegotiation()
                }
                val acceptedTracks = UuWire.acceptedVideoTrackIndices(sdp,mediaRequired=!applicationsOnly)
                if (!applicationsOnly && acceptedTracks.isEmpty()) {
                    emit("UU SDP 拒绝全部视频轨道，无法接收远端画面")
                    terminateUnexpectedly()
                    return
                }
                videoMidIndices = UuWire.videoMidIndices(sdp)
                peer?.setRemoteDescription(sdpObserver(onSuccess = {
                    synchronized(this@UuDirectSession) {
                        if (closed.get()) return@synchronized
                        negotiatedVideoTracks = acceptedTracks
                        val audioDirection=microphoneTransceiver?.currentDirection
                        microphoneNegotiated=audioDirection==RtpTransceiver.RtpTransceiverDirection.SEND_RECV || audioDirection==RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                        if (!remoteIce.activate { peer?.addIceCandidate(it) == true }) {
                            emit("UU ICE 候选地址无法安装，已终止会话")
                            terminateUnexpectedly()
                            return@synchronized
                        }
                        maybeStartCapture()
                    }
                    emit("UU WebRTC SDP 已接受（${acceptedTracks.size} 条视频轨道）")
                }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "candidate" -> {
                val ice = data.optJSONObject("candidate") ?: return
                val candidate = ice.optString("candidate")
                val mid = ice.optString("sdpMid", "0")
                val index = ice.optInt("sdpMLineIndex", 0)
                if (candidate.length in 1..4096 && mid.length in 1..64 && index in 0..32) {
                    val next = IceCandidate(mid, index, candidate)
                    val key = "$mid:$index:$candidate"
                    if (!remoteIce.submit(key, next) { peer?.addIceCandidate(it) == true }) {
                        emit("UU 远端 ICE 候选异常或缓冲超限")
                        terminateUnexpectedly()
                    }
                }
            }
        }
    }

    private fun sdpObserver(
        onCreated: ((SessionDescription) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { postRtc {onCreated?.invoke(sdp)} }
        override fun onSetSuccess() { postRtc {onSuccess?.invoke()} }
        override fun onCreateFailure(error: String) { emit("SDP offer error") }
        override fun onSetFailure(error: String) { emit("SDP negotiation error") }
    }

    /** Secondary XR Surfaces may arrive before or after the signaling handshake. */
    /** Rebind a resized/recreated ordinary Android surface without rejoining UU. */
    fun attachMainSurface(surface: Surface) {replacePrimarySurface(surface)}

    fun detachMainSurface() {
        takeWindowRenderer(0)?.let {output -> rendererExecutor.execute {runCatching {output.release()}}}
    }

    private fun takeWindowRenderer(index: Int): EglRenderer?=synchronized(this) {
        windowSurfaces.remove(index)
        hiddenPanes.add(index)
        onWindowVisibility(index,false)
        renderers.remove(index)?.also {output -> windowVideos[index]?.removeSink(output)}
    }

    fun detachWindowSurface(index: Int) {takeWindowRenderer(index)?.release()}

    /** Spatial containers may recreate an Android Surface during system resize. */
    @Synchronized fun replacePrimarySurface(surface: Surface): Boolean {
        if(closed.get()) return false
        if(!started) {start(surface);return !closed.get()}
        if(windowSurfaces[0]===surface) return true
        windowSurfaces[0]=surface;hiddenPanes.remove(0)
        renderers.remove(0)?.let {old ->
            windowVideos[0]?.removeSink(old)
            rendererExecutor.execute {runCatching {old.release()}}
        }
        egl?.let {attachRenderer(0,surface,it)}
        return true
    }

    @Synchronized fun addWindowSurface(index: Int, xrSurface: Surface) {
        if (closed.get() || index !in 1..5) return
        if (windowSurfaces.containsKey(index)) return
        windowSurfaces[index] = xrSurface
        egl?.let { attachRenderer(index, xrSurface, it) }
        updateWindows()
    }

    private val rendererExecutor=Executors.newSingleThreadExecutor {task -> Thread(task,"OpenUUYC-render-setup").apply {isDaemon=true}}
    private val pendingRenderers=mutableSetOf<Int>()
    private fun attachRenderer(index: Int,xrSurface: Surface,base: EglBase) {
        if(closed.get() || renderers.containsKey(index) || !pendingRenderers.add(index)) return
        rendererExecutor.execute {
            var output: EglRenderer?=null
            var adopted=false
            try {
                val created=EglRenderer("OpenUUYC-PICO-pane-$index");output=created
                // EGL allocation waits for native threads. Never hold the session
                // monitor or run this on the Android menu/UI thread.
                created.init(base.eglBaseContext,
                    if(immersiveRendering) EglBase.CONFIG_RGBA else EglBase.CONFIG_PLAIN,
                    if(immersiveRendering) org.webrtc.OpenUuRoundedDrawer() else GlRectDrawer())
                created.setLayoutAspectRatio(0f) // full frame; OpenXR quad supplies true display aspect
                created.createEglSurface(xrSurface)
                synchronized(this) {
                    if(!closed.get() && egl===base && windowSurfaces[index]===xrSurface) {
                        renderers[index]=created
                        windowVideos[index]?.addSink(created)
                        adopted=true
                        onWindowVisibility(index,index !in hiddenPanes && index in panesWithFrames && windowVideos[index]!=null)
                    }
                }
            } catch(error: Exception) {emit("窗口 ${index+1} 渲染初始化失败：${error.javaClass.simpleName}")}
            finally {
                synchronized(this) {
                    pendingRenderers.remove(index)
                    val replacement=windowSurfaces[index]
                    if(!closed.get() && egl===base && replacement!=null && replacement!==xrSurface)
                        attachRenderer(index,replacement,base)
                }
                if(!adopted) runCatching {output?.release()}
            }
        }
    }

    /**
     * Do not bind late RTP callbacks to whichever pane they arrive on.
     * Always use the currently selected ScreenSources.video_track_index.
     */
    @Synchronized private fun updateWindows() {
        requestedScreen?.let { target ->
            val track=screenTracks[target]
            if(track!=null && track in negotiatedVideoTracks && videoTrackRouter.at(track)!=null) {
                viewerSelectedScreen=target;requestedScreen=null;remoteSettings.selected=target
                videoTrackRouter.select(track);emit("目标屏幕映射已就绪")
            }
        }
        if(viewerSelectedScreen!=null && (remoteSettings.screens.none { it.id==viewerSelectedScreen } || screenTracks[viewerSelectedScreen]==null)) {
            releaseInputs();viewerSelectedScreen=null;emit("所选显示器已停止或移除，已返回主屏")
        }
        remoteSettings.selected=selectedScreen()
        windowPlan = UuWindowLayout.plan(
            viewerSelectedScreen ?: captureGate.selectedScreen,
            viewerSelectedScreen?.let(screenTracks::get) ?: captureGate.selectedTrack,
            screenTracks, videoTrackRouter.availableIndices(), maxWindows=6
        )
        for (pane in 0..5) {
            val wanted = windowPlan[pane]?.track?.let(videoTrackRouter::at)
            val old = windowVideos[pane]
            if (wanted !== old) {
                renderers[pane]?.let { old?.removeSink(it) }
                paneFrameSinks.remove(pane)?.let { old?.removeSink(it) }
                panesWithFrames.remove(pane)
                if (pane == 0) old?.removeSink(firstFrameSink)
                if (wanted == null) windowVideos.remove(pane) else windowVideos[pane] = wanted
                renderers[pane]?.let { sink -> wanted?.addSink(sink) }
                if (wanted != null) {
                    val delivered = AtomicBoolean(false)
                    var lastSize: Pair<Int,Int>?=null
                    val frameSink = VideoSink { frame ->
                        val size=frame.rotatedWidth to frame.rotatedHeight
                        if(size!=lastSize) {
                            lastSize=size
                            cleanupHandler.post { synchronized(this@UuDirectSession) {
                                if(!closed.get() && windowVideos[pane]===wanted) {
                                    val id=windowPlan.getOrNull(pane)?.screen
                                    if(id!=null) decodedScreenSizes[id]=size
                                    emit("视频窗口 ${pane+1} 解码尺寸 ${size.first}×${size.second}")
                                    val name=remoteSettings.screens.firstOrNull {it.id==id}?.toString() ?: "屏幕 ${id ?: "?"}"
                                    windowInfoListener?.invoke(pane,id,name,size.first,size.second)
                                }
                            } }
                        }
                        if(delivered.compareAndSet(false,true)) cleanupHandler.post {
                            synchronized(this@UuDirectSession) {
                                if(!closed.get() && windowVideos[pane] === wanted) {
                                    panesWithFrames.add(pane)
                                    onWindowVisibility(pane,pane !in hiddenPanes && renderers.containsKey(pane))
                                }
                            }
                        }
                    }
                    paneFrameSinks[pane]=frameSink;wanted.addSink(frameSink)
                }
                if (pane == 0) wanted?.addSink(firstFrameSink)
            }
            onWindowVisibility(pane, pane !in hiddenPanes && pane in panesWithFrames && wanted != null && renderers.containsKey(pane))
        }
        publishWindowInfo()
    }

    @Synchronized private fun createPeer(info: ControlReply) {
        // A Java SecurityException from NetworkMonitor.startMonitoring crosses
        // a native JNI callback and becomes SIGABRT, not a catchable PC error.
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            emit("缺少网络状态权限，无法启动串流；请更新安装包")
            terminateUnexpectedly()
            return
        }
        health.markPeerCreated()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val localEgl = EglBase.create()
        val decoder = org.webrtc.DefaultVideoDecoderFactory(localEgl.eglBaseContext)
        remoteSettings.localCodecs = localProfiles.map { it.codec }.toSet()
        egl = localEgl
        windowSurfaces.forEach { (index, xrSurface) -> attachRenderer(index, xrSurface, localEgl) }
        updateWindows()
        val engine=if(applicationsOnly) PeerConnectionFactory.builder().createPeerConnectionFactory() else {
        val adm=org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseLowLatency(true)
            .setUseStereoOutput(true)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioRecordErrorCallback(object: org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback {
                private fun fail() {audioModule?.setMicrophoneMute(true);micState.stop();postRtc {updateMicrophone()};emit("本机麦克风录音失败，已关闭回传")}
                override fun onWebRtcAudioRecordInitError(error: String) {fail()}
                override fun onWebRtcAudioRecordStartError(code: org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode,error: String) {fail()}
                override fun onWebRtcAudioRecordError(error: String) {fail()}
            }).createAudioDeviceModule()
        adm.setMicrophoneMute(true);audioModule=adm
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoDecoderFactory(decoder)
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(localEgl.eglBaseContext, false, true))
            .createPeerConnectionFactory()
        }
        factory = engine
        val config = PeerConnection.RTCConfiguration(info.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .setUsername(server.username).setPassword(server.credential).createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            audioJitterBufferMaxPackets=8
            audioJitterBufferFastAccelerate=true
        }
        peer = engine.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) { postRtc {
                if (closed.get()) return@postRtc
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) {
                    health.markIceConnected()
                    emit("UU P2P/中转 WebRTC 已连接")
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    health.markIceDisconnected()
                    emit("UU ICE 连接暂时断开，等待 15 秒内恢复")
                } else if (state == PeerConnection.IceConnectionState.FAILED ||
                           state == PeerConnection.IceConnectionState.CLOSED) {
                    if (!closed.get()) {
                        emit("UU WebRTC ICE 连接失败或已关闭")
                        terminateUnexpectedly()
                    }
                }
            } }
            override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) { postRtc {
                synchronized(this@UuDirectSession) {
                    if (closed.get()) return@synchronized
                    val mid = candidate.sdpMid ?: "0"
                    val key = "$mid:${candidate.sdpMLineIndex}:${candidate.sdp}"
                    if (!localIce.submit(key, candidate) {
                            socket?.send(UuWire.candidate(info, it.sdp, it.sdpMid ?: "0", it.sdpMLineIndex)) == true
                        }) {
                        emit("UU 本地 ICE 候选发送失败或缓冲超限")
                        terminateUnexpectedly()
                    }
                }
            } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel) { postRtc { if (!closed.get()) listenChannel(channel) } }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { postRtc {
                if (closed.get()) return@postRtc
                attachVideo(receiver?.track())
                attachAudio(receiver?.track())
            } }
            override fun onTrack(transceiver: RtpTransceiver?) { postRtc {
                if (closed.get()) return@postRtc
                attachVideo(transceiver?.receiver?.track(), transceiver?.mid)
                attachAudio(transceiver?.receiver?.track())
            } }
        })
        val connection = peer ?: throw IllegalStateException("createPeerConnection failed")
        val options = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        // UU desktop controller offers audio_0 followed by five independent
        // recvonly video m-lines (video_0..video_4); one line is insufficient
        // for desktop/auxiliary video track selection.
        // Reserve an Opus sender without creating an AudioSource/AudioRecord.
        // Real recording is attached only after permission + remote policy gates.
        if(!applicationsOnly) {
        microphoneTransceiver=connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        microphoneTransceiver?.sender?.setStreams(listOf("audio_0"))
        repeat(5) {
            connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, options)
        }
        }
        for (label in listOf(
            "CONTROL_DATA_CHANNEL", "TEXT_DATA_CHANNEL", "STREAMER_DATA_CHANNEL",
            "FILE_DATA_CHANNEL", "BINARY_DATA_CHANNEL", UuQuickApps.CHANNEL, UuPcvrSession.CONTROL, UuPcvrSession.TRACKING, UuStereoFrames.CHANNEL
        ).filter { !applicationsOnly || it !in setOf("STREAMER_DATA_CHANNEL",UuPcvrSession.CONTROL,UuPcvrSession.TRACKING,UuStereoFrames.CHANNEL) }) {
            val channel = connection.createDataChannel(label, DataChannel.Init().apply { ordered = label !in setOf(UuPcvrSession.TRACKING,UuStereoFrames.CHANNEL); if(!ordered) maxRetransmits=0 })
            if (channel != null) {
                channels.add(channel)
                listenChannel(channel)
            }
        }
        connection.createOffer(sdpObserver(onCreated = offer@{ generated ->
            val desc=SessionDescription(generated.type,UuAudioOffer.lowLatency(generated.description))
            val mediaLines=generated.description.lineSequence().count {it.startsWith("m=audio ") || it.startsWith("m=video ")}
            emit("连接用途：${if(applicationsOnly) "应用/文件纯数据" else "桌面串流"}，音视频 m-line=$mediaLines")
            if(applicationsOnly && mediaLines!=0) {emit("纯数据连接出现媒体轨道，已中止");terminateUnexpectedly();return@offer}

            connection.setLocalDescription(sdpObserver(onSuccess = {
                synchronized(this@UuDirectSession) {
                    if (closed.get()) return@synchronized
                    val wireSdp = UuWire.uuOfferSdp(desc.description,if(applicationsOnly) "*" else "audio_0")
                    val zip = UuWire.gzip(wireSdp.toByteArray(StandardCharsets.UTF_8))
                    val ws = socket
                    if (ws == null || !ws.send(UuWire.offerHeader(info)) ||
                        !ws.send(ByteString.of(*UuWire.attachment(zip))) ||
                        !localIce.activate {
                            ws.send(UuWire.candidate(info, it.sdp, it.sdpMid ?: "0", it.sdpMLineIndex))
                        }) {
                        emit("UU SDP/ICE 信令发送失败，已终止会话")
                        terminateUnexpectedly()
                        return@synchronized
                    }
                    emit("UU WebRTC offer 和有序 ICE 候选已提交")
                }
            }), desc)
        }), MediaConstraints())
    }

    private fun attachVideo(track: MediaStreamTrack?, mid: String? = null) {
        if (track !is VideoTrack) return
        synchronized(this) {
            if (closed.get()) return
            val mapped = mid?.let(videoMidIndices::get)
            if (mapped != null) {
                videoTrackRouter.observedAt(mapped, track)
                emit("视频轨道已绑定：index=$mapped，按 SDP MID 映射")
            } else videoTrackRouter.observed(track.id(), track)
            updateWindows()
        }
    }

    private fun attachAudio(track: MediaStreamTrack?) {
        if (track !is AudioTrack) return
        if(applicationsOnly) {track.setEnabled(false);return}
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

    @Synchronized private fun listenChannel(channel: DataChannel) {
        if (closed.get()) return
        if (!channels.contains(channel)) channels.add(channel)
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() { postRtc {
                if (closed.get()) return@postRtc
                synchronized(this@UuDirectSession) {
                    if (closed.get()) return@postRtc
                    val label = channel.label().takeIf { it in setOf("CONTROL_DATA_CHANNEL", "TEXT_DATA_CHANNEL", "STREAMER_DATA_CHANNEL", "FILE_DATA_CHANNEL", "BINARY_DATA_CHANNEL",UuQuickApps.CHANNEL,UuPcvrSession.CONTROL,UuPcvrSession.TRACKING,UuStereoFrames.CHANNEL) } ?: "other"
                    emit("数据通道 $label：${channel.state()}")
                    if (channel.state() == DataChannel.State.OPEN) {
                        when (channel.label()) {
                            UuQuickApps.CHANNEL -> quickApps.bind(channel)
                            UuPcvrSession.CONTROL,UuPcvrSession.TRACKING -> vr.bind(channel)
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
                        if(channel.label()==UuQuickApps.CHANNEL) quickApps.close()
                        if(channel.label() in setOf(UuPcvrSession.CONTROL,UuPcvrSession.TRACKING,UuStereoFrames.CHANNEL)) vr.close()
                        val critical = channel === controlChannel || channel === textChannel
                        if (critical && !closed.get()) {
                            // Revoke the button while CONTROL is still writable.
                            if (pointerFlow.pressed && controlChannel?.state() == DataChannel.State.OPEN) {
                                pointerFlow.release(::sendControl)
                            }
                            captureGate.onTransportClosed()
                            pointerFlow.reset()
                            health.markTrackRegistrationFinished()
                        }
                        if (channel === controlChannel) controlChannel = null
                        if (channel === textChannel) textChannel = null
                        if (critical && !closed.get()) {
                            emit("UU 必需的控制数据通道已关闭")
                            terminateUnexpectedly()
                        }
                    }
                }
            } }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if(closed.get() || buffer.data.remaining()>65536) return
                val data=buffer.data.slice()
                val bytes=ByteArray(data.remaining());data.get(bytes)
                postRtc {
                synchronized(this@UuDirectSession) {
                    if (closed.get()) return@postRtc
                    if(channel.label()==UuStereoFrames.CHANNEL) {vr.video(bytes);return@postRtc}
                    if(channel.label()==UuPcvrSession.CONTROL) {
                        vr.receive(bytes)
                        if(vr.status!=lastVrStatus && (!vr.active || !lastVrActive)) emit("PCVR：${vr.status}")
                        lastVrStatus=vr.status;lastVrActive=vr.active;return@postRtc
                    }
                    if(channel.label()==UuQuickApps.CHANNEL) {quickApps.receive(bytes);return@postRtc}
                    if(channel.label() in setOf("TEXT_DATA_CHANNEL","FILE_DATA_CHANNEL") && transfers.receive(bytes)) return@postRtc
                    val isControl = channel.label() == "CONTROL_DATA_CHANNEL"
                    if (!isControl && channel.label() != "TEXT_DATA_CHANNEL") return@postRtc
                    if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return@postRtc
                    try {
                        if(files.receive(bytes)) return@postRtc
                        if(clipboardWire.receive(bytes,clipboardBinding.enabled && appFocused.get() && inputReady())) return@postRtc
                        val payload = UuWire.decodeEnvelope(bytes)
                        payload.microphoneAck?.let {(id,code) -> micState.response(id,code);updateMicrophone();if(code!=0) emit("远端麦克风策略被拒绝：$code")}
                        payload.simpleAction?.let {action -> if(action in setOf(23,24,27)) {micState.event(action);updateMicrophone()}}
                        payload.captureLevel?.let { remoteSettings.captureLevel = it }
                        if (payload.screens.isNotEmpty()) {
                            remoteSettings.screens = payload.screens
                            if (topologyPending != null && payload.screens.any { it.id !in topologyOriginal && it.screenType == 1 }) {
                                topologyPending=null; emit("远端已报告新增虚拟显示器")
                            }
                        }
                        payload.topologyAck?.let { (id, code) ->
                            if (topologyPending == id) {
                                if (code != 0) { topologyPending=null; emit("虚拟显示器创建失败，错误码 $code") }
                                else emit("远端已受理创建，等待实际显示状态")
                            }
                        }
                        payload.cursorType?.let { kind ->
                            textCursorVisible = kind == 32513 && payload.cursorScreen == selectedScreen()
                            textCursorListener?.invoke(textCursorVisible)
                        }
                        payload.captureAck?.let { id ->
                            val accepted=remoteSettings.pending?.takeIf {it.first==id}?.second
                            val target=pendingSettingsScreen?.takeIf {it.first==id}?.second
                            remoteSettings.confirm(id, payload.captureErrors)?.let { success ->
                                if(success && accepted!=null) {
                                    if(target!=null) screenSettings[target]=accepted else screenSettings.keys.toList().forEach {screenSettings[it]=accepted}
                                }
                                pendingSettingsScreen=null
                                emit(if (success) "远端已确认串流设置" else "远端拒绝部分设置，错误码 ${payload.captureErrors.joinToString()}")
                            }
                        }
                        val beforeScreen = captureGate.selectedScreen
                        val beforeTrack = captureGate.selectedTrack
                        val beforeSources = screenTracks
                        if (isControl && payload.echoRequest) {
                            sendControl(UuWire.echoResponse(payload.seq, payload.timestamp))
                        }
                        val acknowledgment = captureGate.observe(payload, isControl)
                        if (payload.screenTracks.isNotEmpty()) screenTracks = payload.screenTracks
                        remoteSettings.selected = selectedScreen()
                        requestedScreen?.let { target ->
                            val track=screenTracks[target]
                            if(track!=null && track in negotiatedVideoTracks && videoTrackRouter.at(track)!=null) {
                                viewerSelectedScreen=target;requestedScreen=null;remoteSettings.selected=target
                                videoTrackRouter.select(track);updateWindows();emit("目标屏幕视频映射已就绪")
                            }
                        }
                        if (beforeScreen != captureGate.selectedScreen ||
                            beforeTrack != captureGate.selectedTrack || beforeSources != screenTracks) {
                            health.markTrackRegistrationFinished()
                            videoTrackRouter.select(captureGate.selectedTrack)
                            updateWindows()
                        }
                        // A host display/track change revokes the held drag. Do
                        // not allow a fresh DOWN until the old UP was sent.
                        if ((heldKeys.isNotEmpty() || touchActive) && beforeScreen != captureGate.selectedScreen) releaseInputs()
                        if (pointerFlow.pressed && (beforeScreen != captureGate.selectedScreen ||
                                beforeTrack != captureGate.selectedTrack || beforeSources != screenTracks)) {
                            if (!pointerFlow.release(::sendControl)) {
                                emit("UU 显示器切换时按键释放失败，已断开远控")
                                terminateUnexpectedly()
                                return@postRtc
                            }
                        }
                        when (acknowledgment) {
                            UuCaptureGate.Ack.REGISTERED -> {
                                health.markTrackRegistrationFinished()
                                emit("UU 视频轨道注册确认成功")
                            }
                            UuCaptureGate.Ack.REJECTED -> {
                                health.markTrackRegistrationFinished()
                                emit("UU 视频轨道注册被拒绝（错误码 ${payload.trackAckError}）")
                                terminateUnexpectedly()
                                return@postRtc
                            }
                            UuCaptureGate.Ack.NONE -> {}
                        }
                        if (captureGate.pbConnected) echoRetry?.cancel(false)
                        if (beforeScreen != captureGate.selectedScreen || beforeTrack != captureGate.selectedTrack)
                            emit("屏幕映射已更新：screen=${captureGate.selectedScreen},track=${captureGate.selectedTrack}")
                        maybeStartCapture()
                    } catch (_: IllegalArgumentException) {
                        // Vendor extensions may be opaque; never reflect them back.
                    }
                }

                }
            }
        })
    }

    /** Official desktop PB bootstrap retransmits ECHO every 500ms (at most 30). */
    @Synchronized private fun startPbHandshake() {
        emit("控制通道已打开，开始 UU PB 握手")
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
                    terminateUnexpectedly()
                    return@synchronized
                }
                echoAttempts++
                sendControl(UuWire.echoRequest())
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun maybeStartCapture() {
        if(applicationsOnly) return
        if (negotiatedVideoTracks.isEmpty()) return
        val controlOpen = controlChannel?.state() == DataChannel.State.OPEN
        val textOpen = textChannel?.state() == DataChannel.State.OPEN
        val action = captureGate.next(controlOpen, textOpen)
        if (action == UuCaptureGate.Action.NONE) return
        val text = textChannel ?: return
        val sequence = nextRequestSeq++
        val packet = when (action) {
            UuCaptureGate.Action.REGISTER_TRACK ->
                UuWire.registerVideoTracks(sequence, negotiatedVideoTracks)
            UuCaptureGate.Action.START_VIDEO ->
                UuWire.startVideoCapture(sequence, captureGate.selectedScreen ?: return)
            UuCaptureGate.Action.CAPTURE_SETTING -> UuWire.captureSetting(sequence, remoteSettings.current)
            UuCaptureGate.Action.NONE -> return
        }
        if (text.send(DataChannel.Buffer(ByteBuffer.wrap(packet), false))) {
            captureGate.onSubmitted(action, sequence)
            if (action == UuCaptureGate.Action.REGISTER_TRACK) {
                health.markTrackRegistrationRequested()
            }
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

    @Synchronized fun pointer(pane: Int, u: Float, v: Float, down: Boolean) {
        if (closed.get()) return
        val target = windowPlan.getOrNull(pane)
        val screen = target?.screen
        // Side panes without a validated ScreenSources mapping remain view-only.
        val mapped = pane == 0 || (screen != null && screenTracks[screen] == target?.track)
        val ready = requestedScreen == null && pane in panesWithFrames && mapped && captureGate.inputReady(
            controlChannel?.state() == DataChannel.State.OPEN,
            textChannel?.state() == DataChannel.State.OPEN
        )
        if (pointerScreen != screen && pointerFlow.pressed && !pointerFlow.release(::sendControl)) {
            emit("UU 跨窗口释放远端按键失败")
            terminateUnexpectedly()
            return
        }
        pointerScreen = screen
        if (pointerFlow.pointer(screen, u, v, down, ready, ::sendControl) ==
            UuPointerFlow.Result.RELEASE_FAILED) {
            emit("UU 鼠标释放发送失败，停止远程控制以防持续拖拽")
            terminateUnexpectedly()
        }
    }

    private fun inputReady() = !closed.get() && requestedScreen == null && captureGate.inputReady(
        controlChannel?.state() == DataChannel.State.OPEN, textChannel?.state() == DataChannel.State.OPEN)

    @Synchronized fun text(value: String): Boolean {
        if (!inputReady() || value.toByteArray(Charsets.UTF_8).size > 64 * 1024) return false
        return sendControl(UuInputWire.text(value))
    }
    @Synchronized fun key(vk: Int, down: Boolean): Boolean {
        if (vk !in 1..254 || (!inputReady() && down)) return false
        if (!sendControl(UuInputWire.key(vk, down))) {
            if (!down && vk in heldKeys) terminateUnexpectedly()
            return false
        }
        if (down) heldKeys.add(vk) else heldKeys.remove(vk)
        return true
    }
    @Synchronized fun pointerAsTouch(pane: Int,u: Float,v: Float,down: Boolean) {
        if(!down) { if(touchActive) touch(3,emptyList());return }
        if(!inputReady() || pane !in panesWithFrames || !UuPointerWire.valid(u,v)) return
        val target=windowPlan.getOrNull(pane) ?: return
        val screen=target.screen ?: return
        if(screenTracks[screen]!=target.track) return
        if(!touchActive && !sendControl(UuPointerWire.move(screen,u,v))) return
        val kind=if(touchActive) 2 else 1
        if(sendControl(UuInputWire.touch(kind,listOf(UuInputWire.Contact(0,u,v))))) touchActive=true
    }

    @Synchronized fun touch(kind: Int, points: List<UuInputWire.Contact>): Boolean {
        if (!inputReady() && kind < 3) return false
        if(kind==1 && !touchActive) {
            val first=points.firstOrNull() ?: return false
            val screen=selectedScreen() ?: return false
            // Native UU touch has no screen ID. Select its validated target via
            // the existing absolute-position contract on the same ordered channel.
            if(!sendControl(UuPointerWire.move(screen,first.x,first.y))) return false
        }
        val sent = sendControl(UuInputWire.touch(kind, points))
        if (sent) touchActive = kind < 3
        else if (touchActive && kind >= 3) terminateUnexpectedly()
        return sent
    }
    @Synchronized fun releaseInputs() {
        heldKeys.toList().forEach { key(it, false) }
        if (touchActive) touch(4, emptyList())
        pointerFlow.release(::sendControl)
    }
    @Synchronized fun screenSnapshot() = remoteSettings.screens.toList()
    @Synchronized fun selectedScreen() = viewerSelectedScreen ?: captureGate.selectedScreen
    @Synchronized fun settingsSnapshot(target: Int? = null): UuRemoteSettingsState = UuRemoteSettingsState().also {
        it.screens = remoteSettings.screens.toList(); it.selected = target ?: selectedScreen()
        it.captureLevel = remoteSettings.captureLevel; it.profiles = remoteSettings.profiles.toList()
        it.localCodecs = remoteSettings.localCodecs; it.current = screenSettings[target ?: selectedScreen()] ?: remoteSettings.current; it.pending = remoteSettings.pending
    }
    @Synchronized fun publishWindowInfo() {
        windowPlan.forEachIndexed {pane,window ->
            window?.screen?.let {id -> remoteSettings.screens.firstOrNull {it.id==id}?.let {screen ->
                windowInfoListener?.invoke(pane,id,screen.toString(),screen.current?.width ?: 0,screen.current?.height ?: 0)
            } }
        }
    }
    @Synchronized fun windowScreen(pane: Int)=windowPlan.getOrNull(pane)?.screen
    @Synchronized fun closeWindow(pane: Int) { if(pane in 0..5) {releaseInputs();hiddenPanes.add(pane);onWindowVisibility(pane,false)} }
    @Synchronized fun showAllWindows() {hiddenPanes.clear();updateWindows()}
    @Synchronized fun switchScreen(screen: Int): Boolean {
        if (closed.get() || !captureGate.inputReady(controlChannel?.state()==DataChannel.State.OPEN,textChannel?.state()==DataChannel.State.OPEN) ||
            remoteSettings.screens.none { it.id==screen }) return false
        releaseInputs()
        // Like desktop ScreenPlayback: select an already registered stream locally;
        // SimpleAction 8 only resumes capture when that display has no active track.
        val track=screenTracks[screen]
        if(track!=null && videoTrackRouter.at(track)!=null && track in negotiatedVideoTracks) {
            hiddenPanes.remove(0);viewerSelectedScreen=screen;requestedScreen=null;remoteSettings.selected=screen
            videoTrackRouter.select(track);updateWindows()
            emit("已切换到 ${remoteSettings.screens.first { it.id==screen }}")
            return true
        }
        val packet=UuWire.startVideoCapture(nextRequestSeq++,screen)
        if(textChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(packet),false))!=true) return false
        requestedScreen=screen
        cleanupHandler.postDelayed({ synchronized(this) {
            if(!closed.get() && requestedScreen==screen) {
                emit("目标屏幕未返回可用视频轨；控制已暂停，可在屏幕菜单重选原屏幕")
            }
        } },12000)
        emit("已请求启动目标屏幕，等待实际视频映射")
        return true
    }
    @Synchronized fun createVirtualDisplay(width: Int = 1920, height: Int = 1080): Boolean {
        if (!inputReady()) return false
        require(remoteSettings.captureLevel >= 5) { "对端未开放虚拟显示器功能" }
        require(topologyPending == null) { "上一次虚拟屏创建仍在等待确认" }
        require(remoteSettings.screens.size < 5 && remoteSettings.screens.count { it.screenType == 1 } < 3) { "已达到远端虚拟屏数量上限" }
        require(remoteSettings.screens.none { it.screenType == 2 }) { "请先退出超级屏" }
        val id = nextRequestSeq++
        val packet = UuWire.createVirtualDisplay(id, width, height)
        val sent = textChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(packet), false)) == true
        if (sent) {
            topologyPending=id; topologyOriginal=remoteSettings.screens.map { it.id }.toSet()
            emit("已请求创建虚拟显示器；以远端返回的屏幕列表为准")
            cleanupHandler.postDelayed({ synchronized(this) {
                if (!closed.get() && topologyPending==id) {
                    topologyPending=null; emit("虚拟屏创建等待超时；请检查实际屏幕列表后重试")
                }
            } }, 10000)
        }
        return sent
    }

    @Synchronized fun applySettings(settings: UuCaptureSettings, physical: UuDisplayMode? = null, dpi: Int = 0, targetScreen: Int? = null,automatic: Boolean=false): Boolean {
        if (!inputReady()) return false
        if(targetScreen==null) remoteSettings.validate(settings) else {
            require(remoteSettings.screens.any {it.id==targetScreen}) {"目标屏幕已移除"}
            settingsSnapshot().apply {selected=targetScreen}.validate(settings)
        }
        val screen = remoteSettings.screens.firstOrNull { it.id == (targetScreen ?: selectedScreen()) }
        if (physical != null || dpi != 0) {
            require(remoteSettings.captureLevel >= 5) { "对端未开放物理屏幕参数修改" }
            require(physical == null || physical in screen?.modes.orEmpty())
            require(dpi == 0 || dpi in screen?.dpis.orEmpty())
        }
        val sequence = nextRequestSeq++
        val packet = UuWire.captureSetting(sequence, settings,
            targetScreen ?: if (physical != null || dpi != 0) selectedScreen() ?: return false else -2, physical, dpi)
        if (textChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(packet), false)) != true) return false
        if(!automatic) userConfiguredScreens.add(targetScreen ?: -2)
        pendingSettingsScreen=sequence to targetScreen
        remoteSettings.pending = sequence to settings
        cleanupHandler.postDelayed({ synchronized(this) {
            if (!closed.get() && remoteSettings.pending?.first == sequence) {
                remoteSettings.pending = null; emit("设置确认超时；未将请求标为成功，请刷新后重试")
            }
        } }, 6000)
        emit("设置已发送，等待远端确认")
        return true
    }

    @Synchronized fun scroll(delta: Int) {
        if (closed.get() || !captureGate.inputReady(
            controlChannel?.state() == DataChannel.State.OPEN,
            textChannel?.state() == DataChannel.State.OPEN
        )) return
        if (delta !in -1200..1200 || delta == 0) return
        sendControl(UuPointerWire.scroll(delta))
    }

    override fun close() {
        // PeerConnection.dispose must not run on Observer/DataChannel callbacks.
        // Mark closed synchronously, then release on the Android main queue.
        audioModule?.setMicrophoneMute(true);micState.stop()
        clipboardBinding.enable(false);transfers.close();files.close();quickApps.close();vr.close();live.remove(this)
        deferredClose.request { rtcCallbacks.close();disposeAfterCallbacks() }
    }

    private fun disposeAfterCallbacks() {
        val actions = synchronized(this) {
            val cleanup = mutableListOf<() -> Unit>()
            videoSizeListener = null
            windowInfoListener=null
            val namespaceJoined=signalGate.phase != UuSignalGate.Phase.WAIT_NAMESPACE && signalGate.phase != UuSignalGate.Phase.CLOSED
            signalGate.close()
            pendingBinaryEvent = null
            pendingBinaryName = null
            echoRetry?.cancel(false)
            heartbeatExecutor.shutdownNow()
            // Build the final release without calling JNI under our monitor.
            val releasePackets = mutableListOf<ByteArray>()
            heldKeys.forEach { releasePackets.add(UuInputWire.key(it, false)) }; heldKeys.clear()
            if (touchActive) releasePackets.add(UuInputWire.touch(4, emptyList()))
            touchActive = false
            textCursorListener = null
            pointerFlow.release { releasePackets.add(it); true }
            pointerFlow.reset()
            val control = controlChannel
            cleanup.add {
                if (control?.state() == DataChannel.State.OPEN) {
                    releasePackets.forEach { control.send(DataChannel.Buffer(ByteBuffer.wrap(it), true)) }
                }
            }
            val signaling=socket
            socket=null
            cleanup.add {
                if(signaling!=null) {
                    UuSocketIoWire.closeFrames(namespaceJoined).forEach { signaling.send(it) }
                    signaling.close(1000,"session ended")
                    // WebSocket.close drains queued leave/namespace frames. Do not
                    // cancelAll here: that would turn a graceful exit into an abort.
                    cleanupHandler.postDelayed({ signaling.cancel(); http.dispatcher.executorService.shutdown() },2000)
                } else http.dispatcher.executorService.shutdown()
            }
            captureGate.onTransportClosed()
            localIce.close()
            remoteIce.close()
            for ((index, track) in windowVideos) {
                val sink = renderers[index]
                val frameSink=paneFrameSinks[index]
                cleanup.add {
                    sink?.let(track::removeSink)
                    frameSink?.let(track::removeSink)
                    if (index == 0) track.removeSink(firstFrameSink)
                    onWindowVisibility(index, false)
                }
            }
            windowVideos.clear()
            paneFrameSinks.clear();panesWithFrames.clear()
            windowSurfaces.clear()
            windowPlan = List(6) { null }
            screenTracks = emptyMap()
            pointerScreen = null
            videoTrackRouter.reset()
            negotiatedVideoTracks = emptyList()
            videoMidIndices = emptyMap()
            val audio = remoteAudio
            cleanup.add { audio?.setEnabled(false) }
            remoteAudio = null
            if (audioFocusRequested) cleanup.add { audioManager.abandonAudioFocusRequest(audioFocusRequest); Unit }
            audioFocusRequested = false
            audioFocusHeld = false
            val closingChannels = channels.toList()
            channels.clear()
            controlChannel = null
            textChannel = null
            cleanup.add {
                closingChannels.forEach { channel ->
                    runCatching { channel.unregisterObserver() }
                    runCatching { channel.close() }
                    runCatching { channel.dispose() }
                }
            }
            val micTrack=microphoneTrack;val micSource=microphoneSource;val micSender=microphoneTransceiver?.sender
            microphoneTrack=null;microphoneSource=null;microphoneActive=false;microphoneNegotiated=false;microphoneTransceiver=null
            cleanup.add {runCatching {micSender?.setTrack(null,false)};runCatching {micTrack?.dispose()};runCatching {micSource?.dispose()}}
            val connection = peer
            peer = null
            cleanup.add { connection?.close(); connection?.dispose() }
            val outputs = renderers.values.toList()
            renderers.clear()
            cleanup.add { outputs.forEach { it.release() } }
            val base = egl
            egl = null
            cleanup.add { base?.release() }
            val engine = factory
            factory = null
            cleanup.add { engine?.dispose() }
            val adm=audioModule;audioModule=null;cleanup.add {adm?.release()}
            cleanup
        }
        // JNI operations may wait for network/signaling callbacks; never hold
        // the session monitor while waiting on these native threads.
        // The first action emits all held-key/button/touch releases. Give the
        // SCTP queue a bounded drain interval before closing its data channels.
        actions.firstOrNull()?.let { runCatching(it) }
        cleanupHandler.postDelayed({
            rendererExecutor.execute {
                actions.drop(1).forEach {action -> runCatching(action)}
                cleanupHandler.post {onDisposed?.invoke();onDisposed=null}
            }
            rendererExecutor.shutdown()
        },120)
        // Surface lifetime remains owned by the Android XR Activity.
    }
}

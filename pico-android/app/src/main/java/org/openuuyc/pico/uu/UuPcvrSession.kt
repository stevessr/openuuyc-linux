package org.openuuyc.pico.uu

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** Built-in tracking bridge. Explicit consent, bounded queues, one PICO owner. */
internal class UuPcvrSession {
    companion object {
        const val CONTROL="OPENUUYC_VR_CONTROL_V1"
        const val TRACKING="OPENUUYC_VR_TRACKING_V1"
        private val owner=AtomicReference<UuPcvrSession?>()
        fun stereoTarget()=owner.get()
    }
    private var eyeCalibration: JSONObject?=null
    @Synchronized fun calibration(width: Int,height: Int,periodNs: Long,views: FloatArray,stage:FloatArray?=null) {
        if(width<64 || height<64 || periodNs<=0 || views.size!=22 || views.any {!it.isFinite()}) return
        val array=org.json.JSONArray();views.forEach {array.put(it.toDouble())}
        eyeCalibration=JSONObject().put("eye_width",width).put("eye_height",height).put("refresh_hz",1e9/periodNs.toDouble()).put("views",array).also {calibration->
            if(stage!=null && stage.size==7 && stage.all {it.isFinite()})calibration.put("stage",org.json.JSONArray().also {data->stage.forEach {data.put(it.toDouble())}})
        }
    }
    private val stereoFrames=UuStereoFrames()
    @Volatile private var stereoDecoder: UuStereoDecoder?=null
    @Volatile private var stereoGeneration=0
    @Volatile private var stereoWidth=0
    @Volatile private var stereoHeight=0
    @Volatile private var stereoCodec=0
    private var stereoNative: ((Int,Int,Int)->Unit)?=null
    private var localFailure:String?=null
    private var controlGeneration=0
    private var hapticNative:((Int,Float,Float,Int)->Unit)?=null
    private var stereoRendered: ((Int,Long,FloatArray)->Unit)?=null
    private val decodedFrames=java.util.concurrent.atomic.AtomicLong(0)
    private val lastDecoded=java.util.concurrent.atomic.AtomicLong(0)
    private var lastKeyframeRequest=0L
    fun setStereoCallbacks(format: (Int,Int,Int)->Unit,frame: (Int,Long,FloatArray)->Unit,haptics:((Int,Float,Float,Int)->Unit)?=null) {stereoNative=format;stereoRendered=frame;hapticNative=haptics}
    @Synchronized fun stereoSurfaceFailed(generation:Int,code:Int) {
        if(owner.get()!==this || generation!=stereoGeneration) return
        request(false)
        localFailure="OpenXR 双眼 Surface 创建失败（$code）；VR 已停止，可重试连接"
        status=localFailure!!
    }
    fun stereoSurface(surface: android.view.Surface?,width: Int,height: Int) {
        stereoDecoder?.surface(surface,width,height,if(stereoCodec==1) "video/avc" else "video/hevc")
        synchronized(this) {
            if(surface!=null && stereoDecoder?.isReady()==true) {acknowledgeStereo();requestKeyframe()}
        }
    }
    private fun requestKeyframe() {
        synchronized(stereoFrames) {stereoFrames.lost()}
        val now=android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if(!active || now-lastKeyframeRequest<500) return
            lastKeyframeRequest=now
            control?.send(DataChannel.Buffer(ByteBuffer.wrap(JSONObject().put("id",0).put("op","video-keyframe").toString().toByteArray()),false))
        }
    }
    fun video(bytes: ByteArray) {
        if(!active || stereoGeneration==0) return
        val frame=runCatching {synchronized(stereoFrames) {stereoFrames.accept(bytes,android.os.SystemClock.elapsedRealtime())}}.getOrElse {requestKeyframe();return}
        if(frame!=null) {
            if(frame.generation!=stereoGeneration || frame.width!=stereoWidth || frame.height!=stereoHeight || frame.codec!=stereoCodec) {requestKeyframe();return}
            stereoDecoder?.frame(frame)
        }
    }
    private fun stopStereo() {
        decodedFrames.set(0);lastDecoded.set(0)
        val hadStereo=stereoGeneration!=0
        stereoGeneration=0;if(hadStereo && owner.get()===this) stereoNative?.invoke(0,0,0)
        val old=stereoDecoder;stereoDecoder=null
        // Codec loss callbacks are posted to main, so closing cannot invert this monitor.
        old?.close()
        synchronized(stereoFrames) {stereoFrames.reset()}
    }
    private fun configureStereo(value: JSONObject) {
        require(value.getInt("version")==1)
        if(!active || owner.get()!==this || stereoNative==null) return
        val generation=value.getInt("generation");val width=value.getInt("width");val height=value.getInt("height");val codec=value.getInt("codec")
        if(generation==stereoGeneration && width==stereoWidth && height==stereoHeight && codec==stereoCodec) {
            acknowledgeStereo();return
        }
        require(generation>stereoGeneration && width in 128..8192 && width%4==0 && height in 64..4096 && height%2==0 && codec in 1..2)
        stopStereo();stereoGeneration=generation;stereoWidth=width;stereoHeight=height;stereoCodec=codec
        stereoDecoder=UuStereoDecoder({frame->
            if(frame.generation==stereoGeneration) {
                decodedFrames.incrementAndGet();lastDecoded.set(android.os.SystemClock.elapsedRealtime())
                stereoRendered?.invoke(frame.generation,frame.id,frame.views)
            }
        },{main.post {requestKeyframe()}},{message->status=message;stereoNative?.invoke(0,0,0)})
        stereoNative?.invoke(generation,width,height)
        status="双眼接收器已配置，等待真实编码首帧（不是可玩状态）"
        acknowledgeStereo()
    }
    private fun acknowledgeStereo() {
        if(stereoDecoder?.isReady()!=true) return
        control?.takeIf {it.state()==DataChannel.State.OPEN}?.send(DataChannel.Buffer(ByteBuffer.wrap(
            JSONObject().put("id",0).put("op","video-configured").put("generation",stereoGeneration).toString().toByteArray()),false))
    }
    private val state=UuPcvrControlState()
    private val main=Handler(Looper.getMainLooper())
    private var control: DataChannel?=null
    private var poses: DataChannel?=null
    @Volatile var active=false;private set
    @Volatile var status="内置 VR 追踪诊断：未开启";private set
    /** Host-reported attach liveness ("running"/"ended"/"none"): decides
     * between confirming a live SteamVR mirror and re-requesting a dead one. */
    @Volatile private var mirror="none"
    @Synchronized fun bind(channel: DataChannel) {
        when(channel.label()) {CONTROL->control=channel;TRACKING->poses=channel}
    }
    /** Wait for the correlated acknowledgement of a start request we sent. */
    private fun awaitStart(callback: (Boolean,String)->Unit,timeoutMs: Long,expectedBackend: String) {
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs
        fun poll() {
            val outcome=synchronized(this) {
                when {
                    active && owner.get()===this -> {
                        if(state.activeOn(expectedBackend)) true to status else {
                            val actual=status
                            request(false)
                            false to "远端未确认请求的 VR 后端（$actual）；已请求停止"
                        }
                    }
                    !state.desired || android.os.SystemClock.elapsedRealtime()>=deadline -> false to status
                    else -> null
                }
            }
            if(outcome!=null) {callback(outcome.first,outcome.second);return}
            main.postDelayed({poll()},100)
        }
        main.post {poll()}
    }
    fun prepareStereo(callback: (Boolean,String)->Unit) {
        synchronized(this) {
            if(stereoNative==null || eyeCalibration==null) {
                main.post {callback(false,"请进入沉浸式串流并唤醒头显，等待真实眼位/FOV 校准后重试")};return
            }
            if(active) {
                val matches=state.activeOn("openxr-pico-device")
                val message=if(matches) status else "当前 VR 会话不是双眼 PCVR 后端；请先停止它再启动双眼串流"
                main.post {callback(matches,message)};return
            }
            if(!request(true)) {main.post {callback(false,status)};return}
        }
        awaitStart(callback,8500,"openxr-pico-device")
    }
    /** Attach to a running SteamVR scene on the host: one-sided stereo
     * mirror. Needs no eye calibration (the host registers coordinates from
     * the tracked head pose) and never starts or restarts SteamVR/games.
     * A live mirror is only confirmed; one whose helper ended is re-requested
     * so the host replaces it, and another backend must be stopped first. */
    fun prepareSteamVr(callback: (Boolean,String)->Unit) {
        synchronized(this) {
            when(UuPcvrMirror.action(active,state.activeOn(UuPcvrMirror.BACKEND),mirror)) {
                UuPcvrMirror.Action.CONFIRM -> {main.post {callback(true,status)};return}
                UuPcvrMirror.Action.WRONG_BACKEND -> {
                    main.post {callback(false,"当前 VR 会话使用其他后端；请先停止后再附加 SteamVR 场景")};return
                }
                UuPcvrMirror.Action.REQUEST -> {}
            }
            if(!request(true,UuPcvrRequests.BACKEND_STEAMVR)) {main.post {callback(false,status)};return}
        }
        awaitStart(callback,12000,UuPcvrMirror.BACKEND)
    }
    @Synchronized fun request(start: Boolean,backend: String?=null): Boolean {
        if(start)localFailure=null
        val c=control
        if(c?.state()!=DataChannel.State.OPEN || (start && poses?.state()!=DataChannel.State.OPEN)) {
            revoke();status="远端不支持内置 VR，或通道尚未就绪";return false
        }
        if(start && owner.get()!==this && !owner.compareAndSet(null,this)) {
            status="另一个串流正在使用内置 VR，请先停止它的追踪";return false
        }
        active=false;stopStereo()
        val id=state.request(start)
        if(!start) owner.compareAndSet(this,null)
        val request=UuPcvrRequests.control(id,start,if(start) eyeCalibration else null,backend)
        val sent=c.send(DataChannel.Buffer(ByteBuffer.wrap(request.toString().toByteArray()),false))
        if(!sent) {revoke();status="VR 请求未发送";return false}
        status="等待远端确认（仅追踪，无远端双眼画面）"
        main.postDelayed({synchronized(this) {
            if(state.timeout(id)) {
                active=false;owner.compareAndSet(this,null)
                // A delayed start may have reached the host. Stop it, but never
                // reactivate based on a late start acknowledgement.
                control?.takeIf {it.state()==DataChannel.State.OPEN}?.send(DataChannel.Buffer(
                    ByteBuffer.wrap(JSONObject().put("id",0).put("op","stop").toString().toByteArray()),false))
                status="VR 确认超时，已撤销本地授权并请求释放远端"
            }
        }},8000)
        return true
    }
    @Synchronized fun receive(bytes: ByteArray) {
        if(bytes.size>4096) return
        runCatching {
            val value=JSONObject(String(bytes,Charsets.UTF_8))
            if(value.optString("type")=="haptic") {
                if(active && owner.get()===this) UuPcvrHaptics.parse(value,controlGeneration)?.let {hapticNative?.invoke(it.hand,it.amplitude,it.frequency,it.durationMs)}
                return
            }
            if(value.optString("type")=="video-config") {configureStereo(value);return}
            if(value.optInt("version")!=1) {revoke();status="VR 协议不兼容";return}
            trackingVersion=if(value.optInt("tracking_version",1)>=2) 2 else 1
            mirror=value.optString("mirror","none")
            val ready=value.optBoolean("tracking") && control?.state()==DataChannel.State.OPEN && poses?.state()==DataChannel.State.OPEN
            if(!state.receive(value.optInt("id"),ready,!value.isNull("error"),value.optString("backend").takeIf {it.isNotBlank()})) return
            active=state.active && owner.get()===this
            controlGeneration=if(active) value.optInt("generation",0).coerceAtLeast(0) else 0
            if(!active) stopStereo()
            if(!state.desired) owner.compareAndSet(this,null)
            val cadence=if(value.has("target_fps")) "目标 ${value.optDouble("target_fps")} Hz · ${if(value.isNull("send_fps")) "发送统计中" else "发送 ${String.format(java.util.Locale.ROOT,"%.1f",value.optDouble("send_fps"))} fps"} · " else ""
            status=localFailure ?: if(!value.isNull("error")) value.optString("error") else
                if(active) "${value.optString("backend","追踪")} · ${cadence}已转发 ${value.optLong("frames")} 帧 · ${if(value.optBoolean("fresh")) "近期发送" else "等待头显"} · ${if(lastDecoded.get()>0 && android.os.SystemClock.elapsedRealtime()-lastDecoded.get()<1000) "双眼解码输出 ${decodedFrames.get()} 帧" else if(value.optBoolean("stereo")) "远端正在发送双眼视频，等待本机解码输出" else if(!value.isNull("video_error")) value.optString("video_error") else if(!value.optBoolean("stage",true)) "未获得真实地面；请在 PICO 设置地面后重连，OpenVR 当前不可用" else if(!value.optBoolean("fresh") || !value.optBoolean("head_valid",true)) "等待有效头部追踪" else if(value.optLong("captured_frames")==0L) "等待合成器双眼画面" else if(value.optLong("encoded_frames")==0L) "等待双眼视频编码" else if(!value.optBoolean("decoder_ready")) "等待本机解码器就绪" else "等待双眼视频传输"}" else "内置 VR 追踪已停止"
        }.onFailure {revoke();status="VR 状态无效"}
    }
    private var trackingVersion=1
    @Synchronized fun frame(bytes: ByteArray) {
        val c=poses
        if(!active || c?.state()!=DataChannel.State.OPEN || c.bufferedAmount()>800) return
        val packet=UuPcvrTracking.packet(bytes,trackingVersion) ?: return
        c.send(DataChannel.Buffer(ByteBuffer.wrap(packet),true))
    }
    private fun revoke() {if(owner.get()===this) for(hand in 0..1) hapticNative?.invoke(hand,0f,0f,0);controlGeneration=0;trackingVersion=1;mirror="none";stopStereo();active=false;state.reset();owner.compareAndSet(this,null)}
    @Synchronized fun close() {revoke();eyeCalibration=null;control=null;poses=null;status="VR 会话已结束"}
}

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
    @Synchronized fun calibration(width: Int,height: Int,periodNs: Long,views: FloatArray) {
        if(width<64 || height<64 || periodNs<=0 || views.size!=22 || views.any {!it.isFinite()}) return
        val array=org.json.JSONArray();views.forEach {array.put(it.toDouble())}
        eyeCalibration=JSONObject().put("eye_width",width).put("eye_height",height).put("refresh_hz",1e9/periodNs.toDouble()).put("views",array)
    }
    private val stereoFrames=UuStereoFrames()
    @Volatile private var stereoDecoder: UuStereoDecoder?=null
    @Volatile private var stereoGeneration=0
    @Volatile private var stereoWidth=0
    @Volatile private var stereoHeight=0
    @Volatile private var stereoCodec=0
    private var stereoNative: ((Int,Int,Int)->Unit)?=null
    private var stereoRendered: ((Int,Long,FloatArray)->Unit)?=null
    private var lastKeyframeRequest=0L
    fun setStereoCallbacks(format: (Int,Int,Int)->Unit,frame: (Int,Long,FloatArray)->Unit) {stereoNative=format;stereoRendered=frame}
    fun stereoSurface(surface: android.view.Surface?,width: Int,height: Int) {
        stereoDecoder?.surface(surface,width,height,if(stereoCodec==1) "video/avc" else "video/hevc")
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
        val hadStereo=stereoGeneration!=0
        stereoGeneration=0;if(hadStereo && owner.get()===this) stereoNative?.invoke(0,0,0)
        val old=stereoDecoder;stereoDecoder=null
        // Codec loss callbacks are posted to main, so closing cannot invert this monitor.
        old?.close()
        synchronized(stereoFrames) {stereoFrames.reset()}
    }
    private fun configureStereo(value: JSONObject) {
        require(value.getInt("version")==1)
        check(active && owner.get()===this && stereoNative!=null) {"未授权沉浸式双眼会话"}
        val generation=value.getInt("generation");val width=value.getInt("width");val height=value.getInt("height");val codec=value.getInt("codec")
        require(generation>stereoGeneration && width in 128..8192 && width%4==0 && height in 64..4096 && height%2==0 && codec in 1..2)
        stopStereo();stereoGeneration=generation;stereoWidth=width;stereoHeight=height;stereoCodec=codec
        stereoDecoder=UuStereoDecoder({frame->stereoRendered?.invoke(frame.generation,frame.id,frame.views)},{main.post {requestKeyframe()}},{message->status=message;stereoNative?.invoke(0,0,0)})
        stereoNative?.invoke(generation,width,height)
        status="双眼接收器已配置，等待真实编码首帧（不是可玩状态）"
    }
    private val state=UuPcvrControlState()
    private val main=Handler(Looper.getMainLooper())
    private var control: DataChannel?=null
    private var poses: DataChannel?=null
    @Volatile var active=false;private set
    @Volatile var status="内置 VR 追踪诊断：未开启";private set
    @Synchronized fun bind(channel: DataChannel) {
        when(channel.label()) {CONTROL->control=channel;TRACKING->poses=channel}
    }
    @Synchronized fun request(start: Boolean): Boolean {
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
        val request=JSONObject().put("id",id).put("op",if(start) "start" else "stop")
        if(start) eyeCalibration?.let {request.put("calibration",it)}
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
            if(value.optString("type")=="video-config") {configureStereo(value);return}
            if(value.optInt("version")!=1) {revoke();status="VR 协议不兼容";return}
            val ready=value.optBoolean("tracking") && control?.state()==DataChannel.State.OPEN && poses?.state()==DataChannel.State.OPEN
            if(!state.receive(value.optInt("id"),ready,!value.isNull("error"))) return
            active=state.active && owner.get()===this
            if(!active) stopStereo()
            if(!state.desired) owner.compareAndSet(this,null)
            status=if(!value.isNull("error")) value.optString("error") else
                if(active) "${value.optString("backend","追踪")} · 已转发 ${value.optLong("frames")} 帧 · ${if(value.optBoolean("fresh")) "近期发送" else "等待头显"} · 无远端双眼画面" else "内置 VR 追踪已停止"
        }.onFailure {revoke();status="VR 状态无效"}
    }
    @Synchronized fun frame(bytes: ByteArray) {
        val c=poses
        if(!active || bytes.size!=224 || c?.state()!=DataChannel.State.OPEN || c.bufferedAmount()>224*2) return
        c.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),true))
    }
    private fun revoke() {stopStereo();active=false;state.reset();owner.compareAndSet(this,null)}
    @Synchronized fun close() {revoke();eyeCalibration=null;control=null;poses=null;status="VR 会话已结束"}
}

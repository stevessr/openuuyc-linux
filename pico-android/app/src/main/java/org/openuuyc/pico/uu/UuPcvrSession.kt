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
        active=false
        val id=state.request(start)
        if(!start) owner.compareAndSet(this,null)
        val request=JSONObject().put("id",id).put("op",if(start) "start" else "stop")
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
            if(value.optInt("version")!=1 || value.optBoolean("stereo")) {revoke();status="VR 协议不兼容";return}
            val ready=value.optBoolean("tracking") && control?.state()==DataChannel.State.OPEN && poses?.state()==DataChannel.State.OPEN
            if(!state.receive(value.optInt("id"),ready,!value.isNull("error"))) return
            active=state.active && owner.get()===this
            if(!state.desired) owner.compareAndSet(this,null)
            status=if(!value.isNull("error")) value.optString("error") else
                if(active) "已转发 ${value.optLong("frames")} 帧 · ${if(value.optBoolean("fresh")) "近期发送" else "等待头显"} · 无远端双眼画面" else "内置 VR 追踪已停止"
        }.onFailure {revoke();status="VR 状态无效"}
    }
    @Synchronized fun frame(bytes: ByteArray) {
        val c=poses
        if(!active || bytes.size!=224 || c?.state()!=DataChannel.State.OPEN || c.bufferedAmount()>224*2) return
        c.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),true))
    }
    private fun revoke() {active=false;state.reset();owner.compareAndSet(this,null)}
    @Synchronized fun close() {revoke();control=null;poses=null;status="VR 会话已结束"}
}

package org.openuuyc.pico.uu

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import java.nio.ByteBuffer

internal class UuQuickApps {
    companion object {const val CHANNEL="OPENUUYC_QUICK_APPS_V1"}
    data class App(val id: String,val name: String,val vr: Boolean,val enabled: Boolean)
    private val main=Handler(Looper.getMainLooper())
    private var channel: DataChannel?=null
    @Volatile var initialized=false;private set
    private var allowed=false
    private var sequence=0
    private val pending=mutableMapOf<Int,(JSONObject?,String?)->Unit>()
    private var apps=emptyList<App>()
    @Synchronized fun bind(value: DataChannel) {channel=value;allowed=false;initialized=false}
    @Synchronized fun receive(bytes: ByteArray) {
        if(bytes.size>4*1024*1024) return
        runCatching {
            val value=JSONObject(String(bytes,Charsets.UTF_8))
            if(value.optString("type")=="hello") {allowed=value.optBoolean("allowed");initialized=true;return}
            val id=value.optInt("id")
            val callback=pending.remove(id) ?: return
            main.post {callback(if(value.optString("type")=="error") null else value,if(value.optString("type")=="error") value.optString("message") else null)}
        }
    }
    @Synchronized private fun send(value: JSONObject,timeout: Long=8000,callback: (JSONObject?,String?)->Unit) {
        val c=channel
        if(c?.state()!=DataChannel.State.OPEN) {main.post {callback(null,"快速应用通道尚未就绪，或该设备不支持 OpenUUYC 扩展")};return}
        if(!initialized) {main.post {callback(null,"正在等待该设备的快速应用能力握手，请稍候刷新")};return}
        if(!allowed) {main.post {callback(null,"该设备未授权应用启动：请在 Linux OpenUUYC 被控设置中允许远程终端")};return}
        if(pending.size>=4) {main.post {callback(null,"请等待上一次操作完成")};return}
        val id=++sequence;value.put("id",id);pending[id]=callback
        if(!c.send(DataChannel.Buffer(ByteBuffer.wrap(value.toString().toByteArray()),false))) {
            pending.remove(id);main.post {callback(null,"快速启动请求未发送")};return
        }
        main.postDelayed({synchronized(this) {pending.remove(id)?.let {it(null,"快速启动确认超时；未标为成功")}}},timeout)
    }
    fun list(callback: (List<App>?,String?)->Unit) = send(JSONObject().put("op","list")) {value,error ->
        if(value==null) {callback(null,error);return@send}
        if(value.optString("type")!="list") {callback(null,"远端返回了无效的应用列表");return@send}
        val list=value.optJSONArray("apps")
        val result=(0 until (list?.length() ?: 0)).mapNotNull {i ->
            val a=list?.optJSONObject(i) ?: return@mapNotNull null
            val id=a.optString("id");val name=a.optString("name")
            if(id.length !in 1..100 || name.length !in 1..160) null else App(id,name,a.optBoolean("vr"),a.optBoolean("enabled",!a.optBoolean("vr")))
        }
        synchronized(this) {apps=result};callback(result,null)
    }
    fun runtime(callback: (JSONObject?,String?)->Unit) = send(JSONObject().put("op","vr-runtime")) {value,error->callback(value?.optJSONObject("runtime"),error)}
    fun setRuntime(preference: String,callback: (String)->Unit) = send(JSONObject().put("op","vr-runtime-set").put("backend",preference)) {value,error->callback(error ?: value?.optString("message") ?: "后端设置失败")}
    fun launch(app: App,launchOnlyAck: Boolean=false,callback: (Boolean,String)->Unit) {
        if(!app.enabled) {main.post {callback(false,"内置 VR 尚未就绪，未启动应用")};return}
        if(app.vr && !launchOnlyAck) {main.post {callback(false,"请先确认：仅启动远端 VR 游戏，不提供双眼串流")};return}
        synchronized(this) {if(app !in apps) {main.post {callback(false,"应用列表已变化，请刷新")};return}}
        send(JSONObject().put("op","launch").put("app",app.id).put("launch_only_ack",launchOnlyAck),10000) {v,e ->callback(v?.optString("type")=="started",e ?: v?.optString("message") ?: "未确认启动")}
    }
    @Synchronized fun close() {
        initialized=false;allowed=false;channel=null;apps=emptyList()
        val callbacks=pending.values.toList();pending.clear()
        callbacks.forEach {main.post {it(null,"会话已结束")}}
    }
}

package org.openuuyc.pico.uu

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/** A shell is requested only by open(), including a request waiting for its channel. */
internal class UuTerminal(
    private val post: ((()->Unit)->Unit)?=null,
    private val later: ((Long,()->Unit)->Unit)?=null,
) {
    companion object { const val CHANNEL="OPENUUYC_TERMINAL_V1" }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private fun dispatch(action: ()->Unit) { post?.invoke(action) ?: main.post(action) }
    private fun delay(ms: Long,action: ()->Unit) { later?.invoke(ms,action) ?: main.postDelayed(action,ms) }
    private var sender: ((ByteArray)->Boolean)?=null
    private var official=false
    @Volatile var ready=false; private set
    @Volatile var active=false; private set
    @Volatile var opening=false; private set
    private var waiting=false
    // Official OPEN has no request id. Do not issue another while an old reply is outstanding.
    private var unresolvedOfficial=false
    private var handle=0
    private var epoch=0L
    private var owner: Any?=null
    // Legacy XR toolbar assigns these directly; dialog users use owner-scoped attach/detach.
    var changed: (String)->Unit={}
    var output: (ByteArray)->Unit={}
    private var message="终端通道尚未就绪"
    private val pendingOutput=UuTerminalOutput()

    @Synchronized fun attach(owner: Any,changed: (String)->Unit,output: (ByteArray)->Unit) {
        this.owner=owner;this.changed=changed;this.output=output
        status(message)
    }
    @Synchronized fun detach(owner: Any) {
        if(this.owner!==owner) return
        endShell();this.owner=null;changed={};output={}
    }
    private fun status(value: String) {
        message=value
        val token=epoch;val listener=owner
        dispatch { synchronized(this) { if(token==epoch && listener===owner) changed(value) } }
    }
    @Synchronized fun bind(value: DataChannel) {
        bind(value.label()) { bytes ->
            runCatching {
                value.state()==DataChannel.State.OPEN && value.bufferedAmount()<=512*1024 &&
                    value.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),true))
            }.getOrDefault(false)
        }
    }
    /** Also permits deterministic session/lifecycle tests without WebRTC or an Android looper. */
    @Synchronized internal fun bind(label: String,send: (ByteArray)->Boolean) {
        if(sender!=null) {
            endShell();unresolvedOfficial=false
        }
        official=label=="BINARY_DATA_CHANNEL";sender=send;ready=true
        if(waiting) { waiting=false;start() } else status("终端通道已连接")
    }
    private fun send(kind: Int,bytes: ByteArray=byteArrayOf(),session: Int=handle): Boolean =
        sender?.invoke(UuTerminalWire.encode(kind,session,bytes)) ?: false
    private fun release(session: Int=handle) = send(if(official) 16 else 4,session=session)

    @Synchronized fun open(): Boolean {
        if(active || opening) return false
        if(unresolvedOfficial) { status("仍在等待上一次创建回复，请重新连接终端后重试");return false }
        epoch++;pendingOutput.reset();opening=true
        if(ready) start() else {
            waiting=true;status("等待终端通道连接…")
            val token=epoch
            delay(15000) { synchronized(this) {
                if(epoch==token && waiting) {waiting=false;opening=false;status("终端通道未就绪，请检查连接和远端终端权限后重试")}
            } }
        }
        return true
    }
    private fun start() {
        handle=if(official) 0 else java.security.SecureRandom().nextInt(Int.MAX_VALUE-1)+1
        val options=JSONObject().put("cols",80).put("rows",24).put("name","OpenUUYC").put("option","none")
        if(!send(1,options.toString().toByteArray())) {
            opening=false;status("创建请求未发送：终端通道拥塞或已断开，请重试");return
        }
        unresolvedOfficial=official
        status("等待远端终端授权与创建确认…")
        val token=epoch
        delay(8000) { synchronized(this) {
            if(epoch==token && opening && !waiting) {
                // An official host may allocate an unknown id: detach it when its late reply arrives.
                if(!official) release()
                opening=false;status("终端创建超时，请检查远端授权；尚未确认成功")
            }
        } }
    }
    @Synchronized fun input(text: String): Boolean {
        if(!active) {status("终端尚未确认就绪，未发送输入");return false}
        val bytes=text.toByteArray()
        if(bytes.size>8192) {status("单次终端输入不能超过 8 KiB");return false}
        val sent=send(2,bytes)
        if(!sent) status("输入未发送：终端通道拥塞或已断开，已保留输入")
        return sent
    }
    @Synchronized fun receive(bytes: ByteArray): Boolean {
        val frame=UuTerminalWire.decode(bytes) ?: return false
        if(frame.kind==13 && (if(official) unresolvedOfficial else frame.session==handle)) {
            val result=runCatching {JSONObject(String(frame.payload,Charsets.UTF_8))}.getOrNull()
            val success=result?.optInt("code",1)==0
            val responseHandle=if(official && success) responseHandle(result!!,frame.session) else frame.session
            unresolvedOfficial=false
            if(!opening) {if(success && !active) release(responseHandle);return true}
            opening=false;active=success
            if(success) handle=responseHandle
            status(if(success) "终端已打开" else remoteMessage(result,"远端拒绝终端或创建回复无效"))
            return true
        }
        if(frame.session!=handle || waiting) return true
        when(frame.kind) {
            5->if(active) {
                val delivery=pendingOutput.append(frame.payload)
                if(!delivery.accepted) {
                    release();active=false;pendingOutput.reset();status("终端输出超过本机处理上限，已断开 Shell")
                } else delivery.ticket?.let {ticket ->
                    val listener=owner
                    dispatch { synchronized(this) {
                        pendingOutput.drain(ticket)?.let {if(listener===owner) output(it)}
                    } }
                }
            }
            6->{active=false;opening=false;status("Shell 已退出，可重新打开")}
            8->{
                active=false;opening=false;unresolvedOfficial=false
                val error=runCatching {JSONObject(String(frame.payload,Charsets.UTF_8))}.getOrNull()
                status(remoteMessage(error,"远端终端错误"))
            }
        }
        return true
    }
    private fun remoteMessage(result: JSONObject?,fallback: String) =
        result?.optString("message").orEmpty().filterNot {it.isISOControl()}.take(500).ifBlank {fallback}
    private fun responseHandle(result: JSONObject,fallback: Int): Int {
        for(key in listOf("session_id","resp_session_id","respSessionId","handle")) {
            val value=result.optString(key).toLongOrNull()
            if(value!=null && value in 1..0xffffffffL) return value.toInt()
        }
        return fallback
    }
    @Synchronized fun endShell() {
        epoch++;pendingOutput.reset()
        if(active || (opening && !waiting && !official)) release()
        active=false;opening=false;waiting=false
        if(owner==null) {changed={};output={}}
    }
    @Synchronized fun close() {
        endShell();ready=false;sender=null;unresolvedOfficial=false
        status("终端通道已断开，请重新连接后重试")
    }
}

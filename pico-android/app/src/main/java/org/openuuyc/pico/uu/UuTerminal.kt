package org.openuuyc.pico.uu
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/** Real UU TERM frames. Never creates a shell without an explicit open(). */
internal class UuTerminal {
    companion object {const val CHANNEL="OPENUUYC_TERMINAL_V1"}
    private val main=Handler(Looper.getMainLooper())
    private var channel: DataChannel?=null
    @Volatile var ready=false;private set
    @Volatile var active=false;private set
    private var handle=0
    private var opening=false
    private val pendingOutput=java.io.ByteArrayOutputStream()
    private var outputScheduled=false
    private var epoch=0
    var changed: (String)->Unit={}
    var output: (ByteArray)->Unit={}
    @Synchronized fun bind(value: DataChannel) {channel=value;ready=true;main.post {changed("终端通道已连接，等待你打开 Shell")}}
    @Synchronized private fun send(kind: Int,bytes: ByteArray=byteArrayOf()): Boolean {
        val c=channel ?: return false
        if(c.state()!=DataChannel.State.OPEN || c.bufferedAmount()>512*1024) return false
        return c.send(DataChannel.Buffer(ByteBuffer.wrap(UuTerminalWire.encode(kind,handle,bytes)),true))
    }
    @Synchronized fun open(): Boolean {
        if(!ready || active || opening) return false
        handle=java.security.SecureRandom().nextInt(Int.MAX_VALUE-1)+1
        val token=++epoch
        if(!send(1,JSONObject().put("cols",80).put("rows",24).put("name","OpenUUYC").toString().toByteArray())) return false
        opening=true;main.post {changed("等待远端终端授权与创建确认…")}
        main.postDelayed({synchronized(this) {if(epoch==token && opening) {send(4);opening=false;changed("终端创建超时，已请求关闭；未标为成功")}}},8000)
        return true
    }
    @Synchronized fun input(text: String): Boolean = active && text.toByteArray().size<=8192 && send(2,text.toByteArray())
    @Synchronized fun receive(bytes: ByteArray): Boolean {
        val frame=UuTerminalWire.decode(bytes) ?: return false
        if(frame.session!=handle) return true
        when(frame.kind) {
            13->{
                val result=runCatching {JSONObject(String(frame.payload,Charsets.UTF_8))}.getOrNull()
                if(!opening) {if(result?.optInt("code",1)==0) send(4);return true}
                opening=false;active=result?.optInt("code",1)==0
                val message=if(active) "终端已打开" else result?.optString("message").orEmpty().ifBlank {"远端拒绝终端"}
                main.post {changed(message)}
            }
            5->if(active) {
                if(pendingOutput.size()+frame.payload.size>512*1024) {
                    send(4);active=false;pendingOutput.reset();main.post {changed("终端输出超过本机处理上限，已请求关闭 Shell")}
                } else {
                    pendingOutput.write(frame.payload)
                    if(!outputScheduled) {
                        outputScheduled=true;val token=epoch
                        main.post {
                            val value=synchronized(this) {outputScheduled=false;if(token!=epoch) null else pendingOutput.toByteArray().also {pendingOutput.reset()}}
                            if(value!=null) output(value)
                        }
                    }
                }
            }
            6->{active=false;opening=false;main.post {changed("Shell 已退出")}}
            8->{active=false;opening=false;val error=runCatching {JSONObject(String(frame.payload,Charsets.UTF_8)).optString("message")}.getOrDefault("终端错误");main.post {changed(error.take(500))}}
        }
        return true
    }
    @Synchronized fun endShell() {epoch++;pendingOutput.reset();outputScheduled=false;if(active || opening) send(4);active=false;opening=false;output={};changed={}}
    @Synchronized fun close() {endShell();ready=false;channel=null}
}

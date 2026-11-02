package org.openuuyc.pico.uu

import android.os.Handler
import android.os.Looper
import java.util.zip.InflaterInputStream
import org.openuuyc.pico.uu.UuWire.data
import org.openuuyc.pico.uu.UuWire.number

/** Official FTP protobuf on TEXT_DATA_CHANNEL, not an OpenUUYC-only extension. */
internal object UuFileWire {
    data class Entry(val name: String,val path: String,val type: Int,val size: Long) {
        val directory: Boolean get()=type in 0..3
    }
    data class Reply(val id: Long,val code: Int,val path: String,val entries: List<Entry>,val error: String)
    fun request(id: Long,operation: Int,path: String,name: String=""): ByteArray {
        require(operation in setOf(5,6,7,8,10));require(path.length<=32768 && '\u0000' !in path)
        return UuWire.rpc(id,8,UuWire.message {ftp->UuWire.bytes(ftp,operation,UuWire.message {body->
            UuWire.bytes(body,if(operation==10) 2 else 1,path.toByteArray(Charsets.UTF_8))
            if(operation==5) {require(name.isNotBlank() && name.length<=255 && name.none {it=='/' || it=='\\' || it=='\u0000'} && name !in setOf(".",".."));UuWire.bytes(body,2,name.toByteArray(Charsets.UTF_8))}
        })})
    }
    fun response(bytes: ByteArray): Reply? {
        if(bytes.isEmpty() || bytes[0]=='{'.code.toByte()) return null
        val envelope=UuWire.fields(bytes)
        val rpc=envelope.data(22)?.let(UuWire::fields) ?: return null
        val ftp=rpc.data(4)?.let(UuWire::fields) ?: return null
        val id=rpc.data(1)?.let(UuWire::fields)?.number(1) ?: 0
        ftp.data(7)?.let {body->
            val dir=UuWire.fields(body)
            val packed=dir.data(5)
            val entries=if(packed!=null && packed.isNotEmpty()) {
                val expanded=InflaterInputStream(packed.inputStream()).use {input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val count=input.read(buffer);if(count<0) break;require(output.size()+count<=1024*1024) {"目录过大"};output.write(buffer,0,count)}
                    output.toByteArray()
                }
                require(expanded.size<=1024*1024) {"目录过大"}
                UuWire.fields(expanded)[1].orEmpty()
            } else dir[3].orEmpty()
            require(entries.size<=10000)
            return Reply(id,dir.number(4).toInt(),dir.data(2)?.toString(Charsets.UTF_8).orEmpty(),entries.mapNotNull {field->
                field.data?.let {UuWire.fields(it)}?.let {entry->Entry(entry.data(2)?.toString(Charsets.UTF_8).orEmpty(),entry.data(5)?.toString(Charsets.UTF_8).orEmpty(),entry.number(1).toInt(),entry.number(3))}
            },"")
        }
        ftp.data(5)?.let {val op=UuWire.fields(it);return Reply(id,op.number(1).toInt(),op.data(3)?.toString(Charsets.UTF_8).orEmpty(),emptyList(),op.data(2)?.toString(Charsets.UTF_8).orEmpty())}
        return null
    }
}
internal class UuFileManager(private val next: ()->Long,private val send: (ByteArray)->Boolean) {
    private val main=Handler(Looper.getMainLooper())
    private val pending=mutableMapOf<Long,(UuFileWire.Reply?,String?)->Unit>()
    fun request(operation: Int,path: String,name: String="",callback: (UuFileWire.Reply?,String?)->Unit) {
        if(synchronized(this) {pending.size>=4}) {main.post {callback(null,"请等待当前文件操作")};return}
        val id=next()
        val packet=runCatching {UuFileWire.request(id,operation,path,name)}.getOrElse {main.post {callback(null,it.message)};return}
        synchronized(this) {pending[id]=callback}
        if(!send(packet)) {synchronized(this) {pending.remove(id)};main.post {callback(null,"文件通道尚未就绪，请连接后重试")};return}
        main.postDelayed({synchronized(this) {pending.remove(id)?.invoke(null,"文件请求超时；远端可能不支持或未授权文件管理")}},12000)
    }
    @Synchronized fun receive(bytes: ByteArray): Boolean {
        val reply=runCatching {UuFileWire.response(bytes)}.getOrNull() ?: return false
        val callback=pending.remove(reply.id) ?: return true
        main.post {callback(if(reply.code==1) reply else null,if(reply.code==1) null else reply.error.ifBlank {"远端文件操作失败：${reply.code}"})};return true
    }
    @Synchronized fun close() {val callbacks=pending.values.toList();pending.clear();callbacks.forEach {main.post {it(null,"文件连接已关闭")}}}
}

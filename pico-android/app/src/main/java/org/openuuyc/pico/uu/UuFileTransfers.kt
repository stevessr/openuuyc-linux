package org.openuuyc.pico.uu

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.openuuyc.pico.uu.UuWire.data
import org.openuuyc.pico.uu.UuWire.number

internal object UuTransferWire {
    data class Packet(val response: Boolean,val kind: Int,val header: Long,val task: Int,val index: Int,val body: Map<Int,List<UuWire.Field>>)
    fun task(task: Int,index: Int)=UuWire.message {UuWire.integer(it,1,task.toLong());UuWire.integer(it,2,index.toLong())}
    fun packet(header: Long,response: Boolean,kind: Int,body: ByteArray)=UuWire.message {out->
        UuWire.integer(out,1,header);UuWire.integer(out,2,System.currentTimeMillis())
        UuWire.bytes(out,if(response) 22 else 21,UuWire.message {rpc->
            UuWire.bytes(rpc,1,UuWire.message {UuWire.integer(it,1,header)})
            UuWire.bytes(rpc,if(response) 4 else 8,UuWire.message {UuWire.bytes(it,kind,body)})
        })
    }
    fun decode(bytes: ByteArray): Packet? {
        if(bytes.isEmpty() || bytes[0]=='{'.code.toByte()) return null
        val env=UuWire.fields(bytes);val response=env.data(22)!=null
        val rpc=(env.data(if(response) 22 else 21) ?: return null).let(UuWire::fields)
        val ftp=(rpc.data(if(response) 4 else 8) ?: return null).let(UuWire::fields)
        val kind=ftp.keys.singleOrNull() ?: return null
        val body=(ftp.data(kind) ?: return null).let(UuWire::fields)
        val id=(body.data(1) ?: return null).let(UuWire::fields)
        return Packet(response,kind,rpc.data(1)?.let(UuWire::fields)?.number(1) ?: 0,id.number(1).toInt(),id.number(2).toInt(),body)
    }
    fun safeName(value: String): String {
        require(value.isNotBlank() && value.length<=255 && value !in setOf(".","..") && value.none {it=='/' || it=='\\' || it=='\u0000'}) {"文件名无效"}
        return value
    }
    fun file(name: String,size: Long)=UuWire.message {UuWire.bytes(it,1,safeName(name).toByteArray());UuWire.integer(it,2,size)}
}

/** One bounded, cancellable transfer per authenticated connection. Disk I/O is never on RTC/UI. */
internal class UuFileTransfers(private val next: ()->Long,private val send: (ByteArray,Boolean)->Boolean) {
    private class Job(val task: Int) {
        val stop=AtomicBoolean(false)
        val queue=ArrayBlockingQueue<UuTransferWire.Packet>(32)
        @Volatile var resource: java.io.Closeable?=null
        @Volatile var worker: Thread?=null
    }
    @Volatile private var job: Job?=null
    @Volatile private var closed=false
    fun receive(bytes: ByteArray): Boolean {
        val active=job ?: return false
        val packet=runCatching {UuTransferWire.decode(bytes)}.getOrNull() ?: return false
        if(packet.task!=active.task) return false
        if(!active.queue.offer(packet)) active.stop.set(true)
        return true
    }
    fun cancel() {
        val active=job ?: return
        active.stop.set(true);active.worker?.interrupt()
        val resource=active.resource
        if(resource!=null) Thread({runCatching {resource.close()}},"uuyc-file-cancel").apply {isDaemon=true;start()}
    }
    fun close() {closed=true;cancel()}
    private fun launch(progress: (Long,Long)->Unit,done: (String?)->Unit,body: (Job,(Long,Long)->Unit)->Unit) {
        val active: Job
        synchronized(this) {
            if(closed || job!=null) {done(if(closed) "连接已关闭" else "此连接已有文件传输");return}
            active=Job((next() and 0x7fffffff).toInt().coerceAtLeast(1));job=active
        }
        Thread({
            active.worker=Thread.currentThread()
            val result=runCatching {body(active,progress)}
            active.resource=null
            if(result.isFailure) runCatching {send(UuTransferWire.packet(next(),false,9,fields(active,-1) {UuWire.integer(it,2,if(active.stop.get()) 10 else 6)}),false)}
            synchronized(this) {if(job===active) job=null}
            done(result.exceptionOrNull()?.let {it.message ?: if(active.stop.get()) "文件传输已取消" else it.javaClass.simpleName})
        },"uuyc-file-${active.task}").start()
    }
    private fun fields(j: Job,index: Int,block: (java.io.ByteArrayOutputStream)->Unit={})=UuWire.message {UuWire.bytes(it,1,UuTransferWire.task(j.task,index));block(it)}
    private fun check(j: Job) {check(!closed && !j.stop.get()) {"文件传输已取消或连接已关闭"}}
    private fun transmit(j: Job,response: Boolean,kind: Int,body: ByteArray,header: Long=next(),bulk: Boolean=false) {
        val bytes=UuTransferWire.packet(header,response,kind,body)
        val deadline=System.nanoTime()+10_000_000_000L
        while(true) {check(j);if(send(bytes,bulk)) return;check(System.nanoTime()<deadline) {"文件发送队列超时"};Thread.sleep(10)}
    }
    private fun incoming(j: Job): UuTransferWire.Packet {
        val deadline=System.nanoTime()+60_000_000_000L
        while(true) {check(j);check(System.nanoTime()<deadline) {"文件响应超时"}
            val p=j.queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if(p.response && p.kind==6 && p.body.number(2)!=1L) error(p.body.data(3)?.toString(Charsets.UTF_8)?.ifBlank {null} ?: "远端传输失败：${p.body.number(2)}")
            return p
        }
    }
    private fun expect(j: Job,kind: Int,index: Int?=null): UuTransferWire.Packet {
        val p=incoming(j);check(p.response && p.kind==kind && (index==null || p.index==index)) {"文件响应与当前任务状态不符"};return p
    }
    fun upload(path: String,name: String,size: Long,source: ()->InputStream,progress: (Long,Long)->Unit,done: (String?)->Unit) {
        launch(progress,done) {j,notify->
            require(size>=0);UuTransferWire.safeName(name)
            transmit(j,false,1,fields(j,-1) {out->
                UuWire.bytes(out,2,path.toByteArray());UuWire.bytes(out,3,UuTransferWire.file(name,size))
                UuWire.integer(out,4,2) // Keep both on collision; never silently overwrite.
                UuWire.bytes(out,7,java.util.UUID.randomUUID().toString().toByteArray())
            })
            check(expect(j,1).body.number(3)==1L) {"远端拒绝接收文件"}
            transmit(j,false,3,fields(j,1) {UuWire.integer(it,3,size)})
            val confirm=expect(j,3,1)
            check(confirm.body.number(3)==1L && confirm.body.number(2)==0L) {"远端拒绝准备文件或跳过了文件"}
            var position=confirm.body.number(4);check(position in 0..size) {"无效续传位置"}
            source().also {j.resource=it}.use {input->
                var remaining=position
                while(remaining>0) {check(j);val skipped=input.skip(remaining);if(skipped>0) remaining-=skipped else {check(input.read()>=0) {"源文件短于续传位置"};remaining--}}
                var block=0
                while(position<size) {
                    check(j);val data=ByteArray(minOf(65536L,size-position).toInt());var offset=0
                    while(offset<data.size) {val n=input.read(data,offset,data.size-offset);check(n>0) {"源文件读取失败或已变化"};offset+=n}
                    block++;transmit(j,false,4,fields(j,1) {UuWire.integer(it,2,block.toLong());UuWire.bytes(it,3,data)},bulk=true)
                    val ack=expect(j,4,1)
                    check(ack.body.number(2)==block.toLong() && ack.body.number(3)==1L && ack.body.number(4) in listOf(0L,data.size.toLong())) {"文件块确认不符"}
                    position+=data.size;notify(position,size)
                }
                check(input.read()==-1) {"源文件在传输期间变大"}
            }
            transmit(j,false,9,fields(j,1) {UuWire.integer(it,2,1)})
            check(expect(j,6,1).body.number(2)==1L) {"远端未确认文件保存"}
            transmit(j,false,9,fields(j,-1) {UuWire.integer(it,2,1)});notify(size,size)
        }
    }
    fun download(path: String,expectedSize: Long,destination: ()->OutputStream,progress: (Long,Long)->Unit,done: (String?)->Unit) {
        launch(progress,done) {j,notify->
            transmit(j,false,2,fields(j,-1) {UuWire.bytes(it,2,path.toByteArray())})
            val manifest=expect(j,2)
            check(manifest.body.number(2)==1L) {"远端拒绝发送文件"}
            // This API saves one selected file to one SAF document, never remote-chosen local paths.
            val plain=manifest.body[3].orEmpty()
            val packed=manifest.body.data(5)
            val records=if(packed!=null && packed.isNotEmpty()) {
                val expanded=java.util.zip.InflaterInputStream(packed.inputStream()).use {input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val n=input.read(buffer);if(n<0) break;require(output.size()+n<=1024*1024);output.write(buffer,0,n)};output.toByteArray()
                };UuWire.fields(expanded)[1].orEmpty()
            } else plain
            check(records.size==1) {"请选择单个文件下载，文件夹批量传输尚不支持"}
            val metadata=UuWire.fields(records.single().data ?: error("文件元数据无效"))
            val total=metadata.number(2);check(total>=0 && total==expectedSize) {"远端文件大小已变化，请刷新目录"}
            var position=0L;var lastBlock=0;var opened=false;var completed=false
            destination().also {j.resource=it}.use {output->
                while(true) {
                    val p=incoming(j);check(!p.response && p.index in listOf(1,-1)) {"下载响应状态不符"}
                    when(p.kind) {
                        3->{check(!opened && p.index==1 && p.body.number(3)==total) {"文件准备数据不符"};opened=true
                            transmit(j,true,3,fields(j,1) {UuWire.integer(it,3,1)},p.header)}
                        4->{check(opened && !completed && p.index==1) {"未准备接收文件"}
                            val block=p.body.number(2).toInt();val data=p.body.data(3) ?: error("缺少文件块")
                            check(block==lastBlock+1 && data.isNotEmpty() && data.size<=204800 && position+data.size<=total) {"文件块乱序或超过声明长度"}
                            output.write(data);position+=data.size;lastBlock=block
                            transmit(j,true,4,fields(j,1) {UuWire.integer(it,2,block.toLong());UuWire.integer(it,3,1);UuWire.integer(it,4,data.size.toLong())},p.header);notify(position,total)}
                        9->{check(p.body.number(2)==1L) {"远端终止传输"}
                            if(p.index==1) {check(opened && !completed && position==total) {"文件未完整接收"};output.flush();completed=true}
                            else check(completed) {"远端提前结束任务"}
                            transmit(j,true,6,fields(j,p.index) {UuWire.integer(it,2,1)},p.header)
                            if(p.index==-1) break}
                        13->{} // Optional directory-size notice.
                        else->error("下载消息与当前任务不符")
                    }
                }
            };notify(total,total)
        }
    }
}

package org.openuuyc.pico.uu

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Account online room only: no media, peer control or publication permission. */
internal class UuAccountPush(private val room: UuRoom,
    private val socketFactory: ((Request,WebSocketListener)->WebSocket)?=null): AutoCloseable {
    private val client=OkHttpClient.Builder().followRedirects(false).connectTimeout(10,TimeUnit.SECONDS).build()
    private val endpoints=UuSignalEndpoints(room.endpoints)
    private val ready=CountDownLatch(1)
    private val stopped=AtomicBoolean(false)
    private val observer=AtomicReference<((JSONObject)->Unit)?>(null)
    @Volatile private var namespace=false
    @Volatile private var generation=0
    @Volatile private var socket: WebSocket?=null
    @Volatile var failure: String?=null;private set
    init {connect()}
    private fun connect() {
        if(stopped.get()) return
        val ticket=++generation
        val request=Request.Builder().url(endpoints.current).header("X-NRD-AUTH",room.token)
            .header("X-NRD-CONTROLLING","0").header("streamer_version","V4.7.3").build()
        val listener=object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) {
                if(stopped.get() || ticket!=generation) ws.cancel() else ws.send("40")
            }
            override fun onMessage(ws: WebSocket,text: String) {
                if(stopped.get() || ticket!=generation || text.length>1024*1024) return
                try {
                    when(val packet=UuSocketIoWire.parse(text)) {
                        is UuSocketIoWire.Packet.Ping -> ws.send("3"+packet.payload)
                        UuSocketIoWire.Packet.NamespaceConnected -> {namespace=true;ws.send("421[\"room_info\"]")}
                        is UuSocketIoWire.Packet.RoomAck -> ready.countDown()
                        is UuSocketIoWire.Packet.Event -> {
                            if(packet.name=="bmsg_push") packet.payload?.let {observer.get()?.invoke(it)}
                            packet.acknowledge?.let {ws.send(UuSocketIoWire.eventAck(it))}
                        }
                        UuSocketIoWire.Packet.Disconnected,is UuSocketIoWire.Packet.Rejected -> fail("共享请求的账号推送连接已断开")
                        else -> {}
                    }
                } catch(_: Exception) {fail("共享请求的账号推送数据无效")}
            }
            override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) {
                if(ticket!=generation) return
                if(!stopped.get() && !namespace && endpoints.nextBeforeNamespace()) connect()
                else if(!stopped.get()) fail("无法连接共享请求的账号推送服务，请重试")
            }
            override fun onClosed(ws: WebSocket,code: Int,reason: String) {
                if(!stopped.get() && ticket==generation) fail("共享请求的账号推送连接已关闭")
            }
        }
        socket=socketFactory?.invoke(request,listener) ?: client.newWebSocket(request,listener)
    }
    private fun fail(message: String) {failure=message;ready.countDown();close()}
    fun awaitReady(cancelled: ()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(!ready.await(200,TimeUnit.MILLISECONDS)) {
            if(cancelled()) throw IOException("共享请求已取消")
            if(System.nanoTime()>=deadline) {fail("共享请求的账号推送连接超时");break}
        }
        if(cancelled()) throw IOException("共享请求已取消")
        failure?.let {throw IOException(it)}
        if(stopped.get()) throw IOException("账号推送连接已关闭")
    }
    fun subscribe(callback: (JSONObject)->Unit): AutoCloseable {
        check(!stopped.get()) {"账号推送连接已关闭"}
        check(observer.compareAndSet(null,callback)) {"已有共享请求正在等待处理"}
        return AutoCloseable {observer.compareAndSet(callback,null)}
    }
    val ended: Boolean get()=stopped.get()
    override fun close() {
        if(!stopped.compareAndSet(false,true)) return
        observer.set(null);ready.countDown()
        socket?.let {ws->
            UuSocketIoWire.closeFrames(namespace).forEach {ws.send(it)}
            ws.close(1000,"account push closed");ws.cancel()
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

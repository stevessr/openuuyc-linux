package org.openuuyc.pico.uu

import okhttp3.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort release for a joined room whose viewer was cancelled before RTC start. */
internal object UuRoomRelease {
    fun cancel(room: UuRoom) {
        val client=OkHttpClient.Builder().followRedirects(false).connectTimeout(5,TimeUnit.SECONDS).build()
        val timer=Executors.newSingleThreadScheduledExecutor()
        val finished=AtomicBoolean(false)
        fun finish(){if(finished.compareAndSet(false,true)){timer.shutdownNow();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}}
        try {
            val request=Request.Builder().url(UuSignalEndpoints(room.endpoints).current)
                .header("X-NRD-AUTH",room.token).header("X-NRD-CONTROLLING","0")
                .header("streamer_version","V4.7.3").build()
            val socket=client.newWebSocket(request,object: WebSocketListener(){
                override fun onOpen(ws: WebSocket,response: Response){ws.send("40")}
                override fun onMessage(ws: WebSocket,text: String){
                    if(text.length>65536)return
                    val packet=runCatching {UuSocketIoWire.parse(text)}.getOrElse {ws.close(1002,"invalid frame");return}
                    when(packet){
                        is UuSocketIoWire.Packet.Ping->ws.send("3"+text.drop(1))
                        UuSocketIoWire.Packet.NamespaceConnected->{UuSocketIoWire.closeFrames(true).forEach {ws.send(it)};ws.close(1000,"viewer cancelled")}
                        else->{}
                    }
                }
                override fun onClosed(ws: WebSocket,code: Int,reason: String){finish()}
                override fun onFailure(ws: WebSocket,t: Throwable,response: Response?){finish()}
            })
            timer.schedule({socket.cancel();finish()},5,TimeUnit.SECONDS)
        }catch(_: Exception){finish()}
    }
}

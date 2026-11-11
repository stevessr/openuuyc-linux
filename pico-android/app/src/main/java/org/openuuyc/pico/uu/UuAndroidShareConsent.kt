package org.openuuyc.pico.uu

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Called on a worker. Subscription precedes HTTP; only an explicit matching accept proceeds. */
internal object UuAndroidShareConsent {
    fun await(push: UuAccountPush,target: String,localId: String,alias: String,version: String,
              send: (JSONObject)->Unit,cancelled: ()->Boolean,status: (String)->Unit,onResolved: ()->Unit = {}) {
        push.awaitReady(cancelled)
        val gate=UuAndroidShareApproval(target)
        val changed=CountDownLatch(1)
        val subscription=push.subscribe {if(gate.receive(it)) changed.countDown()}
        var sent=false
        try {
            if(cancelled()) throw IOException("共享请求已取消")
            sent=true
            send(UuAndroidShareApproval.request(localId,alias,version))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
            var lastSeconds=-1L
            while(gate.result==null) {
                if(cancelled()) throw IOException("共享请求已取消")
                push.failure?.let {throw IOException(it)}
                if(push.ended) throw IOException("共享请求的账号推送连接已关闭")
                val remaining=deadline-System.nanoTime()
                if(remaining<=0) throw IOException("对方 60 秒内未同意共享，请重新发起")
                val seconds=(remaining+999_999_999L)/1_000_000_000L
                if(seconds!=lastSeconds) {lastSeconds=seconds;status("等待对方同意共享（剩余 ${seconds} 秒）")}
                changed.await(minOf(remaining,TimeUnit.MILLISECONDS.toNanos(200)),TimeUnit.NANOSECONDS)
            }
            if(cancelled()) throw IOException("共享请求已取消")
            val result=gate.result!!
            onResolved()
            if(result!=UuAndroidShareApproval.Result.ACCEPTED) throw IOException(result.message)
            status(result.message)
        } finally {
            subscription.close()
            if(sent && (cancelled() || gate.result==null)) {
                runCatching {send(UuAndroidShareApproval.request(localId,alias,version,"cancel_request"))}
            }
        }
    }
}

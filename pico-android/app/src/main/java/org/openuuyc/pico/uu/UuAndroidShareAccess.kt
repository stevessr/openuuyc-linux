package org.openuuyc.pico.uu

import java.io.IOException

/** Official approval replies have no request nonce. Quarantine unresolved requests across retries. */
internal class UuAndroidShareAttempts(
    private val readUntil: (String)->Long, private val writeUntil: (String,Long)->Unit,
    private val nowMs: ()->Long = System::currentTimeMillis
) {
    @Synchronized fun begin(target: String) {
        val remaining=readUntil(target)-nowMs()
        if(remaining>0) throw IOException("上一次共享请求仍可能收到回应，请等待 ${(remaining+999)/1000} 秒后重试")
        sent(target)
    }
    @Synchronized fun sent(target: String) {
        // 60 seconds for consent plus a short grace period for an in-flight response.
        writeUntil(target,nowMs()+70_000L)
    }
    @Synchronized fun resolved(target: String) {writeUntil(target,0L)}
}

/** A matching approval grants exactly one short-lived join, scoped to this logged-in API. */
internal class UuAndroidSharePermits(private val nowMs: ()->Long = {System.nanoTime()/1_000_000L}) {
    private val permits=mutableMapOf<String,Long>()
    @Synchronized fun grant(target: String) {permits[target]=nowMs()+30_000L}
    @Synchronized fun consume(target: String): Boolean = permits.remove(target)?.let {it>nowMs()}==true
    @Synchronized fun clear() {permits.clear()}
}

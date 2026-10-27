package org.openuuyc.pico.uu

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native signaling callbacks must return without acquiring a session monitor.
 * A blocking JNI call holding that monitor may itself be waiting for this callback.
 */
internal class UuRtcCallbackQueue(
    capacity: Int=256,
    private val onFailure: (Exception)->Unit
) : AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(capacity),
        {task -> Thread(task,"OpenUUYC-RTC-events").apply {isDaemon=true}})
    fun post(action: ()->Unit): Boolean {
        if(closed.get()) return false
        return try {
            executor.execute {
                if(!closed.get()) try {action()} catch(error: Exception) {onFailure(error)}
            }
            true
        } catch(_: RejectedExecutionException) {false}
    }
    override fun close() {
        if(closed.compareAndSet(false,true)) {executor.queue.clear();executor.shutdown()}
    }
}

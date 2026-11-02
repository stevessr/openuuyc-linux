package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UuRtcCallbackQueueTest {
    @Test fun nativeCallbackReturnsEvenWhenBlockingNativeCallerHoldsSessionLock() {
        val monitor=Any();val delivered=CountDownLatch(1)
        val errors=mutableListOf<Exception>()
        val queue=UuRtcCallbackQueue {errors.add(it)}
        val nativeThread=Executors.newSingleThreadExecutor()
        try {
            synchronized(monitor) {
                // Models addIceCandidate waiting for an Observer on the signaling
                // thread while its caller holds the Java session monitor.
                val nativeCall=nativeThread.submit<Boolean> {queue.post {synchronized(monitor) {delivered.countDown()}}}
                assertTrue(nativeCall.get(2,TimeUnit.SECONDS))
                assertEquals(1,delivered.count)
            }
            assertTrue(delivered.await(2,TimeUnit.SECONDS));assertTrue(errors.isEmpty())
        } finally {queue.close();nativeThread.shutdownNow()}
    }
    @Test fun preservesOrderingAndRejectsCallbacksAfterClose() {
        val queue=UuRtcCallbackQueue {throw AssertionError(it)}
        val values=mutableListOf<Int>();val done=CountDownLatch(1)
        try {
            repeat(50) {i -> assertTrue(queue.post {values.add(i)})}
            queue.post {done.countDown()}
            assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals((0 until 50).toList(),values)
        } finally {queue.close()}
        assertFalse(queue.post {fail("closed callback executed")})
    }
    @Test fun queueIsBoundedAndErrorsAreReported() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val error=CountDownLatch(1)
        val queue=UuRtcCallbackQueue(1) {error.countDown()}
        try {
            assertTrue(queue.post {entered.countDown();release.await(2,TimeUnit.SECONDS)})
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertTrue(queue.post {throw IllegalStateException("fixture")})
            assertFalse(queue.post {})
            release.countDown();assertTrue(error.await(2,TimeUnit.SECONDS))
        } finally {release.countDown();queue.close()}
    }
}

package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuAudioLatencyTest {
    @Test fun missingOrZeroCountersDoNotInventLatency() {
        assertTrue(UuAudioLatency.summary(emptyMap()).contains("jbAvg=n/a"))
        assertTrue(UuAudioLatency.summary(mapOf("jitterBufferDelay" to 2,"jitterBufferEmittedCount" to 0)).contains("jbAvg=n/a"))
    }
    @Test fun convertsSecondsAndSamplesWithoutTreatingDelayAsRtt() {
        val text=UuAudioLatency.summary(mapOf("jitterBufferDelay" to 2.4,"jitterBufferTargetDelay" to 1.2,"jitterBufferEmittedCount" to 120,"jitter" to .006))
        assertTrue(text.contains("jbAvg=20ms"));assertTrue(text.contains("jbTarget=10ms"));assertTrue(text.contains("jitter=6ms"))
    }
    @Test fun rejectsNonFiniteStats() {assertTrue(UuAudioLatency.summary(mapOf("jitterBufferDelay" to Double.NaN,"jitterBufferEmittedCount" to 2)).contains("jbAvg=n/a"))}
    @Test fun intervalDelayDoesNotHideBehindLifetimeAverage() {
        val window=UuAudioLatency.Window()
        fun sample(delay: Double,count: Int)=mapOf<String,Any>("jitterBufferDelay" to delay,"jitterBufferEmittedCount" to count)
        assertTrue(window.summary("a",sample(1.0,100)).contains("jbNow=n/a"))
        assertTrue(window.summary("a",sample(2.0,110)).contains("jbNow=100ms"))
        assertTrue(window.summary("b",sample(2.0,110)).contains("jbNow=n/a"))
        assertTrue(window.summary("a",sample(0.0,0)).contains("jbNow=n/a"))
    }
}

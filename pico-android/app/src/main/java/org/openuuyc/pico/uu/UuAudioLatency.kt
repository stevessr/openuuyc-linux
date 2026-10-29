package org.openuuyc.pico.uu

/** Observed receiver buffer residence, NOT acoustic end-to-end latency. */
internal object UuAudioLatency {
    fun summary(m: Map<String,Any>): String {
        fun finite(key: String)=(m[key] as? Number)?.toDouble()?.takeIf {it.isFinite() && it>=0}
        val count=finite("jitterBufferEmittedCount")
        fun average(key: String): String {
            val delay=finite(key)
            return if(delay!=null && count!=null && count>0) "${(delay*1000/count).toInt()}ms" else "n/a"
        }
        val jitter=finite("jitter")?.let {"${(it*1000).toInt()}ms"} ?: "n/a"
        return "audio(lowLatency=true,jbAvg=${average("jitterBufferDelay")},jbTarget=${average("jitterBufferTargetDelay")},jitter=$jitter,concealed=${m["concealedSamples"] ?: "n/a"},lost=${m["packetsLost"] ?: "n/a"})"
    }
}

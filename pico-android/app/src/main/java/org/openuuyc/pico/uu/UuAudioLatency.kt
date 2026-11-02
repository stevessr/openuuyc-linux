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
    /** Per-track interval deltas: lifetime averages hide a new latency spike. */
    class Window {
        private val previous=linkedMapOf<String,Map<String,Double>>()
        @Synchronized fun summary(id: String,m: Map<String,Any>): String {
            val keys=listOf("jitterBufferDelay","jitterBufferTargetDelay","jitterBufferEmittedCount","concealedSamples","packetsLost")
            val current=keys.mapNotNull {key->(m[key] as? Number)?.toDouble()?.takeIf {it.isFinite() && it>=0}?.let {key to it}}.toMap()
            val old=previous.put(id,current)
            while(previous.size>8) previous.remove(previous.keys.first())
            fun delta(key: String): Double? {
                val now=current[key] ?: return null;val before=old?.get(key) ?: return null
                return (now-before).takeIf {it>=0}
            }
            val count=delta("jitterBufferEmittedCount")
            fun delay(key: String): String {
                val value=delta(key)
                return if(value!=null && count!=null && count>0) "${(value*1000/count).toInt()}ms" else "n/a"
            }
            return UuAudioLatency.summary(m).removeSuffix(")")+",jbNow=${delay("jitterBufferDelay")},jbTargetNow=${delay("jitterBufferTargetDelay")},concealedNow=${delta("concealedSamples")?.toLong() ?: "n/a"})"
        }
    }
}

package org.openuuyc.pico.uu

/** Decoder-thread-only pose metadata, bounded by both count and local residence time. */
internal class UuStereoOutput {
    companion object {
        const val MAX_PENDING=8
        const val MAX_AGE_MS=250L
        fun withinDeadline(arrived:Long,now:Long)=arrived>=0 && now>=arrived && now-arrived<=MAX_AGE_MS
    }
    private data class Entry(val frame:UuStereoFrames.Frame,val arrived:Long)
    private val entries=linkedMapOf<Long,Entry>()
    val size get()=entries.size
    fun contains(id:Long)=entries.containsKey(id)
    fun clear()=entries.clear()
    fun fresh(id:Long,now:Long)=entries[id]?.let {withinDeadline(it.arrived,now)} ?: false
    fun remove(id:Long) {entries.remove(id)}
    fun put(frame:UuStereoFrames.Frame,arrived:Long,now:Long):Boolean {
        if(size>=MAX_PENDING || contains(frame.id) || !withinDeadline(arrived,now))return false
        entries[frame.id]=Entry(frame,arrived);return true
    }
    fun take(id:Long,now:Long):UuStereoFrames.Frame?=entries.remove(id)?.takeIf {withinDeadline(it.arrived,now)}?.frame
}

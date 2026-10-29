package org.openuuyc.pico

/** At most one UI hover runnable is queued; clicks never wait behind old XR frames. */
internal class PicoLatestHover<T>(private val post: ((()->Unit)->Unit),private val consume: (T)->Unit) {
    private var latest: T?=null
    private var queued=false
    fun offer(value: T) {
        val schedule=synchronized(this) {latest=value;if(queued) false else {queued=true;true}}
        if(schedule) post {
            val next=synchronized(this) {val value=latest;latest=null;queued=false;value}
            next?.let(consume)
        }
    }
    @Synchronized fun clear() {latest=null}
}

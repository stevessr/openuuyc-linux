package org.openuuyc.pico.uu

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unordered/lossy media channel: one bounded frame, no stale-frame queue. */
internal class UuStereoFrames {
    data class Frame(val generation: Int,val id: Long,val predictedTime: Long,val width: Int,val height: Int,val codec: Int,val keyframe: Boolean,val views: FloatArray,val bytes: ByteArray,val receivedAtMs:Long?=null)
    companion object {const val CHANNEL="OPENUUYC_VR_VIDEO_V1";const val HEADER=136;const val CHUNK=48000;const val MAX=4*1024*1024}
    private var assembling: Frame?=null
    private var fragments=BooleanArray(0)
    private var received=0
    private var highest=0L
    private var generation=0
    private var started=0L
    var needsKeyframe=true;private set
    fun reset() {assembling=null;fragments=BooleanArray(0);received=0;highest=0;generation=0;needsKeyframe=true}
    fun lost() {assembling=null;needsKeyframe=true}
    fun accept(packet: ByteArray,nowMs: Long): Frame? {
        require(nowMs>=0)
        require(packet.size in HEADER+1..HEADER+CHUNK)
        val b=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int==0x5356554f && b.short.toInt()==1)
        val flags=b.short.toInt();require(flags in 0..1)
        val gen=b.int;val id=b.long;val time=b.long
        val width=b.short.toInt() and 65535;val height=b.short.toInt() and 65535;val codec=b.get().toInt()
        require(b.get()==0.toByte() && b.get()==0.toByte() && b.get()==0.toByte())
        val total=b.int;val offset=b.int;val count=b.int
        require(gen>0 && id>0 && time>0 && codec in 1..2 && width in 128..8192 && width%4==0 && height in 64..4096 && height%2==0)
        require(total in 1..MAX && offset>=0 && offset<total && offset%CHUNK==0 && count==minOf(CHUNK,total-offset) && packet.size==HEADER+count)
        val views=FloatArray(22) {b.float}
        for(base in listOf(0,11)) {
            require((0..10).all {views[base+it].isFinite()} && (0..2).all {kotlin.math.abs(views[base+it])<=1000})
            val norm=(3..6).sumOf {val v=views[base+it].toDouble();v*v};require(norm in 0.98..1.02)
            require((7..10).all {kotlin.math.abs(views[base+it])<1.56} && views[base+8]-views[base+7]>0.05 && views[base+9]-views[base+10]>0.05)
        }
        if(gen!=generation) {require(gen>generation);reset();generation=gen}
        if(id<highest || (id==highest && assembling==null)) return null
        if(assembling!=null && (id!=highest || nowMs-started>120)) lost()
        if(id>highest || assembling==null) {
            if(highest!=0L && id!=highest+1) needsKeyframe=true
            highest=id;started=nowMs
            assembling=Frame(gen,id,time,width,height,codec,flags==1,views,ByteArray(total),nowMs);fragments=BooleanArray((total+CHUNK-1)/CHUNK);received=0
        }
        val frame=checkNotNull(assembling)
        require(frame.bytes.size==total && frame.width==width && frame.height==height && frame.codec==codec && frame.keyframe==(flags==1) && frame.predictedTime==time && frame.views.contentEquals(views))
        val index=offset/CHUNK
        if(!fragments[index]) {b.get(frame.bytes,offset,count);fragments[index]=true;received++}
        if(received!=fragments.size) return null
        assembling=null
        if(needsKeyframe && !frame.keyframe) return null
        if(frame.keyframe) needsKeyframe=false
        return frame
    }
}

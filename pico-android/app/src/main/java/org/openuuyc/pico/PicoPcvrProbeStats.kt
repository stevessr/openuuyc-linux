package org.openuuyc.pico

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded diagnostic counters only: never stores room imagery or raw poses. */
internal class PicoPcvrProbeStats {
    private var samples=0L
    private var headValid=0L
    private val handsValid=LongArray(2)
    private var sequence=0L
    private var stageSamples=0L
    private var stageValid=0L
    private var surfaceAttempted=false
    private var surfaceCreated=false
    private var surfaceReference=0
    private var surfaceError=0
    private var stageSupported=false
    @Synchronized fun calibration(stage:FloatArray?) {
        stageSamples++
        if(stage!=null && stage.size==7 && stage.all {it.isFinite()} && stage.sliceArray(3..6).sumOf {it.toDouble()*it.toDouble()} in .98..1.02)stageValid++
    }
    @Synchronized fun surface(created:Boolean,reference:Int,error:Int,supportsStage:Boolean) {surfaceAttempted=true;surfaceCreated=created;surfaceReference=reference;surfaceError=error;stageSupported=supportsStage}
    @Synchronized fun initialization():JSONObject=JSONObject().put("surfaceAttempted",surfaceAttempted).put("surfaceCreated",surfaceCreated)
        .put("surfaceReferenceKind",surfaceReference).put("surfaceError",surfaceError).put("stageSupported",stageSupported)
        .put("stageSamples",stageSamples).put("stageValidSamples",stageValid).put("remoteVideo",false).put("probeOnly",true)
    @Synchronized fun accept(bytes: ByteArray) {
        val packet=org.openuuyc.pico.uu.UuPcvrTracking.packet(bytes,2) ?: return
        val b=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        if(b.getLong(8)<=sequence) return
        sequence=b.getLong(8);samples++
        if(b.getInt(32) and 3 == 3) headValid++
        for(i in 0..1) if(b.getInt(96+i*64) and 3 == 3) handsValid[i]++
    }
    @Synchronized fun report(frames: Long,leftWidth: Int,leftHeight: Int,rightWidth: Int,rightHeight: Int,ipd: Float,error: String?=null): JSONObject =
        JSONObject().put("projectionFrames",frames).put("trackingSamples",samples).put("headValid",headValid)
            .put("leftValid",handsValid[0]).put("rightValid",handsValid[1]).put("lastSequence",sequence)
            .put("leftWidth",leftWidth).put("leftHeight",leftHeight).put("rightWidth",rightWidth).put("rightHeight",rightHeight)
            .put("ipdMeters",if(ipd.isFinite()) ipd else 0f).put("error",error ?: JSONObject.NULL)
            .put("remoteVideo",false).put("probeOnly",true)
}

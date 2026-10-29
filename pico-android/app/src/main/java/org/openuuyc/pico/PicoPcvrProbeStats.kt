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
    @Synchronized fun accept(bytes: ByteArray) {
        if(bytes.size!=224 || !bytes.copyOfRange(0,4).contentEquals("OUVR".toByteArray())) return
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if(b.getInt(4)!=1 || b.getLong(8)<=sequence) return
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

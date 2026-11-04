package org.openuuyc.pico.uu

/** V2 retains v1 tracking; V3 appends explicitly opted-in gaze for capable hosts. */
internal object UuPcvrTracking {
    fun gazeAllowed(runtimeSupported:Boolean,sessionActive:Boolean,userConsent:Boolean,peerVersion:Int):Boolean =
        runtimeSupported && sessionActive && userConsent && peerVersion>=3

    fun packet(frame: ByteArray, version: Int, gazeVersion: Int=1): ByteArray? {
        if(frame.size!=224 && frame.size!=400 && frame.size!=444) return null
        if(frame[0]!=79.toByte() || frame[1]!=85.toByte() || frame[2]!=86.toByte() || frame[3]!=82.toByte()) return null
        if(frame[5]!=0.toByte() || frame[6]!=0.toByte() || frame[7]!=0.toByte()) return null
        val expected=when(frame.size) {224->1;400->2;else->3}
        if(frame[4].toInt()!=expected) return null
        if(expected==1) return frame
        if(expected==3) {
            if(gazeVersion>=3) return frame
            val v2=frame.copyOf(400).also {it[4]=2}
            return if(version>=2) v2 else downgradeV2(v2)
        }
        if(version>=2) return frame
        return downgradeV2(frame)
    }
    private fun downgradeV2(frame: ByteArray)=frame.copyOf(224).also {
        it[4]=1
        for(offset in intArrayOf(32,96,160)) it[offset]=(it[offset].toInt() and 15).toByte()
    }
}

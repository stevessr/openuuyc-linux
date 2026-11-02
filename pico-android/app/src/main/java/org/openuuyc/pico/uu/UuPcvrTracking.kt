package org.openuuyc.pico.uu

/** V2 retains v1 head/grip prefix; never invent aim/analog values for a v1 peer. */
internal object UuPcvrTracking {
    fun packet(frame: ByteArray, version: Int): ByteArray? {
        if(frame.size!=224 && frame.size!=400) return null
        if(frame[0]!=79.toByte() || frame[1]!=85.toByte() || frame[2]!=86.toByte() || frame[3]!=82.toByte()) return null
        if(frame[5]!=0.toByte() || frame[6]!=0.toByte() || frame[7]!=0.toByte()) return null
        val expected=if(frame.size==224) 1 else 2
        if(frame[4].toInt()!=expected) return null
        if(expected==1 || version>=2) return frame
        return frame.copyOf(224).also {it[4]=1;for(offset in intArrayOf(32,96,160)) it[offset]=(it[offset].toInt() and 15).toByte()}
    }
}

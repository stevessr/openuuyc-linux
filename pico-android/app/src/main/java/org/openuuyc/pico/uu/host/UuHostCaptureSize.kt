package org.openuuyc.pico.uu.host

internal object UuHostCaptureSize {
    fun fit(width: Int,height: Int): Pair<Int,Int> {
        require(width>0 && height>0)
        val scale=minOf(1.0,2160.0/maxOf(width,height))
        return maxOf(2,(width*scale).toInt()/2*2) to maxOf(2,(height*scale).toInt()/2*2)
    }
}

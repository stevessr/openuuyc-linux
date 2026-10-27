package org.openuuyc.pico

internal object PicoXrMenuHit {
    fun toolbarRect(i: Int)=floatArrayOf(16f+i*146f,130f,154f+i*146f,266f)
    fun settingsRect(i: Int)=floatArrayOf(16f+(i%2)*592f,50f+(i/2)*51f,592f+(i%2)*592f,96f+(i/2)*51f)
    private fun hit(u: Float,v: Float,count: Int,rect: (Int)->FloatArray): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        return (0 until count).firstOrNull {i -> val r=rect(i);u*1200 in r[0]..r[2] && v*400 in r[1]..r[3]}
    }
    fun toolbar(u: Float,v: Float)=hit(u,v,8,::toolbarRect)
    fun settings(u: Float,v: Float,count: Int=12)=hit(u,v,count,::settingsRect)
    fun cell(u: Float, v: Float, count: Int = 12): Int? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x=u*1200; val y=v*400-48
        if(y<0 || x>=1200) return null
        val column=(x/300).toInt(); val row=(y/104).toInt()
        if (column !in 0..3 || row !in 0..2 || x%300 !in 7f..293f || y%104 !in 5f..97f) return null
        return (row*4+column).takeIf { it<count }
    }
}

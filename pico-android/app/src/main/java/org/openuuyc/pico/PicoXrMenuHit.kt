package org.openuuyc.pico

internal object PicoXrMenuHit {
    fun toolbarRect(i: Int): FloatArray {
        if(i==8) return floatArrayOf(480f,330f,720f,408f)
        if(i==9) return floatArrayOf(480f,432f,720f,510f)
        if(i==10) return floatArrayOf(1060f,14f,1184f,76f)
        val centres=arrayOf(600f to 172f,886f to 243f,1005f to 416f,886f to 589f,
            600f to 660f,314f to 589f,195f to 416f,314f to 243f)
        val (x,y)=centres[i];return floatArrayOf(x-100,y-60,x+100,y+60)
    }
    fun settingsRect(i: Int)=floatArrayOf(16f+(i%2)*592f,50f+(i/2)*51f,592f+(i%2)*592f,96f+(i/2)*51f)
    private fun hit(u: Float,v: Float,count: Int,rect: (Int)->FloatArray,height: Float=400f): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        return (0 until count).firstOrNull {i -> val r=rect(i);u*1200 in r[0]..r[2] && v*height in r[1]..r[3]}
    }
    fun toolbar(u: Float,v: Float)=hit(u,v,11,::toolbarRect,800f)
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

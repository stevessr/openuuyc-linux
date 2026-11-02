package org.openuuyc.pico

internal object PicoXrMenuHit {
    fun toolbarRect(i: Int): FloatArray {
        if(i==8) return floatArrayOf(505f,342f,695f,402f)
        if(i==9) return floatArrayOf(505f,418f,695f,478f)
        if(i==10) return floatArrayOf(1060f,14f,1184f,76f)
        val angle=i*Math.PI/4
        val x=600f+220f*kotlin.math.sin(angle).toFloat()
        val y=410f-220f*kotlin.math.cos(angle).toFloat()
        return floatArrayOf(x-72,y-48,x+72,y+48)
    }
    fun settingsRect(i: Int)=floatArrayOf(16f+(i%2)*592f,50f+(i/2)*51f,592f+(i%2)*592f,96f+(i/2)*51f)
    private fun hit(u: Float,v: Float,count: Int,rect: (Int)->FloatArray,height: Float=400f): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        return (0 until count).firstOrNull {i -> val r=rect(i);u*1200 in r[0]..r[2] && v*height in r[1]..r[3]}
    }
    fun toolbar(u: Float,v: Float): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x=u*1200-600;val y=v*800-410
        val radius=kotlin.math.sqrt(x*x+y*y)
        if(radius in 142f..305f) {
            val angle=kotlin.math.atan2(x.toDouble(),-y.toDouble())
            val sector=kotlin.math.round(angle/(Math.PI/4)).toInt()
            // Match the visible gap between wedges.
            if(kotlin.math.abs(angle-sector*Math.PI/4)>Math.toRadians(20.5)) return null
            return (sector+8)%8
        }
        return hit(u,v,11,::toolbarRect,800f)?.takeIf {it>=8}
    }
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

package org.openuuyc.pico

/** Shared drawing/hit-test coordinates for the landscape parameter submenus. */
internal object PicoXrOptionLayout {
    fun bitrate(u: Float): Int = if(u.isFinite())
        (1+((u-.08f)/.84f).coerceIn(0f,1f)*499).toInt().coerceIn(1,500) else 1
    fun hit(u: Float,v: Float,count: Int): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x=u*600;val y=v*1000
        if(y in 12f..80f) return 10
        if(y in 730f..810f) return if(x<300) 8 else 9
        if(y in 860f..920f) return 10
        val row=((y-110)/70).toInt()
        return row.takeIf {y>=110 && it in 0 until minOf(8,count) && y-(110+it*70)<62 && x in 24f..576f}
    }
    fun pageOf(selected: Int): Int = (selected.coerceAtLeast(0)/8)*8
}

package org.openuuyc.pico

/**
 * Hit-test geometry for the landscape dropdown panels (1200×800 logical px).
 * A page is a title bar plus up to [rows] list rows; rows scroll by [offset].
 * Slider rows own a horizontal track: the same row rect, [PicoXrDropdownLayout.SLIDER_BASE]+i
 * on the track itself so joystick focus and trigger drags stay inside one region.
 */
internal object PicoXrDropdownLayout {
    const val SLIDER_BASE = 1000
    const val ROW_HEIGHT = 92f
    const val ROW_TOP = 132f
    const val ROW_LEFT = 24f
    const val ROW_RIGHT = 1176f
    const val FOOTER_TOP = 720f
    val visibleRows = ((FOOTER_TOP - ROW_TOP) / ROW_HEIGHT).toInt()

    /** Row index for u,v over the current page; -1 selects the header (back/close). */
    fun hit(u: Float,v: Float,count: Int,offset: Int=0,sliders: Int=0): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x=u*1200f;val y=v*800f
        if(y in 12f..96f) return -1
        val rows=minOf(count-offset,visibleRows).coerceAtLeast(0)
        for(i in 0 until rows) {
            val top=ROW_TOP+i*ROW_HEIGHT
            if(y in top..(top+ROW_HEIGHT-8) && x in ROW_LEFT..ROW_RIGHT)
                return if(i+offset<count-sliders) i+offset else i+offset
        }
        return null
    }

    /** Slider position 0..1 for a slider row hit; null when u,v is not on row [index]'s track. */
    fun sliderFraction(u: Float,v: Float,index: Int,count: Int,offset: Int=0): Float? {
        val row=hit(u,v,count,offset) ?: return null
        if(row!=index+offset) return null
        if(!u.isFinite()) return null
        return ((u*1200f-ROW_LEFT-380f)/ (ROW_RIGHT-ROW_LEFT-460f)).coerceIn(0f,1f)
    }

    /** Value mapped from a slider fraction with inclusive integer range (floor keeps .5 at the mid value). */
    fun sliderValue(fraction: Float,min: Int,max: Int): Int {
        if(!fraction.isFinite()) return min
        val span=(max-min).coerceAtLeast(0)
        return (min+(fraction*span).toInt()).coerceIn(min,max)
    }

    fun pageOf(selected: Int): Int = (selected.coerceAtLeast(0)/visibleRows)*visibleRows
}

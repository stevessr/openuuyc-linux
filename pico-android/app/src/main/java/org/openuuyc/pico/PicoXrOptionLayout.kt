package org.openuuyc.pico

/** Shared drawing/hit-test coordinates for the landscape parameter submenus. */
internal object PicoXrOptionLayout {
    fun bitrate(u: Float): Int = if(u.isFinite())
        (1+((u-.08f)/.84f).coerceIn(0f,1f)*499).toInt().coerceIn(1,500) else 1
    fun pageOf(selected: Int): Int = (selected.coerceAtLeast(0)/8)*8
}

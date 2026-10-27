package org.openuuyc.pico

/** Never replace a visible window or reuse a Surface still owned by closing RTC. */
internal object PicoXrSlotPolicy {
    fun pick(mainAlive: Boolean,visible: Set<Int>,reserved: Set<Int>,surfaces: Set<Int>): Int? =
        (0..5).firstOrNull {(!mainAlive || it!=0) && it !in visible && it !in reserved && it in surfaces}
}

package org.openuuyc.pico

/** Six data sectors plus two reserved page sectors, accessible by stick or ray. */
internal object PicoXrRingPage {
    const val SIZE=6
    fun pages(count: Int)=maxOf(1,(count.coerceAtLeast(0)+SIZE-1)/SIZE)
    fun clamp(page: Int,count: Int)=page.coerceIn(0,pages(count)-1)
    fun enabled(cell: Int,page: Int,count: Int): Boolean = when(cell) {
        in 0 until SIZE -> page*SIZE+cell<count
        6 -> page>0
        7 -> (page+1)*SIZE<count
        10 -> true
        else -> false
    }
}

package org.openuuyc.pico

/** Reuses hit-tested control regions, so joystick focus never targets panel gaps. */
internal object PicoMenuNavigation {
    data class Selection(val cell: Int,val u: Float,val v: Float)
    fun select(previous: Int,direction: Int,hit: (Float,Float)->Int?): Selection? {
        val points=linkedMapOf<Int,MutableList<Pair<Float,Float>>>()
        for(y in 0..23) for(x in 0..31) {
            val u=(x+.5f)/32;val v=(y+.5f)/24
            hit(u,v)?.let {points.getOrPut(it) {mutableListOf()}.add(u to v)}
        }
        val cells=points.keys.sorted();if(cells.isEmpty()) return null
        var index=cells.indexOf(previous).coerceAtLeast(0)
        if(direction>=100) {index=cells.indexOf(direction-100);if(index<0) return null}
        else if(direction!=0) index=(index+direction+cells.size)%cells.size
        val cell=cells[index];val candidates=points.getValue(cell)
        val meanU=candidates.map {it.first}.average().toFloat();val meanV=candidates.map {it.second}.average().toFloat()
        val point=candidates.minBy {(it.first-meanU)*(it.first-meanU)+(it.second-meanV)*(it.second-meanV)}
        return Selection(cell,point.first,point.second)
    }
}

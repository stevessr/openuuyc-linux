package org.openuuyc.pico

internal object PicoXrQualityLayout {
    data class Item(val action: Int,val x: Float,val y: Float,val w: Float,val h: Float)
    val items=listOf(Item(11,525f,12f,55f,60f),Item(3,24f,90f,552f,65f),
        Item(105,24f,175f,552f,62f),Item(104,24f,242f,552f,62f),Item(103,24f,309f,552f,62f),Item(102,24f,376f,552f,62f),
        Item(2,24f,452f,552f,62f),Item(98,24f,530f,552f,62f))+
        listOf(165,144,90,60,30).mapIndexed {i,fps -> Item(200+fps,24f+i*112f,655f,102f,56f)}+
        listOf(Item(99,24f,853f,552f,65f))
    fun hit(u: Float,v: Float): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        return items.firstOrNull {u*600 in it.x..it.x+it.w && v*1000 in it.y..it.y+it.h}?.action
    }
}

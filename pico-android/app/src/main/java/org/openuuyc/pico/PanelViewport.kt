package org.openuuyc.pico

/** Letterbox geometry shared by the Android video view and JVM regression tests. */
internal object PanelViewport {
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)
    fun fit(width: Int, height: Int, videoWidth: Int, videoHeight: Int): Box {
        if (width <= 0 || height <= 0) return Box(0, 0, 0, 0)
        val scale = minOf(width.toDouble() / videoWidth.coerceAtLeast(1), height.toDouble() / videoHeight.coerceAtLeast(1))
        val w = (videoWidth.coerceAtLeast(1) * scale).toInt().coerceIn(1, width)
        val h = (videoHeight.coerceAtLeast(1) * scale).toInt().coerceIn(1, height)
        return Box((width - w) / 2, (height - h) / 2, w, h)
    }
    fun coordinate(value: Float, extent: Int) = if (extent <= 0) 0f else (value / extent).coerceIn(0f, 1f)
}

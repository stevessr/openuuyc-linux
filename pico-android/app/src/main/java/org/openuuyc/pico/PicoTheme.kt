package org.openuuyc.pico

import android.graphics.Color

/** Exact palette/radii from src/ui/theme.rs; checked by the parity test. */
internal object PicoTheme {
    val BG = Color.rgb(22, 26, 33)
    val SIDEBAR = Color.rgb(16, 20, 26)
    val SURFACE = Color.rgb(31, 37, 47)
    val LINE = Color.rgb(45, 53, 66)
    val TEXT = Color.rgb(231, 235, 242)
    val MUTED = Color.rgb(156, 167, 184)
    val ACCENT = Color.rgb(75, 136, 235)
    val GREEN = Color.rgb(102, 207, 156)
    val AMBER = Color.rgb(238, 190, 111)
    val RED = Color.rgb(241, 125, 132)
    val HOVER = Color.rgb(44, 54, 69)
    val SELECTED = Color.rgb(33, 51, 77)
    const val CONTROL_RADIUS = 5
    const val PANEL_RADIUS = 8
    const val SIDEBAR_WIDTH = 188
    const val AUTH_QR_SIZE = 216
}

package org.openuuyc.pico
internal object PicoPointerStyle {
    val labels=listOf("Windows 7 风格触摸提示","Windows 11 风格触摸提示","被控端鼠标","主控端鼠标","自适应鼠标")
    fun remoteCursor(style: Int,textCursor: Boolean)=style==2 || (style==4 && textCursor)
    fun localCursor(style: Int,textCursor: Boolean)=style!=2 && !(style==4 && textCursor)
}

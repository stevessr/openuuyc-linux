package org.openuuyc.pico
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

internal object PicoKeyboardCanvas {
    fun draw(c: Canvas,mode: PicoKeyboardMode,keys: PicoKeyboardKeys,u: Float,v: Float) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=25f}
        listOf("系统键盘","自定义键盘","全键盘","返回").forEachIndexed {i,label ->
            p.color=if(i==mode.ordinal) PicoTheme.ACCENT else PicoTheme.SURFACE
            c.drawRoundRect(RectF(i*300+7f,48f,i*300+293f,90f),6f,6f,p)
            p.color=PicoTheme.TEXT;c.drawText(label,i*300+20f,78f,p)
        }
        if(mode==PicoKeyboardMode.SYSTEM) {p.color=PicoTheme.TEXT;c.drawText("已请求系统键盘；若未显示，可切换自定义键盘或全键盘",20f,160f,p);return}
        val rows=PicoKeyboardLayout.rows(mode)
        val height=264f/rows.size
        val hover=PicoKeyboardLayout.keyAt(mode,u,v)
        rows.forEachIndexed {r,row -> row.forEachIndexed {col,label ->
            val width=1200f/row.size;val x=col*width;val y=96+r*height
            p.color=if(hover==label || keys.active(label)) PicoTheme.ACCENT else PicoTheme.SURFACE
            c.drawRoundRect(RectF(x+3,y+3,x+width-3,y+height-3),5f,5f,p)
            p.color=PicoTheme.TEXT;p.textSize=if(label.length>3) 19f else 24f
            c.drawText(label,x+8,y+height*.65f,p)
        }}
    }
}

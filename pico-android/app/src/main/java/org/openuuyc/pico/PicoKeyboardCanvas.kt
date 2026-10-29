package org.openuuyc.pico
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

internal object PicoKeyboardCanvas {
    fun draw(c: Canvas,mode: PicoKeyboardMode,keys: PicoKeyboardKeys,u: Float,v: Float) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=25f}
        listOf("系统键盘","自定义键盘","全键盘","语音输入","返回").forEachIndexed {i,label ->
            PicoXrMaterial.control(c,RectF(i*240+7f,48f,i*240+233f,90f),i==mode.ordinal,u*1200 in i*240+7f..i*240+233f && v*400 in 48f..90f)
            p.color=PicoTheme.TEXT;c.drawText(label,i*240+20f,78f,p)
        }
        if(mode==PicoKeyboardMode.SYSTEM || mode==PicoKeyboardMode.VOICE) {p.color=PicoTheme.TEXT;c.drawText("系统键盘可切换语言；语音请点键盘麦克风，完成后点发送",20f,160f,p);return}
        val rows=PicoKeyboardLayout.rows(mode)
        val height=264f/rows.size
        val hover=PicoKeyboardLayout.keyAt(mode,u,v)
        rows.forEachIndexed {r,row -> row.forEachIndexed {col,label ->
            val width=1200f/row.size;val x=col*width;val y=96+r*height
            PicoXrMaterial.control(c,RectF(x+3,y+3,x+width-3,y+height-3),keys.active(label),hover==label)
            p.color=PicoTheme.TEXT;p.textSize=if(label.length>3) 19f else 24f
            c.drawText(label,x+8,y+height*.65f,p)
        }}
    }
}

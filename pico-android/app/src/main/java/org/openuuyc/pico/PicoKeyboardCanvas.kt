package org.openuuyc.pico
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

internal object PicoKeyboardCanvas {
    fun draw(c: Canvas,mode: PicoKeyboardMode,keys: PicoKeyboardKeys,u: Float,v: Float,voiceActive: Boolean=false) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=25f}
        listOf("输入方式 ›","返回").forEachIndexed {i,label ->
            val left=if(i==0) 7f else 967f;val right=if(i==0) 953f else 1193f
            PicoXrMaterial.control(c,RectF(left,48f,right,90f),false,u*1200 in left..right && v*400 in 48f..90f)
            p.color=PicoTheme.TEXT;c.drawText(label,left+20f,78f,p)
        }
        if(mode==PicoKeyboardMode.VOICE) {
            p.color=if(voiceActive) PicoTheme.RED else PicoTheme.ACCENT;c.drawCircle(600f,220f,64f,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=5f;p.color=android.graphics.Color.WHITE
            c.drawRoundRect(RectF(587f,185f,613f,226f),13f,13f,p)
            c.drawArc(RectF(578f,202f,622f,242f),0f,180f,false,p);c.drawLine(600f,242f,600f,254f,p);c.drawLine(589f,254f,611f,254f,p)
            p.style=Paint.Style.FILL;p.color=PicoTheme.TEXT;p.textSize=25f
            c.drawText(if(voiceActive) "正在聆听 · 松开 / 再次点击结束" else "点击开始再点结束，或按住说话松开结束",300f,330f,p)
            return
        }
        if(mode==PicoKeyboardMode.SYSTEM) {p.color=PicoTheme.TEXT;c.drawText("使用原生系统键盘输入多语言文字",20f,160f,p);return}
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

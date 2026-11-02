package org.openuuyc.pico
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

internal object PicoKeyboardCanvas {
    fun draw(c: Canvas,mode: PicoKeyboardMode,keys: PicoKeyboardKeys,u: Float,v: Float,voiceActive: Boolean=false) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=25f}
        val header=PicoKeyboardLayout.headerAt(mode,u,v)
        listOf(Triple("输入方式 ›",7f,383f),Triple("Codex 语音 · F8",393f,953f),Triple("返回",967f,1193f)).forEachIndexed {i,(label,left,right) ->
            PicoXrMaterial.control(c,RectF(left,48f,right,90f),false,header==PicoKeyboardLayout.Header.entries[i])
            p.color=PicoTheme.TEXT;c.drawText(label,left+20f,78f,p)
            if(i==1) {
                p.style=Paint.Style.STROKE;p.strokeWidth=2.5f;p.color=PicoTheme.ACCENT
                c.drawRoundRect(RectF(914f,55f,926f,72f),6f,6f,p)
                c.drawArc(RectF(908f,61f,932f,80f),0f,180f,false,p);c.drawLine(920f,80f,920f,85f,p)
                p.style=Paint.Style.FILL
            }
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
        val hover=PicoKeyboardLayout.keyAt(mode,u,v)
        PicoKeyboardLayout.keys(mode).forEach {key->
            val label=PicoKeyboardLayout.legend(key.label)
            val box=RectF(key.x+2,key.y+2,key.x+key.width-2,key.y+key.height-2)
            PicoXrMaterial.control(c,box,keys.active(key.label),hover==key.label)
            p.color=PicoTheme.TEXT;p.textSize=if(label.length>5) 16f else if(label.length>3) 18f else 21f
            while(p.measureText(label)>box.width()-8 && p.textSize>12f) p.textSize-=1f
            val secondary=if(mode==PicoKeyboardMode.FULL) PicoKeyboardLayout.shifted(key.label) else null
            val x=key.x+(key.width-p.measureText(label))/2
            c.drawText(label,x,key.y+key.height*(if(secondary!=null) .8f else .63f),p)
            if(secondary!=null) {p.textSize=12f;p.color=PicoTheme.MUTED;c.drawText(secondary,key.x+7,key.y+15,p)}
        }
    }
}

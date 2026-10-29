package org.openuuyc.pico
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** MR controller dashboard: one radial hub, no independent floating tool cards. */
internal object PicoXrControllerCanvas {
    fun draw(c: Canvas,labels: List<String>,following: Boolean,pending: Boolean,hand: Int,u: Float,v: Float,notice: String) {
        PicoXrMaterial.panel(c,1200f,800f)
        PicoXrMaterial.label(c,"OpenUUYC · 手柄面板",32f,58f,1000f,30f,medium=true)
        PicoXrMaterial.label(c,notice,32f,96f,1100f,20f,muted=true)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=Color.argb(75,105,170,220)
        c.drawOval(RectF(196f,170f,1004f,658f),p)
        c.drawCircle(600f,420f,122f,p)
        for(i in 0..10) {
            val r=PicoXrMenuHit.toolbarRect(i)
            val hovered=u*1200 in r[0]..r[2] && v*800 in r[1]..r[3]
            val selected=i==8 && following || i==9 && !following
            PicoXrMaterial.control(c,RectF(r[0],r[1],r[2],r[3]),selected,hovered,enabled=!(pending && i in 8..9))
            p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER
            p.color=if(i==7) PicoTheme.RED else if(hovered || selected) Color.rgb(161,221,255) else PicoTheme.TEXT
            if(i<8) {
                PicoXrMaterial.rootIcon(c,i,(r[0]+r[2])/2-18,r[1]+18,p.color)
                p.textSize=29f;c.drawText(labels[i],(r[0]+r[2])/2,r[3]-18,p)
            } else {
                p.textSize=if(i==10) 25f else 28f
                c.drawText(when(i) {8->if(pending) "切换中…" else "跟随视角";9->"固定窗口";else->"收起"},(r[0]+r[2])/2,(r[1]+r[3])/2+10,p)
            }
        }
        p.textSize=22f;p.color=PicoTheme.MUTED
        c.drawText("画面位置",600f,307f,p)
        c.drawText(if(hand==0) "左手柄固定 · 右手射线操作" else "右手柄固定 · 左手射线操作",600f,770f,p)
    }
    fun collapsed(c: Canvas,hand: Int) {
        c.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        PicoXrMaterial.sheet(c,RectF(32f,32f,1168f,368f),90f)
        PicoXrMaterial.label(c,"UU · ${if(hand==0) "左手" else "右手"}菜单 ▸",100f,240f,1000f,70f,medium=true)
    }
}

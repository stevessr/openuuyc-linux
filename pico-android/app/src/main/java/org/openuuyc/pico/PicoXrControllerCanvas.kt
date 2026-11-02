package org.openuuyc.pico
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** MR controller dashboard: one radial hub, no independent floating tool cards. */
internal object PicoXrControllerCanvas {
    fun draw(c: Canvas,labels: List<String>,following: Boolean,pending: Boolean,hand: Int,u: Float,v: Float,notice: String,icons: Boolean=true,title: String="快速控制",previous: Boolean=false,next: Boolean=false,appIcons: List<android.graphics.Bitmap?> = emptyList()) {
        c.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val hovered=PicoXrMenuHit.toolbar(u,v)
        val outer=RectF(295f,105f,905f,715f)
        val inner=RectF(458f,268f,742f,552f)
        for(i in labels.indices.take(8)) {
            if(labels[i].isBlank()) continue
            val start=-110.5f+i*45
            val end=start+41
            val a=Math.toRadians(end.toDouble())
            val wedge=android.graphics.Path().apply {
                arcTo(outer,start,41f)
                lineTo(600f+142f*kotlin.math.cos(a).toFloat(),410f+142f*kotlin.math.sin(a).toFloat())
                arcTo(inner,end,-41f);close()
            }
            p.style=Paint.Style.FILL
            p.color=if(hovered==i) Color.argb(245,46,139,192) else Color.argb(235,24,35,48)
            c.drawPath(wedge,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=if(hovered==i) 3f else 1.5f
            p.color=if(hovered==i) Color.rgb(135,229,255) else Color.argb(120,88,129,155)
            c.drawPath(wedge,p)
            val r=PicoXrMenuHit.toolbarRect(i)
            val x=(r[0]+r[2])/2;val y=(r[1]+r[3])/2
            val color=if(i==7) PicoTheme.RED else if(hovered==i) Color.WHITE else PicoTheme.TEXT
            val appIcon=appIcons.getOrNull(i)
            if(appIcon!=null) c.drawBitmap(appIcon,null,RectF(x-28,y-48,x+28,y+8),p)
            if(icons) PicoXrMaterial.rootIcon(c,i,x-18,y-29,color)
            p.style=Paint.Style.FILL;p.color=color;p.textSize=25f;p.textAlign=Paint.Align.CENTER
            PicoXrMaterial.label(c,labels[i],x-84,y+if(icons || appIcon!=null) 38 else 8,168f,if(icons) 25f else 22f)
        }
        p.style=Paint.Style.FILL;p.color=Color.argb(235,16,26,37);c.drawCircle(600f,410f,132f,p)
        p.textAlign=Paint.Align.CENTER;p.textSize=21f;p.color=PicoTheme.MUTED
        PicoXrMaterial.label(c,title,490f,322f,220f,20f)
        for(i in if(icons) 8..10 else 10..10) {
            val r=PicoXrMenuHit.toolbarRect(i)
            val selected=i==8 && following || i==9 && !following
            PicoXrMaterial.control(c,RectF(r[0],r[1],r[2],r[3]),selected,hovered==i,enabled=if(icons) !(pending && i in 8..9) else i==10 || (i==8 && previous) || (i==9 && next))
            p.style=Paint.Style.FILL;p.textSize=24f;p.color=PicoTheme.TEXT;p.textAlign=Paint.Align.CENTER
            c.drawText(when { !icons->when(i) {8->if(previous) "‹ 上一页" else "首页";9->if(next) "下一页 ›" else "末页";else->"返回"};else->when(i) {8->if(pending) "切换中…" else "跟随视角";9->"固定窗口";else->"收起"}},(r[0]+r[2])/2,(r[1]+r[3])/2+8,p)
        }
        p.textSize=20f;p.color=PicoTheme.MUTED;p.textAlign=Paint.Align.CENTER
        c.drawText("X / A 唤起 · 摇杆选择 · 快速松开执行",600f,752f,p)
        // Keep contextual status outside the ring rather than covering wedges.
        PicoXrMaterial.label(c,notice,200f,790f,800f,17f,muted=true)
    }

    fun collapsed(c: Canvas,hand: Int) {
        c.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
        PicoXrMaterial.sheet(c,RectF(32f,32f,1168f,368f),90f)
        PicoXrMaterial.label(c,"UU · ${if(hand==0) "左手" else "右手"}菜单 ▸",100f,240f,1000f,70f,medium=true)
    }
}

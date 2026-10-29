package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

internal object PicoXrOptionCanvas {
    fun draw(c: Canvas,title: String,labels: List<String>,selected: Int,offset: Int,draft: Int,u: Float,v: Float,notice: String,pressed: Boolean=false) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        c.save();c.scale(2f,.4f);PicoXrMaterial.panel(c,600f,1000f)
        fun text(value: String,x: Float,y: Float,muted: Boolean=false) {
            PicoXrMaterial.label(c,value,x,y,576f-x,26f,muted)
        }
        fun box(label: String,x: Float,y: Float,w: Float,h: Float,active: Boolean,selected: Boolean=false) {
            PicoXrMaterial.control(c,RectF(x,y,x+w,y+h),selected=selected,hovered=active,pressed=pressed)
            text(label,x+16,y+h/2+9)
        }
        PicoXrMaterial.label(c,"‹  $title",24f,55f,552f,29f,medium=true)
        if(title=="自定义码率") {
            text("视频码率     $draft / 500 Mbps",24f,180f)
            p.strokeWidth=5f;p.color=Color.rgb(58,68,82);c.drawLine(48f,450f,552f,450f,p)
            val x=48f+(draft-1)/499f*504f;p.color=PicoTheme.ACCENT;c.drawLine(48f,450f,x,450f,p)
            p.color=Color.rgb(218,231,251);c.drawCircle(x,450f,14f,p);p.color=PicoTheme.ACCENT;c.drawCircle(x,450f,7f,p);text("1",48f,510f,true);text("500",490f,510f,true)
            text("调整后点击应用；取消不会发送",24f,640f,true)
            box("取消",24f,740f,260f,130f,u<.5f && v in .7f.. .92f)
            box("应用",316f,740f,260f,130f,u>=.5f && v in .7f.. .92f,true)
        } else {
            val rows=labels.drop(offset).take(8);val hit=PicoXrOptionLayout.hit(u,v,rows.size)
            if(rows.isEmpty()) text("远端未提供可用选项",24f,155f,true)
            rows.forEachIndexed {i,label ->box("${if(offset+i==selected) "✓ " else "    "}$label",24f,110f+i*70,552f,62f,hit==i,offset+i==selected)}
            box(if(offset>0) "‹ 上一页" else "已是首页",24f,730f,260f,80f,hit==8 && offset>0)
            box(if(offset+8<labels.size) "下一页 ›" else "已是末页",316f,730f,260f,80f,hit==9 && offset+8<labels.size)
            box("返回",24f,860f,552f,60f,hit==10)
        }
        PicoXrMaterial.label(c,notice,24f,950f,552f,18f,true)
        PicoXrMaterial.handle(c,600f,1000f,v>=.95f)
        c.restore()
    }
}

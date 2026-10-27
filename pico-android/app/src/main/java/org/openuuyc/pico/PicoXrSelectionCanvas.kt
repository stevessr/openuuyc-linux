package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

internal object PicoXrSelectionCanvas {
    data class Row(val name: String,val status: String,val selected: Boolean,val online: Boolean)
    fun hit(u: Float,v: Float,count: Int): Int? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val x=u*1200;val y=v*400
        if(y in 342f..366f) return (x/300).toInt().coerceAtMost(3)+8
        val height=if(count>8) 28f else 34f
        if(x !in 16f..1184f || y<48) return null
        val i=((y-48)/height).toInt()
        return i.takeIf {it in 0 until count && (y-48)%height<height-3}
    }
    fun draw(c: Canvas,title: String,rows: List<Row>,u: Float,v: Float) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(22,26,33));p.textSize=24f;p.color=Color.rgb(156,167,184);c.drawText(title,24f,31f,p)
        val height=if(rows.size>8) 28f else 34f
        rows.forEachIndexed {i,row ->
            val y=48f+i*height;val hovered=hit(u,v,rows.size)==i
            if(row.selected || hovered) {p.color=if(hovered) Color.rgb(34,53,78) else Color.rgb(32,39,49);c.drawRoundRect(RectF(16f,y,1184f,y+height-3),7f,7f,p)}
            p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=Color.rgb(75,136,235)
            c.drawRoundRect(RectF(31f,y+5,56f,y+height-10),3f,3f,p);p.style=Paint.Style.FILL
            p.textSize=23f;p.color=Color.rgb(231,235,242)
            val n=p.breakText(row.name,true,870f,null);c.drawText(row.name.take(n),76f,y+height-10,p)
            p.color=if(row.selected) Color.rgb(75,136,235) else if(row.online) Color.rgb(82,188,145) else Color.rgb(156,167,184)
            val status=if(row.selected) "✓ 已打开" else row.status
            c.drawText(status,1160f-p.measureText(status),y+height-10,p)
        }
        listOf("上一页","下一页","刷新","返回").forEachIndexed {i,label -> p.color=if(hit(u,v,rows.size)==i+8) Color.rgb(75,136,235) else Color.rgb(156,167,184);p.textSize=20f;c.drawText(label,i*300f+28,363f,p)}
        p.color=if(v>=.92f) Color.rgb(75,136,235) else Color.rgb(128,138,152);c.drawRoundRect(RectF(400f,374f,800f,390f),8f,8f,p)
    }
}

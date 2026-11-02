package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

internal object PicoToolbarIcons {
    fun draw(c: Canvas,kind: String,x: Float,y: Float,size: Float,color: Int) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=2.5f;strokeCap=Paint.Cap.ROUND}
        c.save();c.translate(x,y);c.scale(size/32,size/32)
        if(kind=="mic") {
            c.drawRoundRect(RectF(12f,3f,20f,21f),4f,4f,p)
            c.drawArc(RectF(8f,10f,24f,26f),0f,180f,false,p)
            c.drawLine(8f,13f,8f,18f,p);c.drawLine(24f,13f,24f,18f,p)
            c.drawLine(16f,26f,16f,30f,p);c.drawLine(12f,30f,20f,30f,p)
        } else {
            c.drawRoundRect(RectF(7f,5f,25f,29f),3f,3f,p)
            c.drawRoundRect(RectF(12f,3f,20f,8f),2f,2f,p)
        }
        c.restore()
    }
}

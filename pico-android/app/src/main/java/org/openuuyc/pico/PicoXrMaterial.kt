package org.openuuyc.pico

import android.graphics.*

/** Shared MR glass-like surfaces. Premultiplied alpha, not camera-image blur. */
internal object PicoXrMaterial {
    private val text=Color.rgb(232,237,245)
    fun panel(c: Canvas,w: Float,h: Float) {
        c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        sheet(c,RectF(3f,2f,w-3,h-5),24f)
    }
    /** Does not clear its canvas: safe for independent cells in the frame atlas. */
    fun sheet(c: Canvas,r: RectF,radius: Float=20f) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=Color.argb(42,0,0,0)
        c.drawRoundRect(RectF(r.left,r.top+3,r.right,r.bottom+3),radius,radius,p)
        p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,
            intArrayOf(Color.argb(226,44,54,68),Color.argb(214,23,31,43)),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(r,radius,radius,p)
        p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=1.4f
        p.color=Color.argb(100,190,211,239);c.drawRoundRect(r,radius,radius,p)
        p.style=Paint.Style.FILL
    }
    fun control(c: Canvas,r: RectF,selected: Boolean=false,hovered: Boolean=false,pressed: Boolean=false,enabled: Boolean=true) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val top=when { !enabled->Color.rgb(29,34,42);pressed&&hovered->Color.rgb(57,105,168);hovered->Color.rgb(45,66,95);selected->Color.rgb(38,61,93);else->Color.rgb(36,43,54) }
        val bottom=when { !enabled->Color.rgb(26,31,39);pressed&&hovered->Color.rgb(42,81,135);hovered->Color.rgb(35,52,76);selected->Color.rgb(31,49,75);else->Color.rgb(30,36,46) }
        val alpha=if(!enabled) 100 else if(hovered || selected) 238 else 160
        val glassTop=(top and 0x00ffffff) or (alpha shl 24)
        val glassBottom=(bottom and 0x00ffffff) or (alpha shl 24)
        p.shader=LinearGradient(0f,r.top,0f,r.bottom,glassTop,glassBottom,Shader.TileMode.CLAMP)
        c.drawRoundRect(r,10f,10f,p);p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=if(hovered) 1.8f else 1f
        p.color=when { !enabled->Color.rgb(41,47,57);hovered->Color.rgb(102,160,242);selected->Color.rgb(64,102,156);else->Color.rgb(49,59,74) }
        c.drawRoundRect(r,10f,10f,p)
    }
    fun label(c: Canvas,value: String,x: Float,y: Float,width: Float,size: Float=26f,muted: Boolean=false,medium: Boolean=false) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=if(muted) PicoTheme.MUTED else text;textSize=size
            typeface=Typeface.create(if(medium) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        }
        val count=p.breakText(value,true,width.coerceAtLeast(0f),null)
        val display=if(count<value.length && count>1) value.take(count-1)+"…" else value.take(count)
        c.drawText(display,x,y,p)
    }
    fun rootIcon(c: Canvas,kind: Int,x: Float,y: Float,color: Int) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=2.4f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        c.save();c.translate(x,y)
        when(kind) {
            0 -> {c.drawRoundRect(RectF(7f,0f,29f,34f),10f,10f,p);c.drawLine(18f,0f,18f,13f,p);c.drawLine(8f,13f,28f,13f,p)}
            1 -> {c.drawRoundRect(RectF(0f,5f,36f,29f),4f,4f,p);for(row in 0..1) for(col in 0..4) c.drawPoint(6f+col*6,11f+row*6,p);c.drawLine(10f,24f,26f,24f,p)}
            2 -> {c.drawRoundRect(RectF(0f,1f,36f,25f),3f,3f,p);c.drawLine(18f,25f,18f,33f,p);c.drawLine(10f,33f,26f,33f,p)}
            3 -> {c.drawOval(RectF(0f,10f,36f,25f),p);c.drawOval(RectF(10f,0f,26f,35f),p);c.drawCircle(18f,18f,3f,p)}
            4 -> {c.drawCircle(18f,18f,17f,p);c.drawLine(2f,23f,11f,13f,p);c.drawLine(11f,13f,22f,26f,p);c.drawLine(22f,26f,30f,18f,p)}
            5 -> {val path=Path().apply {moveTo(1f,16f);lineTo(18f,1f);lineTo(35f,16f);moveTo(6f,13f);lineTo(6f,33f);lineTo(30f,33f);lineTo(30f,13f)};c.drawPath(path,p)}
            6 -> {for(row in 0..1) for(col in 0..1) c.drawRoundRect(RectF(col*21f,row*21f,col*21f+14f,row*21f+14f),3f,3f,p)}
            7 -> {c.drawArc(RectF(2f,2f,34f,34f),-55f,290f,false,p);c.drawLine(18f,0f,18f,17f,p)}
        }
        c.restore()
    }
    fun handle(c: Canvas,w: Float,h: Float,hover: Boolean) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(hover) PicoTheme.ACCENT else Color.rgb(117,128,145)}
        c.drawRoundRect(RectF(w*.36f,h-22,w*.64f,h-15),4f,4f,p)
    }
}

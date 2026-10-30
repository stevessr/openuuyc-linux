package org.openuuyc.pico

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.Surface

/** One atlas row per window: independent hover states, transparent video area. */
internal class PicoXrWindowFrame(private val surface: Surface) {
    private val titles=Array(6) {"正在连接…"}
    private val microphones=BooleanArray(6)
    private val clipboards=BooleanArray(6)
    fun sharing(pane: Int,mic: Boolean,clip: Boolean) {
        if(pane in 0..5 && (microphones[pane]!=mic || clipboards[pane]!=clip)) {microphones[pane]=mic;clipboards[pane]=clip;draw()}
    }
    fun title(pane: Int,text: String) {if(pane in 0..5 && titles[pane]!=text) {titles[pane]=text;draw()}}
    private var background=Color.BLACK
    private var backgroundImage: Bitmap?=null
    private var backgroundAlpha=0
    private var brightness=1f
    fun background(color: Int,image: Bitmap?,alpha: Int,brightness: Int) {background=color;backgroundImage=image;backgroundAlpha=alpha.coerceIn(0,255);this.brightness=brightness.coerceIn(25,100)/100f;draw()}
    private var hover=-1
    private var pointerDown=false
    fun pointer(target: Int,down: Boolean) {
        if(target==hover && down==pointerDown) return
        hover=target;pointerDown=down;draw()
    }
    fun hover(target: Int) { if(target!=hover) {hover=target;draw()} }
    fun draw() {
        if(!surface.isValid) return
        val c=runCatching {surface.lockCanvas(null)}.getOrNull() ?: return
        try {
            c.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(6) { pane ->
                c.save();c.translate((pane%2)*1200f,(pane/2)*800f)
                val layout=PicoXrFrameLayout
                val left=layout.WIDTH/2-layout.WIDTH/(2*layout.SCALE_X)
                val right=layout.WIDTH-left
                val bottom=layout.HEIGHT/2+layout.HEIGHT/(2*layout.SCALE_Y)
                // Thin connected-device title bar; the video area stays transparent.
                val header=android.graphics.Path().apply {
                    addRoundRect(RectF(left,8f,right,55.2f),floatArrayOf(18f,18f,18f,18f,0f,0f,0f,0f),android.graphics.Path.Direction.CW)
                }
                p.color=Color.argb(239,19,23,29);c.drawPath(header,p)
                p.style=Paint.Style.STROKE;p.strokeWidth=1.3f;p.color=Color.argb(85,168,184,205)
                c.drawRoundRect(RectF(left,8f,right,bottom),18f,18f,p)
                p.style=Paint.Style.FILL
                fun region(values: FloatArray,target: Int,active: Boolean=false) {
                    if(hover==target || active) PicoXrMaterial.control(c,RectF(values[0],values[1],values[2],values[3]),active,hover==target,pointerDown)
                }
                region(layout.device,49+pane)
                region(layout.screen,55+pane)
                p.style=Paint.Style.STROKE;p.strokeWidth=2.1f;p.color=PicoTheme.MUTED
                c.drawRoundRect(RectF(64f,23f,85f,37f),2f,2f,p)
                c.drawLine(74.5f,37f,74.5f,41f,p);c.drawLine(69f,41f,80f,41f,p)
                p.style=Paint.Style.FILL
                val device=titles[pane].substringBefore(" - ")+" ▾"
                PicoXrMaterial.label(c,device,98f,40f,214f,23f,medium=true)
                p.color=Color.argb(62,182,194,210);c.drawRect(335f,21f,336f,43f,p)
                val screen=titles[pane].substringAfter(" - ","屏幕")+" ▾"
                PicoXrMaterial.label(c,screen,360f,40f,410f,23f)
                for(slot in 0..1) {
                    val active=if(slot==0) microphones[pane] else clipboards[pane]
                    val rect=if(slot==0) layout.microphone else layout.clipboard
                    val target=(if(slot==0) 67 else 73)+pane
                    region(rect,target)
                    PicoToolbarIcons.draw(c,if(slot==0) "mic" else "clipboard",rect[0]+9,19f,25f,
                        if(active || hover==target) PicoTheme.ACCENT else PicoTheme.MUTED)
                }
                region(layout.quality,43+pane);region(layout.add,61+pane)
                p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=if(hover==43+pane) PicoTheme.ACCENT else PicoTheme.MUTED
                for(i in 0..2) {val y=24f+i*8;c.drawLine(922f,y,944f,y,p);c.drawCircle(if(i%2==0) 929f else 939f,y,2.4f,p)}
                p.color=if(hover==61+pane) PicoTheme.ACCENT else PicoTheme.MUTED
                c.drawLine(985f,32f,1005f,32f,p);c.drawLine(995f,22f,995f,42f,p)
                val close=hover==37+pane
                p.style=Paint.Style.FILL;p.color=if(close) Color.argb(220,151,55,66) else Color.argb(42,230,96,107)
                c.drawCircle(1125f,32f,15f,p)
                p.style=Paint.Style.STROKE;p.strokeWidth=2.2f;p.color=if(close) Color.WHITE else Color.rgb(224,105,115)
                c.drawArc(RectF(1117f,24f,1133f,40f),-55f,290f,false,p);c.drawLine(1125f,21f,1125f,31f,p)
                // Compact PICO-style rail: grey until hovered, blue while moving.
                p.style=Paint.Style.FILL;p.color=if(hover==7+pane) PicoTheme.ACCENT else Color.argb(214,100,110,123)
                c.drawRoundRect(RectF(470f,766f,730f,782f),8f,8f,p)
                p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.strokeCap=Paint.Cap.ROUND
                repeat(4) {corner ->
                    // Quiet idle corners; only the active external resize handle lights up.
                    p.color=if(hover==13+pane*4+corner) PicoTheme.ACCENT else Color.argb(36,184,199,217)
                    val rightCorner=corner%2==1;val low=corner>=2
                    val x=if(rightCorner) 1175f else 25f;val y=if(low) 752f else 12f
                    c.drawLine(x,y,x+if(rightCorner) -20f else 20f,y,p)
                    c.drawLine(x,y,x,y+if(low) -18f else 18f,p)
                }
                p.style=Paint.Style.FILL
                c.restore()
            }
            // Atlas-only white tile: reserved outside all window subimages.
            p.style=Paint.Style.FILL;p.color=Color.WHITE
            c.drawRect(0f,2400f,32f,2432f,p)
            // Transparent circular pointer tiles, sampled by native billboard quads.
            p.color=Color.WHITE;c.drawCircle(48f,2416f,12f,p)
            p.color=PicoTheme.ACCENT;c.drawCircle(80f,2416f,12f,p)
            // Win7 outline ring, Win11 translucent touch target, local arrow.
            p.style=Paint.Style.STROKE;p.strokeWidth=2.5f;p.color=Color.WHITE;c.drawCircle(112f,2416f,11f,p)
            p.style=Paint.Style.FILL;p.color=Color.argb(100,180,220,255);c.drawCircle(144f,2416f,13f,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=Color.WHITE;c.drawCircle(144f,2416f,12f,p)
            val arrow=android.graphics.Path().apply {moveTo(174f,2416f);lineTo(174f,2430f);lineTo(180f,2424f);lineTo(187f,2424f);close()}
            p.style=Paint.Style.FILL;p.color=Color.WHITE;c.drawPath(arrow,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=Color.BLACK;c.drawPath(arrow,p);p.style=Paint.Style.FILL
            c.save();c.clipRect(0,2432,1200,3232)
            p.color=background;p.alpha=backgroundAlpha
            p.colorFilter=android.graphics.ColorMatrixColorFilter(floatArrayOf(brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,1f,0f))
            if(backgroundImage==null || backgroundImage?.isRecycled==true) c.drawRect(0f,2432f,1200f,3232f,p)
            backgroundImage?.takeUnless {it.isRecycled}?.let {image ->
                val scale=maxOf(1200f/image.width,800f/image.height)
                val w=image.width*scale;val h=image.height*scale
                p.color=Color.WHITE;p.alpha=backgroundAlpha;p.isFilterBitmap=true
                c.drawBitmap(image,null,RectF((1200-w)/2,2432+(800-h)/2,(1200+w)/2,2432+(800+h)/2),p)
            }
            c.restore()
        } finally {runCatching {surface.unlockCanvasAndPost(c)}}
    }
    fun close() {surface.release()}
}

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
    private var pointerPane=-1
    private var pointerU=0f
    private var pointerV=0f
    private var pointerDown=false
    private var lastDraw=0L
    fun pointer(target: Int,u: Float,v: Float,down: Boolean,touchMode: Boolean) {
        val pane=if(touchMode && target in 0..5 && u in 0f..1f && v in 0f..1f) target else -1
        val now=android.os.SystemClock.uptimeMillis()
        val changed=pane!=pointerPane || down!=pointerDown || target!=hover
        if(!changed && (pane<0 || now-lastDraw<33 || (kotlin.math.abs(u-pointerU)<.002f && kotlin.math.abs(v-pointerV)<.002f))) return
        hover=target;pointerPane=pane;pointerU=u;pointerV=v;pointerDown=down;lastDraw=now;draw()
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
                p.color=Color.argb(245,22,27,35)
                c.drawRoundRect(RectF(100f,0f,1100f,54f),14f,14f,p)
                p.color=if(hover==49+pane) Color.rgb(34,53,78) else Color.rgb(32,39,49)
                c.drawRoundRect(RectF(110f,5f,330f,49f),7f,7f,p)
                p.color=PicoTheme.TEXT;p.textSize=24f
                val device=titles[pane].substringBefore(" - ")+" ▾"
                c.drawText(device.take(p.breakText(device,true,190f,null)),122f,35f,p)
                for(slot in 0..1) {
                    val active=if(slot==0) microphones[pane] else clipboards[pane]
                    val hovered=hover==(if(slot==0) 67 else 73)+pane
                    val x=340f+slot*65
                    p.color=if(active || hovered) Color.rgb(34,53,78) else Color.rgb(22,26,33)
                    c.drawRoundRect(RectF(x,5f,x+55,49f),7f,7f,p)
                    PicoToolbarIcons.draw(c,if(slot==0) "mic" else "clipboard",x+12,11f,30f,if(active || hovered) PicoTheme.ACCENT else PicoTheme.MUTED)
                }
                p.color=if(hover==55+pane) PicoTheme.ACCENT else Color.rgb(34,53,78)
                c.drawRoundRect(RectF(475f,5f,880f,49f),7f,7f,p)
                p.color=PicoTheme.TEXT
                val screen="▣ "+titles[pane].substringAfter(" - ","屏幕")
                c.drawText(screen.take(p.breakText(screen,true,370f,null)),488f,35f,p)
                p.color=PicoTheme.ACCENT;c.drawRect(490f,47f,866f,50f,p)
                p.color=if(hover==43+pane) PicoTheme.ACCENT else Color.rgb(32,39,49)
                c.drawRoundRect(RectF(900f,5f,1010f,49f),7f,7f,p)
                p.color=Color.WHITE;c.drawText("画质",929f,35f,p)
                p.color=if(hover==61+pane) PicoTheme.ACCENT else PicoTheme.TEXT;p.textSize=32f;c.drawText("+",1050f,37f,p)
                val drag=hover==7+pane
                val close=hover==37+pane
                p.color=if(drag) PicoTheme.ACCENT else Color.rgb(128,138,152)
                c.drawRoundRect(RectF(300f,762f,880f,786f),12f,12f,p)
                p.color=if(close) Color.rgb(225,88,95) else Color.rgb(90,100,116)
                c.drawRoundRect(RectF(910f,748f,1000f,798f),12f,12f,p)
                p.color=Color.WHITE;p.strokeWidth=5f
                c.drawLine(940f,762f,970f,786f,p);c.drawLine(970f,762f,940f,786f,p)
                p.style=Paint.Style.STROKE;p.strokeWidth=6f
                repeat(4) { corner ->
                    p.color=if(hover==13+pane*4+corner) PicoTheme.ACCENT else Color.rgb(128,138,152)
                    val right=corner%2==1;val bottom=corner>=2
                    val x=if(right) 1184f else 16f;val y=if(bottom) 784f else 16f
                    c.drawLine(x,y,x+if(right) -72f else 72f,y,p)
                    c.drawLine(x,y,x,y+if(bottom) -46f else 46f,p)
                }
                p.style=Paint.Style.FILL
                if(pointerPane==pane) {
                    val x=(.5f+(pointerU-.5f)/1.06f)*1200
                    val y=(.5f+(pointerV-.5f)/1.16f)*800
                    p.style=Paint.Style.FILL;p.color=Color.argb(110,0,0,0)
                    c.drawCircle(x,y,if(pointerDown) 13f else 10f,p)
                    p.color=if(pointerDown) PicoTheme.ACCENT else Color.WHITE
                    c.drawCircle(x,y,if(pointerDown) 9f else 6f,p)
                }
                c.restore()
            }
            // Atlas-only white tile: reserved outside all window subimages.
            p.style=Paint.Style.FILL;p.color=Color.WHITE
            c.drawRect(0f,2400f,32f,2432f,p)
            // Transparent circular pointer tiles, sampled by native billboard quads.
            p.color=Color.WHITE;c.drawCircle(48f,2416f,12f,p)
            p.color=PicoTheme.ACCENT;c.drawCircle(80f,2416f,12f,p)
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

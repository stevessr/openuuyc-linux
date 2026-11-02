package org.openuuyc.pico

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/** Ordinary resizable Android video surface; never creates an OpenXR session. */
internal class PicoPanelVideoView(
    context: Context,
    private val attach: (Surface) -> Unit,
    private val detach: () -> Unit,
    private val pointer: (Float, Float, Boolean) -> Unit,
    private val scroll: (Int) -> Unit,
    private val onTap: () -> Unit = {},
    private val touch: (Int, List<org.openuuyc.pico.uu.UuInputWire.Contact>) -> Boolean = { _, _ -> false }
) : FrameLayout(context) {
    private val video = SurfaceView(context)
    private val hint = android.widget.TextView(context).apply {
        text = "等待远端画面…"; textSize = 16f; setTextColor(PicoTheme.MUTED)
        gravity = android.view.Gravity.CENTER; setPadding(24, 24, 24, 24)
    }
    private var videoWidth = 1920
    private var videoHeight = 1080
    var touchMode = false
        set(value) { if (field != value) releasePointer(); field = value }
    var pointerStyle=4
        set(value) {field=value.coerceIn(0,4);invalidate()}
    var remoteTextCursor=false
        set(value) {field=value;invalidate()}
    private var pointerVisible=false
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if(!pointerVisible || !PicoPointerStyle.localCursor(pointerStyle,remoteTextCursor) || (pointerStyle in 0..1 && !pressed)) return
        val x=video.left+u*video.width;val y=video.top+v*video.height
        val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        if(pointerStyle in 0..1) {
            p.color=if(pointerStyle==0) Color.WHITE else Color.argb(110,180,220,255)
            p.style=if(pointerStyle==0) android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL;p.strokeWidth=3f
            canvas.drawCircle(x,y,if(pointerStyle==0) 16f else 20f,p)
        } else {
            val path=android.graphics.Path().apply {moveTo(x,y);lineTo(x,y+24);lineTo(x+7,y+17);lineTo(x+17,y+17);close()}
            p.color=Color.WHITE;canvas.drawPath(path,p);p.style=android.graphics.Paint.Style.STROKE;p.strokeWidth=2f;p.color=Color.BLACK;canvas.drawPath(path,p)
        }
    }
    private var downU = 0f
    private var downV = 0f
    private var moved = false
    private var pressed = false
    private var u = .5f
    private var v = .5f
    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(-1, -1))
        addView(hint, LayoutParams(-1, -1))
        video.contentDescription = "远程桌面：触摸移动和点击，鼠标滚轮滚动"
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { attach(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { releasePointer(); detach() }
        })
        video.setOnTouchListener { _, event ->
            u = PanelViewport.coordinate(event.x, video.width)
            v = PanelViewport.coordinate(event.y, video.height)
            if (touchMode) {
                val contacts = (0 until event.pointerCount.coerceAtMost(10)).filter {
                    event.actionMasked != MotionEvent.ACTION_POINTER_UP || it != event.actionIndex
                }.map { i -> org.openuuyc.pico.uu.UuInputWire.Contact(event.getPointerId(i),
                    PanelViewport.coordinate(event.getX(i), video.width), PanelViewport.coordinate(event.getY(i), video.height),
                    event.getPressure(i).coerceIn(0f, 1f)) }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pressed = true; moved = false; downU = u; downV = v; touch(1, contacts) }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { moved = true; touch(2, contacts) }
                    MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(u - downU) + kotlin.math.abs(v - downV) > .03f) moved = true; touch(2, contacts) }
                    MotionEvent.ACTION_UP -> { touch(3, emptyList()); pressed = false; if (!moved) onTap(); video.performClick() }
                    MotionEvent.ACTION_CANCEL -> releasePointer()
                }
            } else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = true; moved = false; downU = u; downV = v; parent?.requestDisallowInterceptTouchEvent(true); pointer(u, v, true) }
                MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(u - downU) + kotlin.math.abs(v - downV) > .03f) moved = true; pointer(u, v, pressed) }
                MotionEvent.ACTION_UP -> { pointer(u, v, false); pressed = false; if (!moved) onTap(); video.performClick() }
                MotionEvent.ACTION_CANCEL -> releasePointer()
            }
            pointerVisible=true;invalidate()
            true
        }
        video.setOnHoverListener { _, event ->
            pointerVisible=event.actionMasked!=MotionEvent.ACTION_HOVER_EXIT;invalidate()
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                u = PanelViewport.coordinate(event.x, video.width)
                v = PanelViewport.coordinate(event.y, video.height)
                pointer(u, v, false)
            }
            true
        }
        video.setOnGenericMotionListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                scroll((event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().coerceIn(-1200, 1200)); true
            } else false
        }
    }
    fun showStatus(text: String) {
        if (text.contains("第一帧")) hint.visibility = GONE
        else if (hint.visibility != GONE && !text.startsWith("媒体诊断")) hint.text = "等待远端画面\n$text"
    }
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == videoWidth && height == videoHeight)) return
        videoWidth = width; videoHeight = height; requestLayout()
    }
    fun releasePointer() { if (pressed) { if (touchMode) touch(4, emptyList()) else pointer(u, v, false) }; pressed = false;invalidate() }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val box = PanelViewport.fit(right - left, bottom - top, videoWidth, videoHeight)
        video.layout(box.left, box.top, box.left + box.width, box.top + box.height)
        hint.layout(0, 0, right - left, bottom - top)
    }
}

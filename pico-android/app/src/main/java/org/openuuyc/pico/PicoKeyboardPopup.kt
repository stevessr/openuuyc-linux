package org.openuuyc.pico

import android.app.Activity
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import org.openuuyc.pico.uu.UuDirectSession

internal object PicoKeyboardPopup {
    fun show(activity: Activity,anchor: View,session: UuDirectSession,system: ()->Unit) {
        val keys=PicoKeyboardKeys(session::key)
        var mode=PicoKeyboardMode.CUSTOM
        lateinit var popup: PopupWindow
        val panel=object: View(activity) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(PicoTheme.BG)
                canvas.save();canvas.scale(width/1200f,height/400f)
                PicoKeyboardCanvas.draw(canvas,mode,keys,-1f,-1f)
                canvas.restore()
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if(event.actionMasked==MotionEvent.ACTION_UP) {
                    val u=event.x/width;val v=event.y/height
                    if(v*400 in 48f..90f && u in 0f..<1f) {
                        keys.release();val choice=(u*4).toInt()
                        if(choice==3) popup.dismiss() else {
                            mode=PicoKeyboardMode.entries[choice]
                            if(mode==PicoKeyboardMode.SYSTEM) {popup.dismiss();system()}
                        }
                    } else PicoKeyboardLayout.keyAt(mode,u,v)?.let(keys::pressed)
                    invalidate();performClick()
                }
                return true
            }
            override fun performClick(): Boolean {super.performClick();return true}
        }
        val width=(activity.resources.displayMetrics.widthPixels*.9f).toInt()
        popup=PopupWindow(panel,width,width/3,true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(PicoTheme.SIDEBAR))
            isOutsideTouchable=true;setOnDismissListener { keys.release() }
        }
        popup.showAsDropDown(anchor)
    }
}

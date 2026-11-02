package org.openuuyc.pico

import android.app.Activity
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import org.openuuyc.pico.uu.UuDirectSession

internal object PicoKeyboardPopup {
    fun show(activity: Activity,anchor: View,session: UuDirectSession,system: ()->Unit,voice: ()->Unit=system) {
        val keys=PicoKeyboardKeys(session::key)
        var mode=PicoKeyboardMode.CUSTOM
        var hoverU=-1f;var hoverV=-1f
        val footerHeight=(64*activity.resources.displayMetrics.density).toInt()
        val statusLabel=android.widget.TextView(activity).apply {
            text="Codex 语音：先聚焦远端 Codex，再点击语音键"
            setTextColor(android.graphics.Color.WHITE);textSize=14f
            setPadding(12,4,12,4);maxLines=3
            setBackgroundColor(PicoTheme.SIDEBAR)
        }
        lateinit var popup: PopupWindow
        val panel=object: View(activity) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(PicoTheme.BG)
                canvas.save();canvas.scale(width/1200f,height/PicoKeyboardLayout.height(mode))
                PicoKeyboardCanvas.draw(canvas,mode,keys,hoverU,hoverV)
                canvas.restore()

            }
            private fun chooseMode() {
                android.widget.PopupMenu(activity,this).apply {
                    listOf("系统键盘","自定义键盘","全键盘 · 标准104键","语音输入").forEachIndexed {index,label->
                        menu.add(label).setOnMenuItemClickListener {
                            keys.release();mode=PicoKeyboardMode.entries[index]
                            when(mode) {
                                PicoKeyboardMode.SYSTEM->{popup.dismiss();system()}
                                PicoKeyboardMode.VOICE->{popup.dismiss();voice()}
                                else->popup.update(popup.width,(popup.width*PicoKeyboardLayout.height(mode)/1200).toInt()+footerHeight)
                            }
                            invalidate();true
                        }
                    };show()
                }
            }
            override fun onHoverEvent(event: MotionEvent): Boolean {
                hoverU=if(event.actionMasked==MotionEvent.ACTION_HOVER_EXIT) -1f else event.x/width
                hoverV=event.y/height;invalidate();return true
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                hoverU=event.x/width;hoverV=event.y/height
                if(event.actionMasked==MotionEvent.ACTION_UP) {
                    when(PicoKeyboardLayout.headerAt(mode,hoverU,hoverV)) {
                        PicoKeyboardLayout.Header.MODE->chooseMode()
                        PicoKeyboardLayout.Header.BACK->popup.dismiss()
                        PicoKeyboardLayout.Header.CODEX_VOICE->{
                            session.codexVoice(activity,keys::codexVoice) {message->
                                android.widget.Toast.makeText(activity,message,android.widget.Toast.LENGTH_LONG).show()
                                statusLabel.text=message;invalidate()
                            }
                        }
                        null->PicoKeyboardLayout.keyAt(mode,hoverU,hoverV)?.let(keys::pressed)
                    }
                    performClick()
                }
                if(event.actionMasked==MotionEvent.ACTION_CANCEL) keys.release()
                invalidate();return true
            }
            override fun performClick(): Boolean {super.performClick();return true}
        }
        val width=(activity.resources.displayMetrics.widthPixels*.9f).toInt()
        val content=android.widget.LinearLayout(activity).apply {
            orientation=android.widget.LinearLayout.VERTICAL
            addView(panel,android.widget.LinearLayout.LayoutParams(-1,0,1f))
            addView(statusLabel,android.widget.LinearLayout.LayoutParams(-1,footerHeight))
        }
        popup=PopupWindow(content,width,width/3+footerHeight,true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(PicoTheme.SIDEBAR))
            isOutsideTouchable=true;setOnDismissListener { keys.release() }
        }
        popup.showAsDropDown(anchor)
    }
}

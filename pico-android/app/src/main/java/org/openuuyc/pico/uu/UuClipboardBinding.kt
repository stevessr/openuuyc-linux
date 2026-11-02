package org.openuuyc.pico.uu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** Explicit foreground-only text clipboard. Contents are never logged or persisted. */
internal class UuClipboardBinding(context: Context,private val permitted: ()->Boolean,private val publish: (String)->Boolean) {
    private val manager=context.getSystemService(ClipboardManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val generation=AtomicLong()
    @Volatile var enabled=false;private set
    private var sent: String?=null
    private var received: String?=null
    private val listener=ClipboardManager.OnPrimaryClipChangedListener {read()}
    fun enable(value: Boolean) {
        generation.incrementAndGet();enabled=value
        main.post {
            manager.removePrimaryClipChangedListener(listener);sent=null;received=null
            if(enabled) {manager.addPrimaryClipChangedListener(listener);read()}
        }
    }
    private fun read() {
        if(!enabled || !permitted()) return
        val text=runCatching {manager.primaryClip?.takeIf {it.itemCount>0}?.getItemAt(0)?.text?.toString()}.getOrNull() ?: return
        if(text==received || text==sent || text.isEmpty() || text.toByteArray().size>UuClipboardWire.MAX_BYTES) return
        if(publish(text)) sent=text
    }
    fun write(text: String,done: (Boolean)->Unit) {
        val ticket=generation.get()
        main.post {
            if(!enabled || generation.get()!=ticket || !permitted()) {done(false);return@post}
            done(runCatching {received=text;manager.setPrimaryClip(ClipData.newPlainText("UU 远程文本",text));true}.getOrDefault(false))
        }
    }
    fun focusChanged() {generation.incrementAndGet();main.post {if(enabled && permitted()) read() else {received=null;sent=null}}}
}

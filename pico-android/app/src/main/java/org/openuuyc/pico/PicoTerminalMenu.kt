package org.openuuyc.pico

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.openuuyc.pico.uu.UuDirectSession
import org.openuuyc.pico.uu.UuTerminal
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One visible terminal per connection. No command or output is persisted. */
internal object PicoTerminalMenu {
    private val dialogs=WeakHashMap<UuTerminal,WeakReference<AlertDialog>>()

    fun show(activity: Activity,direct: UuDirectSession,title: String="远端终端") {
        if(activity.isFinishing || activity.isDestroyed) return
        val terminal=direct.terminal
        dialogs[terminal]?.get()?.takeIf {it.isShowing}?.let { dialog ->
            if(dialog.ownerActivity===activity) {dialog.window?.decorView?.requestFocus();return}
            dialog.dismiss()
        }
        val owner=Any()
        var screen=PicoTerminalScreen()
        val density=activity.resources.displayMetrics.density
        fun dp(value: Int)=(value*density).toInt()
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12))
        }
        val status=TextView(activity)
        val note=TextView(activity).apply {
            text="在远端执行；需要远端允许终端。仅发送你明确提交的输入。"
            textSize=12f
        }
        val display=TextView(activity).apply {
            typeface=Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true)
            setTextColor(PicoTheme.TEXT);setBackgroundColor(PicoTheme.BG)
            setPadding(dp(6),dp(6),dp(6),dp(6))
        }
        val horizontal=HorizontalScrollView(activity).apply {addView(display)}
        val scroller=ScrollView(activity).apply {isFillViewport=true;addView(horizontal)}
        val input=EditText(activity).apply {
            hint="输入内容，点击发送或键盘发送键"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            imeOptions=EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        root.addView(note);root.addView(status)
        root.addView(scroller,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        root.addView(input)
        val actions=LinearLayout(activity)
        val keys=LinearLayout(activity)
        val inputButtons=mutableListOf<Button>()
        fun button(row: LinearLayout,label: String,action: ()->Unit): Button = Button(activity).apply {
            text=label;setOnClickListener {action()}
            row.addView(this,LinearLayout.LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        }
        fun submit() { if(terminal.input(input.text.toString()+"\r")) input.text.clear() }
        val retry=button(actions,"重新打开") {screen=PicoTerminalScreen();display.text="";terminal.open()}
        inputButtons+=button(actions,"发送 ↵",::submit)
        for((label,value) in listOf("Ctrl+C" to "\u0003","Tab" to "\t","↑" to "\u001b[A","↓" to "\u001b[B")) {
            inputButtons+=button(keys,label) {terminal.input(value)}
        }
        input.setOnEditorActionListener { _,action,_ ->
            if(action==EditorInfo.IME_ACTION_SEND) {if(terminal.active) submit();true} else false
        }
        root.addView(actions);root.addView(keys)
        fun update(message: String) {
            status.text=message
            input.isEnabled=terminal.active
            inputButtons.forEach {it.isEnabled=terminal.active}
            retry.isEnabled=!terminal.active && !terminal.opening
            retry.text=if(terminal.opening) "正在打开…" else "重新打开"
        }
        val dialog=AlertDialog.Builder(activity).setTitle(title).setView(root)
            .setNegativeButton("退出终端",null).create()
        dialog.setOwnerActivity(activity)
        dialog.setOnDismissListener {
            terminal.detach(owner)
            if(dialogs[terminal]?.get()===dialog) dialogs.remove(terminal)
        }
        root.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) {dialog.dismiss();terminal.detach(owner)}
        })
        dialogs[terminal]=WeakReference(dialog)
        update("等待终端通道…")
        terminal.attach(owner,::update) {bytes ->
            val follow=!scroller.canScrollVertically(1)
            screen.append(bytes);display.text=screen.text(includeHistory=true)
            if(follow) scroller.post {scroller.fullScroll(View.FOCUS_DOWN)}
        }
        dialog.show()
        dialog.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT)
        }
        // show() is the user's explicit terminal action; binding a connection alone never opens one.
        terminal.open()
    }
}

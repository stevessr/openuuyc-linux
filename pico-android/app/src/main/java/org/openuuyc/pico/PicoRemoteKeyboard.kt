package org.openuuyc.pico

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/** IME composing remains local; only committed text travels over authenticated CONTROL. */
internal class PicoRemoteKeyboard(
    context: Context,
    private val text: (String) -> Boolean,
    private val key: (Int, Boolean) -> Boolean
) : View(context) {
    private val held = mutableSetOf<Int>()
    init { isFocusable = true; isFocusableInTouchMode = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var dialog: android.app.AlertDialog?=null
    private var editor: android.widget.EditText?=null
    internal val nativeEditor: android.widget.EditText? get()=editor
    private var editorGeneration=0
    var imeStateChanged: (String)->Unit={}
    private var imeTask: Runnable?=null
    private fun cancelImeRequest() {imeTask?.let {editor?.removeCallbacks(it)};imeTask=null}
    /** Use a real native EditText/window token; the old 1px custom View wasn't a reliable IME host. */
    fun show() {
        val activity=context as? android.app.Activity ?: return
        if(activity.isFinishing || activity.isDestroyed) return
        if(dialog?.isShowing==true && editor!=null) {requestIme();return}
        // A voice dialog has no text editor/token. It must not swallow a
        // subsequent system-keyboard request.
        hide()
        releaseKeys()
        val input=object: android.widget.EditText(context) {
            override fun onCreateInputConnection(info: EditorInfo): InputConnection? {
                val connection=super.onCreateInputConnection(info)
                info.imeOptions=(info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()) or
                    EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                info.actionLabel="发送";info.actionId=EditorInfo.IME_ACTION_SEND
                info.hintLocales=android.os.LocaleList.getDefault()
                return connection
            }
        }.apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setSingleLine(false);minLines=2;maxLines=5
            hint="使用系统键盘切换语言；候选词确认后，点发送"
            filters=arrayOf(android.text.InputFilter.LengthFilter(16384))
            setPadding(24,16,24,16)
        }
        editor=input
        val submission=PicoTextSubmission(text)
        val native=android.app.AlertDialog.Builder(activity).setTitle("远程文本输入 · 系统键盘")
            .setView(input).setPositiveButton("发送",null).setNegativeButton("取消",null)
            .setNeutralButton("系统键盘语音",null).create()
        dialog=native;editorGeneration++
        fun send() {
            if(submission.submit(input.text.toString())) native.dismiss()
            else android.widget.Toast.makeText(context,"文本未发送，请检查连接后重试",android.widget.Toast.LENGTH_SHORT).show()
        }
        input.setOnEditorActionListener {_,action,event ->
            if(action in setOf(EditorInfo.IME_ACTION_SEND,EditorInfo.IME_ACTION_DONE,EditorInfo.IME_ACTION_GO) ||
                (event?.keyCode==KeyEvent.KEYCODE_ENTER && event.isCtrlPressed && event.action==KeyEvent.ACTION_DOWN)) {send();true} else false
        }
        native.setOnShowListener {
            native.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            native.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            native.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {send()}
            native.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {showVoiceInput()}
            input.requestFocus();requestIme()
        }
        native.setOnDismissListener {
            submission.cancel();cancelImeRequest();editorGeneration++
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
            if(dialog===native) {dialog=null;editor=null}
            input.text.clear();releaseKeys()
        }
        // Configure BEFORE showing; PICO IME needs a focusable editor window,
        // not a keyboard request against the OpenXR rendering surface.
        native.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        native.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        native.show()
    }
    private fun requestIme() {
        val input=editor ?: return
        cancelImeRequest()
        val generation=editorGeneration
        val gate=PicoImeLaunchGate()
        // Use the editor's display context. PICO's panel and immersive windows
        // can live on different displays; the Activity's service can be stale.
        val ime=input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val result=object: android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int,data: android.os.Bundle?) {
                if(generation!=editorGeneration || editor!==input) return
                val shown=code==InputMethodManager.RESULT_SHOWN || code==InputMethodManager.RESULT_UNCHANGED_SHOWN
                gate.acknowledge(shown)
                imeStateChanged(if(shown) "PICO 系统输入法已响应" else "系统输入法尚未显示，正在重试")
            }
        }
        val task=object: Runnable {
            override fun run() {
                if(generation!=editorGeneration || editor!==input || dialog?.isShowing!=true) return
                input.requestFocus()
                val visible=android.os.Build.VERSION.SDK_INT>=30 && input.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true
                when(gate.step(input.isAttachedToWindow && input.windowToken!=null,input.hasWindowFocus(),visible)) {
                    PicoImeLaunchGate.Step.DONE->{imeTask=null;return}
                    PicoImeLaunchGate.Step.TIMEOUT->{imeTask=null;imeStateChanged("系统键盘未响应，请点击输入框重试或切换自定义键盘");return}
                    PicoImeLaunchGate.Step.CONNECT->ime.restartInput(input)
                    PicoImeLaunchGate.Step.SHOW->{
                        ime.showSoftInput(input,0,result)
                        if(android.os.Build.VERSION.SDK_INT>=30) input.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                    }
                    PicoImeLaunchGate.Step.WAIT->Unit
                }
                input.postDelayed(this,150)
            }
        }
        imeTask=task;input.post(task)
    }
    var voiceStarting: ()->Unit={}
    var voiceChanged: (Boolean,String)->Unit={_,_->}
    private var voiceButton: android.widget.Button?=null
    private val voiceGesture=PicoVoiceGesture()
    private val voice by lazy {PicoVoiceInput(context as android.app.Activity,text) {active,message->
        voiceButton?.text=if(active) "🎙 正在聆听" else "🎙 点击 / 按住说话"
        voiceChanged(active,message)
        if(voiceButton!=null) android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()
    }}
    val voiceListening get()=voice.listening
    fun startVoice() {voiceStarting();voice.start()}
    fun stopVoice() {voice.stop()}
    fun cancelVoice() {voiceGesture.reset();voice.cancel()}
    fun showVoiceInput() {
        val activity=context as? android.app.Activity ?: return
        hide()
        val button=android.widget.Button(context).apply {
            text="🎙 点击 / 按住说话";textSize=26f;minHeight=180
            setOnTouchListener {view,event ->
                when(event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN->when(voiceGesture.down(android.os.SystemClock.elapsedRealtime(),voice.listening)) {PicoVoiceGesture.Action.START->startVoice();else->Unit}
                    android.view.MotionEvent.ACTION_UP->{if(voiceGesture.up(android.os.SystemClock.elapsedRealtime())==PicoVoiceGesture.Action.STOP) voice.stop();view.performClick()}
                    android.view.MotionEvent.ACTION_CANCEL->cancelVoice()
                };true
            }
        }
        voiceButton=button
        val native=android.app.AlertDialog.Builder(activity).setTitle("语音输入").setView(button).setNegativeButton("关闭",null).create()
        dialog=native;native.setOnDismissListener {cancelVoice();voiceButton=null;if(dialog===native) dialog=null};native.show()
    }
    fun hide() {cancelVoice();cancelImeRequest();editorGeneration++;dialog?.dismiss();dialog=null;editor=null;clearFocus();releaseKeys()}
    fun releaseKeys() { held.toList().forEach { key(it, false) }; held.clear() }
    private fun dispatchRemoteKey(event: KeyEvent): Boolean {
        val vk = vk(event.keyCode) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        if (!key(vk, down)) return false
        if (down) held.add(vk) else held.remove(vk)
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = dispatchRemoteKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = dispatchRemoteKey(event) || super.onKeyUp(keyCode, event)
    companion object {
        fun vk(code: Int): Int? = when(code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> code - KeyEvent.KEYCODE_A + 65
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0 + 48
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> code - KeyEvent.KEYCODE_F1 + 112
            KeyEvent.KEYCODE_DEL -> 8; KeyEvent.KEYCODE_FORWARD_DEL -> 46; KeyEvent.KEYCODE_ENTER -> 13
            KeyEvent.KEYCODE_TAB -> 9; KeyEvent.KEYCODE_ESCAPE -> 27; KeyEvent.KEYCODE_SPACE -> 32
            KeyEvent.KEYCODE_DPAD_LEFT -> 37; KeyEvent.KEYCODE_DPAD_UP -> 38; KeyEvent.KEYCODE_DPAD_RIGHT -> 39; KeyEvent.KEYCODE_DPAD_DOWN -> 40
            KeyEvent.KEYCODE_CTRL_LEFT -> 162; KeyEvent.KEYCODE_CTRL_RIGHT -> 163
            KeyEvent.KEYCODE_SHIFT_LEFT -> 160; KeyEvent.KEYCODE_SHIFT_RIGHT -> 161
            KeyEvent.KEYCODE_ALT_LEFT -> 164; KeyEvent.KEYCODE_ALT_RIGHT -> 165
            KeyEvent.KEYCODE_META_LEFT -> 91; KeyEvent.KEYCODE_META_RIGHT -> 92
            KeyEvent.KEYCODE_MOVE_HOME -> 36; KeyEvent.KEYCODE_MOVE_END -> 35
            KeyEvent.KEYCODE_PAGE_UP -> 33; KeyEvent.KEYCODE_PAGE_DOWN -> 34
            else -> null
        }
    }
}

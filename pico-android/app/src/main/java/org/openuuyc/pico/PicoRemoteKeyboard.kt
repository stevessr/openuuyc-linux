package org.openuuyc.pico

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
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
    fun show() {
        requestFocus()
        post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun hide() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)
        clearFocus();releaseKeys()
    }
    fun releaseKeys() { held.toList().forEach { key(it, false) }; held.clear() }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            private val buffer = Editable.Factory.getInstance().newEditable("")
            override fun getEditable() = buffer
            override fun commitText(value: CharSequence?, newCursorPosition: Int): Boolean {
                val committed = value?.toString().orEmpty()
                val sent = committed.isEmpty() || text(committed)
                if (sent) buffer.clear()
                return sent
            }
            override fun finishComposingText(): Boolean {
                val value = buffer.toString()
                if (value.isNotEmpty() && !text(value)) return false
                buffer.clear(); return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (buffer.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
                repeat(beforeLength.coerceIn(0, 32)) { key(8, true); key(8, false) }
                repeat(afterLength.coerceIn(0, 32)) { key(46, true); key(46, false) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent) = dispatchRemoteKey(event)
            override fun performEditorAction(editorAction: Int): Boolean { key(13, true); key(13, false); return true }
        }
    }
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

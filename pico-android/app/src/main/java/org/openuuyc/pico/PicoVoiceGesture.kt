package org.openuuyc.pico
internal class PicoVoiceGesture {
    enum class Action { NONE,START,STOP }
    private var began: Long?=null
    private var wasListening=false
    fun down(now: Long,listening: Boolean): Action {
        if(began!=null) return Action.NONE
        began=now;wasListening=listening
        return if(listening) Action.NONE else Action.START
    }
    fun up(now: Long): Action {
        val start=began ?: return Action.NONE;began=null
        return if(wasListening || now-start>=300) Action.STOP else Action.NONE
    }
    fun reset() {began=null;wasListening=false}
}

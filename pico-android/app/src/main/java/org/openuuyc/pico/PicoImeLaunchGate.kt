package org.openuuyc.pico

/** Never restart a composing input connection while retrying a show request. */
internal class PicoImeLaunchGate(private val limit: Int=16) {
    enum class Step { WAIT,CONNECT,SHOW,DONE,TIMEOUT }
    private var attempts=0
    private var connected=false
    private var complete=false
    fun step(attached: Boolean,focused: Boolean,visible: Boolean): Step {
        if(complete) return Step.DONE
        if(visible) {complete=true;return Step.DONE}
        if(attempts++>=limit) {complete=true;return Step.TIMEOUT}
        if(!attached || !focused) return Step.WAIT
        if(!connected) {connected=true;return Step.CONNECT}
        return Step.SHOW
    }
    fun acknowledge(shown: Boolean) {if(shown) complete=true}
}

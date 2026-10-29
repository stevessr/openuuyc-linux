package org.openuuyc.pico
/** IME composition/dictation stays local until explicit confirmation. No ASCII key conversion. */
internal class PicoTextSubmission(private val send: (String)->Boolean) {
    private var finished=false
    fun submit(value: String): Boolean {
        if(finished) return true
        if(value.toByteArray(Charsets.UTF_8).size>64*1024) return false
        if(value.isNotEmpty() && !send(value)) return false
        finished=true;return true
    }
    fun cancel() {finished=true}
}

package org.openuuyc.pico.uu
/** Coalesces UI output with a bounded buffer and generation-safe delivery. */
internal class UuTerminalOutput(private val limit: Int=512*1024) {
    data class Append(val accepted: Boolean,val ticket: Long?)
    private val bytes=java.io.ByteArrayOutputStream()
    private var generation=0L
    private var scheduled=false
    @Synchronized fun append(value: ByteArray): Append {
        if(value.size>limit-bytes.size()) return Append(false,null)
        bytes.write(value)
        if(scheduled) return Append(true,null)
        scheduled=true;return Append(true,generation)
    }
    @Synchronized fun drain(ticket: Long): ByteArray? {
        if(ticket!=generation) return null
        scheduled=false
        return bytes.toByteArray().also {bytes.reset()}
    }
    @Synchronized fun reset() {generation++;bytes.reset();scheduled=false}
}

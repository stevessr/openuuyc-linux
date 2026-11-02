package org.openuuyc.pico.uu
import java.nio.ByteBuffer
import java.nio.ByteOrder
internal object UuTerminalWire {
    const val MAX_PAYLOAD=256*1024
    data class Frame(val kind: Int,val session: Int,val payload: ByteArray)
    fun encode(kind: Int,session: Int,payload: ByteArray=byteArrayOf()): ByteArray {
        require(kind in 1..16 && session>=0 && payload.size<=MAX_PAYLOAD)
        return ByteBuffer.allocate(16+payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("TERM".toByteArray(Charsets.US_ASCII)).put(1).put(kind.toByte()).putShort(0).putInt(session).putInt(payload.size).put(payload).array()
    }
    fun decode(bytes: ByteArray): Frame? {
        if(bytes.size<16 || bytes.size>16+MAX_PAYLOAD) return null
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if(b.int!=0x4d524554 || b.get().toInt()!=1) return null
        val kind=b.get().toInt() and 255
        if(kind !in 1..16 || b.short.toInt()!=0) return null
        val session=b.int;val length=b.int
        if(session<0 || length !in 0..MAX_PAYLOAD || length!=b.remaining()) return null
        return Frame(kind,session,ByteArray(length).also {b.get(it)})
    }
}

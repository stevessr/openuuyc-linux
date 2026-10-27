package org.openuuyc.pico.uu

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object UuInputWire {
    data class Contact(val id: Int, val x: Float, val y: Float, val pressure: Float = 1f)
    fun text(value: String): ByteArray {
        require(value.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        return JSONObject().put("action", "text_input").put("content", value).toString().toByteArray(Charsets.UTF_8)
    }
    fun key(vk: Int, down: Boolean): ByteArray {
        require(vk in 1..254)
        return JSONObject().put("action", if (down) "kbd_press" else "kbd_release").put("key", vk).toString().toByteArray(Charsets.UTF_8)
    }
    /** CONTROL PB.InputEvent(20) -> Input.touch(1) -> Touch(kind, points).
     * Native multi-touch, not mouse packets disguised as touch.
     */
    fun touch(kind: Int, contacts: List<Contact>): ByteArray {
        require(kind in 1..4 && contacts.size <= 10 && contacts.map { it.id }.distinct().size == contacts.size)
        contacts.forEach { require(it.id >= 0 && UuPointerWire.valid(it.x, it.y) && it.pressure.isFinite() && it.pressure in 0f..1f) }
        fun float(out: java.io.ByteArrayOutputStream, tag: Int, value: Float) {
            out.write((tag shl 3) or 5)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }
        return UuWire.message { envelope -> UuWire.bytes(envelope, 20, UuWire.message { input ->
            UuWire.bytes(input, 1, UuWire.message { touch ->
                UuWire.integer(touch, 1, kind.toLong())
                contacts.forEach { point -> UuWire.bytes(touch, 2, UuWire.message { p ->
                    UuWire.integer(p, 1, point.id.toLong()); float(p, 2, point.x); float(p, 3, point.y)
                    float(p, 4, .01f); float(p, 5, .01f); float(p, 6, 0f); float(p, 7, point.pressure)
                }) }
            })
        }) }
    }
}

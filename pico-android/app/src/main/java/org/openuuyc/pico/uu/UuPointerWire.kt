package org.openuuyc.pico.uu

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Remote-input format mirrored from src/features/remote_input.rs.
 * Pointer coordinates are normalized in [0,1), not pixels; invalid input
 * is rejected instead of snapping to a remote display edge.
 */
internal object UuPointerWire {
    fun valid(u: Float, v: Float): Boolean =
        u.isFinite() && v.isFinite() && u in 0f..1f && v in 0f..1f

    fun move(screen: Int, u: Float, v: Float): ByteArray {
        require(screen >= 0) { "negative UU screen ID" }
        require(valid(u, v)) { "pointer outside remote screen" }
        return JSONObject()
            .put("action", "mouse_move_absolute")
            .put("screen_id", screen)
            .put("abs_x", u.toDouble().coerceAtMost(0.999999))
            .put("abs_y", v.toDouble().coerceAtMost(0.999999))
            .toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun button(down: Boolean): ByteArray =
        JSONObject()
            .put("action", if (down) "mouse_press" else "mouse_release")
            .put("button", 1)
            .toString().toByteArray(StandardCharsets.UTF_8)
}

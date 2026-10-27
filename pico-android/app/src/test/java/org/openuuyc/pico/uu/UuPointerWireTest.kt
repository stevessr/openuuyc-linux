package org.openuuyc.pico.uu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UuPointerWireTest {
    @Test fun normalizedAbsoluteMouseMatchesDesktopJsonShape() {
        val json = JSONObject(String(UuPointerWire.move(7, 0.5f, 0.25f)))
        assertEquals("mouse_move_absolute", json.getString("action"))
        assertEquals(7, json.getInt("screen_id"))
        assertEquals(0.5, json.getDouble("abs_x"), 0.00001)
        assertEquals(0.25, json.getDouble("abs_y"), 0.00001)
    }

    @Test fun rightBottomEdgeNeverCrossesHostUnitInterval() {
        val json = JSONObject(String(UuPointerWire.move(0, 1f, 1f)))
        assertTrue(json.getDouble("abs_x") < 1.0)
        assertTrue(json.getDouble("abs_y") < 1.0)
        assertTrue(json.getDouble("abs_x") >= 0.0)
    }

    @Test fun offscreenInvalidAndNonfiniteCoordinatesNeverClampToOtherMonitor() {
        for (point in listOf(
            -0.001f to 0.5f, 1.1f to 0.1f,
            Float.NaN to 0.3f, Float.POSITIVE_INFINITY to 0.4f,
            0f to Float.NEGATIVE_INFINITY
        )) {
            assertFalse(UuPointerWire.valid(point.first, point.second))
            assertThrows(IllegalArgumentException::class.java) {
                UuPointerWire.move(0, point.first, point.second)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuPointerWire.move(-1, 0.1f, 0.1f)
        }
    }

    @Test fun pressAndReleaseAreReversibleUUEvents() {
        val press = JSONObject(String(UuPointerWire.button(true)))
        val release = JSONObject(String(UuPointerWire.button(false)))
        assertEquals("mouse_press", press.getString("action"))
        assertEquals("mouse_release", release.getString("action"))
        assertEquals(1, press.getInt("button"))
        assertEquals(press.getInt("button"), release.getInt("button"))
    }
}

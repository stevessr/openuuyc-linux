package org.openuuyc.pico.uu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UuPointerFlowTest {
    private fun name(packet: ByteArray): String = JSONObject(String(packet)).getString("action")

    @Test fun releaseIsAlwaysSentBeforePotentiallyCongestedMovement() {
        val flow = UuPointerFlow()
        assertEquals(UuPointerFlow.Result.SENT,
            flow.pointer(0, .1f, .1f, true, true) { true })
        val actual = mutableListOf<String>()
        assertEquals(UuPointerFlow.Result.IGNORED,
            flow.pointer(0, .2f, .2f, false, true) {
                actual.add(name(it))
                name(it) != "mouse_move_absolute"
            })
        assertEquals(listOf("mouse_release", "mouse_move_absolute"), actual)
        assertFalse(flow.pressed)
    }

    @Test fun aFailedReleaseKeepsPressedStateAndRequiresSessionTeardown() {
        val flow = UuPointerFlow()
        flow.pointer(0, 0f, 0f, true, true) { true }
        val actual = mutableListOf<String>()
        val outcome = flow.pointer(0, .5f, .5f, false, true) {
            actual.add(name(it))
            false
        }
        assertEquals(UuPointerFlow.Result.RELEASE_FAILED, outcome)
        assertEquals(listOf("mouse_release"), actual)
        assertTrue(flow.pressed)
    }

    @Test fun dragIsReleasedWhenScreenOrInputPermissionsDisappear() {
        val flow = UuPointerFlow()
        flow.pointer(0, .1f, .1f, true, true) { true }
        val actual = mutableListOf<String>()
        assertEquals(UuPointerFlow.Result.IGNORED,
            flow.pointer(null, Float.NaN, Float.NaN, false, false) {
                actual.add(name(it)); true
            })
        assertEquals(listOf("mouse_release"), actual)
        assertFalse(flow.pressed)
    }

    @Test fun pressIsNeverSentIfPointerMoveFails() {
        val flow = UuPointerFlow()
        val messages = mutableListOf<String>()
        assertEquals(UuPointerFlow.Result.IGNORED,
            flow.pointer(0, .1f, .1f, true, true) {
                messages.add(name(it)); false
            })
        assertEquals(listOf("mouse_move_absolute"), messages)
        assertFalse(flow.pressed)
    }

    @Test fun releaseIsIdempotentAndResetDoesNotRepress() {
        val flow = UuPointerFlow()
        flow.pointer(0, .1f, .1f, true, true) { true }
        val actual = mutableListOf<String>()
        assertTrue(flow.release { actual.add(name(it)); true })
        assertTrue(flow.release { actual.add(name(it)); true })
        assertEquals(listOf("mouse_release"), actual)
        flow.reset()
        assertFalse(flow.pressed)
    }
}

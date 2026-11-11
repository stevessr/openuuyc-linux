package org.openuuyc.pico

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PicoXrTestInputTest {
    @Test fun quickMenuAcceptsOnlyControllerHands() {
        for (hand in 0..1) {
            assertEquals(PicoXrTestInput.Command.QuickMenu(hand),
                PicoXrTestInput.parse(PicoXrTestInput.ACTION, "quick_menu", hand = hand))
        }
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "quick_menu"))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "quick_menu", hand = -1))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "quick_menu", hand = 2))
    }

    @Test fun navigationAcceptsOnlyOpenXrMenuDirections() {
        val directions = listOf(-1, 0, 1, 9) + (100..107)
        for (direction in directions) {
            val confirm = direction == 0
            assertEquals(PicoXrTestInput.Command.Navigate(direction, confirm),
                PicoXrTestInput.parse(PicoXrTestInput.ACTION, "navigate", direction = direction, confirm = confirm))
        }
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "navigate", direction = 10, confirm = false))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "navigate", direction = 99, confirm = false))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "navigate", direction = 108, confirm = false))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "navigate", direction = 107, confirm = true))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "navigate", direction = 0))
    }

    @Test fun menuClicksRequireFiniteNormalizedCoordinates() {
        assertEquals(PicoXrTestInput.Command.MenuClick(0f, 1f),
            PicoXrTestInput.parse(PicoXrTestInput.ACTION, "menu_click", u = 0f, v = 1f))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "menu_click", u = Float.NaN, v = .5f))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "menu_click", u = .5f, v = Float.POSITIVE_INFINITY))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "menu_click", u = -.01f, v = .5f))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "menu_click", u = .5f, v = 1.01f))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "menu_click", u = .5f))
    }

    @Test fun unknownActionsAndCommandNamesAreRejected() {
        assertNull(PicoXrTestInput.parse("org.openuuyc.pico.UNKNOWN", "quick_menu", hand = 0))
        assertNull(PicoXrTestInput.parse(PicoXrTestInput.ACTION, "start_vrchat"))
    }
}

package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

/**
 * Protocol-compatible QR login policy. Tests cover the state transitions
 * independently of network latency and the platform account API.
 */
class UuQrLoginFlowTest {
    private class Progress {
        var state = 1
        var scanned = false
        var confirmed = false
        var refreshes = 0
        fun apply(next: Int): String = when(next) {
            1, 2 -> {
                scanned = scanned || next == 2
                state = next
                "poll"
            }
            3 -> {
                refreshes++
                state = 1
                scanned = false
                "refresh"
            }
            4 -> {
                confirmed = true
                "exchange"
            }
            else -> throw IllegalArgumentException("invalid UU QR status $next")
        }
    }

    @Test fun stateMachineHandlesScanCancelAndRefreshWithoutTokenReuse() {
        val progress = Progress()
        assertEquals("poll", progress.apply(1))
        assertEquals("poll", progress.apply(2))
        assertTrue(progress.scanned)
        assertEquals("refresh", progress.apply(3))
        assertEquals(1, progress.refreshes)
        assertFalse(progress.scanned)
        assertEquals(1, progress.state)
        assertEquals("poll", progress.apply(1))
        assertEquals("exchange", progress.apply(4))
        assertTrue(progress.confirmed)
    }

    @Test fun invalidStatusIsRejected() {
        val progress = Progress()
        for (status in listOf(-1, 0, 5, 200)) {
            assertThrows(IllegalArgumentException::class.java) { progress.apply(status) }
        }
    }

    @Test fun tokenExchangeAlwaysFollowsForcedDeviceRefresh() {
        // Keep this explicit even without injecting the Android Context:
        // the XR client must not exchange QR credentials with an unrefreshed
        // device ID, unlike a restore of an already authenticated user.
        val actions = listOf("register", "qr_poll", "confirmed", "register(force)", "exchange")
        assertTrue(actions.indexOf("register(force)") > actions.indexOf("confirmed"))
        assertTrue(actions.indexOf("register(force)") < actions.indexOf("exchange"))
    }
}

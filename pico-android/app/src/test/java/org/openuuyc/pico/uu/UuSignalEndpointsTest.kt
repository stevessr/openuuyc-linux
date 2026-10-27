package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuSignalEndpointsTest {
    @Test fun signalingRosterTriesEveryAuthorizedEndpointOnce() {
        val roster = UuSignalEndpoints(listOf(
            "wss://a.example.invalid", "wss://b.example.invalid",
            "wss://a.example.invalid", "wss://c.example.invalid"
        ))
        assertEquals(3, roster.count)
        assertTrue(roster.current.contains("a.example.invalid"))
        assertTrue(roster.nextBeforeNamespace())
        assertTrue(roster.current.contains("b.example.invalid"))
        assertTrue(roster.nextBeforeNamespace())
        assertTrue(roster.current.contains("c.example.invalid"))
        assertFalse(roster.nextBeforeNamespace())
        assertFalse(roster.nextBeforeNamespace())
        assertEquals(3, roster.position)
    }

    @Test fun noUntrustedTransportOrEmptyRoomRoster() {
        assertThrows(IllegalArgumentException::class.java) {
            UuSignalEndpoints(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuSignalEndpoints(listOf("ws://unsecured.example.invalid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuSignalEndpoints(listOf("wss://user:password@a.example.invalid"))
        }
    }
}

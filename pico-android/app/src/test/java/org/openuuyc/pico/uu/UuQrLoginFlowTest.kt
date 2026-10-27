package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuQrLoginFlowTest {
    @Test fun qrStateMachineHandlesScanCancelAndRefresh() {
        val tracker = UuQrStatusTracker()
        assertEquals(UuQrStatusTracker.Transition.WAITING, tracker.receive(1))
        assertEquals(UuQrStatusTracker.Transition.SCANNED, tracker.receive(2))
        assertEquals(UuQrStatusTracker.Transition.WAITING, tracker.receive(2))
        assertEquals(2, tracker.status)
        assertEquals(UuQrStatusTracker.Transition.REFRESH, tracker.receive(3))
        tracker.reset()
        assertEquals(1, tracker.status)
        assertEquals(UuQrStatusTracker.Transition.CONFIRMED, tracker.receive(4))
    }

    @Test fun invalidQrStatusCannotSucceed() {
        val tracker = UuQrStatusTracker()
        listOf(-1, 0, 5, 200).forEach {
            assertThrows(IllegalArgumentException::class.java) { tracker.receive(it) }
        }
    }

    @Test fun forcedDeviceRegistrationPrecedesQrCredentialExchange() {
        val calls = mutableListOf<String>()
        val qr = UuQr("https://example.invalid/qr", "one-time-token", "status-ticket")
        val exchange = UuQrCredentialExchange(
            refreshDevice = { calls.add("register") },
            exchangeToken = { value ->
                assertEquals(qr, value)
                calls.add("exchange")
                UuAccount("access-token", "user-id", "tester")
            }
        )
        assertEquals("access-token", exchange.complete(qr).token)
        assertEquals(listOf("register", "exchange"), calls)
    }

    @Test fun registrationErrorNeverRedeemsOneTimeTicket() {
        var wasRedeemed = false
        val exchange = UuQrCredentialExchange(
            refreshDevice = { throw IllegalStateException("registration unavailable") },
            exchangeToken = { wasRedeemed = true; UuAccount("t", "u", "") }
        )
        assertThrows(IllegalStateException::class.java) {
            exchange.complete(UuQr("https://example.invalid", "token", "ticket"))
        }
        assertFalse(wasRedeemed)
    }

    @Test fun invalidQrCannotRegisterOrExchange() {
        var wasRegistered = false
        val exchange = UuQrCredentialExchange(
            refreshDevice = { wasRegistered = true },
            exchangeToken = { UuAccount("t", "u", "") }
        )
        assertThrows(IllegalArgumentException::class.java) {
            exchange.complete(UuQr("", "", ""))
        }
        assertFalse(wasRegistered)
    }
}

package org.openuuyc.pico.uu

/** QR protocol state shared by login UI and repeatable JVM tests. */
internal class UuQrStatusTracker {
    enum class Transition { WAITING, SCANNED, REFRESH, CONFIRMED }
    var status: Int = 1
        private set

    fun receive(next: Int): Transition = when(next) {
        1 -> { status = 1; Transition.WAITING }
        2 -> {
            val firstScan = status != 2
            status = 2
            if (firstScan) Transition.SCANNED else Transition.WAITING
        }
        3 -> Transition.REFRESH
        4 -> Transition.CONFIRMED
        else -> throw IllegalArgumentException("Unknown UU QR status: $next")
    }

    fun reset() { status = 1 }
}

/**
 * Refreshing a device after user confirmation must happen before exchanging
 * the one-time QR credential. Credentials are returned only on full success.
 */
internal class UuQrCredentialExchange(
    private val refreshDevice: () -> Unit,
    private val exchangeToken: (UuQr) -> UuAccount
) {
    fun complete(qr: UuQr): UuAccount {
        require(qr.url.isNotBlank() && qr.token.isNotBlank())
        refreshDevice()
        return exchangeToken(qr)
    }
}

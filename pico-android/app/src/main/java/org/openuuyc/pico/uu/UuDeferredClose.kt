package org.openuuyc.pico.uu

import java.util.concurrent.atomic.AtomicBoolean

/** Revoke immediately; release native WebRTC resources after observer unwinds. */
internal class UuDeferredClose(
    private val closed: AtomicBoolean,
    private val enqueue: (() -> Unit) -> Unit
) {
    fun request(cleanup: () -> Unit): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        enqueue(cleanup)
        return true
    }
}

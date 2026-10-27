package org.openuuyc.pico.uu

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class UuDeferredCloseTest {
    @Test fun nativeCleanupNeverRunsInlineFromObserver() {
        val closed = AtomicBoolean(false)
        val tasks = mutableListOf<() -> Unit>()
        val gate = UuDeferredClose(closed) { tasks.add(it) }
        var cleaned = false
        assertTrue(gate.request { cleaned = true })
        assertTrue(closed.get())
        assertFalse(cleaned)
        assertEquals(1, tasks.size)
        tasks.single().invoke()
        assertTrue(cleaned)
    }
    @Test fun concurrentCloseRequestsOnlyScheduleOnce() {
        val closed = AtomicBoolean(false)
        val tasks = java.util.Collections.synchronizedList(mutableListOf<() -> Unit>())
        val gate = UuDeferredClose(closed) { tasks.add(it) }
        val callers = (1..12).map { Thread { gate.request {} }.also { it.start() } }
        callers.forEach { it.join() }
        assertTrue(closed.get()); assertEquals(1, tasks.size)
    }
}

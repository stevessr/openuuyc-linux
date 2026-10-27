package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuIceCandidateQueueTest {
    @Test fun candidatesNeverOvertakeOfferOrRemoteAnswer() {
        val gate = UuIceCandidateQueue<String>()
        val sent = mutableListOf<String>()
        assertTrue(gate.submit("1", "candidate1") { sent.add(it); true })
        assertTrue(gate.submit("2", "candidate2") { sent.add(it); true })
        assertTrue(sent.isEmpty())
        sent.add("offer")
        assertTrue(gate.activate { sent.add(it); true })
        assertEquals(listOf("offer", "candidate1", "candidate2"), sent)
        assertTrue(gate.submit("3", "candidate3") { sent.add(it); true })
        assertEquals("candidate3", sent.last())
    }

    @Test fun samePreOfferCandidateIsNotQueuedTwice() {
        val gate = UuIceCandidateQueue<String>()
        assertTrue(gate.submit("same", "first") { fail("not yet activated"); false })
        assertTrue(gate.submit("same", "second") { fail("not yet activated"); false })
        assertEquals(1, gate.size())
        val sent = mutableListOf<String>()
        assertTrue(gate.activate { sent.add(it); true })
        assertEquals(listOf("first"), sent)
    }

    @Test fun boundedQueueRejectsExcessAndInvalidKeys() {
        val gate = UuIceCandidateQueue<Int>(limit = 2)
        assertTrue(gate.submit("a", 1) { false })
        assertTrue(gate.submit("b", 2) { false })
        assertFalse(gate.submit("c", 3) { false })
        assertFalse(gate.submit("", 4) { false })
        assertFalse(gate.submit("x".repeat(8193), 5) { false })
        assertEquals(2, gate.size())
    }

    @Test fun failedFlushPreservesUnsentCandidatesForRetry() {
        val gate = UuIceCandidateQueue<String>()
        gate.submit("1", "a") { false }
        gate.submit("2", "b") { false }
        val sent = mutableListOf<String>()
        assertFalse(gate.activate {
            if (it == "b") false else { sent.add(it); true }
        })
        assertEquals(listOf("a"), sent)
        assertEquals(1, gate.size())
        assertFalse(gate.isReady())
        assertTrue(gate.activate { sent.add(it); true })
        assertEquals(listOf("a", "b"), sent)
        assertTrue(gate.isReady())
    }

    @Test fun closeAndRenegotiationRevokeOldGeneration() {
        val gate = UuIceCandidateQueue<String>()
        gate.submit("old", "oldCandidate") { false }
        gate.resetForRenegotiation()
        assertEquals(0, gate.size())
        gate.submit("fresh", "freshCandidate") { false }
        val sent = mutableListOf<String>()
        assertTrue(gate.activate { sent.add(it); true })
        assertEquals(listOf("freshCandidate"), sent)
        gate.close()
        assertFalse(gate.isReady())
        assertFalse(gate.submit("late", "lateCandidate") { sent.add(it); true })
        assertFalse(gate.activate { sent.add(it); true })
        assertEquals(listOf("freshCandidate"), sent)
    }
}

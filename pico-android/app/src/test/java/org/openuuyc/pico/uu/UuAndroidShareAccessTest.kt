package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuAndroidShareAccessTest {
    @Test fun unresolvedCancelledRequestBlocksSamePhoneAcrossNewGuardInstances() {
        var now=1000L
        val stored=mutableMapOf<String,Long>()
        fun guard()=UuAndroidShareAttempts({stored[it] ?: 0},{id,until->stored[id]=until}) {now}
        guard().begin("phone-a")
        now=2000L
        try {guard().begin("phone-a");fail("Must not interpret an old accept as the next request")}
        catch(expected: java.io.IOException) {assertTrue(expected.message!!.contains("上一次"))}
        guard().begin("phone-b")
        now=71_001L;guard().begin("phone-a")
    }
    @Test fun confirmedTerminalReplyAllowsImmediateRetry() {
        val stored=mutableMapOf<String,Long>()
        val guard=UuAndroidShareAttempts({stored[it] ?: 0},{id,until->stored[id]=until}) {1000L}
        guard.begin("phone");guard.resolved("phone");guard.begin("phone")
    }
    @Test fun httpSuccessStartsTheRemoteApprovalWindowRatherThanInitialConnectTime() {
        var now=0L
        val stored=mutableMapOf<String,Long>()
        val guard=UuAndroidShareAttempts({stored[it] ?: 0},{id,until->stored[id]=until}) {now}
        guard.begin("phone");now=10_000L;guard.sent("phone")
        now=75_000L
        try {guard.begin("phone");fail("Approval may still be in flight")} catch(_: java.io.IOException) {}
        now=80_001L;guard.begin("phone")
    }
    @Test fun onlyOneJoinForTheApprovedTargetCanConsumeAPermit() {
        val permits=UuAndroidSharePermits {0L}
        assertFalse(permits.consume("phone"))
        permits.grant("phone")
        assertFalse(permits.consume("another-phone"))
        assertTrue(permits.consume("phone"));assertFalse(permits.consume("phone"))
    }
    @Test fun expiredApprovalAndLogoutNeverPermitAJoin() {
        var now=0L
        val permits=UuAndroidSharePermits {now}
        permits.grant("phone");now=30_000L;assertFalse(permits.consume("phone"))
        permits.grant("phone");permits.clear();assertFalse(permits.consume("phone"))
    }
}

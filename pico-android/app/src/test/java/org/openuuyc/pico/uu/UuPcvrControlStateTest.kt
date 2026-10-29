package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuPcvrControlStateTest {
    @Test fun unsolicitedStatusNeverStartsTracking() {
        val s=UuPcvrControlState();s.receive(0,true,false);assertFalse(s.active)
    }
    @Test fun startNeedsMatchingAck() {
        val s=UuPcvrControlState();val id=s.request(true)
        assertFalse(s.receive(0,true,false));assertFalse(s.active)
        assertFalse(s.receive(id+1,true,false));assertFalse(s.active)
        assertTrue(s.receive(id,true,false));assertTrue(s.active)
    }
    @Test fun stoppingCannotBeUndoneByDelayedStart() {
        val s=UuPcvrControlState();val start=s.request(true);val stop=s.request(false)
        s.receive(start,true,false);assertFalse(s.active)
        s.receive(stop,false,false);s.receive(0,true,false);assertFalse(s.active)
    }
    @Test fun timeoutRejectsDelayedSuccess() {
        val s=UuPcvrControlState();val id=s.request(true)
        assertTrue(s.timeout(id));s.receive(id,true,false);assertFalse(s.active)
    }
    @Test fun oldTimeoutCannotCancelNewRequest() {
        val s=UuPcvrControlState();val old=s.request(true);val current=s.request(true)
        assertFalse(s.timeout(old));s.receive(current,true,false);assertTrue(s.active)
    }
    @Test fun revokedPermissionRequiresExplicitRestart() {
        val s=UuPcvrControlState();val id=s.request(true);s.receive(id,true,false)
        s.receive(0,false,true);assertFalse(s.active)
        s.receive(0,true,false);assertFalse(s.active)
        val next=s.request(true);s.receive(next,true,false);assertTrue(s.active)
    }
    @Test fun disconnectClearsPendingConsent() {
        val s=UuPcvrControlState();val id=s.request(true);s.reset()
        s.receive(id,true,false);assertFalse(s.active);assertFalse(s.desired)
    }
}

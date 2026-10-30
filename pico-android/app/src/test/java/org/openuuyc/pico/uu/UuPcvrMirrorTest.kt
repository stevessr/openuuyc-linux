package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuPcvrMirrorTest {
    @Test fun aFirstTapAlwaysRequests() {
        assertEquals(UuPcvrMirror.Action.REQUEST,UuPcvrMirror.action(false,false,"none"))
        assertEquals(UuPcvrMirror.Action.REQUEST,UuPcvrMirror.action(false,true,"running"))
        assertEquals(UuPcvrMirror.Action.REQUEST,UuPcvrMirror.action(false,true,"ended"))
    }
    @Test fun aLiveMirrorIsOnlyConfirmed() {
        assertEquals(UuPcvrMirror.Action.CONFIRM,UuPcvrMirror.action(true,true,UuPcvrMirror.RUNNING))
    }
    @Test fun anEndedHelperIsRetriedNotConfirmed() {
        assertEquals(UuPcvrMirror.Action.REQUEST,UuPcvrMirror.action(true,true,"ended"))
        // A host that predates the liveness field must not fake a confirm.
        assertEquals(UuPcvrMirror.Action.REQUEST,UuPcvrMirror.action(true,true,""))
        assertEquals(UuPcvrMirror.Action.REQUEST,UuPcvrMirror.action(true,true,"none"))
    }
    @Test fun anotherBackendMustBeStoppedFirst() {
        assertEquals(UuPcvrMirror.Action.WRONG_BACKEND,UuPcvrMirror.action(true,false,"running"))
        assertEquals(UuPcvrMirror.Action.WRONG_BACKEND,UuPcvrMirror.action(true,false,"none"))
    }
}

package org.openuuyc.pico

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PicoDiagnosticStampTest {
    @Test fun acceptsOnlyFreshReportsFromTheCurrentActivityAndProcess() {
        val report = PicoDiagnosticStamp.attach(JSONObject().put("frames", 120), "session", 42, 1000)
        assertTrue(PicoDiagnosticStamp.current(report, "session", 42, 6000, 5000))
        assertFalse(PicoDiagnosticStamp.current(report, "session", 42, 6001, 5000))
        assertFalse(PicoDiagnosticStamp.current(report, "other", 42, 1000, 5000))
        assertFalse(PicoDiagnosticStamp.current(report, "session", 43, 1000, 5000))
        assertFalse(PicoDiagnosticStamp.current(report, "session", 42, 999, 5000))
        assertEquals(120, report.getInt("frames"))
    }
    @Test fun oldUnstampedReportsCannotProveCurrentTracking() {
        assertFalse(PicoDiagnosticStamp.current(JSONObject().put("frames", 100000), "session", 42, 1000, 5000))
    }
}

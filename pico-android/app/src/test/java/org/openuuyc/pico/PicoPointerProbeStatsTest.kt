package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoPointerProbeStatsTest {
    @Test fun movingLeftHandIsIndependentOfStationaryRight() {
        val s=PicoPointerProbeStats();s.accept(3,floatArrayOf(0f,0f,-2f,1f,0f,-2f));s.accept(3,floatArrayOf(.4f,.3f,-2f,1f,0f,-2f))
        val r=s.report();assertEquals(.5,r.getDouble("leftRangeMeters"),.001);assertEquals(0.0,r.getDouble("rightRangeMeters"),.001)
        assertEquals(2,r.getLong("leftValid"));assertEquals(2,r.getLong("rightValid"))
    }
    @Test fun lostTrackingCannotReuseStalePosition() {
        val s=PicoPointerProbeStats();s.accept(1,floatArrayOf(0f,0f,-2f,0f,0f,0f));s.accept(0,floatArrayOf(99f,0f,0f,99f,0f,0f))
        val r=s.report();assertEquals(1,r.getLong("leftValid"));assertEquals(0,r.getLong("rightValid"));assertEquals(0.0,r.getDouble("leftRangeMeters"),0.0)
    }
}

package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrMenuHitTest {
    @Test fun linuxStyleToolbarAndSettingsMatchRenderedRects() {
        for(i in 0..10) {val r=PicoXrMenuHit.toolbarRect(i);assertEquals(i,PicoXrMenuHit.toolbar((r[0]+r[2])/2400,(r[1]+r[3])/1600))}
        for(i in 0..11) {val r=PicoXrMenuHit.settingsRect(i);assertEquals(i,PicoXrMenuHit.settings((r[0]+r[2])/2400,(r[1]+r[3])/800))}
        assertNull(PicoXrMenuHit.toolbar(.5f,.95f)) // rail is never an action
        assertNull(PicoXrMenuHit.settings(.5f,.95f))
        assertNull(PicoXrMenuHit.settings(Float.NaN,.5f))
        assertNull(PicoXrMenuHit.settings(.5f,.1f))
        assertNull(PicoXrMenuHit.settings(.1f,.8f,4))
    }
    @Test fun ringGapsAndOutsideDoNotActivate() {
        assertNull(PicoXrMenuHit.toolbar(600f/1200,100f/800))
        assertNull(PicoXrMenuHit.toolbar(600f/1200,275f/800))
        val angle=Math.toRadians(22.5)
        assertNull(PicoXrMenuHit.toolbar((600+220*kotlin.math.sin(angle)).toFloat()/1200,(410-220*kotlin.math.cos(angle)).toFloat()/800))
    }
    @Test fun centersMatchTwelveMenuCells() {
        for(i in 0..11) assertEquals(i,PicoXrMenuHit.cell((i%4*300+150)/1200f,(48+i/4*104+52)/400f))
    }
    @Test fun gapsHeaderOutsideAndInvalidDoNotActivate() {
        assertNull(PicoXrMenuHit.cell(.5f,0f))
        assertNull(PicoXrMenuHit.cell(0f,.5f))
        assertNull(PicoXrMenuHit.cell(Float.NaN,.5f))
        assertNull(PicoXrMenuHit.cell(1.1f,.5f))
        assertNull(PicoXrMenuHit.cell(150f/1200,50f/400))
    }
    @Test fun hiddenConfirmationRowsAreNotClickable() {
        assertNull(PicoXrMenuHit.cell(150f/1200,204f/400,4))
        assertEquals(0,PicoXrMenuHit.cell(150f/1200,100f/400,4))
    }
    @Test fun radialTargetsDoNotOverlapOrEscapeControllerCard() {
        val rects=(0..10).map(PicoXrMenuHit::toolbarRect)
        rects.forEachIndexed {i,r->
            assertTrue(r[0]>=0 && r[1]>=0 && r[2]<=1200 && r[3]<=800)
            for(j in i+1 until rects.size) {
                val other=rects[j]
                assertFalse(r[0]<other[2] && r[2]>other[0] && r[1]<other[3] && r[3]>other[1])
            }
        }
    }
}

package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrFrameLayoutTest {
    private val controls=listOf(PicoXrFrameLayout.device,PicoXrFrameLayout.screen,PicoXrFrameLayout.microphone,
        PicoXrFrameLayout.clipboard,PicoXrFrameLayout.quality,PicoXrFrameLayout.add,PicoXrFrameLayout.close,PicoXrFrameLayout.drag)
    @Test fun headerCloseAndRailNeverCoverAnyVideoPixel() {
        for(r in controls) for(x in listOf(r[0],r[2])) for(y in listOf(r[1],r[3]))
            assertFalse(PicoXrFrameLayout.videoContainsPixel(x,y))
    }
    @Test fun allButtonsAreDistinctAndInsideTheirAtlasCell() {
        for((i,r) in controls.withIndex()) {
            assertTrue(r[0]>=0 && r[1]>=0 && r[2]<=1200 && r[3]<=800)
            for(other in controls.drop(i+1))
                assertFalse(r[0]<other[2] && r[2]>other[0] && r[1]<other[3] && r[3]>other[1])
        }
    }
    @Test fun portraitAndLandscapeUseTheSameDrawingHitTransform() {
        for(aspect in listOf(9f/16,16f/9,1f)) for(r in controls) {
            val x=(r[0]+r[2])/2;val y=(r[1]+r[3])/2
            val local=PicoXrFrameLayout.panelFromPixel(x,y,1.6f,1.6f*aspect)
            val pixel=PicoXrFrameLayout.pixelFromPanel(local.first,local.second,1.6f,1.6f*aspect)
            assertEquals(x,pixel.first,.001f);assertEquals(y,pixel.second,.001f)
        }
    }
}

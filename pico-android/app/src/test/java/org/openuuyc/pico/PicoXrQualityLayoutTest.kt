package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrQualityLayoutTest {
    @Test fun everyDisplayedActionMatchesHitRegion() {
        for(item in PicoXrQualityLayout.items) assertEquals(item.action,PicoXrQualityLayout.hit((item.x+item.w/2)/600,(item.y+item.h/2)/1000))
    }
    @Test fun unsupportedRowsRailAndInvalidCoordinatesAreNotActions() {
        assertNull(PicoXrQualityLayout.hit(.5f,.78f))
        assertNull(PicoXrQualityLayout.hit(.5f,.98f))
        assertNull(PicoXrQualityLayout.hit(Float.NaN,.3f))
    }
    @Test fun selectionMenuDoesNotActivateMissingRowsOrRail() {
        assertEquals(0,PicoXrSelectionCanvas.hit(.5f,.16f,2))
        assertNull(PicoXrSelectionCanvas.hit(.5f,.5f,2))
        assertNull(PicoXrSelectionCanvas.hit(.5f,.95f,2))
        assertEquals(11,PicoXrSelectionCanvas.hit(.9f,.88f,2))
    }
}

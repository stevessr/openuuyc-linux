package org.openuuyc.pico

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PicoXrOptionLayoutTest {
    @Test fun listHitMatchesRowsAndSpacing() {
        assertEquals(0,PicoXrOptionLayout.hit(.5f,.13f,8))
        assertEquals(7,PicoXrOptionLayout.hit(.5f,.63f,8))
        assertNull(PicoXrOptionLayout.hit(.5f,.175f,8))
        assertNull(PicoXrOptionLayout.hit(.5f,.63f,7))
        assertNull(PicoXrOptionLayout.hit(.01f,.13f,8))
    }
    @Test fun pagingAndBackRemainAvailableForEmptyLists() {
        assertEquals(8,PicoXrOptionLayout.hit(.25f,.77f,0))
        assertEquals(9,PicoXrOptionLayout.hit(.75f,.77f,0))
        assertEquals(10,PicoXrOptionLayout.hit(.5f,.89f,0))
        assertEquals(10,PicoXrOptionLayout.hit(.5f,.04f,0))
        assertNull(PicoXrOptionLayout.hit(Float.NaN,.5f,8))
        assertNull(PicoXrOptionLayout.hit(.5f,1.1f,8))
    }
    @Test fun sliderBounds() {
        assertEquals(1,PicoXrOptionLayout.bitrate(-1f))
        assertEquals(1,PicoXrOptionLayout.bitrate(.08f))
        assertEquals(500,PicoXrOptionLayout.bitrate(.92f))
        assertEquals(500,PicoXrOptionLayout.bitrate(2f))
    }
    @Test fun sliderMidpointAndInvalidInput() {
        assertEquals(250,PicoXrOptionLayout.bitrate(.5f))
        assertEquals(1,PicoXrOptionLayout.bitrate(Float.NaN))
    }
    @Test fun selectionOpensItsOwnPage() {
        assertEquals(0,PicoXrOptionLayout.pageOf(-1))
        assertEquals(0,PicoXrOptionLayout.pageOf(7))
        assertEquals(8,PicoXrOptionLayout.pageOf(8))
        assertEquals(16,PicoXrOptionLayout.pageOf(19))
    }
}

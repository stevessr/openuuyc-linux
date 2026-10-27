package org.openuuyc.pico

import org.junit.Assert.assertEquals
import org.junit.Test

class PicoXrOptionLayoutTest {
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

package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrRingPageTest {
    @Test fun emptyAndExactPagesHaveNoPhantomNextButton() {
        assertEquals(1,PicoXrRingPage.pages(0));assertEquals(1,PicoXrRingPage.pages(6))
        assertFalse(PicoXrRingPage.enabled(0,0,0));assertFalse(PicoXrRingPage.enabled(7,0,6))
        assertTrue(PicoXrRingPage.enabled(10,0,0))
    }
    @Test fun allItemsRemainReachableAndPageButtonsCannotBecomeData() {
        for(count in 1..100) {
            val reached=mutableListOf<Int>()
            for(page in 0 until PicoXrRingPage.pages(count)) {
                for(cell in 0..5) if(PicoXrRingPage.enabled(cell,page,count)) reached.add(page*6+cell)
                assertEquals(page>0,PicoXrRingPage.enabled(6,page,count))
                assertEquals(page+1<PicoXrRingPage.pages(count),PicoXrRingPage.enabled(7,page,count))
            }
            assertEquals((0 until count).toList(),reached)
            assertEquals(PicoXrRingPage.pages(count)-1,PicoXrRingPage.clamp(100,count))
        }
    }
}

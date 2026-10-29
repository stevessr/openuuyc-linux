package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoMenuNavigationTest {
    @Test fun allRadialDirectionsSelectInsideTheirActualButton() {
        for(cell in 0..7) {
            val result=PicoMenuNavigation.select(0,100+cell,PicoXrMenuHit::toolbar)!!
            assertEquals(cell,result.cell);assertEquals(cell,PicoXrMenuHit.toolbar(result.u,result.v))
        }
    }
    @Test fun sequentialMenuSelectionWrapsAndDoesNotTargetGaps() {
        val result=PicoMenuNavigation.select(0,-1) {u,v->PicoXrMenuHit.settings(u,v)}!!
        assertEquals(11,result.cell);assertEquals(result.cell,PicoXrMenuHit.settings(result.u,result.v))
        assertNull(PicoMenuNavigation.select(0,1) {_,_->null})
    }
}

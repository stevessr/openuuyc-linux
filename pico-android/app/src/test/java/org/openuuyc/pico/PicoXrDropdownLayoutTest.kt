package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrDropdownLayoutTest {
    private val count=10
    @Test fun visibleRowsStayAboveFooterNotice() {
        val lastVisibleBottom=PicoXrDropdownLayout.ROW_TOP+PicoXrDropdownLayout.visibleRows*PicoXrDropdownLayout.ROW_HEIGHT-8f
        val nextRowBottom=lastVisibleBottom+PicoXrDropdownLayout.ROW_HEIGHT
        assertEquals(6,PicoXrDropdownLayout.visibleRows)
        assertTrue(lastVisibleBottom<=PicoXrDropdownLayout.FOOTER_TOP)
        assertTrue(nextRowBottom>PicoXrDropdownLayout.FOOTER_TOP)
    }
    @Test fun controllerNavigationScrollsDropdownRowsInBothDirections() {
        assertEquals(-1,PicoXrDropdownLayout.navigationStep(100))
        assertEquals(-1,PicoXrDropdownLayout.navigationStep(107))
        assertEquals(1,PicoXrDropdownLayout.navigationStep(102))
        assertEquals(PicoXrDropdownLayout.NAVIGATE_BACK,PicoXrDropdownLayout.navigationStep(104))
        assertEquals(1,PicoXrDropdownLayout.navigationStep(105))
        val nextOffset=PicoXrDropdownLayout.navigationOffset(0,5,1,count)
        assertEquals(1,nextOffset)
        assertEquals(6,PicoMenuNavigation.select(5,1) {u,v->PicoXrDropdownLayout.hit(u,v,count,nextOffset)}?.cell)
        val previousOffset=PicoXrDropdownLayout.navigationOffset(6,6,-1,count)
        assertEquals(5,previousOffset)
        assertEquals(5,PicoMenuNavigation.select(6,-1) {u,v->PicoXrDropdownLayout.hit(u,v,count,previousOffset)}?.cell)
        assertEquals(6,PicoXrDropdownLayout.navigationOffset(6,7,-1,count))
        assertEquals(6,PicoXrDropdownLayout.navigationOffset(6,9,1,count))
        assertEquals(0,PicoXrDropdownLayout.navigationOffset(0,0,-1,count))
        assertEquals(0,PicoXrDropdownLayout.navigationOffset(0,5,1,6))
    }
    @Test fun rowsHitByTheirOwnBandsAndNothingBetween() {
        val top=PicoXrDropdownLayout.ROW_TOP;val h=PicoXrDropdownLayout.ROW_HEIGHT;val n=PicoXrDropdownLayout.visibleRows
        assertEquals(0,PicoXrDropdownLayout.hit(.5f,.2f,count))
        assertEquals(3,PicoXrDropdownLayout.hit(.5f,(top+3*h+30)/800f,count))
        assertEquals(n-1,PicoXrDropdownLayout.hit(.5f,(top+(n-1)*h+30)/800f,count))
        assertNull(PicoXrDropdownLayout.hit(.5f,(top+n*h+30)/800f,count)) // beyond the visible window
    }
    @Test fun offsetScrollsWindowAndClampsToCount() {
        val offset=6
        assertEquals(6,PicoXrDropdownLayout.hit(.5f,(PicoXrDropdownLayout.ROW_TOP+30)/800f,count,offset))
        assertEquals(9,PicoXrDropdownLayout.hit(.5f,(PicoXrDropdownLayout.ROW_TOP+3*PicoXrDropdownLayout.ROW_HEIGHT+30)/800f,count,offset))
        assertNull(PicoXrDropdownLayout.hit(.5f,(PicoXrDropdownLayout.ROW_TOP+4*PicoXrDropdownLayout.ROW_HEIGHT+30)/800f,count,offset))
    }
    @Test fun headerBackAndCloseAreOneTarget() {
        assertEquals(-1,PicoXrDropdownLayout.hit(.06f,.07f,count))
        assertEquals(-1,PicoXrDropdownLayout.hit(.94f,.07f,count))
        assertNull(PicoXrDropdownLayout.hit(.5f,.005f,count)) // above the header band
    }
    @Test fun gapsAndInvalidCoordinatesAreNotActions() {
        assertNull(PicoXrDropdownLayout.hit(.01f,.3f,count))
        assertNull(PicoXrDropdownLayout.hit(.99f,.3f,count))
        assertNull(PicoXrDropdownLayout.hit(Float.NaN,.3f,count))
        assertNull(PicoXrDropdownLayout.hit(.5f,1.1f,count))
        assertNull(PicoXrDropdownLayout.hit(.5f,.3f,0)) // empty page
    }
    @Test fun sliderFractionOnlyInsideOwnTrack() {
        val y=(PicoXrDropdownLayout.ROW_TOP+PicoXrDropdownLayout.ROW_HEIGHT/2)/800f
        // Track spans ROW_LEFT+380 .. ROW_RIGHT-80 in the 1200-wide panel.
        val left=(PicoXrDropdownLayout.ROW_LEFT+380f)/1200f
        val right=(PicoXrDropdownLayout.ROW_RIGHT-80f)/1200f
        assertEquals(0f,PicoXrDropdownLayout.sliderFraction(left,y,0,count)!!,1e-4f)
        assertEquals(1f,PicoXrDropdownLayout.sliderFraction(right,y,0,count)!!,1e-4f)
        val mid=(left+right)/2f
        assertEquals(.5f,PicoXrDropdownLayout.sliderFraction(mid,y,0,count)!!,1e-4f)
        assertNull(PicoXrDropdownLayout.sliderFraction(.5f,y,1,count)) // row 1 has no slider
        assertNull(PicoXrDropdownLayout.sliderFraction(Float.NaN,y,0,count))
    }
    @Test fun sliderValuesMapInclusiveIntegerRanges() {
        assertEquals(1,PicoXrDropdownLayout.sliderValue(-1f,1,500))
        assertEquals(1,PicoXrDropdownLayout.sliderValue(0f,1,500))
        assertEquals(250,PicoXrDropdownLayout.sliderValue(.5f,1,500))
        assertEquals(500,PicoXrDropdownLayout.sliderValue(1f,1,500))
        assertEquals(500,PicoXrDropdownLayout.sliderValue(2f,1,500))
        assertEquals(100,PicoXrDropdownLayout.sliderValue(1f,0,100))
        assertEquals(0,PicoXrDropdownLayout.sliderValue(Float.NaN,0,100))
        assertEquals(7,PicoXrDropdownLayout.sliderValue(.3f,7,7)) // degenerate span
    }
    @Test fun everySliderRowCenterResolvesItsOwnRow() {
        val rows=List(4) {i -> PicoXrDropdownRow("r$i",kind=PicoXrDropdownRow.SLIDER,sliderFraction=.25f)}
        rows.forEachIndexed {i,_ ->
            val y=(PicoXrDropdownLayout.ROW_TOP+i*PicoXrDropdownLayout.ROW_HEIGHT+PicoXrDropdownLayout.ROW_HEIGHT/2)/800f
            assertEquals(i,PicoXrDropdownLayout.hit(.5f,y,rows.size))
            assertNotNull(PicoXrDropdownLayout.sliderFraction(.5f,y,i,rows.size))
        }
    }
    @Test fun selectedScreenOpensOnItsOwnPageAndClampsToTheLastPage() {
        val n=PicoXrDropdownLayout.visibleRows
        assertEquals(0,PicoXrDropdownLayout.pageOfSelection(-1,13))
        assertEquals(0,PicoXrDropdownLayout.pageOfSelection(0,13))
        assertEquals(0,PicoXrDropdownLayout.pageOfSelection(n-1,13))
        assertEquals(n,PicoXrDropdownLayout.pageOfSelection(n,13))
        assertEquals(2*n,PicoXrDropdownLayout.pageOfSelection(2*n,13))
        assertEquals(2*n,PicoXrDropdownLayout.pageOfSelection(2*n+1,13)) // 13 rows: last page starts at 2*n
        assertEquals(2*n,PicoXrDropdownLayout.pageOfSelection(99,13))   // beyond the list still clamps
        assertEquals(0,PicoXrDropdownLayout.pageOfSelection(4,2))       // the whole 2-row list fits one page
        assertEquals(0,PicoXrDropdownLayout.pageOfSelection(-1,0))
    }
    @Test fun pageCountRoundsUpVisibleWindows() {
        assertEquals(0,PicoXrDropdownLayout.pageCount(0))
        assertEquals(1,PicoXrDropdownLayout.pageCount(1))
        assertEquals(1,PicoXrDropdownLayout.pageCount(PicoXrDropdownLayout.visibleRows))
        assertEquals(2,PicoXrDropdownLayout.pageCount(PicoXrDropdownLayout.visibleRows+1))
        assertEquals(3,PicoXrDropdownLayout.pageCount(13))
    }
    @Test fun refreshedShorterListsClampTheVisiblePage() {
        assertEquals(0,PicoXrDropdownLayout.clampOffset(12,2))
        assertEquals(6,PicoXrDropdownLayout.clampOffset(12,9))
        assertEquals(0,PicoXrDropdownLayout.clampOffset(-3,9))
        assertEquals(0,PicoXrDropdownLayout.clampOffset(5,0))
        assertEquals(6,PicoXrDropdownLayout.clampOffset(7,9)) // in-page row offsets stay where they are
    }
    @Test fun selectedRowIsVisibleOnThePageItOpensOn() {
        val rows=14
        listOf(0,3,6,7,11,12,13).forEach {selected ->
            val offset=PicoXrDropdownLayout.pageOfSelection(selected,rows)
            val y=(PicoXrDropdownLayout.ROW_TOP+(selected-offset)*PicoXrDropdownLayout.ROW_HEIGHT+30)/800f
            assertEquals(selected,PicoXrDropdownLayout.hit(.5f,y,rows,offset))
        }
    }
}

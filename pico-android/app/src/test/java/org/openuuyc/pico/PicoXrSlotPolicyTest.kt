package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrSlotPolicyTest {
    private val surfaces=setOf(0,1,2)
    @Test fun supportsSixSlotsButOnlyUsesRuntimeCreatedSurfaces() {
        assertEquals(3,PicoXrSlotPolicy.pick(true,setOf(0,1,2),emptySet(),(0..5).toSet()))
        assertEquals(5,PicoXrSlotPolicy.pick(true,setOf(0,1,2,3,4),emptySet(),(0..5).toSet()))
        assertNull(PicoXrSlotPolicy.pick(true,(0..5).toSet(),emptySet(),(0..5).toSet()))
        assertNull(PicoXrSlotPolicy.pick(true,setOf(0,1,2),emptySet(),setOf(0,1,2)))
    }
    @Test fun keepsExistingStreamAndChoosesFreeAuxiliarySurface() {assertEquals(1,PicoXrSlotPolicy.pick(true,setOf(0),emptySet(),surfaces))}
    @Test fun neverReplacesVisibleOrClosingSurfaces() {
        assertNull(PicoXrSlotPolicy.pick(true,setOf(0,1,2),emptySet(),surfaces))
        assertNull(PicoXrSlotPolicy.pick(false,setOf(1,2),setOf(0),surfaces))
    }
    @Test fun mainSurfaceCanBeReusedOnlyAfterPrimaryDisposal() {
        assertEquals(0,PicoXrSlotPolicy.pick(false,setOf(1,2),emptySet(),surfaces))
        assertNull(PicoXrSlotPolicy.pick(false,setOf(1,2),emptySet(),setOf(1,2)))
    }
}

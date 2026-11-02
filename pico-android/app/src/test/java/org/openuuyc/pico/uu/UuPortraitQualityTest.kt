package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuPortraitQualityTest {
    private val current=UuCaptureSettings()
    private val profiles=listOf(UuCodecProfile(1,4096,4096,60))
    @Test fun portraitDoesNotInheritLandscape1080HeightLimit() {
        val next=UuPortraitQuality.plan(UuDisplayMode(1080,1920),608 to 1080,current,profiles,false)!!
        assertEquals(1920,next.maxHeight);assertEquals(current.maxWidth,next.maxWidth)
    }
    @Test fun landscapeIsNotChanged() {assertNull(UuPortraitQuality.plan(UuDisplayMode(1920,1080),1920 to 1080,current,profiles,false))}
    @Test fun explicitChoiceOrMissingRemoteCapabilityIsNeverOverridden() {
        assertNull(UuPortraitQuality.plan(UuDisplayMode(1080,1920),null,current,profiles,true))
        assertNull(UuPortraitQuality.plan(UuDisplayMode(1080,1920),null,current,listOf(UuCodecProfile(1,1920,1080,60)),false))
    }
    @Test fun higherPortraitSourceIsBoundedByRenderBudgetAndNotReRequested() {
        val next=UuPortraitQuality.plan(UuDisplayMode(1440,2560),null,current,profiles,false)!!
        assertEquals(2160,next.maxHeight)
        assertNull(UuPortraitQuality.plan(UuDisplayMode(1440,2560),null,next,profiles,false))
    }
}

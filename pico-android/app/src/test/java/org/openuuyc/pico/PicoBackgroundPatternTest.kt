package org.openuuyc.pico

import org.junit.Assert.*
import org.junit.Test

class PicoBackgroundPatternTest {
    @Test fun allPresetsAreOpaqueAndDeterministic() {
        assertEquals(10,PicoBackgroundPattern.presets.size)
        assertEquals(10,PicoBackgroundPattern.presets.map {it.id}.distinct().size)
        for(preset in PicoBackgroundPattern.presets) {
            val pixels=PicoBackgroundPattern.render(preset.id,96,64,7)
            assertEquals(6144,pixels.size)
            assertTrue(pixels.all {it ushr 24==255})
            assertArrayEquals(pixels,PicoBackgroundPattern.render(preset.id,96,64,7))
            if(preset.procedural) assertTrue(pixels.distinct().size>8)
            else assertEquals(1,pixels.distinct().size)
        }
    }
    @Test fun regeneratingSeedChangesRandomScenery() {
        for(id in PicoBackgroundPattern.presets.filter {it.procedural}.map {it.id})
            assertFalse(PicoBackgroundPattern.render(id,96,64,1).contentEquals(PicoBackgroundPattern.render(id,96,64,2)))
    }
    @Test fun unknownPresetFallsBackToBlack() {
        assertTrue(PicoBackgroundPattern.render("unknown",4,4,0).all {it==0xff000000.toInt()})
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnboundedAllocation() {PicoBackgroundPattern.render("stars",100000,2000,1)}
}

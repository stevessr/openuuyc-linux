package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoKeyboardLayoutTest {
    @Test fun fullKeyboardIncludesFunctionNavigationAndNumpad() {
        val keys=PicoKeyboardLayout.rows(PicoKeyboardMode.FULL).flatten()
        assertTrue(keys.containsAll(listOf("F12","Ctrl","Win","Insert","PgDn","Num7","Num.")))
        assertTrue(keys.all {PicoKeyboardLayout.vk(it)!=null})
        assertEquals(103,PicoKeyboardLayout.vk("Num7"));assertEquals(123,PicoKeyboardLayout.vk("F12"))
    }
    @Test fun customKeyboardHasCompleteLetterSetAndEditingKeys() {
        val keys=PicoKeyboardLayout.rows(PicoKeyboardMode.CUSTOM).flatten()
        assertTrue(('A'..'Z').all {it.toString() in keys})
        assertTrue(keys.containsAll(listOf("Space","Enter","Backspace","Ctrl")))
        assertNull(PicoKeyboardLayout.keyAt(PicoKeyboardMode.CUSTOM,Float.NaN,.5f))
        assertNull(PicoKeyboardLayout.keyAt(PicoKeyboardMode.SYSTEM,.5f,.5f))
    }
    @Test fun heldModifiersReleaseWhenKeyboardIsHidden() {
        val events=mutableListOf<Pair<Int,Boolean>>()
        val keys=PicoKeyboardKeys {code,down->events.add(code to down);true}
        assertTrue(keys.pressed("Ctrl"));assertTrue(keys.pressed("C"));keys.release()
        assertEquals(listOf(162 to true,67 to true,67 to false,162 to false),events)
        assertFalse(keys.active("Ctrl"))
    }
    @Test fun everyRenderedKeyCenterHitsItsOwnKey() {
        for(mode in listOf(PicoKeyboardMode.CUSTOM,PicoKeyboardMode.FULL)) {
            val rows=PicoKeyboardLayout.rows(mode)
            rows.forEachIndexed {r,row -> row.forEachIndexed {col,key->
                assertEquals(key,PicoKeyboardLayout.keyAt(mode,(col+.5f)/row.size,(96+(r+.5f)*264/rows.size)/400))
            }}
        }
    }
}

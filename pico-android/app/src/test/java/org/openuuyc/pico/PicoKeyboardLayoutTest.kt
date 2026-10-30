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
            PicoKeyboardLayout.keys(mode).forEach {key->
                assertEquals(key.label,PicoKeyboardLayout.keyAt(mode,(key.x+key.width/2)/1200,(key.y+key.height/2)/PicoKeyboardLayout.height(mode)))
            }
        }
    }
    @Test fun standard104GeometryHasRealWidthsClustersAndNoOverlappingHitboxes() {
        val keys=PicoKeyboardLayout.keys(PicoKeyboardMode.FULL)
        assertEquals(104,keys.size);assertEquals(104,keys.map {it.label}.toSet().size)
        fun key(label: String)=keys.single {it.label==label}
        assertEquals(2f,key("Backspace").width/key("A").width,.001f)
        assertEquals(6.25f,key("Space").width/key("A").width,.001f)
        assertTrue(key("Tab").x+key("Tab").width<key("Caps").x+key("Caps").width)
        assertTrue(key("Insert").x>key("Backspace").x+key("Backspace").width)
        assertTrue(key("Num7").x>key("PgDn").x+key("PgDn").width)
        assertEquals(key("↑").x,key("↓").x,.001f)
        assertTrue(key("↑").y<key("↓").y)
        assertTrue(key("NumEnter").height>key("Num1").height*2)
        assertEquals(2f,key("Num0").width/key("Num1").width,.001f)
        keys.forEachIndexed {i,k->
            assertTrue(k.x>=0 && k.y>=96 && k.x+k.width<=1200 && k.y+k.height<440)
            for(other in keys.drop(i+1)) assertFalse(k.x<other.x+other.width && k.x+k.width>other.x && k.y<other.y+other.height && k.y+k.height>other.y)
        }
        assertNull(PicoKeyboardLayout.keyAt(PicoKeyboardMode.FULL,790f/1200,200f/480))
        assertEquals(220,PicoKeyboardLayout.vk("\\"))
        assertEquals("\"",PicoKeyboardLayout.shifted("'"))
    }
    @Test fun rightModifiersAreIndependentAndCodexVoiceSendsPlainF8() {
        val events=mutableListOf<Pair<Int,Boolean>>()
        val keys=PicoKeyboardKeys {vk,down->events.add(vk to down);true}
        keys.pressed("Ctrl");keys.pressed("RCtrl");keys.pressed("RAlt")
        assertTrue(keys.active("Ctrl"));assertTrue(keys.active("RCtrl"))
        assertTrue(keys.codexVoice())
        assertEquals(listOf(162 to true,163 to true,165 to true,162 to false,163 to false,165 to false,119 to true,119 to false),events)
        assertFalse(keys.active("RCtrl"))
    }
    @Test fun failedReleaseDoesNotSendAModifiedVoiceShortcut() {
        val events=mutableListOf<Pair<Int,Boolean>>()
        val keys=PicoKeyboardKeys {vk,down->events.add(vk to down);down}
        assertTrue(keys.pressed("Ctrl"));assertFalse(keys.codexVoice())
        assertTrue(keys.active("Ctrl"));assertFalse(events.any {it.first==119})
    }
    @Test fun sharedHeaderZonesMatchAcrossKeyboardModesAndGapsAreNotActions() {
        for(mode in PicoKeyboardMode.entries) {
            val v=70f/PicoKeyboardLayout.height(mode)
            assertEquals(PicoKeyboardLayout.Header.MODE,PicoKeyboardLayout.headerAt(mode,.1f,v))
            assertEquals(PicoKeyboardLayout.Header.CODEX_VOICE,PicoKeyboardLayout.headerAt(mode,.5f,v))
            assertEquals(PicoKeyboardLayout.Header.BACK,PicoKeyboardLayout.headerAt(mode,.9f,v))
            assertNull(PicoKeyboardLayout.headerAt(mode,389f/1200,v))
        }
    }
}

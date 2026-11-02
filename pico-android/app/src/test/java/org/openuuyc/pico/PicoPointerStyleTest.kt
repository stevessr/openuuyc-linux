package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoPointerStyleTest {
 @Test fun onlyOneMouseCursorIsRequested() {
  for(style in 0..4) for(text in listOf(false,true))
   assertNotEquals(PicoPointerStyle.localCursor(style,text),PicoPointerStyle.remoteCursor(style,text))
  assertTrue(PicoPointerStyle.remoteCursor(2,false));assertTrue(PicoPointerStyle.localCursor(3,true))
 }
 @Test fun adaptiveUsesHostTextCaretAndLocalMouseOtherwise() {
  assertTrue(PicoPointerStyle.localCursor(4,false));assertFalse(PicoPointerStyle.localCursor(4,true))
  assertTrue(PicoPointerStyle.remoteCursor(4,true));assertFalse(PicoPointerStyle.remoteCursor(4,false))
 }
}

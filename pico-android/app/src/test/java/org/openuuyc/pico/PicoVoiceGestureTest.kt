package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoVoiceGestureTest {
 @Test fun shortClickTogglesAndHoldEndsOnRelease() {
  val g=PicoVoiceGesture()
  assertEquals(PicoVoiceGesture.Action.START,g.down(0,false));assertEquals(PicoVoiceGesture.Action.NONE,g.up(100))
  assertEquals(PicoVoiceGesture.Action.NONE,g.down(200,true));assertEquals(PicoVoiceGesture.Action.STOP,g.up(220))
  assertEquals(PicoVoiceGesture.Action.START,g.down(300,false));assertEquals(PicoVoiceGesture.Action.NONE,g.down(400,true))
  assertEquals(PicoVoiceGesture.Action.STOP,g.up(700));assertEquals(PicoVoiceGesture.Action.NONE,g.up(800))
 }
 @Test fun cancelCannotFinishANewGesture() {val g=PicoVoiceGesture();g.down(0,false);g.reset();assertEquals(PicoVoiceGesture.Action.NONE,g.up(500))}
}

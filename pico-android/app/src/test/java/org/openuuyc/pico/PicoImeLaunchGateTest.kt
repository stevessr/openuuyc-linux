package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoImeLaunchGateTest {
 @Test fun waitsForRealWindowFocusAndConnectsOnlyOnce() {
  val g=PicoImeLaunchGate()
  assertEquals(PicoImeLaunchGate.Step.WAIT,g.step(false,false,false))
  assertEquals(PicoImeLaunchGate.Step.WAIT,g.step(true,false,false))
  assertEquals(PicoImeLaunchGate.Step.CONNECT,g.step(true,true,false))
  repeat(3) {assertEquals(PicoImeLaunchGate.Step.SHOW,g.step(true,true,false))}
  g.acknowledge(true);assertEquals(PicoImeLaunchGate.Step.DONE,g.step(true,true,false))
 }
 @Test fun rejectedOrUnfocusedRequestsAreBounded() {
  val g=PicoImeLaunchGate(2);g.acknowledge(false)
  repeat(2) {assertEquals(PicoImeLaunchGate.Step.WAIT,g.step(false,false,false))}
  assertEquals(PicoImeLaunchGate.Step.TIMEOUT,g.step(true,true,false))
 }
 @Test fun visibleKeyboardNeedsNoRestart() {assertEquals(PicoImeLaunchGate.Step.DONE,PicoImeLaunchGate().step(true,true,true))}
}

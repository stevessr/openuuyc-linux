package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuTerminalOutputTest {
 @Test fun coalescesChunksWithoutSchedulingUnlimitedUiCallbacks() {
  val q=UuTerminalOutput(16);val first=q.append("one".toByteArray())
  assertTrue(first.accepted);assertNotNull(first.ticket)
  assertNull(q.append("two".toByteArray()).ticket)
  assertEquals("onetwo",String(q.drain(first.ticket!!)!!))
  assertNotNull(q.append("three".toByteArray()).ticket)
 }
 @Test fun staleCallbackCannotDrainOrUnscheduleNewShellOutput() {
  val q=UuTerminalOutput();val old=q.append("secret".toByteArray()).ticket!!
  q.reset();val current=q.append("new".toByteArray()).ticket!!
  assertNull(q.drain(old));assertNull(q.append(" shell".toByteArray()).ticket)
  assertEquals("new shell",String(q.drain(current)!!))
 }
 @Test fun refusesOverflowAndClearsAllBytesOnReset() {
  val q=UuTerminalOutput(4);val ticket=q.append(byteArrayOf(1,2,3,4)).ticket!!
  assertFalse(q.append(byteArrayOf(5)).accepted)
  assertArrayEquals(byteArrayOf(1,2,3,4),q.drain(ticket))
  q.reset();assertTrue(q.append(byteArrayOf(9)).accepted)
 }
}

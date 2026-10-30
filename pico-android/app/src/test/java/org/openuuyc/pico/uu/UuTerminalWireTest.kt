package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuTerminalWireTest {
 @Test fun nativeTermHeaderAndUtf8PayloadMatchHostAbi() {
  val bytes=UuTerminalWire.encode(2,123,"中文\r".toByteArray())
  assertArrayEquals(byteArrayOf(84,69,82,77,1,2,0,0,123,0,0,0,7,0,0,0),bytes.copyOf(16))
  val frame=UuTerminalWire.decode(bytes)!!;assertEquals(2,frame.kind);assertEquals(123,frame.session);assertEquals("中文\r",String(frame.payload))
 }
 @Test fun terminalConnectUsesNativeKindEightWithoutChangingDesktopDefaults() {
  assertArrayEquals(byteArrayOf(8,8),UuWire.connectOptions("abcdefghijklmnop",terminal=true).copyOf(2))
  assertArrayEquals(byteArrayOf(8,1),UuWire.connectOptions("abcdefghijklmnop").copyOf(2))
 }
 @Test fun rejectsMalformedAndTrailingFrames() {
  val valid=UuTerminalWire.encode(9,0)
  assertNotNull(UuTerminalWire.decode(valid))
  assertNull(UuTerminalWire.decode(valid+0.toByte()))
  assertNull(UuTerminalWire.decode(valid.copyOf(15)))
  for(i in listOf(0,4,5,6,12)) {val b=valid.clone();b[i]=99;assertNull(UuTerminalWire.decode(b))}
 }
}

package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuStereoOutputTest {
 private fun frame(id:Long)=UuStereoFrames.Frame(1,id,1,128,64,1,true,FloatArray(22),byteArrayOf(1))
 @Test fun staleOutputCannotBeAdvertisedAsFreshWhenDecoderRecovers() {
  val output=UuStereoOutput();val f=frame(1)
  assertTrue(output.put(f,100,100));assertTrue(output.fresh(1,350));assertFalse(output.fresh(1,351));assertNull(output.take(1,351));assertEquals(0,output.size)
  assertFalse(output.put(frame(2),100,351));assertFalse(output.put(frame(3),101,100))
 }
 @Test fun countDuplicatesAndFlushRemainBounded() {
  val output=UuStereoOutput();for(id in 1L..8L)assertTrue(output.put(frame(id),100,100))
  assertFalse(output.put(frame(9),100,100));assertFalse(output.put(frame(1),100,100));assertNotNull(output.take(1,110));assertTrue(output.put(frame(9),110,110))
  output.clear();assertEquals(0,output.size);assertNull(output.take(9,110))
 }
}

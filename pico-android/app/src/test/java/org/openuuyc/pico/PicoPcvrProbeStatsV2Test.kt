package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class PicoPcvrProbeStatsV2Test {
 @Test fun acceptsCurrentTrackingWithoutCountingStaleOrInvalidPackets() {
  val stats=PicoPcvrProbeStats();val frame=ByteArray(400);val b=ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
  "OUVR".toByteArray().copyInto(frame);b.putInt(4,2);b.putLong(8,1);b.putInt(32,51);b.putInt(96,51);b.putInt(160,51);for(offset in intArrayOf(64,128,192))b.putFloat(offset,1f)
  stats.accept(frame);stats.accept(frame);b.putInt(4,1);stats.accept(frame)
  val report=stats.report(0,0,0,0,0,0f);assertEquals(1L,report.getLong("trackingSamples"));assertEquals(1L,report.getLong("headValid"));assertEquals(1L,report.getLong("leftValid"))
 }
 @Test fun initializationSeparatesSurfaceSupportFromValidFloorAndRemoteVideo() {
  val stats=PicoPcvrProbeStats();stats.surface(true,2,0,true);stats.calibration(null);stats.calibration(floatArrayOf(0f,-1.6f,0f,0f,0f,0f,1f))
  val report=stats.initialization();assertTrue(report.getBoolean("surfaceCreated"));assertTrue(report.getBoolean("stageSupported"));assertEquals(1L,report.getLong("stageValidSamples"));assertFalse(report.getBoolean("remoteVideo"))
 }
}

package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UuStereoFramesTest {
    private fun packet(id: Long=1,total: Int=48003,offset: Int=0,key: Boolean=true,generation: Int=1): ByteArray {
        val count=minOf(48000,total-offset)
        val b=ByteBuffer.allocate(136+count).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OUVS".toByteArray());b.putShort(1);b.putShort(if(key) 1 else 0);b.putInt(generation);b.putLong(id);b.putLong(100)
        b.putShort(2048);b.putShort(1024);b.put(1);b.put(ByteArray(3));b.putInt(total);b.putInt(offset);b.putInt(count)
        repeat(2) {b.putFloat(0f);b.putFloat(0f);b.putFloat(0f);repeat(3) {b.putFloat(0f)};b.putFloat(1f);b.putFloat(-.8f);b.putFloat(.8f);b.putFloat(.8f);b.putFloat(-.8f)}
        repeat(count) {b.put(((offset+it)%251).toByte())};return b.array()
    }
    @Test fun reorderedFragmentsKeepBothEyesAndIgnoreDuplicates() {
        val receiver=UuStereoFrames()
        assertNull(receiver.accept(packet(offset=48000),0));assertNull(receiver.accept(packet(offset=48000),1))
        val frame=receiver.accept(packet(),2)!!
        assertEquals(0L,frame.receivedAtMs)
        assertEquals(48003,frame.bytes.size);assertEquals(22,frame.views.size);assertEquals(2048,frame.width)
        assertTrue(frame.bytes.indices.all {frame.bytes[it]==(it%251).toByte()})
        assertNull(receiver.accept(packet(),3))
    }
    @Test fun missingFrameRequiresAnIdrAndDoesNotReplayOlderImages() {
        val receiver=UuStereoFrames();assertNotNull(receiver.accept(packet(total=4),0))
        assertNull(receiver.accept(packet(id=3,total=4,key=false),1));assertTrue(receiver.needsKeyframe)
        assertNotNull(receiver.accept(packet(id=4,total=4),2));assertFalse(receiver.needsKeyframe)
        assertNull(receiver.accept(packet(id=2,total=4),3))
    }
    @Test fun staleIncompleteFramesAreDropped() {
        val receiver=UuStereoFrames();assertNull(receiver.accept(packet(),0))
        assertNull(receiver.accept(packet(id=2,total=4,key=false),130))
        assertTrue(receiver.needsKeyframe)
    }
    @Test fun rejectsOversizeAndNonfiniteViewsBeforeAllocatingFrame() {
        val oversized=packet(total=4);ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(36,UuStereoFrames.MAX+1)
        assertTrue(runCatching {UuStereoFrames().accept(oversized,0)}.isFailure)
        val bad=packet(total=4);ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(48,Float.NaN)
        assertTrue(runCatching {UuStereoFrames().accept(bad,0)}.isFailure)
    }
    @Test fun newGenerationResetsSequenceButRejectsOldGeneration() {
        val receiver=UuStereoFrames();assertNotNull(receiver.accept(packet(id=30,total=4),0))
        assertNotNull(receiver.accept(packet(id=1,total=4,generation=2),1))
        assertTrue(runCatching {receiver.accept(packet(id=31,total=4),2)}.isFailure)
    }
}

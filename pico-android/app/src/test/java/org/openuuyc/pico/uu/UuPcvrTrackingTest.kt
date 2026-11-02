package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuPcvrTrackingTest {
    private fun frame(size:Int,version:Int)=ByteArray(size).also {"OUVR".toByteArray().copyInto(it);it[4]=version.toByte();it[8]=1}
    @Test fun v2PreservesAimAndAnalogWithCapableHost() {
        val f=frame(400,2);f[224]=3;f[352]=127;f[32]=63
        assertSame(f,UuPcvrTracking.packet(f,2))
        val old=UuPcvrTracking.packet(f,1)!!
        assertEquals(224,old.size);assertEquals(1,old[4].toInt());assertEquals(2,f[4].toInt())
        assertEquals(15,old[32].toInt());assertEquals(63,f[32].toInt());f[32]=15
        assertArrayEquals(f.copyOfRange(8,224),old.copyOfRange(8,224))
    }
    @Test fun acceptsV1AndRejectsVersionSizeMismatch() {
        val v1=frame(224,1);assertSame(v1,UuPcvrTracking.packet(v1,2))
        assertNull(UuPcvrTracking.packet(frame(400,1),2))
        assertNull(UuPcvrTracking.packet(frame(224,2),2))
        assertNull(UuPcvrTracking.packet(ByteArray(399),2))
        v1[0]=0;assertNull(UuPcvrTracking.packet(v1,2))
    }
}

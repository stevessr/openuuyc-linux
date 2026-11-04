package org.openuuyc.pico.uu
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    @Test fun gazeV3RequiresPeerCapabilityAndDowngradesWithoutLosingTracking() {
        val gaze=frame(444,3).also {
            it[32]=63
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(16,100_000_000L);putInt(400,1);putLong(408,95_000_000L);putFloat(64,1f)
                putFloat(424,-.03f);putFloat(440,1f)
            }
        }
        assertSame(gaze,UuPcvrTracking.packet(gaze,2,3))
        val v2=UuPcvrTracking.packet(gaze,2,2)!!
        assertEquals(400,v2.size);assertEquals(2,v2[4].toInt());assertEquals(63,v2[32].toInt())
        val v1=UuPcvrTracking.packet(gaze,1,2)!!
        assertEquals(224,v1.size);assertEquals(1,v1[4].toInt());assertEquals(15,v1[32].toInt())
        assertNull(UuPcvrTracking.packet(frame(444,2),2,3))
    }
    @Test fun gazeRequiresRuntimeSupportActiveSessionExplicitConsentAndPeerV3() {
        assertFalse(UuPcvrTracking.gazeAllowed(false,true,true,3))
        assertFalse(UuPcvrTracking.gazeAllowed(true,false,true,3))
        assertFalse(UuPcvrTracking.gazeAllowed(true,true,false,3))
        assertFalse(UuPcvrTracking.gazeAllowed(true,true,true,2))
        assertTrue(UuPcvrTracking.gazeAllowed(true,true,true,3))
    }
}

package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuProtoDefaultsTest {
    @Test fun prostOmittedPrimaryScreenAndVideoZeroAreValidMappings() {
        // PbControlMessage.screens { screens: [ { is_primary_screen: true } ] }
        // prost omits id=0, current_screen_id=0 and video_track_index=0.
        val envelope = UuWire.decodeEnvelope(byteArrayOf(0x3a, 4, 0x0a, 2, 0x38, 1))
        assertEquals(0, envelope.screenId)
        assertEquals(0, envelope.videoTrackIndex)
        assertEquals(mapOf(0 to 0), envelope.screenTracks)
    }
    @Test fun explicitlyListedEmptyProtoScreenStillHasProto3Defaults() {
        val envelope = UuWire.decodeEnvelope(byteArrayOf(0x3a, 2, 0x0a, 0))
        assertEquals(mapOf(0 to 0), envelope.screenTracks)
    }
    @Test fun preCaptureNegativeCurrentSelectsFirstRealListedSource() {
        val negative = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 1)
        val sources = byteArrayOf(0x0a, 0, 0x10) + negative
        val envelope = UuWire.decodeEnvelope(byteArrayOf(0x3a, sources.size.toByte()) + sources)
        assertEquals(0, envelope.screenId); assertEquals(0, envelope.videoTrackIndex)
    }
    @Test fun missingScreensPayloadDoesNotInventPrimaryScreen() {
        val echo = UuWire.decodeEnvelope(byteArrayOf(0x1a, 0))
        assertTrue(echo.echoRequest)
        assertFalse(echo.echoResponse)
        assertNull(echo.screenId)
        assertTrue(echo.screenTracks.isEmpty())
    }
}

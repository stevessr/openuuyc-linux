package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuVideoMidTest {
    @Test fun dataOnlyAnswerIsNotARejectedVideoStream() {
        val data="v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:0\r\na=sctp-port:5000\r\n"
        assertTrue(UuWire.acceptedVideoTrackIndices(data,mediaRequired=false).isEmpty())
        assertTrue(UuWire.videoMidIndices(data).isEmpty())
        assertTrue(runCatching {UuWire.acceptedVideoTrackIndices(data)}.isFailure)
    }
    private val sdp = """v=0
m=audio 9 UDP/TLS/RTP/SAVPF 111
a=mid:0
m=video 9 UDP/TLS/RTP/SAVPF 102
a=mid:1
a=msid:video_0 sdk-generated-uuid
m=video 0 UDP/TLS/RTP/SAVPF 102
a=mid:2
m=video 9 UDP/TLS/RTP/SAVPF 102
a=mid:3
m=application 9 UDP/DTLS/SCTP webrtc-datachannel
a=mid:4
"""
    @Test fun sdkUuidTrackBindsBySdpMidNotByCallbackOrder() {
        assertEquals(mapOf("1" to 0, "3" to 2), UuWire.videoMidIndices(sdp))
        val router = UuVideoTrackRouter<String>()
        router.select(0)
        router.observed("sdk-generated-uuid", "video-frame-source")
        assertNull(router.active())
        router.observedAt(UuWire.videoMidIndices(sdp).getValue("1"), "video-frame-source")
        assertEquals("video-frame-source", router.active())
        router.observedAt(2, "other-monitor")
        assertEquals("video-frame-source", router.active())
    }
    @Test fun rejectedMLineDoesNotRenumberFollowingVideo() {
        assertEquals(2, UuWire.videoMidIndices(sdp)["3"])
        assertFalse(UuWire.videoMidIndices(sdp).containsKey("2"))
    }
    @Test fun invalidExplicitIndexIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { UuVideoTrackRouter<String>().observedAt(5, "bad") }
    }
}

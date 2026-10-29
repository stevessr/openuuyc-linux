package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuAudioOfferTest {
 @Test fun editsAudioOnlyAndIsIdempotent() {
    val s="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=ptime:60\r\na=fmtp:111 useinbandfec=1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=ptime:90\r\n"
    val result=UuAudioOffer.lowLatency(s)
    assertTrue(result.contains("a=ptime:10"));assertTrue(result.contains("a=ptime:90"));assertTrue(result.contains("useinbandfec=1"))
    assertEquals(result,UuAudioOffer.lowLatency(result))
 }
 @Test fun addsMissingPtimeAndLeavesVideoOnlyOffersUnchanged() {
    val a="v=0\nm=audio 9 x 111\na=mid:audio\nm=video 9 x 96\n"
    assertTrue(UuAudioOffer.lowLatency(a).contains("a=mid:audio\na=ptime:10\nm=video"))
    val v="v=0\nm=video 9 x 96\na=mid:video\n";assertEquals(v,UuAudioOffer.lowLatency(v))
 }
}

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
 @Test fun independentAudioPreservesTrackTransportAndAllVideoLines() {
    val s="v=0\r\na=msid-semantic: WMS shared\r\nm=audio 9 x 111\r\na=msid:shared audio_0\r\na=ssrc:12 msid:shared audio_0\r\na=ssrc:12 cname:unchanged\r\na=ssrc:12 mslabel:shared\r\na=ice-pwd:not-modified\r\nm=video 9 x 96\r\na=msid:shared video_0\r\na=ssrc:13 msid:shared video_0\r\n"
    val result=UuAudioOffer.independentPlayback(s,true)
    assertTrue(result.contains("a=msid:- audio_0"));assertTrue(result.contains("a=ssrc:12 msid:- audio_0"))
    assertTrue(result.contains("a=ssrc:12 mslabel:-"));assertTrue(result.contains("a=ssrc:12 cname:unchanged"))
    assertTrue(result.contains("a=ice-pwd:not-modified"));assertEquals(s.substringAfter("m=video"),result.substringAfter("m=video"))
    assertEquals(result,UuAudioOffer.independentPlayback(result,true))
    assertEquals(s,UuAudioOffer.independentPlayback(s,false))
 }
 @Test fun dataAndVideoOnlyDescriptionsAreUnchanged() {
    val s="v=0\nm=application 9 x 1\na=mid:data\nm=video 9 x 96\na=msid:shared video\n"
    assertEquals(s,UuAudioOffer.independentPlayback(s,true))
 }
 @Test fun shortPacketsUseIntegerThreeAndOnlyRewriteOpusMinptime() {
    val s="v=0\nm=audio 9 x 111 0\na=fmtp:111 minptime=10;useinbandfec=1\na=rtpmap:111 opus/48000/2\na=rtpmap:0 PCMU/8000\na=fmtp:0 untouched=1\na=ptime:60\nm=video 9 x 111\na=fmtp:111 minptime=90\n"
    val result=UuAudioOffer.lowLatency(s,true)
    assertTrue(result.contains("a=ptime:3\n"));assertTrue(result.contains("useinbandfec=1;minptime=3"))
    assertTrue(result.contains("a=fmtp:0 untouched=1"));assertTrue(result.contains("a=fmtp:111 minptime=90"))
    assertEquals(result,UuAudioOffer.lowLatency(result,true))
 }
}

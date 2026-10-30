package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuNativeAudioPolicyTest {
 @Test fun probesPcmOnlyAfterHealthyWindows() {val p=UuNativeAudioPolicy();repeat(4){assertTrue(p.update(it*1000L,400,0,0,0,1.0))};assertEquals("opus_lowdelay",p.codec);p.update(5000,400,0,0,0,1.0);assertEquals("pcm_s16le",p.codec)}
 @Test fun degradationReturnsToOpusAndAvoidsFastFlapping() {val p=UuNativeAudioPolicy();repeat(6){p.update(it*1000L,400,0,0,0,1.0)};p.update(6000,400,1,1,0,4.0);assertEquals("opus_lowdelay",p.codec);repeat(4){p.update(7000+it*1000L,400,0,0,0,1.0)};assertEquals("opus_lowdelay",p.codec)}
 @Test fun persistentFailureFallsBackRatherThanInflatingQueuesForever(){val p=UuNativeAudioPolicy();assertTrue(p.update(0,100,12,12,0,20.0));assertTrue(p.update(1000,100,12,12,0,20.0));assertFalse(p.update(2000,100,12,12,0,20.0));assertTrue(p.bursts<=4)}
 @Test fun stablePlaybackCanShrinkDeviceBuffer(){val p=UuNativeAudioPolicy();repeat(12){p.update(it*1000L,400,0,0,0,1.0)};assertEquals(1,p.bursts)}
 @Test fun transitionUnderrunDoesNotCauseImmediateCodecFlapping(){val p=UuNativeAudioPolicy();repeat(5){p.update(it*1000L,400,0,0,0,1.0)};assertEquals("pcm_s16le",p.codec);p.update(4500,400,2,2,0,5.0);assertEquals("pcm_s16le",p.codec)}
 @Test fun pcmNeedsAnExplicitFastLinkObservation(){val p=UuNativeAudioPolicy();repeat(20){p.update(it*1000L,400,0,0,0,1.0,false)};assertEquals("opus_lowdelay",p.codec)}
}

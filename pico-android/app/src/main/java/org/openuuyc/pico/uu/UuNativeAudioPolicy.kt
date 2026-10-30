package org.openuuyc.pico.uu
/** Hysteresis: favor PCM only after measured healthy windows; do not flap. */
internal class UuNativeAudioPolicy {
    var codec="opus_lowdelay";private set
    var bursts=2;private set
    private var good=0
    private var bad=0
    private var cooldown=0L
    private var settleUntil=0L
    private var bufferCooldown=0L
    fun update(now: Long,packets: Long,gaps: Long,underruns: Long,xruns: Long,jitterMs: Double,pcmAllowed: Boolean=true): Boolean {
        if(now<settleUntil) return true
        val severe=gaps>8 || underruns>8 || xruns>0 || jitterMs>12
        bad=if(severe) bad+1 else 0
        if(bad>=3) return false
        val healthy=packets>=200 && gaps==0L && underruns==0L && xruns==0L && jitterMs<=4.0
        good=if(healthy) good+1 else 0
        if(!pcmAllowed && codec=="pcm_s16le") {codec="opus_lowdelay";cooldown=now+5000}
        if(!healthy) {
            bursts=(bursts+1).coerceAtMost(4);bufferCooldown=now+30000
            if(codec=="pcm_s16le") {codec="opus_lowdelay";cooldown=now+5000;settleUntil=now+2000}
        } else {
            if(pcmAllowed && good>=5 && now>=cooldown && codec!="pcm_s16le") {codec="pcm_s16le";cooldown=now+5000;settleUntil=now+2000}
            if(good>=10 && now>=bufferCooldown && bursts>1) {bursts=1;settleUntil=now+2000;bufferCooldown=now+30000}
        }
        return true
    }
}

package org.openuuyc.pico.uu
/** Request short Opus packets only in audio sections; do not rewrite video/ICE/credentials. */
internal object UuAudioOffer {
    fun lowLatency(sdp: String,shortPackets: Boolean=false): String {
        val eol=if(sdp.contains("\r\n")) "\r\n" else "\n"
        val lines=sdp.trimEnd('\r','\n').split(Regex("\\r?\\n"))
        val ptime=if(shortPackets) 3 else 10 // RFC7587: 2.5ms is signalled as ptime=3.
        var opusPayloads=emptySet<String>()
        val result=mutableListOf<String>();var audio=false;var requested=false
        fun finish() {if(audio && !requested) result.add("a=ptime:$ptime")}
        for((index,line) in lines.withIndex()) {
            if(line.startsWith("m=")) {
                finish();audio=line.startsWith("m=audio ");requested=false
                val end=(index+1 until lines.size).firstOrNull {lines[it].startsWith("m=")} ?: lines.size
                opusPayloads=if(audio) lines.subList(index+1,end).mapNotNull {Regex("^a=rtpmap:([0-9]+) opus/48000/2$",RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)}.toSet() else emptySet()
            }
            if(audio && line.startsWith("a=ptime:")) {if(!requested) result.add("a=ptime:$ptime");requested=true}
            else if(audio && shortPackets && line.startsWith("a=fmtp:") && line.substringAfter("a=fmtp:").substringBefore(' ') in opusPayloads) {
                val parameters=line.substringAfter(' ').split(';').map {it.trim()}.filter {it.isNotEmpty() && !it.startsWith("minptime=",ignoreCase=true)}
                result.add(line.substringBefore(' ')+" "+(parameters+"minptime=3").joinToString(";"))
            } else result.add(line)
        }
        finish();return result.joinToString(eol)+eol
    }
    /** Only local receiver grouping changes. Track IDs, SSRC, codec and ICE stay intact.
     * A streamless audio track has no video sync group that can add playout delay. */
    fun independentPlayback(sdp: String,enabled: Boolean): String {
        if(!enabled) return sdp
        val eol=if(sdp.contains("\r\n")) "\r\n" else "\n"
        var audio=false
        val lines=sdp.trimEnd('\r','\n').split(Regex("\\r?\\n")).map {line->
            if(line.startsWith("m=")) audio=line.startsWith("m=audio ")
            if(!audio) line else when {
                line.startsWith("a=msid:")->line.replace(Regex("^a=msid:[^ ]+"),"a=msid:-")
                line.startsWith("a=ssrc:") && line.contains(" msid:")->line.replace(Regex(" msid:[^ ]+")," msid:-")
                line.startsWith("a=ssrc:") && line.contains(" mslabel:")->line.substringBefore(" mslabel:")+" mslabel:-"
                else->line
            }
        }
        return lines.joinToString(eol)+eol
    }
}

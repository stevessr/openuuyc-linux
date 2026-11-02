package org.openuuyc.pico.uu
/** Advertise PICO Opus receive quality and packet timing without rewriting video/ICE. */
internal object UuAudioOffer {
    fun lowLatency(sdp: String,shortPackets: Boolean=false): String {
        val eol=if(sdp.contains("\r\n")) "\r\n" else "\n"
        val lines=sdp.trimEnd('\r','\n').split(Regex("\\r?\\n"))
        val ptime=if(shortPackets) 3 else 10 // RFC7587: 2.5ms is signalled as ptime=3.
        var opusPayloads=emptySet<String>()
        var opusFmtp=mutableSetOf<String>()
        val result=mutableListOf<String>();var audio=false;var requested=false
        fun finish() {
            if(!audio) return
            for(payload in opusPayloads) if(payload !in opusFmtp) {
                val parameters=mutableListOf("stereo=1","maxaveragebitrate=256000")
                if(shortPackets) parameters.add("minptime=3")
                result.add("a=fmtp:$payload "+parameters.joinToString(";"))
            }
            if(!requested) result.add("a=ptime:$ptime")
        }
        for((index,line) in lines.withIndex()) {
            if(line.startsWith("m=")) {
                finish();audio=line.startsWith("m=audio ");requested=false;opusFmtp=mutableSetOf()
                val end=(index+1 until lines.size).firstOrNull {lines[it].startsWith("m=")} ?: lines.size
                opusPayloads=if(audio) lines.subList(index+1,end).mapNotNull {Regex("^a=rtpmap:([0-9]+) opus/48000/2$",RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)}.toSet() else emptySet()
            }
            if(audio && line.startsWith("a=ptime:")) {if(!requested) result.add("a=ptime:$ptime");requested=true}
            else if(audio && line.startsWith("a=fmtp:")) {
                val fmtp=line.substringAfter("a=fmtp:")
                val payload=fmtp.substringBefore(' ')
                if(payload !in opusPayloads) result.add(line)
                else {
                    val existing=fmtp.substringAfter(' ',"").split(';').map {it.trim()}.filter {parameter->
                        val name=parameter.substringBefore('=')
                        parameter.isNotEmpty() && !name.equals("stereo",true) &&
                            !name.equals("maxaveragebitrate",true) &&
                            (!shortPackets || !name.equals("minptime",true))
                    }
                    val packetParameters=if(shortPackets) listOf("minptime=3") else emptyList()
                    val parameters=existing+listOf("stereo=1","maxaveragebitrate=256000")+packetParameters
                    result.add("a=fmtp:$payload "+parameters.joinToString(";"))
                    opusFmtp.add(payload)
                }
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

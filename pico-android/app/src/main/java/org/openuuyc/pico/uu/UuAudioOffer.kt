package org.openuuyc.pico.uu
/** Request short Opus packets only in audio sections; do not rewrite video/ICE/credentials. */
internal object UuAudioOffer {
    fun lowLatency(sdp: String): String {
        val eol=if(sdp.contains("\r\n")) "\r\n" else "\n"
        val lines=sdp.trimEnd('\r','\n').split(Regex("\\r?\\n"))
        val result=mutableListOf<String>();var audio=false;var requested=false
        fun finish() {if(audio && !requested) result.add("a=ptime:10")}
        for(line in lines) {
            if(line.startsWith("m=")) {finish();audio=line.startsWith("m=audio ");requested=false}
            if(audio && line.startsWith("a=ptime:")) {if(!requested) result.add("a=ptime:10");requested=true}
            else result.add(line)
        }
        finish();return result.joinToString(eol)+eol
    }
}

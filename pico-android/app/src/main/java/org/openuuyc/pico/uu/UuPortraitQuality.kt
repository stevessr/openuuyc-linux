package org.openuuyc.pico.uu

/** Orientation-aware startup correction. Never overrides explicit user choices. */
internal object UuPortraitQuality {
    fun plan(source: UuDisplayMode?,decoded: Pair<Int,Int>?,current: UuCaptureSettings,profiles: List<UuCodecProfile>,configured: Boolean): UuCaptureSettings? {
        if(configured) return null
        val portrait=(source!=null && source.height>source.width) || (decoded!=null && decoded.second>decoded.first)
        if(!portrait) return null
        val desired=minOf(2160,maxOf(1920,source?.height ?: 0))
        if(current.maxHeight>=desired) return null
        if(profiles.none {(current.codec==0 || it.codec==current.codec) && it.width>=current.maxWidth && it.height>=desired && it.fps>=current.fps}) return null
        return current.copy(maxHeight=desired)
    }
}

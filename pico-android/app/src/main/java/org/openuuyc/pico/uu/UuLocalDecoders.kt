package org.openuuyc.pico.uu

import android.media.MediaCodecList
import org.webrtc.DefaultVideoDecoderFactory

internal object UuLocalDecoders {
    /** Only advertise formats returned by the actual Android WebRTC decoder factory. */
    fun profiles(): List<UuCodecProfile> {
        val names = DefaultVideoDecoderFactory(null).supportedCodecs.map { it.name.uppercase() }.toSet()
        val types = listOf(Triple(1, "H264", "video/avc"), Triple(2, "H265", "video/hevc"), Triple(5, "AV1", "video/av01"))
        return types.mapNotNull { (id, name, mime) ->
            if (name !in names && !(name == "H265" && "HEVC" in names)) return@mapNotNull null
            val limits = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && mime in it.supportedTypes }.mapNotNull { info ->
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            }
            val width = limits.maxOfOrNull { it.supportedWidths.upper }?.coerceAtMost(4096) ?: 1920
            val height = limits.maxOfOrNull { it.supportedHeights.upper }?.coerceAtMost(4096) ?: 1080
            if (limits.isEmpty() && id != 1) return@mapNotNull null
            val fps = listOf(30, 60, 90, 120, 144, 165, 240).filter { rate ->
                limits.any { cap -> runCatching { cap.areSizeAndRateSupported(minOf(width, 1920), minOf(height, 1080), rate.toDouble()) }.getOrDefault(false) }
            }.maxOrNull() ?: 30
            UuCodecProfile(id, width, height, fps)
        }.ifEmpty { listOf(UuCodecProfile(1, 1920, 1080)) }
    }
}

package org.openuuyc.pico.uu

import org.json.JSONObject

internal data class UuDisplayMode(val width: Int, val height: Int) {
    override fun toString() = "${width} × $height"
}
internal data class UuRemoteScreen(
    val id: Int, val track: Int, val name: String, val fps: Int,
    val current: UuDisplayMode?, val modes: List<UuDisplayMode>, val dpi: Int, val dpis: List<Int>, val screenType: Int = 0
) { override fun toString() = name.ifBlank { "屏幕 ${id + 1}" } }
internal data class UuCaptureSettings(
    val fps: Int = 60, val quality: Int = 5, val bitrateMbps: Int = 30,
    val codec: Int = 0, val cursor: Boolean = true, val maxWidth: Int = 1920, val maxHeight: Int = 1080
) {
    fun validate() {
        require(fps in 1..240 && quality in 1..6 && bitrateMbps in 1..500)
        require(codec in setOf(0, 1, 2, 5) && maxWidth in 320..8192 && maxHeight in 200..8192)
    }
}
internal data class UuCodecProfile(val codec: Int, val width: Int, val height: Int, val fps: Int = 60) {
    override fun toString() = when(codec) { 0 -> "自动（双方能力协商）"; 2 -> "H.265 / HEVC"; 5 -> "AV1"; else -> "H.264" }
}
internal class UuRemoteSettingsState {
    var screens: List<UuRemoteScreen> = emptyList()
    var selected: Int? = null
    var captureLevel = 0
    var profiles: List<UuCodecProfile> = emptyList()
    var localCodecs: Set<Int> = setOf(1)
    var current = UuCaptureSettings()
    var pending: Pair<Long, UuCaptureSettings>? = null
    fun receiveCapabilities(json: JSONObject) {
        val list = json.optJSONArray("video_codec_capability") ?: return
        profiles = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val codec = item.optInt("video_codec")
            if (codec !in localCodecs || item.optInt("bit_depth", 8) != 8 || item.optInt("chroma_sampling", 1) != 1) return@mapNotNull null
            UuCodecProfile(codec, item.optInt("width", 1920), item.optInt("height", 1080))
        }.filter { it.width > 0 && it.height > 0 }.distinct()
    }
    fun selectableProfiles(): List<UuCodecProfile> = if (profiles.isEmpty()) emptyList() else
        listOf(UuCodecProfile(0, profiles.minOf { it.width }, profiles.minOf { it.height }, profiles.minOf { it.fps })) + profiles.distinctBy { it.codec }
    fun validate(settings: UuCaptureSettings) {
        settings.validate()
        require(profiles.any { (settings.codec == 0 || it.codec == settings.codec) && settings.maxWidth <= it.width && settings.maxHeight <= it.height && settings.fps <= it.fps }) { "目标编码/分辨率超出双方能力交集" }
        val maximum = screens.firstOrNull { it.id == selected }?.fps ?: 60
        require(settings.fps <= maximum.coerceAtLeast(30)) { "目标帧率超出对端屏幕能力" }
        require(pending == null) { "上一次设置尚未收到确认" }
    }
    fun confirm(id: Long, errors: List<Int>): Boolean? {
        val update = pending?.takeIf { it.first == id } ?: return null
        pending = null
        val success = errors.none { it != 0 && it != -3 }
        if (success) current = update.second
        return success
    }
}

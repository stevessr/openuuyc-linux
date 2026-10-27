package org.openuuyc.pico.uu

/**
 * UU remote video identifiers are "video_0" through "video_4".
 * Desktop controller negotiates five separate recvonly m-lines and the
 * selected screen's ScreenSources.video_track_index chooses the visible sink.
 *
 * Unknown stream IDs are retained as fallback only for peer dialects without
 * standard UU MSIDs. They must never override an explicitly selected track.
 */
internal class UuVideoTrackRouter<T> {
    private val tracks = LinkedHashMap<Int, T>()
    private var fallback: T? = null
    private var desired: Int? = null

    fun observed(trackId: String, track: T): T? {
        val index = trackId.removePrefix("video_")
            .takeIf { trackId.startsWith("video_") && it.length in 1..2 &&
                it.all(Char::isDigit) && (it == "0" || !it.startsWith("0")) }
            ?.toIntOrNull()?.takeIf { it in 0..4 }
        if (index != null) tracks[index] = track
        else if (fallback == null) fallback = track
        return active()
    }

    fun select(index: Int?): T? {
        require(index == null || index >= 0)
        desired = index
        return active()
    }

    fun active(): T? {
        val wanted = desired
        return if (wanted != null) tracks[wanted]
        else tracks[0] ?: tracks.values.firstOrNull() ?: fallback
    }

    fun reset() {
        tracks.clear()
        fallback = null
        desired = null
    }
}

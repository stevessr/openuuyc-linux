package org.openuuyc.pico.uu

/**
 * Map the received UU streams into up to three spatial windows.
 *
 * The central window always respects the server-selected track. Side windows
 * are created only for actually received, separate tracks. Screen identifiers
 * come exclusively from ScreenSources messages, never from track indices.
 */
internal object UuWindowLayout {
    data class Pane(val track: Int, val screen: Int?)

    fun plan(
        selectedScreen: Int?,
        selectedTrack: Int?,
        screenTracks: Map<Int, Int>,
        receivedTracks: List<Int>,
        maxWindows: Int = 3
    ): List<Pane?> {
        val present = receivedTracks.filter { it in 0..4 }.distinct().sorted()
        val main = selectedTrack ?: present.firstOrNull()
        if (main == null || main !in 0..4) return List(maxWindows.coerceIn(1,6)) {null}
        val screensByTrack = screenTracks.entries
            .filter { it.key >= 0 && it.value in 0..4 }
            .sortedBy { it.key }
            .associate { it.value to it.key }
        val rest = present.filter { it != main }
        val ranked = rest.sortedWith(compareBy<Int> { if (screensByTrack.containsKey(it)) 0 else 1 }.thenBy { it })
        return List(maxWindows.coerceIn(1,6)) {index ->
            if(index==0) Pane(main, selectedScreen ?: screensByTrack[main])
            else ranked.getOrNull(index-1)?.let {Pane(it,screensByTrack[it])}
        }
    }
}

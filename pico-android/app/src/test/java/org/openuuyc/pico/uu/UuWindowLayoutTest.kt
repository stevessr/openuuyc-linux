package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuWindowLayoutTest {
    @Test fun mapsThreeIndependentScreenSources() {
        val panes = UuWindowLayout.plan(7, 2, mapOf(7 to 2, 13 to 3, 21 to 1), listOf(1, 2, 3))
        assertEquals(UuWindowLayout.Pane(2, 7), panes[0])
        assertEquals(UuWindowLayout.Pane(1, 21), panes[1])
        assertEquals(UuWindowLayout.Pane(3, 13), panes[2])
    }

    @Test fun selectedTrackIsNotReplacedWithUnrelatedStream() {
        val panes = UuWindowLayout.plan(8, 4, mapOf(8 to 4), listOf(0, 1))
        assertEquals(UuWindowLayout.Pane(4, 8), panes[0])
        assertNull(panes[2])
    }

    @Test fun onlyObservedStreamsProduceSideWindows() {
        val panes = UuWindowLayout.plan(0, 0, mapOf(0 to 0, 3 to 1, 4 to 4), listOf(0, 4))
        assertEquals(UuWindowLayout.Pane(4, 4), panes[1])
        assertNull(panes[2])
    }

    @Test fun neverInventScreenIdFromTrackIndex() {
        val panes = UuWindowLayout.plan(null, null, emptyMap(), listOf(2, 3, 4))
        assertEquals(2, panes[0]?.track)
        assertNull(panes[0]?.screen)
        assertNull(panes[1]?.screen)
    }

    @Test fun invalidAndDuplicateStreamsCannotCreateGhostWindows() {
        val panes = UuWindowLayout.plan(null, null, emptyMap(), listOf(-1, 1, 1, 7))
        assertEquals(1, panes.count { it != null })
    }
}

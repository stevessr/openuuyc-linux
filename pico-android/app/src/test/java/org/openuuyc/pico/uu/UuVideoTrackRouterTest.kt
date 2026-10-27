package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuVideoTrackRouterTest {
    @Test fun desktopFiveMLineTrackIdsSelectCorrectScreen() {
        val router = UuVideoTrackRouter<String>()
        for (i in 0..4) router.observed("video_$i", "decoded-$i")
        assertEquals("decoded-0", router.active())
        assertEquals("decoded-4", router.select(4))
        assertEquals("decoded-2", router.select(2))
        assertEquals("decoded-0", router.select(0))
    }

    @Test fun lateArrivalCannotOverwriteSelectedScreenWithOtherMonitor() {
        val router = UuVideoTrackRouter<String>()
        router.observed("video_0", "display0")
        assertNull(router.select(3))  // stop showing screen 0
        assertNull(router.observed("video_1", "display1"))
        assertEquals("display3", router.observed("video_3", "display3"))
        assertEquals("display3", router.observed("video_4", "display4"))
    }

    @Test fun nonStandardTrackIdsNeverHijackExplicitSelection() {
        val router = UuVideoTrackRouter<String>()
        assertEquals("fallback", router.observed("random-rtc-id", "fallback"))
        assertEquals("correct", router.observed("video_1", "correct"))
        assertEquals("correct", router.select(1))
        router.observed("video_99", "unrecognized")
        assertEquals("correct", router.active())
        assertNull(router.select(3))
    }

    @Test fun badVideoIdentifiersCannotAliasExistingTrack() {
        val router = UuVideoTrackRouter<String>()
        router.observed("video_1", "original")
        router.observed("video_-1", "invalid")
        router.observed("video_01", "leading-zero")
        router.observed("audio_1", "not-video")
        assertEquals("original", router.select(1))
        assertThrows(IllegalArgumentException::class.java) { router.select(-1) }
    }

    @Test fun trackReplacementAndNewSessionResetHaveNoStaleSinks() {
        val router = UuVideoTrackRouter<String>()
        router.observed("video_0", "old")
        router.select(0)
        assertEquals("new", router.observed("video_0", "new"))
        router.reset()
        assertNull(router.active())
        assertEquals("next", router.observed("video_0", "next"))
    }
}

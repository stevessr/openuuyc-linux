package org.openuuyc.pico

import org.junit.Assert.*
import org.junit.Test

class PanelViewportTest {
    @Test fun defaultModeNeverEntersImmersive() {
        assertEquals(PicoViewerMode.WINDOW, PicoViewerMode.DEFAULT)
        assertNotEquals(PicoViewerMode.IMMERSIVE, PicoViewerMode.DEFAULT)
    }
    @Test fun landscapeAndPortraitVideoFitWithoutStretching() {
        assertEquals(PanelViewport.Box(0, 0, 1280, 720), PanelViewport.fit(1280, 720, 1920, 1080))
        assertEquals(PanelViewport.Box(0, 219, 1000, 562), PanelViewport.fit(1000, 1000, 1920, 1080))
        assertEquals(PanelViewport.Box(15, 0, 450, 800), PanelViewport.fit(480, 800, 1080, 1920))
    }
    @Test fun pointerCoordinatesStayInsideRemoteScreen() {
        assertEquals(.5f, PanelViewport.coordinate(200f, 400), .0001f)
        assertEquals(0f, PanelViewport.coordinate(-10f, 400), 0f)
        assertEquals(1f, PanelViewport.coordinate(410f, 400), 0f)
        assertEquals(0f, PanelViewport.coordinate(10f, 0), 0f)
    }
    @Test fun emptyWindowCanBeMeasuredWhileResizing() {
        assertEquals(PanelViewport.Box(0, 0, 0, 0), PanelViewport.fit(0, 0, 1920, 1080))
    }
}

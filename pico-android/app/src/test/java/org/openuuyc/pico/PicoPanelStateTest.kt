package org.openuuyc.pico

import org.junit.Assert.*
import org.junit.Test
import org.openuuyc.pico.uu.UuDevice

class PicoPanelStateTest {
    private fun device(name: String, online: Boolean, controllable: Boolean = true, occupants: Int = 0) =
        UuDevice(name, name, online, controllable, occupants)

    @Test fun onlineFirstAndNameSortAreDeterministic() {
        val state = PicoPanelState()
        state.devices = listOf(device("Zulu", false), device("Beta", true), device("Alpha", false))
        assertEquals(listOf("Beta", "Alpha", "Zulu"), state.visibleDevices().map { it.alias })
        state.sortByName = true
        assertEquals(listOf("Alpha", "Beta", "Zulu"), state.visibleDevices().map { it.alias })
    }
    @Test fun searchAndPresenceFilterComposeWithoutChangingSourceList() {
        val state = PicoPanelState()
        state.devices = listOf(device("Studio Linux", true), device("Studio PC", false), device("Lab", true))
        state.query = "  STUDIO  "
        state.filter = PicoPanelState.Filter.OFFLINE
        assertEquals(listOf("Studio PC"), state.visibleDevices().map { it.alias })
        assertEquals(3, state.devices.size)
        state.query = "no-match"
        assertTrue(state.visibleDevices().isEmpty())
    }
    @Test fun controlsNeverOfferOfflineDisallowedOrOccupiedTargets() {
        assertTrue(PicoPanelState.canConnect(device("ready", true)))
        assertFalse(PicoPanelState.canConnect(device("offline", false)))
        assertFalse(PicoPanelState.canConnect(device("denied", true, false)))
        assertFalse(PicoPanelState.canConnect(device("occupied", true, occupants = 1)))
    }
    @Test fun logoutClearsDeviceCatalogAndFilters() {
        val state = PicoPanelState()
        state.devices = listOf(device("private-device", true))
        state.page = PicoPanelState.Page.SETTINGS; state.query = "private"; state.sortByName = true
        state.filter = PicoPanelState.Filter.ONLINE
        state.clear()
        assertTrue(state.visibleDevices().isEmpty()); assertEquals("", state.query)
        assertEquals(PicoPanelState.Page.DEVICES, state.page)
        assertEquals(PicoPanelState.Filter.ALL, state.filter); assertFalse(state.sortByName)
    }
}

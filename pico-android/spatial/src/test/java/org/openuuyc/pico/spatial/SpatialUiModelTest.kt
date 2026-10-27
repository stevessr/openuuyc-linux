package org.openuuyc.pico.spatial
import org.junit.Assert.*
import org.junit.Test
import org.openuuyc.pico.uu.*

class SpatialUiModelTest {
    private val devices=listOf(UuDevice("a","Zeta",false,true,0),UuDevice("b","Alpha",true,true,0),UuDevice("c","Studio",true,true,1))
    @Test fun connectedThenOnlineThenName() {assertEquals(listOf("c","b","a"),SpatialUiModel.devices(devices,"",DeviceFilter.ALL,setOf("c")).map {it.id})}
    @Test fun searchTrimsAndMatchesCaseInsensitiveNameOrId() {
        assertEquals(listOf("c"),SpatialUiModel.devices(devices," STU ",DeviceFilter.ALL,emptySet()).map {it.id})
        assertEquals(listOf("b"),SpatialUiModel.devices(devices,"B",DeviceFilter.ALL,emptySet()).map {it.id})
        assertTrue(SpatialUiModel.devices(devices,"no match",DeviceFilter.ALL,emptySet()).isEmpty())
    }
    @Test fun filtersDoNotMutateSourceOrHideBusyOnlineDevices() {
        assertEquals(2,SpatialUiModel.devices(devices,"",DeviceFilter.ONLINE,emptySet()).size)
        assertEquals(listOf("a"),SpatialUiModel.devices(devices,"",DeviceFilter.CONNECTED,setOf("a")).map {it.id})
        assertEquals(3,devices.size)
    }
    private fun state()=UuRemoteSettingsState().apply {
        selected=7
        screens=listOf(UuRemoteScreen(7,0,"Display",165,null,emptyList(),100,emptyList()))
        profiles=listOf(UuCodecProfile(1,1920,1080,144),UuCodecProfile(2,1920,1080,60))
    }
    @Test fun fpsChoicesRespectSelectedScreenAndActiveCodec() {
        val state=state()
        state.current=state.current.copy(codec=1)
        assertTrue(144 in SpatialUiModel.fpsChoices(state))
        assertFalse(165 in SpatialUiModel.fpsChoices(state))
        state.current=state.current.copy(codec=2)
        assertEquals(listOf(60,30),SpatialUiModel.fpsChoices(state))
    }
    @Test fun missingCapabilitiesOrRemovedScreenOfferNoUnverifiedFps() {
        val state=state();state.selected=99;assertTrue(SpatialUiModel.fpsChoices(state).isEmpty())
        state.selected=7;state.profiles=emptyList();assertTrue(SpatialUiModel.fpsChoices(state).isEmpty())
    }
}

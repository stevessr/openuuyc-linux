package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuGracefulExitTest {
    @Test fun releaseRoomBeforeDisconnectingNamespace() {
        val frames=UuSocketIoWire.closeFrames(true)
        assertEquals(listOf("42[\"leave\"]","41"),frames)
        val leave=UuSocketIoWire.parse(frames[0]) as UuSocketIoWire.Packet.Event
        assertEquals("leave",leave.name)
        assertEquals(UuSocketIoWire.Packet.Disconnected,UuSocketIoWire.parse(frames[1]))
    }
    @Test fun noNamespaceMeansNoFakeLeaveEvent() {
        assertTrue(UuSocketIoWire.closeFrames(false).isEmpty())
    }
    @Test fun inactiveScreensRemainSelectableButHaveNoActiveMapping() {
        with(UuWire) {
            val packet=message { out -> bytes(out,7,message { sources ->
                bytes(sources,1,message { integer(it,1,0);integer(it,12,0) })
                bytes(sources,1,message { integer(it,1,7);integer(it,12,-1) })
                integer(sources,2,0)
            }) }
            val result=decodeEnvelope(packet)
            assertEquals(listOf(0,7),result.screens.map { it.id })
            assertEquals(-1,result.screens[1].track)
            assertEquals(mapOf(0 to 0),result.screenTracks)
        }
    }
}

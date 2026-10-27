package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuSocketIoWireTest {
    @Test fun engineOpenWithExplicitPingBounds() {
        val open = UuSocketIoWire.parse("""0{"sid":"session","pingInterval":25000,"pingTimeout":20000}""")
            as UuSocketIoWire.Packet.Open
        assertEquals("session", open.sid)
        assertEquals(25000L, open.pingIntervalMs)
        assertThrows(IllegalArgumentException::class.java) {
            UuSocketIoWire.parse("""0{"sid":""}""")
        }
    }

    @Test fun pingIsEnginePacketNotDomainControl() {
        assertEquals(UuSocketIoWire.Packet.Ping(""), UuSocketIoWire.parse("2"))
        assertEquals(UuSocketIoWire.Packet.Ping("probe"), UuSocketIoWire.parse("2probe"))
        assertEquals(UuSocketIoWire.Packet.NamespaceConnected, UuSocketIoWire.parse("40"))
    }

    @Test fun roomAckRequiresNonemptyObjectInCorrectSlot() {
        val room = UuSocketIoWire.parse("""431[{"sid":"s"}]""") as UuSocketIoWire.Packet.RoomAck
        assertEquals("s", room.data.getString("sid"))
        listOf("431[]", "431[{}]", "431[\"ok\"]").forEach {
            assertThrows(IllegalArgumentException::class.java) { UuSocketIoWire.parse(it) }
        }
    }

    @Test fun controlAckNotConfusedWithRoomAck() {
        assertTrue(UuSocketIoWire.parse("""432["success",{"code":0}]""") is UuSocketIoWire.Packet.ControlAck)
        assertTrue(UuSocketIoWire.parse("""433["success",{"code":0}]""") is UuSocketIoWire.Packet.Ignore)
    }

    @Test fun pushEventsGenerateCorrectSocketIoAcknowledgment() {
        val p = UuSocketIoWire.parse("""4217["publisher_disconnect",{"reason":"gone"}]""")
            as UuSocketIoWire.Packet.Event
        assertEquals("publisher_disconnect", p.name)
        assertEquals("17", p.acknowledge)
        assertEquals("4317[]", UuSocketIoWire.eventAck(p.acknowledge!!))
        assertNull((UuSocketIoWire.parse("""42["soac",{"type":"candidate"}]""")
            as UuSocketIoWire.Packet.Event).acknowledge)
    }

    @Test fun binaryEventAttachmentFramingAndLimits() {
        val p = UuSocketIoWire.parse("""451-23["soac",{"data":{"type":"answer"}}]""")
            as UuSocketIoWire.Packet.BinaryStart
        assertEquals("soac", p.name)
        assertEquals("3", p.acknowledge)
        assertArrayEquals(byteArrayOf(1, 2), UuSocketIoWire.binaryPayload(byteArrayOf(4, 1, 2)))
        assertThrows(IllegalArgumentException::class.java) {
            UuSocketIoWire.parse("""452-3["soac",{}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuSocketIoWire.binaryPayload(byteArrayOf(2, 1, 2))
        }
    }

    @Test fun malformedEventsRejectedBeforeDispatch() {
        listOf("42", "42[]", "42[1]", "42abc[\"soac\",{}]", "451-[\"soac\"]").forEach {
            assertThrows(IllegalArgumentException::class.java) { UuSocketIoWire.parse(it) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuSocketIoWire.eventAck("1[]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuSocketIoWire.parse("0" + "x".repeat(1024*1024))
        }
    }

    @Test fun signalingRejectionAndDisconnectAreDistinct() {
        assertEquals(UuSocketIoWire.Packet.Disconnected, UuSocketIoWire.parse("41"))
        assertTrue(UuSocketIoWire.parse("""44{"message":"invalid"}""")
            is UuSocketIoWire.Packet.Rejected)
    }
}

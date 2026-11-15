package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuRemoteScreenDeviceTest {
    private fun screen(
        id: Int = 0, name: String = "", track: Int = 0, deviceName: String = "", deviceKind: Int = 0
    ) = UuRemoteScreen(id, track, name, 60, null, emptyList(), 0, emptyList(), 0, deviceName, deviceKind)

    @Test fun plainDisplayKeepsScreenLabel() {
        assertEquals("显示器 1", screen(name = "显示器 1").toString())
        assertEquals("屏幕 2", screen(id = 1).toString())
    }

    @Test fun windowDevicePrefixesDeviceName() {
        assertEquals("应用窗口 · 记事本", screen(id = 3, name = "记事本", deviceName = "应用窗口", deviceKind = 1).toString())
    }

    @Test fun windowDeviceWithoutTitleFallsBackToWindowIndex() {
        assertEquals("应用窗口 · 窗口 4", screen(id = 3, deviceName = "应用窗口", deviceKind = 1).toString())
        // A blank device name or a non-window kind must not invent a device label.
        assertEquals("屏幕 1", screen(deviceName = "", deviceKind = 1).toString())
        assertEquals("显示器 1", screen(name = "显示器 1", deviceName = "应用窗口", deviceKind = 0).toString())
    }

    @Test fun wireTag101DecodesWindowDevice() {
        val device = UuWire.message { out ->
            UuWire.integer(out, 1, 1) // version
            UuWire.bytes(out, 2, "应用窗口".toByteArray(Charsets.UTF_8))
            UuWire.integer(out, 3, 1) // kind = window
        }
        val entry = UuWire.message { out ->
            UuWire.integer(out, 1, 2) // id
            UuWire.integer(out, 12, 0) // video track
            UuWire.bytes(out, 10, "记事本".toByteArray(Charsets.UTF_8))
            UuWire.bytes(out, 101, device)
        }
        val sources = UuWire.message { out -> UuWire.bytes(out, 1, entry) }
        val envelope = UuWire.decodeEnvelope(UuWire.message { out -> UuWire.bytes(out, 7, sources) })
        val decoded = envelope.screens.single()
        assertEquals("应用窗口", decoded.deviceName)
        assertEquals(1, decoded.deviceKind)
        assertEquals("应用窗口 · 记事本", decoded.toString())
    }

    @Test fun wireWithoutTag101StaysPlainScreen() {
        val entry = UuWire.message { out ->
            UuWire.integer(out, 1, 0)
            UuWire.bytes(out, 10, "显示器 1".toByteArray(Charsets.UTF_8))
        }
        val sources = UuWire.message { out -> UuWire.bytes(out, 1, entry) }
        val envelope = UuWire.decodeEnvelope(UuWire.message { out -> UuWire.bytes(out, 7, sources) })
        val decoded = envelope.screens.single()
        assertEquals("", decoded.deviceName)
        assertEquals(0, decoded.deviceKind)
        assertEquals("显示器 1", decoded.toString())
    }
}

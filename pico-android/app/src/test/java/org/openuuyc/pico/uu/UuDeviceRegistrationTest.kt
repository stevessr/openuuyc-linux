package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuDeviceRegistrationTest {
    @Test fun realDeviceHardwareIsNotReplacedByPlaceholders() {
        val body=deviceRegistrationBody(UuIdentity("client","abcdefghijklmnop","system","machine"),"Android 14","PICO actual board","Actual SoC",11000,listOf("Actual GPU"),"2160x2160","0.1.0")
        assertEquals(11000L,body.getLong("memory"));assertEquals("Actual GPU",body.getJSONArray("video").getString(0))
        assertEquals("Actual SoC",body.getString("cpu"));assertEquals("2160x2160",body.getString("screen"))
        assertEquals("OpenUUYC PICO 0.1.0",body.getString("name"))
    }
    @Test fun windowsCompatibleEndpointUsesDesktopWirePlatformButRealHardware() {
        val identity = UuIdentity("client", "abcdefghijklmnop", "system", "machine")
        val body = deviceRegistrationBody(identity, "Android 14", "pico-board", "arm64-v8a")
        assertEquals(1, body.getInt("platform"))
        assertEquals("Android 14", body.getString("os"))
        assertEquals("pico-board", body.getString("base_board"))
        assertEquals("arm64-v8a", body.getString("cpu"))
        assertFalse(body.getBoolean("controllable"))
        assertEquals(identity.clientId, body.getString("client_id"))
        assertEquals(identity.systemId, body.getString("system_id"))
        assertEquals(identity.machineGuid, body.getString("machine_guid"))
    }
}

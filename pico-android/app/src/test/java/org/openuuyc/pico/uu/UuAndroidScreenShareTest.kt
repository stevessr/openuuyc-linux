package org.openuuyc.pico.uu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UuAndroidScreenShareTest {
    @Test fun connectablePhonesKeepPlatformAndPermission() {
        val devices=UuDeviceList.parse(JSONObject("""{"my_binded_devices":[
            {"device_id":"abcdefghijklmnop","alias":"Phone","platform":2,"status":"CONNECTED","controllable":true,"version_name":"4.35.0"},
            {"device_id":"bcdefghijklmnopq","platform":2,"status":"CONNECTED","controlled_support":true},
            {"device_id":"cdefghijklmnopqr","platform":1,"status":"CONNECTED","controllable":true}
        ]}"""))
        assertEquals(3,devices.size)
        assertTrue(devices[0].androidScreenShare);assertTrue(devices[0].available)
        assertEquals("查看共享屏幕",devices[0].connectionLabel)
        assertEquals("4.35.0",devices[0].versionName)
        assertFalse(devices[1].available) // support is not consent
        assertFalse(devices[2].androidScreenShare);assertTrue(devices[2].available)
    }
    @Test fun managementGroupsDoNotGrantScreenSharing() {
        assertTrue(UuDeviceList.parse(JSONObject("""{"mobile_devices":[{"device_id":"abcdefghijklmnop","platform":2,"status":"CONNECTED","controllable":true}]}""")).isEmpty())
    }
    @Test fun occupiedPhoneCannotBeTakenOver() {
        assertFalse(UuDevice("abcdefghijklmnop","Phone",true,true,1,platform=2).available)
        assertTrue(UuDevice("abcdefghijklmnop","Desktop",true,true,1,platform=1).available)
    }
    @Test fun sharingUsesAndroidClientEnumWithoutChangingDesktopOrSessionKind() {
        val desktop=UuWire.fields(UuWire.connectOptions("abcdefghijklmnop"))
        val shared=UuWire.fields(UuWire.controlEvent("abcdefghijklmnop",androidScreenShare=true).second)
        assertEquals(2L,desktop[8]?.single()?.number)
        assertEquals(2L,shared[8]?.single()?.number)
        assertEquals(1L,shared[1]?.single()?.number)
        assertEquals(1L,shared[10]?.single()?.number)
        val capabilities=UuWire.fields(shared[11]!!.single().data!!)
        assertFalse(capabilities.containsKey(6));assertFalse(capabilities.containsKey(7));assertFalse(capabilities.containsKey(8))
        assertArrayEquals(desktop[9]?.single()?.data,shared[9]?.single()?.data)
    }
    @Test fun sharingWaitsForPhoneConsentPastDesktopTimeoutButRemainsBounded() {
        var now=0L
        val health=UuSessionHealth(signalTimeoutMs=90_000L) {now}
        health.markStarted();now=60_000L
        assertEquals(UuSessionHealth.Warning.NONE,health.check())
        now=89_999L;assertEquals(UuSessionHealth.Warning.NONE,health.check())
        now++;assertEquals(UuSessionHealth.Warning.SIGNAL_TIMEOUT,health.check())
        health.markControlAccepted();assertEquals(UuSessionHealth.Warning.NONE,health.check())
    }
}

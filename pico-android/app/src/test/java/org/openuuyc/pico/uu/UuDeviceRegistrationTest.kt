package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuDeviceRegistrationTest {
    private val identity = UuIdentity("client", "abcdefghijklmnop", "system", "install")
    private val profile = UuAndroidRegistrationProfile(
        34, "real/fingerprint", "PICO", "Actual manufacturer", "Actual model", "Actual product",
        "Actual ROM", listOf("arm64-v8a", "armeabi-v7a"), "4320x2160", "2160x1080", 320
    )

    @Test fun androidInitUsesExactlyOfficialSeventeenFields() {
        val body = deviceRegistrationBody(identity, profile)
        assertEquals(setOf("name", "client_id", "system_id", "system_version", "gaid", "install_id",
            "build_fingerprint", "brand", "manufacturer", "model", "product", "rom", "abi",
            "resolution", "screen_size", "dpi", "controllable"), body.keys().asSequence().toSet())
        assertEquals("/api/v1/device/android/init", UuContract.ANDROID_INIT)
        assertEquals("client", body.getString("client_id"))
        assertEquals("system", body.getString("system_id"))
        assertEquals("install", body.getString("install_id"))
        assertEquals("34", body.getString("system_version"))
        assertEquals("", body.getString("gaid"))
        assertFalse(body.getBoolean("controllable"))
    }

    @Test fun actualAndroidHardwareRetainsOfficialFieldTypesAndFormats() {
        val body = deviceRegistrationBody(identity, profile)
        assertEquals("PICOActual model", body.getString("name"))
        assertEquals("real/fingerprint", body.getString("build_fingerprint"))
        assertEquals("Actual manufacturer", body.getString("manufacturer"))
        assertEquals("Actual product", body.getString("product"))
        assertEquals("Actual ROM", body.getString("rom"))
        assertEquals("arm64-v8a, armeabi-v7a", body.getString("abi"))
        assertEquals("4320x2160", body.getString("resolution"))
        assertEquals("2160x1080", body.getString("screen_size"))
        assertEquals(320, body.getInt("dpi"))
        assertEquals("4320x2160", UuAndroidRegistrationProfile.landscapeSize(2160, 4320))
        assertEquals(1080, UuAndroidRegistrationProfile.physicalPixels(2160, 320f))
    }
}

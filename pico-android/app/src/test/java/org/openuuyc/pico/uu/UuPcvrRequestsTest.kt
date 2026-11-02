package org.openuuyc.pico.uu
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class UuPcvrRequestsTest {
    @Test fun startCarriesCalibrationAndOptionalBackend() {
        val calibration=JSONObject().put("eye_width",1920)
        val plain=UuPcvrRequests.control(7,true,calibration)
        assertEquals(7,plain.getInt("id"));assertEquals("start",plain.getString("op"))
        assertEquals(1920,plain.getJSONObject("calibration").getInt("eye_width"))
        assertFalse(plain.has("backend"))
        val mirror=UuPcvrRequests.control(8,true,calibration,UuPcvrRequests.BACKEND_STEAMVR)
        assertEquals("steamvr",mirror.getString("backend"))
        assertFalse(mirror.has("calibration"))
    }
    @Test fun aSteamVrAttachNeedsNoEyeCalibration() {
        val attach=UuPcvrRequests.control(9,true,null,UuPcvrRequests.BACKEND_STEAMVR)
        assertFalse(attach.has("calibration"));assertTrue(attach.has("backend"))
    }
    @Test fun stopNeverCarriesStartOnlyFields() {
        val stop=UuPcvrRequests.control(3,false,JSONObject().put("eye_width",1920),UuPcvrRequests.BACKEND_STEAMVR)
        assertEquals("stop",stop.getString("op"))
        assertFalse(stop.has("calibration"));assertFalse(stop.has("backend"))
    }
}

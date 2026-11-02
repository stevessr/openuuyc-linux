package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoLocalVrAppsTest {
 @Test fun rejectsSystemSelfAndNonVrApplications() {
    assertFalse(PicoLocalVrApps.allowed("self","self",false,true))
    assertFalse(PicoLocalVrApps.allowed("game","self",true,true))
    assertFalse(PicoLocalVrApps.allowed("flat-game","self",false,false))
    assertTrue(PicoLocalVrApps.allowed("vr-game","self",false,true))
 }
 @Test fun neverHandsOffToExternalVrStreamingClients() {
    for(p in listOf("org.meumeu.wivrn.github","org.meumeu.wivrn.github.nightly","alvr.client","com.valvesoftware.steamlinkvr"))
        assertFalse(PicoLocalVrApps.allowed(p,"self",false,true))
 }
}

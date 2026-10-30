package org.openuuyc.pico.uu
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class UuPcvrHapticsTest {
 private fun pulse()=JSONObject().put("generation",7).put("hand",1).put("amplitude",.5).put("frequency",120).put("duration_ms",100)
 @Test fun requiresCurrentGenerationAndBoundedValues(){assertNotNull(UuPcvrHaptics.parse(pulse(),7));assertNull(UuPcvrHaptics.parse(pulse(),8));assertNull(UuPcvrHaptics.parse(pulse(),0));assertNull(UuPcvrHaptics.parse(pulse().put("hand",2),7));assertNull(UuPcvrHaptics.parse(pulse().put("amplitude",1.1),7));assertNull(UuPcvrHaptics.parse(pulse().put("frequency",-1),7));assertNull(UuPcvrHaptics.parse(pulse().put("duration_ms",1001),7));assertNull(UuPcvrHaptics.parse(JSONObject(),7));assertNull(UuPcvrHaptics.parse(pulse().put("hand",1.5),7));assertNull(UuPcvrHaptics.parse(pulse().put("generation",7.5),7));assertNull(UuPcvrHaptics.parse(pulse().put("duration_ms",1.5),7))}
 @Test fun acceptsExplicitStop(){val p=UuPcvrHaptics.parse(pulse().put("amplitude",0).put("duration_ms",0),7)!!;assertEquals(0,p.durationMs);assertEquals(0f,p.amplitude)}
}

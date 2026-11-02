package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
class UuDataConnectionTest {
 @Test fun dataConnectionsUseNativeRolesWithoutChangingDesktop() {
  val id="abcdefghijklmnop"
  assertEquals(1L,UuWire.fields(UuWire.connectOptions(id))[1]!!.single().number)
  assertEquals(5L,UuWire.fields(UuWire.connectOptions(id,dataOnly=true))[1]!!.single().number)
  assertEquals(8L,UuWire.fields(UuWire.connectOptions(id,terminal=true,dataOnly=true))[1]!!.single().number)
  for(terminal in listOf(false,true)) {
   val (header,payload)=UuWire.controlEvent(id,"control",terminal=terminal,dataOnly=true)
   assertTrue(header.contains("\\\"openuuyc_audio_control\\\":0"))
   assertEquals(if(terminal) 8L else 5L,UuWire.fields(payload)[1]!!.single().number)
  }
 }
 @Test fun legitimateRefusalPreservesCodeAndDiagnosticCannotLeakPeerSecrets() {
  val r=JSONObject().put("code",1136).put("client_id","secret-client").put("ice_id","secret-ice").put("message","secret-message")
   .put("iceServers",JSONArray().put(JSONObject().put("credential","secret-password")))
  val ack="432"+JSONArray().put("success").put(r)
  val error=assertThrows(UuWire.ControlRejected::class.java) {UuWire.parseControlAck(ack,"app")}
  assertEquals(1136,error.code)
  val summary=UuWire.controlAckSummary(ack)
  assertTrue(summary.contains("1136"));assertFalse(summary.contains("secret"))
 }
}

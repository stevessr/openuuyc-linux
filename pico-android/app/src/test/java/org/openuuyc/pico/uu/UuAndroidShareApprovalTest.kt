package org.openuuyc.pico.uu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UuAndroidShareApprovalTest {
    private val target="abcdefghijklmnop"
    private fun push(action: String,id: String=target,p: String="android_control_result")=JSONObject()
        .put("type","transport_rpc").put("data",JSONObject().put("p",p).put("need_response",false)
            .put("data",JSONObject().put("action",action).put("device_info",JSONObject().put("device_id",id))))
    @Test fun requestUsesRegisteredLocalAndroidIdentityAndExplicitAction() {
        val body=UuAndroidShareApproval.request(target,"My PICO","4.46.0")
        assertEquals("android_control_request",body.getString("p"));assertFalse(body.getBoolean("need_response"))
        val data=body.getJSONObject("data");assertEquals("request_control",data.getString("action"))
        val info=data.getJSONObject("device_info");assertEquals(target,info.getString("device_id"))
        assertEquals(2,info.getInt("platform"));assertEquals("My PICO",info.getString("alias"))
        assertEquals("4.46.0",info.getString("version_name"))
    }
    @Test fun onlyMatchingExplicitAcceptanceGrantsViewing() {
        val gate=UuAndroidShareApproval(target)
        assertFalse(gate.receive(push("accept_control","bcdefghijklmnopq")))
        assertFalse(gate.receive(push("accept_control",p="android_control_request")))
        assertFalse(gate.receive(push("unknown")))
        assertFalse(gate.receive(JSONObject().put("code",0))) // HTTP success is not approval
        assertNull(gate.result)
        assertTrue(gate.receive(push("accept_control")))
        assertEquals(UuAndroidShareApproval.Result.ACCEPTED,gate.result)
    }
    @Test fun refusalCannotBeOverwrittenByLaterAcceptance() {
        val gate=UuAndroidShareApproval(target)
        assertTrue(gate.receive(push("reject_request")))
        assertFalse(gate.receive(push("accept_control")))
        assertEquals(UuAndroidShareApproval.Result.REJECTED,gate.result)
    }
    @Test fun everyOfficialUnavailableResponseStopsTheRequest() {
        val actions=mapOf("switch_is_off" to UuAndroidShareApproval.Result.SWITCH_OFF,
            "not_foreground" to UuAndroidShareApproval.Result.NOT_FOREGROUND,
            "during_control" to UuAndroidShareApproval.Result.BUSY,
            "during_request" to UuAndroidShareApproval.Result.REQUEST_PENDING)
        actions.forEach {(action,result)->val gate=UuAndroidShareApproval(target);assertTrue(gate.receive(push(action)));assertEquals(result,gate.result)}
    }
    @Test fun cancellationUsesTheSameRpcWithoutPretendingToJoin() {
        val body=UuAndroidShareApproval.request(target,"PICO","4.46.0","cancel_request")
        assertEquals("cancel_request",body.getJSONObject("data").getString("action"))
        assertEquals("android_control_request",body.getString("p"));assertFalse(body.getBoolean("need_response"))
    }
}

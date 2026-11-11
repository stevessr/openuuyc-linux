package org.openuuyc.pico.uu

import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class UuAccountPushTest {
    private class Socket(private val req: Request): WebSocket {
        val sent=mutableListOf<String>();var cancelled=false
        override fun request()=req
        override fun queueSize()=0L
        override fun send(text: String): Boolean {sent.add(text);return true}
        override fun send(bytes: ByteString)=false
        override fun close(code: Int,reason: String?)=true
        override fun cancel() {cancelled=true}
    }
    @Test fun accountRoomReceivesApprovalPushWithoutOpeningMediaControl() {
        lateinit var listener: WebSocketListener
        lateinit var socket: Socket
        lateinit var request: Request
        val push=UuAccountPush(UuRoom("room-secret",listOf("https://signal.example.test"),10000)) {req,callback->
            request=req;listener=callback;Socket(req).also {socket=it}
        }
        try {
            assertEquals("room-secret",request.header("X-NRD-AUTH"))
            assertEquals("0",request.header("X-NRD-CONTROLLING"))
            val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
            listener.onOpen(socket,response)
            listener.onMessage(socket,"40")
            listener.onMessage(socket,"431[{\"room\":\"own\"}]")
            push.awaitReady {false}
            assertEquals(listOf("40","421[\"room_info\"]"),socket.sent)
            var received=0
            val subscription=push.subscribe {assertEquals("transport_rpc",it.getString("type"));received++}
            listener.onMessage(socket,"427[\"bmsg_push\",{\"type\":\"transport_rpc\",\"data\":{}}]")
            assertEquals(1,received);assertEquals("437[]",socket.sent.last())
            listener.onMessage(socket,"2test");assertEquals("3test",socket.sent.last())
            subscription.close()
            listener.onMessage(socket,"42[\"bmsg_push\",{\"type\":\"transport_rpc\"}]")
            assertEquals(1,received)
            assertFalse(socket.sent.any {it.contains("control\"")})
        } finally {push.close()}
        assertTrue(socket.cancelled);assertTrue(push.ended)
    }
    private fun withReadyPush(test: (UuAccountPush,(String)->Unit)->Unit) {
        lateinit var listener: WebSocketListener
        lateinit var socket: Socket
        val push=UuAccountPush(UuRoom("room-secret",listOf("https://signal.example.test"),10000)) {req,callback->
            listener=callback;Socket(req).also {socket=it}
        }
        listener.onMessage(socket,"40")
        listener.onMessage(socket,"431[{\"room\":\"own\"}]")
        try {test(push) {action->
            val payload=org.json.JSONObject().put("type","transport_rpc").put("data",org.json.JSONObject()
                .put("p","android_control_result").put("data",org.json.JSONObject().put("action",action)
                    .put("device_info",org.json.JSONObject().put("device_id","abcdefghijklmnop"))))
            listener.onMessage(socket,"42"+org.json.JSONArray().put("bmsg_push").put(payload))
        }} finally {push.close()}
    }
    @Test fun consentSubscribesBeforeHttpSoAnImmediateReplyIsNotLost() = withReadyPush {push,reply->
        val sent=mutableListOf<String>()
        UuAndroidShareConsent.await(push,"abcdefghijklmnop","bcdefghijklmnopq","PICO","4.46.0",
            send={body->sent.add(body.getJSONObject("data").getString("action"));reply("accept_control")},
            cancelled={false},status={})
        assertEquals(listOf("request_control"),sent)
        // The attempt released its observer, so a subsequent attempt can subscribe.
        push.subscribe {}.close()
    }
    @Test fun cancellingAfterRequestSendsWithdrawalAndReleasesSubscription() = withReadyPush {push,_->
        val sent=mutableListOf<String>();var cancelled=false
        try {
            UuAndroidShareConsent.await(push,"abcdefghijklmnop","bcdefghijklmnopq","PICO","4.46.0",
                send={body->sent.add(body.getJSONObject("data").getString("action"));cancelled=true},
                cancelled={cancelled},status={})
            fail("Cancelled consent must not proceed to joining a room")
        } catch(expected: java.io.IOException) {assertTrue(expected.message!!.contains("取消"))}
        assertEquals(listOf("request_control","cancel_request"),sent)
        push.subscribe {}.close()
    }
    @Test fun remoteRejectionStopsViewingInsteadOfTreatingHttpSuccessAsConsent() = withReadyPush {push,reply->
        try {
            UuAndroidShareConsent.await(push,"abcdefghijklmnop","bcdefghijklmnopq","PICO","4.46.0",
                send={reply("reject_request")},cancelled={false},status={})
            fail("Rejection must not proceed to joining a room")
        } catch(expected: java.io.IOException) {assertTrue(expected.message!!.contains("拒绝"))}
        push.subscribe {}.close()
    }
}

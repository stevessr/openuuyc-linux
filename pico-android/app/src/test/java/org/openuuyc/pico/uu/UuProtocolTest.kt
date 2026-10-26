package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class UuProtocolTest {
    @Test fun signedRestMatchesDesktopHmac() {
        val identity = UuIdentity("abc-client","abcdefghijklmnop","abc-system","m")
        val headers = UuContract.headers(
            "GET", "/api/v1/device/list", ByteArray(0), 1761459600, identity
        )
        assertEquals("eddafad67a42a64b31b5833211d9420e7bddf7260a3fe7b9db6be883ecc13181",
            headers["X-Param-sign"])
        assertEquals("4.41.2.2602", headers["X-Param-VN"])
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test fun protobufConnectIncludesControllerIdentityAndDecoder() {
        val id = "abcdefghijklmnop"
        val options = UuWire.connectOptions(id)
        assertTrue(options.size > 40)
        assertTrue(options.toList().containsAll(id.toByteArray().toList()))
        assertTrue(options.toList().containsAll(UuContract.VERSION.toByteArray().toList()))
    }

    @Test fun captureSettingUsesUuRpcAndDoesNotRequestPhysicalResize() {
        val capture = UuWire.captureSetting(81)
        assertTrue(capture.size > 40)
        assertEquals(81L, UuWire.decodeEnvelope(capture).seq)
        // Top-level protobuf field 21, length-delimited, uses two-byte tag AA 01.
        assertTrue(capture.toList().windowed(2).any { it[0] == 0xAA.toByte() && it[1] == 0x01.toByte() })
    }

    @Test fun echoFramesCanBeDecoded() {
        val request = UuWire.decodeEnvelope(UuWire.echoRequest())
        assertTrue(request.echoRequest)
        val response = UuWire.decodeEnvelope(UuWire.echoResponse(34, 5678))
        assertFalse(response.echoRequest)
        assertEquals(34L, response.seq)
        assertEquals(5678L, response.timestamp)
    }

    @Test fun signalEndpointRequiresTls() {
        val result = UuWire.signalEndpoint("signal.example.invalid")
        assertTrue(result.startsWith("wss://signal.example.invalid/socket.io/?EIO=4&transport=websocket"))
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.signalEndpoint("http://unencrypted.example.invalid")
        }
    }

    @Test fun gzipSdpIsBoundedAndRoundTrips() {
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        assertEquals(sdp, UuWire.ungzip(UuWire.gzip(sdp.toByteArray())))
        assertEquals(sdp, UuWire.ungzip(UuWire.attachment(UuWire.gzip(sdp.toByteArray()))))
    }

    @Test fun controlAckRequiresSuccessThenValidIceConfiguration() {
        val ice = JSONObject().put("urls", "stun:stun.example.org:3478")
        val info = JSONObject()
            .put("code", 0)
            .put("client_id", "controller-id")
            .put("ice_id", "ice-id")
            .put("iceServers", org.json.JSONArray().put(ice))
        val frame = "432" + org.json.JSONArray().put("success").put(info)
        val control = UuWire.parseControlAck(frame, "session-id")
        assertEquals("controller-id", control.clientId)
        assertEquals("ice-id", control.iceId)
        assertEquals("session-id", control.appControlId)
        assertEquals(1, control.iceServers.size)
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.parseControlAck("432" + org.json.JSONArray().put(info), "session-id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.parseControlAck("432" + org.json.JSONArray().put("failed").put(info), "session-id")
        }
    }

    @Test fun uuOfferAddsTransportExtensionsOnlyToSignalingCopy() {
        val source = "v=0\r\na=group:BUNDLE 0 1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=sctp-port:5000\r\na=sendrecv\r\n"
        val out = UuWire.uuOfferSdp(source)
        assertTrue(out.contains("a=msid-semantic: WMS audio_0\r\n"))
        assertTrue(out.contains("a=max-message-size:524288\r\n"))
        assertTrue(out.contains("a=extmap-allow-mixed"))
        assertFalse(out.contains("a=sendrecv"))
        assertEquals(source, source)
        assertEquals(out, UuWire.uuOfferSdp(out))
    }

    @Test fun controlWireHasBinaryPlaceholderAndValidJson() {
        val (header, frame) = UuWire.controlEvent("abcdefghijklmnop", "control-id")
        assertTrue(header.startsWith("451-2"))
        val args = org.json.JSONArray(header.removePrefix("451-2"))
        assertEquals("control", args.getString(0))
        assertEquals("control-id", args.getJSONObject(1).getString("app_control_id"))
        assertTrue(args.getJSONObject(1).getJSONObject("app_data").getBoolean("_placeholder"))
        assertTrue(frame.isNotEmpty())
    }
}

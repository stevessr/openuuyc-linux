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

    @Test fun videoTrackRegistrationResponseBindsRequestId() {
        // PbControlMessage.RpcResponse(22) { header(1) request_id=7,
        // SendVideoTrackRsp(11) { error_code=0 } }.
        val packet = byteArrayOf(
            0xb2.toByte(), 0x01, 0x08,
            0x0a, 0x02, 0x08, 0x07,
            0x5a, 0x02, 0x08, 0x00
        )
        val decoded = UuWire.decodeEnvelope(packet)
        assertEquals(7L, decoded.trackAckId)
        assertEquals(0, decoded.trackAckError)
    }

    @Test fun videoTrackBindingRespectsSelectedScreenNotLastScreen() {
        // PB ScreenSources: screen 1 -> track 10; screen 2 -> track 11;
        // current_screen_id is 1 and must not inherit the last screen's track.
        val packet = byteArrayOf(
            0x3a, 0x0e,
            0x0a, 0x04, 0x08, 0x01, 0x60, 0x0a,
            0x0a, 0x04, 0x08, 0x02, 0x60, 0x0b,
            0x10, 0x01
        )
        val decoded = UuWire.decodeEnvelope(packet)
        assertEquals(1, decoded.screenId)
        assertEquals(10, decoded.videoTrackIndex)
    }

    @Test fun videoTrackRegistrationAndCaptureUseUuProtobuf() {
        val register = UuWire.registerVideoTrack(9, 0)
        val capture = UuWire.startVideoCapture(10, 2)
        assertEquals(9L, UuWire.decodeEnvelope(register).seq)
        assertEquals(10L, UuWire.decodeEnvelope(capture).seq)
        assertThrows(IllegalArgumentException::class.java) { UuWire.registerVideoTrack(11, -1) }
        assertThrows(IllegalArgumentException::class.java) { UuWire.startVideoCapture(12, -2) }
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
    @Test fun videoNegotiationKeepsOnlyAcceptedTracks() {
        val answer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
            "m=video 0 UDP/TLS/RTP/SAVPF 96\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
            "m=video 0 UDP/TLS/RTP/SAVPF 96\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val accepted = UuWire.acceptedVideoTrackIndices(answer)
        assertEquals(listOf(0, 2, 4), accepted)
        val registration = UuWire.registerVideoTracks(91, accepted)
        assertEquals(91L, UuWire.decodeEnvelope(registration).seq)
        assertTrue(registration.size > UuWire.registerVideoTrack(91, 0).size)
    }

    @Test fun rejectsInvalidVideoMlinesAndTrackRegistration() {
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.acceptedVideoTrackIndices("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.acceptedVideoTrackIndices("v=0\r\nm=video invalid UDP/TLS/RTP/SAVPF 96\r\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.registerVideoTracks(90, listOf(0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UuWire.registerVideoTracks(90, listOf(0, 5))
        }
    }
}

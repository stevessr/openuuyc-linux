package org.openuuyc.pico.uu

import java.io.IOException
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Host-independent protocol conformance/negative tests. Intentionally avoid a
 * live UU account or server in CI; those tests require explicit test credentials.
 */
class UuProtocolBoundaryTest {
    private fun bytes(vararg octets: Int): ByteArray =
        ByteArray(octets.size) { octets[it].toByte() }

    @Test fun malformedAndOversizedProtobufFramesAreRejected() {
        val broken = listOf(
            bytes(), // no domain event
            bytes(0),
            bytes(0x0b), // group wire type unsupported
            bytes(0x0f), // reserved wire type
            bytes(0x08), // incomplete seq
            bytes(0x0a, 0x01, 0x00), // seq field 1 wrongly length-delimited
            bytes(0x1a, 0x01, 0x08), // submessage action missing value
            bytes(0x18, 0x00), // SimpleAction cannot be varint
            bytes(0x3a, 0x01), // screens missing body
            bytes(0xb2, 0x01, 0x03, 0x0a), // truncated RPC response
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x02), // 10th byte overflow
            bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0x07, 0), // invalid field number
            ByteArray(65537)
        )
        broken.forEachIndexed { index, frame ->
            try {
                UuWire.decodeEnvelope(frame)
                fail("malformed protobuf accepted: fixture $index")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun opaqueAndUnknownProtobufTagsDoNotTurnOnInput() {
        // Unknown scalar/fixed32/fixed64 and length-delimited extension fields
        // are legal, but none should synthesize an ECHO or a screen selection.
        val packet = bytes(
            0x08, 0x05,        // seq=5
            0x28, 0x2a,        // unknown tag5 varint
            0x32, 0x02, 0xab, 0xcd,
            0x3d, 0x01, 0x02, 0x03, 0x04,
            0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val envelope = UuWire.decodeEnvelope(packet)
        assertEquals(5L, envelope.seq)
        assertFalse(envelope.echoRequest)
        assertFalse(envelope.echoResponse)
        assertNull(envelope.screenId)
        assertNull(envelope.trackAckId)
    }

    @Test fun boundedProtobufFuzzNeverCrashesBeyondProtocolRejection() {
        val random = Random(0x551A4D)
        repeat(1600) {
            val frame = ByteArray(1 + random.nextInt(128))
            random.nextBytes(frame)
            try {
                UuWire.decodeEnvelope(frame)
            } catch (_: IllegalArgumentException) {
                // Malformed, untrusted packets must not become index/shift errors.
            }
        }
    }

    @Test fun hmacSignatureBindsMethodPathBodyTimeAndIdentity() {
        val id = UuIdentity("client", "abcdefghijklmnop", "system", "machine")
        fun sign(method: String, path: String, payload: String, time: Long, identity: UuIdentity = id,
                 platform: Boolean = false): String = UuContract.headers(
            method, path, payload.toByteArray(), time, identity, platformOnly = platform
        ).getValue("X-Param-sign")

        val baseline = sign("POST", "/api/v1/qrcode/login/status", """{"login_status":1}""", 1770000000)
        assertNotEquals(baseline, sign("GET", "/api/v1/qrcode/login/status", """{"login_status":1}""", 1770000000))
        assertNotEquals(baseline, sign("POST", "/api/v1/device/list", """{"login_status":1}""", 1770000000))
        assertNotEquals(baseline, sign("POST", "/api/v1/qrcode/login/status", """{"login_status":2}""", 1770000000))
        assertNotEquals(baseline, sign("POST", "/api/v1/qrcode/login/status", """{"login_status":1}""", 1770000001))
        assertNotEquals(baseline, sign("POST", "/api/v1/qrcode/login/status", """{"login_status":1}""", 1770000000,
            id.copy(clientId = "other")))
        assertNotEquals(baseline, sign("POST", "/api/v1/qrcode/login/status", """{"login_status":1}""", 1770000000,
            platform = true))
        val platformHeaders = UuContract.headers(
            "POST", "/api/v1/device/windows/init", ByteArray(0), 1770000000, id, platformOnly = true
        )
        assertFalse(platformHeaders.containsKey("X-Param-device-id"))
        assertFalse(platformHeaders.containsKey("X-Param-user-id"))
    }

    @Test fun signalEndpointDisallowsPlaintextCredentialsAndUrlInjection() {
        val permitted = UuWire.signalEndpoint("wss://signaling.example.invalid/custom")
        assertTrue(permitted.startsWith("wss://signaling.example.invalid/custom?EIO=4"))
        val invalid = listOf(
            "ws://signaling.example.invalid",
            "http://signaling.example.invalid",
            "wss://admin:secret@signaling.example.invalid"
        )
        invalid.forEach { input ->
            assertThrows("untrusted endpoint should fail: $input", IllegalArgumentException::class.java) {
                UuWire.signalEndpoint(input)
            }
        }
    }

    @Test fun ackWithoutMatchingSessionParametersIsRejected() {
        val valid = JSONObject()
            .put("code", 0)
            .put("client_id", "controller")
            .put("ice_id", "ice")
            .put("iceServers", JSONArray().put(
                JSONObject().put("urls", JSONArray().put("stun:relay.example.invalid:3478"))
            ))
        fun ack(v: JSONObject) = "432" + JSONArray().put("success").put(v)
        assertEquals(1, UuWire.parseControlAck(ack(valid), "app").iceServers.size)
        val failures = listOf(
            valid.put("code", 1136),
            JSONObject().put("code", 0).put("client_id", "controller").put("ice_id", "ice")
        )
        failures.forEach { item ->
            assertThrows(IllegalArgumentException::class.java) {
                UuWire.parseControlAck(ack(item), "app")
            }
        }
    }

    @Test fun gzipRejectsCorruptionAndDecompressionBomb() {
        val payload = "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val compressed = UuWire.gzip(payload.toByteArray())
        assertEquals(payload, UuWire.ungzip(compressed))
        assertThrows(IOException::class.java) { UuWire.ungzip(bytes(0x1f, 0x8b, 0x08)) }
        val big = UuWire.gzip(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() })
        assertThrows(IllegalArgumentException::class.java) { UuWire.ungzip(big) }
    }

    @Test fun uuSdpOverridesConflictingMaxMessageSizeAndIsIdempotent() {
        val source = "v=0\r\na=group:BUNDLE 0 1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
            "a=sctp-port:5000\r\na=max-message-size:65536\r\na=sendrecv\r\n"
        val output = UuWire.uuOfferSdp(source)
        assertEquals(1, output.lines().count { it.startsWith("a=max-message-size:") })
        assertTrue(output.contains("a=max-message-size:524288\r\n"))
        assertEquals(output, UuWire.uuOfferSdp(output))
    }
}

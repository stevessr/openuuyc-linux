package org.openuuyc.pico.uu

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Local NRD contract tests. The fake server receives exact HTTP bytes and
 * validates signing, bearer scoping, rejection and bounded response processing.
 */
class UuNrdTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: UuNrdTransport
    private val identity = UuIdentity("client-id", "abcdefghijklmnop", "system-id", "guid")
    private val account = UuAccount("super-secret-token", "user-id", "tester")
    private val timestamp = 1770000000L

    @Before fun start() {
        server = MockWebServer()
        server.start()
        transport = UuNrdTransport(
            endpoint = server.url("/").toString().trimEnd('/'),
            clockSeconds = { timestamp }
        )
    }

    @After fun close() {
        transport.close()
        server.shutdown()
    }

    private fun ok(json: String = """{"ok":true}""") =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
            .setBody("""{"code":0,"data":$json}""")

    @Test fun officialSmsCodeAcceptsCommonSuccessWithoutData() {
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"ok"}"""))
        val result=transport.request("POST",UuSmsWire.CODE,UuSmsWire.request("86","13800000000"),identity,allowEmptyData=true)
        assertEquals(0,result.length())
        val captured=requireNotNull(server.takeRequest(2,TimeUnit.SECONDS))
        assertEquals("/api/v1/security/mobile/code",captured.path)
        val body=JSONObject(captured.body.readUtf8())
        assertEquals("login",body.getString("type"));assertEquals("86",body.getString("country_code"))
        assertNull(captured.getHeader("Authorization"))
    }
    @Test fun officialSmsLoginUsesExactFieldsAndDoesNotLeakServerEcho() {
        server.enqueue(MockResponse().setBody("""{"code":400,"msg":"13800000000 123456"}"""))
        val error=assertThrows(UuNrdResponseException::class.java) {
            transport.request("POST",UuSmsWire.LOGIN,UuSmsWire.login("86","13800000000","123456"),identity)
        }
        assertFalse(error.message.orEmpty().contains("13800000000"));assertFalse(error.message.orEmpty().contains("123456"))
        val captured=requireNotNull(server.takeRequest(2,TimeUnit.SECONDS))
        assertEquals("/api/v1/login/by_mobile",captured.path)
        assertEquals("123456",JSONObject(captured.body.readUtf8()).getString("code"))
    }

    @Test fun signedQrStatusPostMatchesActualOnWireBody() {
        server.enqueue(ok())
        val payload = JSONObject()
            .put("qrcode_jump_url", "https://example.invalid/qr")
            .put("login_status", 1).put("token", "ticket")
        assertTrue(transport.request(
            "POST", "/api/v1/qrcode/login/status", payload, identity
        ).getBoolean("ok"))
        val captured = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", captured.method)
        assertEquals("/api/v1/qrcode/login/status", captured.path)
        assertEquals("application/json", captured.getHeader("Content-Type"))
        assertNull(captured.getHeader("Authorization"))
        val realBytes = captured.body.readByteArray()
        assertEquals(payload.toString(), String(realBytes))
        assertEquals(
            UuContract.headers("POST", captured.path!!, realBytes, timestamp, identity)["X-Param-sign"],
            captured.getHeader("X-Param-sign")
        )
        assertEquals(identity.deviceId, captured.getHeader("X-Param-device-id"))
    }

    @Test fun registeredPlatformRequestOmitsDeviceAndAccountCredentials() {
        server.enqueue(ok("""{"device_id":"abcdefghijklmnop"}"""))
        val body = JSONObject().put("platform", 2).put("controllable", false)
        transport.request("POST", "/api/v1/device/windows/init", body, identity, account, platform = true)
        val packet = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(packet.getHeader("Authorization"))
        assertNull(packet.getHeader("X-Param-device-id"))
        assertNull(packet.getHeader("X-Param-user-id"))
        assertNotNull(packet.getHeader("X-Param-sign"))
    }

    @Test fun deviceListUsesBearerAndAccountScopeButNoGetBody() {
        server.enqueue(ok("""{"my_binded_devices":[]}"""))
        transport.request("GET", "/api/v1/device/list", identity = identity, account = account)
        val packet = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", packet.method)
        assertEquals(0L, packet.bodySize)
        assertEquals("Bearer super-secret-token", packet.getHeader("Authorization"))
        assertEquals("user-id", packet.getHeader("X-Param-user-id"))
        assertEquals(
            UuContract.headers("GET", "/api/v1/device/list", ByteArray(0), timestamp, identity, account)["X-Param-sign"],
            packet.getHeader("X-Param-sign")
        )
    }

    @Test fun rejectsBusinessFailuresWithoutEchoingTokensOrServerMessages() {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"code":1120,"msg":"super-secret-token","data":{}}"""))
        val exception = assertThrows(IOException::class.java) {
            transport.request("GET", "/api/v1/device/list", identity = identity, account = account)
        }
        assertTrue(exception.message.orEmpty().contains("1120"))
        assertFalse(exception.message.orEmpty().contains("super-secret-token"))
    }

    @Test fun httpFailureIncludesOnlyOperationAndNumericCodes() {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"code":1234,"msg":"super-secret-token","data":{}}"""))
        val exception = assertThrows(UuNrdResponseException::class.java) {
            transport.request("POST", "/api/v1/login/by_qrcode", JSONObject(), identity)
        }
        assertEquals("qr-exchange", exception.operation)
        assertEquals(400, exception.httpStatus)
        assertEquals(1234, exception.businessCode)
        assertFalse(exception.message.orEmpty().contains("super-secret-token"))
    }

    @Test fun refusesRedirectsAndDoesNotForwardSignedHeaders() {
        server.enqueue(MockResponse().setResponseCode(302)
            .setHeader("Location", "https://untrusted.example.invalid/collect"))
        val exception = assertThrows(IOException::class.java) {
            transport.request("GET", "/api/v1/device/list", identity = identity, account = account)
        }
        assertTrue(exception.message.orEmpty().contains("HTTP 302"))
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidPathsAndBodiesNeverSendNetworkRequests() {
        for (path in listOf("//untrusted.example", "https://evil.example/", "not/absolute")) {
            assertThrows(IllegalArgumentException::class.java) {
                transport.request("POST", path, JSONObject(), identity)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            transport.request("GET", "/api/v1/device/list", JSONObject(), identity)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun rejectsMissingDataAndOversizedResponses() {
        server.enqueue(MockResponse().setBody("""{"code":0}"""))
        assertThrows(IOException::class.java) {
            transport.request("GET", "/api/v1/device/list", identity = identity)
        }
        server.enqueue(ok("""{"large":"${"a".repeat(UuNrdTransport.MAX_RESPONSE_BYTES)}"}"""))
        val e = assertThrows(IOException::class.java) {
            transport.request("GET", "/api/v1/device/list", identity = identity)
        }
        assertTrue(e.message.orEmpty().contains("too large"))
    }
}

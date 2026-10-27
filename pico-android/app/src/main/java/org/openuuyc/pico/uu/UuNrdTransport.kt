package org.openuuyc.pico.uu

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Only locally constructed numeric diagnostics; never include response text or credentials. */
internal class UuNrdResponseException(val httpStatus: Int, val businessCode: Int? = null, val operation: String = "NRD") :
    IOException("$operation: HTTP $httpStatus" + (businessCode?.let { ", code $it" } ?: ""))

/**
 * Testable UU NRD HTTP layer. All paths, signatures and auth headers are built
 * here and verified with MockWebServer, without a live UU account or headset.
 */
internal class UuNrdTransport(
    private val endpoint: String = UuContract.BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false) // no auth/signature forwarding to a redirected host
        .build(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }

    fun cancelPendingCalls() { http.dispatcher.cancelAll() }

    fun close() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        identity: UuIdentity,
        account: UuAccount? = null,
        platform: Boolean = false, allowEmptyData: Boolean = false
    ): JSONObject {
        require(method in setOf("GET", "POST", "PUT", "DELETE")) { "unsupported NRD method" }
        require(path.startsWith("/") && !path.startsWith("//") &&
            !path.contains("://") && path.length <= 256) { "invalid NRD path" }
        require(method != "GET" || body == null) { "GET requests cannot carry UU JSON bodies" }
        val bytes = body?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val signed = UuContract.headers(method, path, bytes, clockSeconds(), identity, account, platform)
        val builder = Request.Builder().url(endpoint.trimEnd('/') + path)
        signed.forEach { (key, value) -> builder.header(key, value) }
        if (account != null && !platform) {
            builder.header("Authorization", "Bearer ${account.token}")
        }
        if (body != null) builder.header("Content-Type", "application/json")
        val payload = bytes.toRequestBody("application/json".toMediaType())
        builder.method(method, if (method == "GET") null else payload)

        val operation = when (path) {
            "/api/v1/device/windows/init" -> "device-init"
            "/api/v1/qrcode/gen/login" -> "qr-create"
            "/api/v1/qrcode/login/status" -> "qr-status"
            "/api/v1/login/by_qrcode" -> "qr-exchange"
            else -> "NRD"
        }
        http.newCall(builder.build()).execute().use { response ->
            // Redirects, 4xx and 5xx may have HTML or an empty body. Reject
            // by HTTP status *before* JSON parsing and never print the body.
            if (!response.isSuccessful) {
                // Inspect only a bounded error envelope for numeric diagnostics.
                // Do not expose server text, headers, QR URLs or credentials.
                val code = try {
                    val source = response.peekBody(8192).string()
                    val json = JSONObject(source)
                    if (json.has("code")) json.getInt("code") else null
                } catch (_: Exception) { null }
                throw UuNrdResponseException(response.code, code, operation)
            }
            val stream = response.body?.byteStream() ?: throw IOException("empty UU response")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES)
                    throw IOException("UU response too large")
                output.write(buffer, 0, count)
            }
            val envelope = JSONObject(output.toString("UTF-8"))
            val code = envelope.optInt("code", -1)
            if (!response.isSuccessful || code != 0) {
                // Business messages may contain a phone number/QR/token.
                // Deliberately exclude server-provided text from errors.
                throw UuNrdResponseException(response.code, code, operation)
            }
            return envelope.optJSONObject("data") ?: if(allowEmptyData) JSONObject() else throw IOException("UU response missing data")
        }
    }
}

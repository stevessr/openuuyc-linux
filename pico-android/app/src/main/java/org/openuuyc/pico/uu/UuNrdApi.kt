package org.openuuyc.pico.uu

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Direct UU NRD HTTP protocol.
 *
 * Parity reference: src/account/api/mod.rs / src/account/login/mod.rs.
 * No Linux OpenUUYC instance, ADB reverse or localhost relay is involved.
 */
internal object UuContract {
    const val BASE = "https://api.nrd.nie.163.com"
    const val VERSION = "4.41.2.2602"
    private const val CHANNEL = "gwqd"
    private const val PACKAGE = "com.netease.uuremote"
    private val SIGNING_KEY = "alWiSzXZTLu3WfFnw13uBru3".toByteArray(StandardCharsets.UTF_8)

    fun headers(
        method: String, path: String, body: ByteArray, timestamp: Long, identity: UuIdentity,
        account: UuAccount? = null, platformOnly: Boolean = false
    ): Map<String, String> {
        require(path.startsWith("/") && !path.contains("://"))
        val result = linkedMapOf(
            "X-Param-PLAT" to "1",
            "X-Param-VN" to VERSION,
            "X-Param-VC" to VERSION.substringAfterLast("."),
            "X-Param-PKGN" to PACKAGE,
            "X-Param-CHN" to CHANNEL,
            "X-Param-LANG" to "zh-CN",
            "X-Param-CNT" to "zh-CN",
            "X-Param-REL" to "prod",
            "X-Param-OPR" to "",
            "X-Param-TS" to timestamp.toString()
        )
        if (!platformOnly) {
            result["X-Param-client-id"] = identity.clientId
            result["X-Param-device-id"] = identity.deviceId
            result["X-Param-system-id"] = identity.systemId
        }
        if (account != null && !platformOnly) result["X-Param-user-id"] = account.userId
        val canonical = result.entries
            .filter { it.key.lowercase().startsWith("x-param") && !it.key.equals("x-param-sign", true) }
            .sortedBy { it.key.lowercase() }
            .joinToString("&") { "${it.key.lowercase()}=${it.value}" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGNING_KEY, "HmacSHA256"))
        mac.update(method.uppercase().toByteArray(StandardCharsets.UTF_8))
        mac.update(path.toByteArray(StandardCharsets.UTF_8))
        mac.update(canonical.toByteArray(StandardCharsets.UTF_8))
        mac.update(body)
        result["X-Param-sign"] = mac.doFinal().joinToString("") { "%02x".format(it.toInt() and 255) }
        return result
    }
}

internal data class UuIdentity(
    val clientId: String, var deviceId: String, val systemId: String, val machineGuid: String
)

internal data class UuAccount(val token: String, val userId: String, val nickname: String)

internal data class UuQr(val url: String, val token: String, val ticket: String)

internal data class UuDevice(
    val id: String, val alias: String, val online: Boolean, val controllable: Boolean, val occupants: Int
)

internal data class UuRoom(
    val token: String, val endpoints: List<String>, val timeoutMs: Int
) {
    init {
        require(token.isNotBlank() && endpoints.isNotEmpty())
        require(endpoints.all {
            try {
                val candidate = okhttp3.HttpUrl.Builder()
                val url = okhttp3.HttpUrl.Companion.toHttpUrlOrNull(
                    if (it.contains("://")) it.replaceFirst("wss://", "https://") else "https://$it"
                )
                url != null && url.scheme == "https" && !url.host.isBlank()
            } catch (_: Exception) { false }
        })
    }
}

/**
 * Keeps an install-specific UU device identity. Credentials are never exported
 * into console logs or Android intent extras. Persistence is private to APK.
 */
internal class UuNrdApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("uu_identity", Context.MODE_PRIVATE)
    val identity = UuIdentity(
        prefs.getString("client", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client", it).apply() },
        prefs.getString("device", "") ?: "",
        prefs.getString("system", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("system", it).apply() },
        prefs.getString("machine", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("machine", it).apply() }
    )
    var account: UuAccount? = null
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    fun clearAccount() { account = null }
    fun close() { http.dispatcher.executorService.shutdown() }

    private fun request(method: String, path: String, body: JSONObject? = null, platform: Boolean = false): JSONObject {
        require(path.startsWith("/") && path.length <= 256)
        val bytes = body?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val signed = UuContract.headers(method, path, bytes, System.currentTimeMillis() / 1000, identity, account, platform)
        val url = UuContract.BASE + path
        val builder = Request.Builder().url(url)
        signed.forEach { (key, value) -> builder.header(key, value) }
        if (account != null && !platform) builder.header("Authorization", "Bearer ${account!!.token}")
        if (body != null) builder.header("Content-Type", "application/json")
        val content = bytes.toRequestBody("application/json".toMediaType())
        builder.method(method, if (method == "GET") null else content)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string() ?: throw IOException("empty UU response")
            if (text.length > 4 * 1024 * 1024) throw IOException("UU response too large")
            val envelope = JSONObject(text)
            val code = envelope.optInt("code", -1)
            if (!response.isSuccessful || code != 0) {
                // Never include server-provided message in logs: it can echo auth data.
                throw IOException("UU request failed: HTTP ${response.code}, code $code")
            }
            return envelope.optJSONObject("data") ?: throw IOException("UU response missing data")
        }
    }

    fun initializeDevice() {
        if (identity.deviceId.matches(Regex("[a-z0-9]{16}"))) return
        // Preserve the established WindowsDeviceInitRequest shape to maintain
        // protocol compatibility. The platform value describes the PICO client.
        val body = JSONObject()
            .put("name", "OpenUUYC PICO")
            .put("client_id", identity.clientId)
            .put("system_id", identity.systemId)
            .put("machine_guid", identity.machineGuid)
            .put("os", "Android ${Build.VERSION.RELEASE}")
            .put("base_board", Build.BOARD)
            .put("cpu", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
            .put("video", JSONArray())
            .put("mac", "")
            .put("memory", 0)
            .put("screen", "1920x1080")
            .put("platform", 2)
            .put("controllable", false)
        val reply = request("POST", "/api/v1/device/windows/init", body, platform = true)
        val device = reply.getString("device_id")
        require(device.matches(Regex("[a-z0-9]{16}"))) { "Invalid UU device identity" }
        identity.deviceId = device
        prefs.edit().putString("device", device).apply()
    }

    fun createQr(): UuQr {
        val json = request("POST", "/api/v1/qrcode/gen/login", JSONObject())
        val url = json.getString("qrcode_jump_url")
        val token = json.getString("token")
        return UuQr(url, token, json.optString("status_query_ticket", token))
    }

    fun queryQr(qr: UuQr, last: Int): Int {
        val body = JSONObject().put("qrcode_jump_url", qr.url).put("login_status", last).put("token", qr.ticket)
        val result = request("POST", "/api/v1/qrcode/login/status", body)
        val status = if (result.has("status")) result.getInt("status") else result.getInt("login_status")
        if (result.has("status") && result.has("login_status") && result.getInt("login_status") != status)
            throw IOException("conflicting QR status")
        return status
    }

    fun confirmQr(qr: UuQr): UuAccount {
        // Matching the desktop flow: identity refresh before QR token exchange.
        val result = request("POST", "/api/v1/login/by_qrcode",
            JSONObject().put("qrcode_jump_url", qr.url).put("token", qr.token))
        val session = UuAccount(result.getString("token"), result.getString("user_id"), result.optString("nickname"))
        require(session.token.isNotBlank() && session.userId.isNotBlank())
        account = session
        return session
    }

    fun devices(): List<UuDevice> {
        requireNotNull(account) { "UU login required" }
        val list = request("GET", "/api/v1/device/list")
        val devices = list.optJSONArray("my_binded_devices") ?: return emptyList()
        return (0 until devices.length()).mapNotNull { i ->
            val item = devices.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("device_id")
            if (!id.matches(Regex("[a-z0-9]{16}"))) return@mapNotNull null
            UuDevice(
                id, item.optString("alias", id),
                item.optString("status") == "CONNECTED",
                item.optBoolean("controllable", false),
                item.optJSONArray("participants_info")?.length() ?: 0
            )
        }
    }

    fun join(device: UuDevice): UuRoom {
        requireNotNull(account)
        require(device.online && device.controllable) { "target unavailable or not controllable" }
        require(device.occupants == 0) { "target is occupied; explicit takeover is not implemented" }
        val result = request("POST", "/api/v1/room/join/by_device/${device.id}",
            JSONObject().put("force_join", false))
        val endpoints = mutableListOf<String>()
        val list = result.optJSONArray("signaling_list")
        if (list != null) for (i in 0 until list.length()) list.optString(i).takeIf(String::isNotBlank)?.let(endpoints::add)
        if (endpoints.isEmpty()) result.optString("signaling_server").takeIf(String::isNotBlank)?.let(endpoints::add)
        return UuRoom(result.getString("token"), endpoints, result.optInt("ws_connect_timeout_ms", 10000))
    }
}

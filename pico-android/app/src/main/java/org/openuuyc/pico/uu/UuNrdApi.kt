package org.openuuyc.pico.uu

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
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
    const val VERSION = "4.46.0"
    const val VERSION_CODE = "446000"
    const val ANDROID_INIT = "/api/v1/device/android/init"
    private const val CHANNEL = "gwqd"
    private const val PACKAGE = "com.netease.uuremote"
    private val SIGNING_KEY = "alWiSzXZTLu3WfFnw13uBru3".toByteArray(StandardCharsets.UTF_8)

    fun headers(
        method: String, path: String, body: ByteArray, timestamp: Long, identity: UuIdentity,
        account: UuAccount? = null, platformOnly: Boolean = false
    ): Map<String, String> {
        require(path.startsWith("/") && !path.contains("://"))
        val result = linkedMapOf(
            "X-Param-PLAT" to "2",
            "X-Param-VC" to VERSION_CODE,
            "X-Param-ABI" to identity.abi,
            "X-Param-PKGN" to PACKAGE,
            "X-Param-CHN" to CHANNEL,
            "X-Param-LANG" to "zh-CN",
            "X-Param-CNT" to "CN",
            "X-Param-REL" to "prod",
            "X-Param-OPR" to "",
            "X-Param-TS" to timestamp.toString()
        )
        result["X-Param-client-id"] = identity.clientId
        if (!platformOnly) {
            result["X-Param-device-id"] = identity.deviceId
        }
        if (account != null && !platformOnly) result["X-Param-user-id"] = account.userId
        // Official A9/e filters empty values before canonicalization, and uses
        // METHOD + path/query + sorted lowercase X-Param headers + exact body bytes.
        // Android 4.46.0 libnrdsign.so: 0x4f64 decodes the same public key;
        // 0x4e30 uses the SHA-256 HMAC ipad/opad construction. No native binary is bundled.
        result.entries.removeAll { it.value.isEmpty() }
        val canonical = result.entries
            .filter { it.key.lowercase(Locale.ROOT).startsWith("x-param") && !it.key.equals("x-param-sign", true) }
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .joinToString("&") { "${it.key.lowercase(Locale.ROOT)}=${it.value}" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGNING_KEY, "HmacSHA256"))
        mac.update(method.uppercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
        mac.update(path.toByteArray(StandardCharsets.UTF_8))
        mac.update(canonical.toByteArray(StandardCharsets.UTF_8))
        mac.update(body)
        result["X-Param-sign"] = mac.doFinal().joinToString("") { "%02x".format(it.toInt() and 255) }
        return result
    }
}

internal data class UuIdentity(
    val clientId: String, var deviceId: String, val systemId: String, val machineGuid: String,
    val abi: String = ""
)

internal data class UuAccount(val token: String, val userId: String, val nickname: String) {
    override fun toString() = "UuAccount(<redacted>)"
}

internal data class UuQr(val url: String, val token: String, val ticket: String)

internal data class UuDevice(
    val id: String, val alias: String, val online: Boolean, val controllable: Boolean, val occupants: Int,
    val platform: Int = 0, val versionName: String = ""
) {
    val androidScreenShare: Boolean get() = platform == 2
    val available: Boolean get() = online && controllable && (!androidScreenShare || occupants == 0)
    val connectionLabel: String get() = if(androidScreenShare) "查看共享屏幕" else if(occupants > 0) "抢占连接" else "连接"
}

internal data class UuRoom(
    val token: String, val endpoints: List<String>, val timeoutMs: Int
) {
    init {
        require(token.isNotBlank() && endpoints.isNotEmpty())
        require(endpoints.all {
            try {
                val url = (if (it.contains("://")) it.replaceFirst("wss://", "https://") else "https://$it")
                    .toHttpUrlOrNull()
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
    // Never reuse a Windows compatibility device ID or its account credentials.
    private val prefs = context.getSharedPreferences("uu_android_identity", Context.MODE_PRIVATE)
    val identity = UuIdentity(
        prefs.getString("client", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client", it).apply() },
        prefs.getString("device", "") ?: "",
        prefs.getString("system", null) ?: (
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() && it.any { digit -> digit != '0' } }
                ?: UUID.randomUUID().toString()
            ).also { prefs.edit().putString("system", it).apply() },
        prefs.getString("install", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("install", it).apply() },
        Build.SUPPORTED_ABIS.let { abis ->
            listOf("arm64-v8a", "x86_64").firstOrNull { it in abis } ?: abis.firstOrNull().orEmpty()
        }
    )
    @Volatile var account: UuAccount? = null
        private set

    private val transport = UuNrdTransport()
    private val vault = KeystoreSessionVault(context, "android_login")
    val needsAndroidLoginMigration: Boolean
        get() = !prefs.getBoolean("android_login_completed", false) && (
            context.getSharedPreferences("uu_identity", Context.MODE_PRIVATE).contains("device") ||
                context.getSharedPreferences("uu_secure_login", Context.MODE_PRIVATE).contains("encrypted_session")
            )
    private val credentialLock = Any()
    private val androidPush = java.util.concurrent.atomic.AtomicReference<UuAccountPush?>(null)
    private val sharePermits=UuAndroidSharePermits()
    private val shareAttempts=UuAndroidShareAttempts(
        readUntil={target->prefs.getLong("share_pending_until_$target",0L)},
        writeUntil={target,until->
            if(!prefs.edit().putLong("share_pending_until_$target",until).commit())
                throw java.io.IOException("无法保存共享请求状态，请重试")
        })
    private fun closePush() {sharePermits.clear();androidPush.getAndSet(null)?.close()}

    fun restoreSavedLogin(): UuSavedLoginFlow.Result {
        val result = UuSavedLoginFlow(vault) { saved ->
            account = saved
            request("GET", "/api/v1/user/info")
        }.restore()
        if (result != UuSavedLoginFlow.Result.RESTORED) account = null
        return result
    }

    fun acceptLogin(session: UuAccount, isCurrent: () -> Boolean): Boolean = synchronized(credentialLock) {
        if (!isCurrent()) return@synchronized false
        vault.save(session)
        prefs.edit().putBoolean("android_login_completed", true).apply()
        account = session
        true
    }

    fun logoutLocal() = synchronized(credentialLock) {
        closePush()
        // Explicit logout clears both credential generations; device identities remain stable.
        vault.clear();KeystoreSessionVault(context).clear();account = null
    }

    fun cancelPendingRequests() { transport.cancelPendingCalls() }

    fun clearAccount() { closePush();account = null }
    fun close() { closePush();transport.close() }

    private fun request(
        method: String, path: String, body: JSONObject? = null, platform: Boolean = false, allowEmptyData: Boolean = false
    ): JSONObject = transport.request(method, path, body, identity, account, platform, allowEmptyData)

    fun initializeDevice(force: Boolean = false) {
        if (!force && identity.deviceId.matches(Regex("[a-z0-9]{16}"))) return
        val profile = UuAndroidRegistrationProfile.read(context)
        val body = deviceRegistrationBody(identity, profile)
        val reply = request("POST", UuContract.ANDROID_INIT, body, platform = true)
        val device = reply.getString("device_id")
        require(device.matches(Regex("[a-z0-9]{16}"))) { "Invalid UU device identity" }
        require(!identity.deviceId.matches(Regex("[a-z0-9]{16}")) || identity.deviceId==device) { "Refusing to replace a registered device identity" }
        identity.deviceId = device
        prefs.edit().putString("device", device)
            .putLong("hardware_reported_at",System.currentTimeMillis())
            .putString("hardware_abi", profile.abis.joinToString(", "))
            .putString("hardware_resolution", profile.resolution).apply()
    }

    fun requestSms(country: String,mobile: String) {
        initializeDevice()
        request("POST",UuSmsWire.CODE,UuSmsWire.request(country,mobile),allowEmptyData=true)
    }
    fun loginSms(country: String,mobile: String,code: String): UuAccount {
        initializeDevice(force=true)
        val result=request("POST",UuSmsWire.LOGIN,UuSmsWire.login(country,mobile,code))
        return UuAccount(result.getString("token"),result.getString("user_id"),result.optString("nickname")).also { require(it.token.isNotBlank() && it.userId.isNotBlank()) }
    }

    fun createQr(): UuQr {
        val json = request("POST", "/api/v1/qrcode/gen/login")
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
        val qrDeviceId = identity.deviceId
        val session = UuQrCredentialExchange(
            refreshDevice = {
                initializeDevice(force = true)
                check(identity.deviceId == qrDeviceId) {
                    "Device identity changed; generate a new QR before exchanging credentials"
                }
            },
            exchangeToken = { ticket ->
                val result = request("POST", "/api/v1/login/by_qrcode",
                    JSONObject().put("qrcode_jump_url", ticket.url).put("token", ticket.token))
                UuAccount(
                    result.getString("token"),
                    result.getString("user_id"),
                    result.optString("nickname")
                )
            }
        ).complete(qr)
        require(session.token.isNotBlank() && session.userId.isNotBlank())
        return session
    }

    fun devices(): List<UuDevice> {
        requireNotNull(account) { "UU login required" }
        val list = request("GET", "/api/v1/device/list")
        return UuDeviceList.parse(list)
    }

    /** Request approval before reserving a peer room. Runs off the UI thread. */
    fun approveAndroidShare(device: UuDevice,cancelled: ()->Boolean,status: (String)->Unit) {
        require(device.androidScreenShare && device.available)
        val login=requireNotNull(account)
        var push=androidPush.get()?.takeUnless {it.ended}
        if(push==null) {
            val created=UuAccountPush(decodeRoom(request("POST","/api/v1/room/create",
                JSONObject().put("last_controlled_interval",0))))
            if(cancelled() || account!==login) {created.close();throw java.io.IOException("共享请求已取消")}
            androidPush.getAndSet(created)?.close();push=created
        }
        val localAlias="OpenUUYC ${Build.MANUFACTURER} ${Build.MODEL}"
        shareAttempts.begin(device.id)
        var sent=false;var acknowledged=false
        try {
            UuAndroidShareConsent.await(push,device.id,identity.deviceId,localAlias,UuContract.VERSION,
                send={body->
                    if(account!==login) throw java.io.IOException("登录已改变，共享请求已取消")
                    val initial=body.getJSONObject("data").getString("action")=="request_control"
                    if(initial) sent=true
                    request("POST","/api/v1/transport/${device.id}/rpc/request",body,allowEmptyData=true)
                    if(initial) {acknowledged=true;shareAttempts.sent(device.id)}
                },cancelled={cancelled() || account!==login},status=status,
                onResolved={shareAttempts.resolved(device.id)})
            if(cancelled() || account!==login) throw java.io.IOException("共享请求已取消")
            synchronized(credentialLock) {
                if(account!==login) throw java.io.IOException("登录已改变，共享请求已取消")
                sharePermits.grant(device.id)
            }
        } finally {
            if(!sent) shareAttempts.resolved(device.id)
            else if(!acknowledged) shareAttempts.sent(device.id)
        }
    }

    fun join(device: UuDevice, forceJoin: Boolean = false): UuRoom {
        requireNotNull(account)
        require(device.online && device.controllable) { "target unavailable or not controllable" }
        require(!device.androidScreenShare || (!forceJoin && device.occupants == 0)) { "安卓屏幕共享正在使用中，请先结束当前共享" }
        require(device.occupants == 0 || forceJoin) { "target is occupied; explicit confirmation required" }
        if(device.androidScreenShare && !sharePermits.consume(device.id))
            throw java.io.IOException("请先请求并取得该手机的本次共享同意，再连接")
        val result = request("POST", "/api/v1/room/join/by_device/${device.id}",
            JSONObject().put("force_join", forceJoin))
        return decodeRoom(result)
    }

    private fun decodeRoom(result: JSONObject): UuRoom {
        val endpoints = mutableListOf<String>()
        val list = result.optJSONArray("signaling_list")
        if (list != null) for (i in 0 until list.length()) list.optString(i).takeIf(String::isNotBlank)?.let(endpoints::add)
        if (endpoints.isEmpty()) result.optString("signaling_server").takeIf(String::isNotBlank)?.let(endpoints::add)
        return UuRoom(result.getString("token"), endpoints, result.optInt("ws_connect_timeout_ms", 10000))
    }
}

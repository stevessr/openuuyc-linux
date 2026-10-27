package org.openuuyc.pico.uu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface UuSessionVault {
    fun load(): UuAccount?
    fun save(account: UuAccount)
    fun clear()
}

/** Credentials are stored only as authenticated ciphertext. Key material never
 * leaves AndroidKeyStore. No plaintext fallback, no biometric prompt each launch.
 * App backup is disabled; ciphertext is bound to this package/install and schema.
 */
internal class KeystoreSessionVault(context: Context, namespace: String = "login") : UuSessionVault {
    init { require(namespace.matches(Regex("[a-z0-9_-]{1,32}"))) }
    private val prefs = context.getSharedPreferences("uu_secure_$namespace", Context.MODE_PRIVATE)
    private val alias = "org.openuuyc.pico.session.$namespace.v1"
    private val aad = "${context.packageName}:$namespace:session-v1".toByteArray(Charsets.UTF_8)
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        (keystore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Saved login key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun save(account: UuAccount) {
        require(account.token.isNotBlank() && account.userId.isNotBlank())
        val plain = JSONObject().put("token", account.token).put("user_id", account.userId)
            .put("nickname", account.nickname).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plain)
            val blob = "v1." + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            if (!prefs.edit().putString("encrypted_session", blob).commit()) throw IOException("Cannot save encrypted login")
        } finally { plain.fill(0) }
    }
    @Synchronized override fun load(): UuAccount? {
        val blob = prefs.getString("encrypted_session", null) ?: return null
        require(blob.length <= 65536) { "Encrypted login too large" }
        val parts = blob.split('.')
        require(parts.size == 3 && parts[0] == "v1") { "Unsupported encrypted login" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12) { "Invalid login nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
        try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            return UuAccount(json.getString("token"), json.getString("user_id"), json.optString("nickname"))
                .also { require(it.token.isNotBlank() && it.userId.isNotBlank()) }
        } finally { plain.fill(0) }
    }
    @Synchronized override fun clear() {
        if (!prefs.edit().remove("encrypted_session").commit()) throw IOException("Cannot clear saved login")
        // Keep the non-exportable key: credentials are gone, future saves get a fresh random IV.
    }
}

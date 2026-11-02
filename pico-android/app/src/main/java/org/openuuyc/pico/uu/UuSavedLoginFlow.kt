package org.openuuyc.pico.uu

/** Startup validation policy, independently testable without Android Keystore/network. */
internal class UuSavedLoginFlow(
    private val vault: UuSessionVault,
    private val validate: (UuAccount) -> Unit
) {
    enum class Result { NONE, RESTORED, REVOKED, RETRY }
    fun restore(): Result {
        val saved = try { vault.load() } catch (_: Exception) { return Result.RETRY }
            ?: return Result.NONE
        return try {
            validate(saved)
            Result.RESTORED
        } catch (error: Exception) {
            if (error is UuNrdResponseException &&
                (error.httpStatus == 401 || error.businessCode in setOf(1001, 1002, 1120))) {
                // Only explicit credential rejection clears the encrypted record.
                try { vault.clear(); Result.REVOKED } catch (_: Exception) { Result.RETRY }
            } else Result.RETRY
        }
    }
}

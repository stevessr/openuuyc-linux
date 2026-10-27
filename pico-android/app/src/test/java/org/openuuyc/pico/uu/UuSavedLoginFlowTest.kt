package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UuSavedLoginFlowTest {
    private val sample = UuAccount("test-secret", "test-user", "Fixture")
    private class Vault(var value: UuAccount? = null) : UuSessionVault {
        var clears = 0
        override fun load() = value
        override fun save(account: UuAccount) { value = account }
        override fun clear() { clears++; value = null }
    }
    @Test fun absentSessionDoesNotValidateOrRegister() {
        var called = false
        assertEquals(UuSavedLoginFlow.Result.NONE, UuSavedLoginFlow(Vault()) { called = true }.restore())
        assertFalse(called)
    }
    @Test fun validSessionRestoresWithoutQrExchange() {
        val vault = Vault(sample)
        assertEquals(UuSavedLoginFlow.Result.RESTORED, UuSavedLoginFlow(vault) { assertEquals(sample, it) }.restore())
        assertEquals(sample, vault.value); assertEquals(0, vault.clears)
    }
    @Test fun networkAndServerFailuresKeepSavedCredentials() {
        for (failure in listOf(IOException("offline"), UuNrdResponseException(500), UuNrdResponseException(403))) {
            val vault = Vault(sample)
            assertEquals(UuSavedLoginFlow.Result.RETRY, UuSavedLoginFlow(vault) { throw failure }.restore())
            assertEquals(sample, vault.value); assertEquals(0, vault.clears)
        }
    }
    @Test fun onlyExplicitRevocationClearsCredentials() {
        for (failure in listOf(UuNrdResponseException(401), UuNrdResponseException(200, 1001), UuNrdResponseException(200, 1002), UuNrdResponseException(200, 1120))) {
            val vault = Vault(sample)
            assertEquals(UuSavedLoginFlow.Result.REVOKED, UuSavedLoginFlow(vault) { throw failure }.restore())
            assertNull(vault.value); assertEquals(1, vault.clears)
        }
    }
    @Test fun unreadableKeyOrCiphertextIsNotSilentlyDeleted() {
        var cleared = false
        val vault = object : UuSessionVault {
            override fun load(): UuAccount? = throw IOException("key temporarily unavailable")
            override fun save(account: UuAccount) {}
            override fun clear() { cleared = true }
        }
        assertEquals(UuSavedLoginFlow.Result.RETRY, UuSavedLoginFlow(vault) {}.restore())
        assertFalse(cleared)
    }
    @Test fun accountDiagnosticStringNeverContainsCredentials() {
        assertFalse(sample.toString().contains(sample.token))
        assertFalse(sample.toString().contains(sample.userId))
    }
}

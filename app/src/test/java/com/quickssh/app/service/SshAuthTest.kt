package com.quickssh.app.service

import com.quickssh.app.data.SshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.core.app.NotificationCompat
import org.junit.Test

class SshAuthTest {
    @Test
    fun passwordAuthRequiresPasswordCredential() {
        assertTrue(requiresPassword(AUTH_TYPE_PASSWORD))
        assertFalse(requiresPassword(AUTH_TYPE_PRIVATE_KEY))
    }

    @Test
    fun hasUsableCredentialChecksSelectedAuthType() {
        val passwordConfig = SshConfig(
            name = "server",
            host = "127.0.0.1",
            username = "root",
            authType = AUTH_TYPE_PASSWORD,
            encryptedPassword = "encrypted-password"
        )
        val keyConfig = passwordConfig.copy(
            authType = AUTH_TYPE_PRIVATE_KEY,
            encryptedPassword = null,
            encryptedPrivateKey = "encrypted-key"
        )
        val localConfig = SshConfig(
            name = "local",
            host = "localhost",
            username = "local",
            authType = AUTH_TYPE_LOCAL,
            isLocalSession = true
        )

        assertTrue(hasUsableCredential(passwordConfig))
        assertTrue(hasUsableCredential(keyConfig))
        assertTrue(hasUsableCredential(localConfig))
        assertFalse(hasUsableCredential(passwordConfig.copy(encryptedPassword = null)))
        assertFalse(hasUsableCredential(keyConfig.copy(encryptedPrivateKey = null)))
    }

    @Test
    fun authTypeLabelIsUserFacing() {
        assertEquals("密码", authTypeLabel(AUTH_TYPE_PASSWORD))
        assertEquals("私钥", authTypeLabel(AUTH_TYPE_PRIVATE_KEY))
        assertEquals("本地", authTypeLabel(AUTH_TYPE_LOCAL))
    }
}

package com.quickssh.app.ui.screens

import com.quickssh.app.service.AUTH_TYPE_LOCAL
import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import com.quickssh.app.service.AUTH_TYPE_PRIVATE_KEY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshAddScreenValidationTest {
    @Test
    fun localAuthNeverRequiresCredentials() {
        assertFalse(
            sshPasswordCredentialMissing(
                authType = AUTH_TYPE_LOCAL,
                password = "",
                hasSavedPassword = false
            )
        )
        assertFalse(
            sshPrivateKeyCredentialMissing(
                authType = AUTH_TYPE_LOCAL,
                privateKey = "",
                hasSavedPrivateKey = false
            )
        )
    }

    @Test
    fun passwordAuthRequiresNewOrSavedPassword() {
        assertTrue(
            sshPasswordCredentialMissing(
                authType = AUTH_TYPE_PASSWORD,
                password = "",
                hasSavedPassword = false
            )
        )
        assertFalse(
            sshPasswordCredentialMissing(
                authType = AUTH_TYPE_PASSWORD,
                password = "",
                hasSavedPassword = true
            )
        )
        assertFalse(
            sshPasswordCredentialMissing(
                authType = AUTH_TYPE_PASSWORD,
                password = "secret",
                hasSavedPassword = false
            )
        )
    }

    @Test
    fun privateKeyAuthRequiresNewOrSavedPrivateKey() {
        assertTrue(
            sshPrivateKeyCredentialMissing(
                authType = AUTH_TYPE_PRIVATE_KEY,
                privateKey = "",
                hasSavedPrivateKey = false
            )
        )
        assertFalse(
            sshPrivateKeyCredentialMissing(
                authType = AUTH_TYPE_PRIVATE_KEY,
                privateKey = "",
                hasSavedPrivateKey = true
            )
        )
        assertFalse(
            sshPrivateKeyCredentialMissing(
                authType = AUTH_TYPE_PRIVATE_KEY,
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----",
                hasSavedPrivateKey = false
            )
        )
    }

    @Test
    fun inactiveCredentialTypeDoesNotBlockSaving() {
        assertFalse(
            sshPasswordCredentialMissing(
                authType = AUTH_TYPE_PRIVATE_KEY,
                password = "",
                hasSavedPassword = false
            )
        )
        assertFalse(
            sshPrivateKeyCredentialMissing(
                authType = AUTH_TYPE_PASSWORD,
                privateKey = "",
                hasSavedPrivateKey = false
            )
        )
    }
}

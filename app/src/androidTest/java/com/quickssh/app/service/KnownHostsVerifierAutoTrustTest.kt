package com.quickssh.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.PublicKey

@RunWith(AndroidJUnit4::class)
class KnownHostsVerifierAutoTrustTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun verifierAutomaticallyTrustsHostKeyWithoutPendingConfirmation() {
        val hostname = "auto-trust-${System.currentTimeMillis()}.example.com"
        val verifier = KnownHostsVerifier(context)

        assertTrue(verifier.verify(hostname, 22, FakePublicKey()))
        assertNull(KnownHostsVerifier.pendingFingerprint(context, hostname, 22))
        assertNotNull(KnownHostsVerifier.acceptedFingerprint(context, hostname, 22))
    }

    private class FakePublicKey : PublicKey {
        override fun getAlgorithm(): String = "RSA"
        override fun getFormat(): String = "X.509"
        override fun getEncoded(): ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }
}

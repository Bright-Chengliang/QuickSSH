package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class KnownHostsVerifierTest {
    @Test
    fun preferenceKeyNormalizesHostAndPort() {
        assertEquals("example.com:22", KnownHostsVerifier.preferenceKey(" Example.COM ", 22))
    }

    @Test
    fun nonStrictModeAlwaysAcceptsAndStoresObservedFingerprint() {
        assertEquals(
            HostKeyVerificationDecision.ACCEPT_AND_STORE,
            hostKeyVerificationDecision(
                acceptedFingerprint = null,
                observedFingerprint = "aa:bb",
                strictMode = false
            )
        )
        assertEquals(
            HostKeyVerificationDecision.ACCEPT_AND_STORE,
            hostKeyVerificationDecision(
                acceptedFingerprint = "old",
                observedFingerprint = "new",
                strictMode = false
            )
        )
    }

    @Test
    fun strictModeAcceptsOnlyPreviouslyTrustedMatchingFingerprint() {
        assertEquals(
            HostKeyVerificationDecision.ACCEPT_KNOWN,
            hostKeyVerificationDecision(
                acceptedFingerprint = "aa:bb",
                observedFingerprint = "aa:bb",
                strictMode = true
            )
        )
        assertEquals(
            HostKeyVerificationDecision.REJECT_AND_STORE_PENDING,
            hostKeyVerificationDecision(
                acceptedFingerprint = null,
                observedFingerprint = "aa:bb",
                strictMode = true
            )
        )
        assertEquals(
            HostKeyVerificationDecision.REJECT_AND_STORE_PENDING,
            hostKeyVerificationDecision(
                acceptedFingerprint = "aa:bb",
                observedFingerprint = "cc:dd",
                strictMode = true
            )
        )
    }
}

package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmbeddedSshServerTest {

    private val ed25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHvI2NA2/iMYTrfL6V7zX4UfDOFX1KgY5yxvh4v5cL9F test-ed25519"
    private val ecdsa = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEMWpxeYPKJ99WNLdIaENhSxgq7WbPVxHR0tupzn1jLeCo3fGpnk+A7iOIMg47yQWJeQLQ+pakzspoAaTDJKq0k= test-ecdsa"
    private val rsa = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDkRYpeCIJ2t1nAgxGfIVihP1Uey1gMMLh7RJRqtYzwPKA0pbMJpT7++sy7wJT0RAgluB6YHBSpC+gpNSaBEBTiqkcd/+oyIavtY5wl4gGaVSVqqCXavK/BahkF5I66JOTYq4QXXnwquj7pEwggkOSK66pBE94/rDDcTHeJE3pGGAVmHL9AMmIOcbGglknB6FlTh6Zkq9VzUFomo7CMZhMWSTHA9Ul4QxoaE8ByMZuEXO5EpSV+OSOJjHd9teny8KMELIghHKfnG7Gge6S6UYaLTqUTAfuPY+VX6o8+qTaFGxtr1rYGm3S3NkyKMMHKF5W1FVb44nsHl9KWnYEz0bwSgrLXHl6hLHzdgkT90aotMAK9EdZEzNSflKG5JG+NYztf00BYfOhS9VSeqBC4H3i8JTdCeB6P7Dxxm4k094rP6Oi+KhKMiIA5+zRn56jNtIRa3rgEWk4K2VZr+KHkgLRl4xUhwy2YGezJTpq5+47pHEtElSimZmtRAhAL5hnxXl8= test-rsa"

    @Test
    fun parsesCommonKeyTypesSkippingBlanksAndComments() {
        val keys = EmbeddedSshServer.parseAuthorizedKeys("# laptop\n$ed25519\n\n$ecdsa\nfrom=\"10.0.0.*\" $rsa\n")
        assertEquals(3, keys.size)
        assertEquals(listOf("EdDSA", "EC", "RSA"), keys.map { it.algorithm.replace("Ed25519", "EdDSA") })
    }

    @Test
    fun rejectsGarbageWithLineNumber() {
        try {
            EmbeddedSshServer.parseAuthorizedKeys("$ed25519\nssh-ed25519 not-base64!!")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("line 2"))
        }
    }

    @Test
    fun forwardingIsOnlyAllowedToLoopback() {
        listOf("127.0.0.1", "localhost", "::1", "[::1]", "127.1.2.3").forEach {
            assertTrue(it, EmbeddedSshServer.isLoopbackHost(it))
        }
        listOf("10.0.0.5", "192.168.1.1", "example.com", "0.0.0.0", "", null).forEach {
            assertFalse(it.toString(), EmbeddedSshServer.isLoopbackHost(it))
        }
    }

    @Test
    fun passwordsAreLongAndUnambiguous() {
        val password = EmbeddedSshServer.newPassword()
        assertEquals(20, password.length)
        assertTrue(password.none { it in "0O1lI" })
        assertFalse(password == EmbeddedSshServer.newPassword())
    }

    @Test
    fun signalsMapToShellStyleExitStatus() {
        assertEquals(0, EmbeddedSshServer.sshExitStatus(0))
        assertEquals(3, EmbeddedSshServer.sshExitStatus(3))
        assertEquals(137, EmbeddedSshServer.sshExitStatus(-9))
    }
}

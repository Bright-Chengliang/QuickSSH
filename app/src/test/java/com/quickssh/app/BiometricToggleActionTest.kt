package com.quickssh.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricToggleActionTest {
    @Test
    fun enablingBiometricUnlockDoesNotRequireExistingAuthentication() {
        assertEquals(
            BiometricToggleAction.ENABLE,
            biometricToggleAction(currentEnabled = false, requestedEnabled = true)
        )
    }

    @Test
    fun disablingBiometricUnlockRequiresAuthentication() {
        assertEquals(
            BiometricToggleAction.DISABLE_WITH_AUTH,
            biometricToggleAction(currentEnabled = true, requestedEnabled = false)
        )
    }

    @Test
    fun unchangedBiometricToggleDoesNothing() {
        assertEquals(
            BiometricToggleAction.NO_CHANGE,
            biometricToggleAction(currentEnabled = false, requestedEnabled = false)
        )
        assertEquals(
            BiometricToggleAction.NO_CHANGE,
            biometricToggleAction(currentEnabled = true, requestedEnabled = true)
        )
    }
}

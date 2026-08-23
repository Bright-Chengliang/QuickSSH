package com.quickssh.app

import com.quickssh.app.data.SshConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTunnelHelperTest {
    @Test
    fun resolveTunnelConfigRestoresSavedWorkspaceOnRestart() {
        val configs = listOf(config(1L, "First"), config(2L, "Saved"))

        assertEquals(2L, resolveTunnelConfig(configs, currentConfigId = null, savedConfigId = 2L)?.id)
    }

    @Test
    fun resolveTunnelConfigKeepsCurrentWorkspaceWhenItStillExists() {
        val configs = listOf(config(1L, "First"), config(2L, "Current"))

        assertEquals(2L, resolveTunnelConfig(configs, currentConfigId = 2L, savedConfigId = 1L)?.id)
    }

    @Test
    fun resolveTunnelConfigFallsBackWhenStoredWorkspaceWasDeleted() {
        val configs = listOf(config(3L, "Fallback"))

        assertEquals(3L, resolveTunnelConfig(configs, currentConfigId = 1L, savedConfigId = 2L)?.id)
    }

    @Test
    fun databaseRetryDelayUsesBoundedBackoff() {
        assertEquals(250L, databaseRetryDelayMillis(0L))
        assertEquals(5_000L, databaseRetryDelayMillis(100L))
    }

    private fun config(id: Long, name: String): SshConfig {
        return SshConfig(
            id = id,
            name = name,
            host = "example.com",
            username = "root",
            authType = "PASSWORD"
        )
    }
}

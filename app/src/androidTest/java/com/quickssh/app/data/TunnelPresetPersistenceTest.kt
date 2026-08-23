package com.quickssh.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TunnelPresetPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun tunnelPresetSurvivesDatabaseCloseAndReopen() = runBlocking {
        val firstDatabase = openDatabase()
        val workspaceId = firstDatabase.sshConfigDao().insertConfig(config())
        val presetId = firstDatabase.sshConfigDao().saveTunnelPreset(
            SshTunnelPreset(
                workspaceId = workspaceId,
                name = "SillyTavern",
                remoteHost = "127.0.0.1",
                remotePort = 8000
            )
        )
        assertTrue(presetId > 0L)
        firstDatabase.close()

        val reopenedDatabase = openDatabase()
        try {
            val presets = reopenedDatabase.sshConfigDao().getTunnelPresetsForWorkspace(workspaceId)
            assertEquals(1, presets.size)
            assertEquals("SillyTavern", presets.single().name)
            assertEquals(8000, presets.single().remotePort)
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun saveTunnelPresetRecoversFromStaleSelectedId() = runBlocking {
        val database = openDatabase()
        try {
            val workspaceId = database.sshConfigDao().insertConfig(config())
            val savedId = database.sshConfigDao().saveTunnelPreset(
                SshTunnelPreset(
                    id = 9999L,
                    workspaceId = workspaceId,
                    name = "Recovered",
                    remotePort = 8000
                )
            )

            assertTrue(savedId > 0L)
            assertEquals("Recovered", database.sshConfigDao().getTunnelPresetsForWorkspace(workspaceId).single().name)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB).build()
    }

    private fun config(): SshConfig {
        return SshConfig(
            name = "Workspace",
            host = "example.com",
            username = "root",
            authType = "PASSWORD",
            encryptedPassword = "secret"
        )
    }

    private companion object {
        const val TEST_DB = "tunnel-preset-persistence-test.db"
    }
}

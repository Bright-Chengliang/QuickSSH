package com.quickssh.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshConfigDaoHierarchyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun cleanUp() {
        database.close()
    }

    @Test
    fun insertConfigStoresOneServerNodeWithMultipleWorkspaces() = runBlocking {
        val dao = database.sshConfigDao()
        val firstWorkspaceId = dao.insertConfig(config(name = "Deploy", workDirectory = "/srv/app"))
        val first = dao.getConfigById(firstWorkspaceId)
        assertNotNull(first)

        val secondWorkspaceId = dao.insertConfig(
            config(
                name = "Logs",
                workDirectory = "/var/log",
                serverNodeId = requireNotNull(first).serverNodeId
            )
        )

        val workspaces = dao.getAllConfigs().sortedBy { it.name }

        assertEquals(listOf("Deploy", "Logs"), workspaces.map { it.name })
        assertEquals(workspaces[0].serverNodeId, workspaces[1].serverNodeId)
        assertNotEquals(firstWorkspaceId, secondWorkspaceId)
    }

    @Test
    fun updateConfigChangesServerNodeWithoutOverwritingSiblingWorkspaceFields() = runBlocking {
        val dao = database.sshConfigDao()
        val deployId = dao.insertConfig(config(name = "Deploy", workDirectory = "/srv/app", encryptedPassword = "secret-1"))
        val deploy = requireNotNull(dao.getConfigById(deployId))
        val logsId = dao.insertConfig(
            config(
                name = "Logs",
                workDirectory = "/var/log",
                postConnectCommand = "journalctl -f",
                serverNodeId = deploy.serverNodeId,
                encryptedPassword = "secret-1"
            )
        )

        dao.updateConfig(
            deploy.copy(
                host = "prod.example.com",
                port = 2222,
                username = "deploy",
                encryptedPassword = "secret-2",
                workDirectory = "/srv/app"
            )
        )

        val updatedDeploy = requireNotNull(dao.getConfigById(deployId))
        val updatedLogs = requireNotNull(dao.getConfigById(logsId))

        assertEquals("prod.example.com", updatedDeploy.host)
        assertEquals("prod.example.com", updatedLogs.host)
        assertEquals(2222, updatedLogs.port)
        assertEquals("deploy", updatedLogs.username)
        assertEquals("secret-2", updatedLogs.encryptedPassword)
        assertEquals("/var/log", updatedLogs.workDirectory)
        assertEquals("journalctl -f", updatedLogs.postConnectCommand)
    }

    private fun config(
        name: String,
        host: String = "example.com",
        port: Int = 22,
        username: String = "root",
        workDirectory: String? = null,
        postConnectCommand: String? = null,
        encryptedPassword: String = "secret",
        serverNodeId: Long = 0
    ): SshConfig {
        return SshConfig(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = AUTH_TYPE_PASSWORD,
            encryptedPassword = encryptedPassword,
            workDirectory = workDirectory,
            postConnectCommand = postConnectCommand,
            serverNodeId = serverNodeId
        )
    }
}

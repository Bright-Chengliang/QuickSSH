package com.quickssh.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migratesLegacyVersionOneDatabaseToCurrentSchema() = runBlocking {
        createLegacyVersionOneDatabase()

        val database = openMigratedDatabase()
        try {
            val config = database.sshConfigDao().getConfigById(1)

            assertNotNull(config)
            assertEquals("Legacy", config?.name)
            assertEquals("example.com", config?.host)
            assertEquals("PASSWORD", config?.authType)
            assertEquals("old-secret", config?.encryptedPassword)
            assertEquals("xterm-256color", config?.terminalTerm)
            assertEquals(12, config?.terminalFontSizeSp)
            assertEquals(0, database.transferHistoryDao().count())
        } finally {
            database.close()
        }
    }

    @Test
    fun migratesVersionThreeTransferHistoryWithWorkspaceLabels() = runBlocking {
        createVersionThreeDatabase()

        val database = openMigratedDatabase()
        try {
            val entry = database.transferHistoryDao().getById(1)

            assertNotNull(entry)
            assertEquals("root@example.com:22", entry?.serverNodeName)
            assertEquals("Deploy", entry?.workspaceName)
            assertEquals("/tmp/app.apk", entry?.remotePath)
        } finally {
            database.close()
        }
    }

    private fun openMigratedDatabase(): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.migrations())
            .build()
    }

    private fun createLegacyVersionOneDatabase() {
        openRawDatabase().use { database ->
            database.execSQL(
                """
                CREATE TABLE ssh_configurations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    authType TEXT NOT NULL,
                    encryptedPassword TEXT,
                    updateTime INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO ssh_configurations (
                    id, name, host, port, username, authType, encryptedPassword, updateTime
                ) VALUES (1, 'Legacy', 'example.com', 22, 'root', 'PASSWORD', 'old-secret', 1000)
                """.trimIndent()
            )
            database.version = 1
        }
    }

    private fun createVersionThreeDatabase() {
        openRawDatabase().use { database ->
            database.execSQL(
                """
                CREATE TABLE ssh_configurations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    authType TEXT NOT NULL,
                    encryptedPassword TEXT,
                    encryptedPrivateKey TEXT,
                    workDirectory TEXT,
                    postConnectCommand TEXT,
                    updateTime INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE transfer_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fileName TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    serverName TEXT NOT NULL,
                    status TEXT NOT NULL,
                    localUri TEXT,
                    remotePath TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updateTime INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO ssh_configurations (
                    id, name, host, port, username, authType, encryptedPassword, encryptedPrivateKey,
                    workDirectory, postConnectCommand, updateTime
                ) VALUES (1, 'Deploy', 'example.com', 22, 'root', 'PASSWORD', 'secret', NULL, '/srv/app', NULL, 1000)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO transfer_history (
                    id, fileName, direction, serverName, status, localUri, remotePath, detail, createdAt, updateTime
                ) VALUES (1, 'app.apk', 'Upload', 'root@example.com:22 / Deploy', 'Success', NULL, '/tmp/app.apk', '', 1000, 1000)
                """.trimIndent()
            )
            database.version = 3
        }
    }

    private fun openRawDatabase(): SQLiteDatabase {
        context.deleteDatabase(TEST_DB)
        val path = context.getDatabasePath(TEST_DB)
        path.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(path, null)
    }

    private companion object {
        const val TEST_DB = "quickssh-migration-test.db"
    }
}

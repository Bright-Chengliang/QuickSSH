package com.quickssh.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SshServerNode::class, SshWorkspaceProfile::class, SshTunnelPreset::class, TransferHistoryEntry::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sshConfigDao(): SshConfigDao
    abstract fun transferHistoryDao(): TransferHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSshConfigurationsTable(db)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeTransferHistoryTable(db)
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeTransferHistoryTable(db)
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSshConfigurationsTable(db)
                normalizeTransferHistoryTable(db)
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSshConfigurationsTable(db)
                normalizeTransferHistoryTable(db)
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSshConfigurationsTable(db)
                normalizeSshHierarchyTables(db)
                normalizeTransferHistoryTable(db)
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSshHierarchyTables(db)
                normalizeTunnelPresetTable(db)
                normalizeTransferHistoryTable(db)
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeSshSortOrderColumns(db)
            }
        }

        internal fun migrations(): Array<Migration> {
            return arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9
            )
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quickssh_secure_db"
                )
                .addMigrations(*migrations())
                .build()
                INSTANCE = instance
                instance
            }
        }

        private fun normalizeSshConfigurationsTable(database: SupportSQLiteDatabase) {
            if (!tableExists(database, "ssh_configurations")) {
                createSshConfigurationsTable(database, "ssh_configurations")
                return
            }

            val columns = columnNames(database, "ssh_configurations")
            database.execSQL("DROP TABLE IF EXISTS ssh_configurations_new")
            createSshConfigurationsTable(database, "ssh_configurations_new")

            val now = System.currentTimeMillis()
            val passwordExpr = when {
                "encryptedPassword" in columns -> "encryptedPassword"
                "passwordEncrypted" in columns -> "passwordEncrypted"
                else -> "NULL"
            }
            val privateKeyExpr = when {
                "encryptedPrivateKey" in columns -> "encryptedPrivateKey"
                "privateKeyEncrypted" in columns -> "privateKeyEncrypted"
                else -> "NULL"
            }
            val authExpr = if ("authType" in columns) {
                "COALESCE(NULLIF(authType, ''), 'PASSWORD')"
            } else {
                "CASE WHEN $privateKeyExpr IS NOT NULL AND $privateKeyExpr != '' THEN 'PRIVATE_KEY' ELSE 'PASSWORD' END"
            }

            database.execSQL(
                """
                INSERT INTO ssh_configurations_new (
                    id, name, host, port, username, authType, encryptedPassword, encryptedPrivateKey,
                    workDirectory, postConnectCommand, terminalFontSizeSp, terminalWrapEnabled,
                    terminalTerm, terminalShortcuts, updateTime
                )
                SELECT
                    ${columnOrDefault(columns, "id", "NULL")},
                    ${textColumnOrDefault(columns, "name", "Server")},
                    ${textColumnOrDefault(columns, "host", "127.0.0.1")},
                    ${columnOrDefault(columns, "port", "22")},
                    ${textColumnOrDefault(columns, "username", "root")},
                    $authExpr,
                    $passwordExpr,
                    $privateKeyExpr,
                    ${nullableColumn(columns, "workDirectory")},
                    ${nullableColumn(columns, "postConnectCommand")},
                    ${columnOrDefault(columns, "terminalFontSizeSp", "12")},
                    ${nullableColumn(columns, "terminalWrapEnabled")},
                    ${textColumnOrDefault(columns, "terminalTerm", "xterm-256color")},
                    ${nullableColumn(columns, "terminalShortcuts")},
                    ${columnOrDefault(columns, "updateTime", now.toString())}
                FROM ssh_configurations
                """.trimIndent()
            )
            database.execSQL("DROP TABLE ssh_configurations")
            database.execSQL("ALTER TABLE ssh_configurations_new RENAME TO ssh_configurations")
        }

        private fun normalizeTransferHistoryTable(database: SupportSQLiteDatabase) {
            if (!tableExists(database, "transfer_history")) {
                createTransferHistoryTable(database, "transfer_history")
                return
            }

            val columns = columnNames(database, "transfer_history")
            database.execSQL("DROP TABLE IF EXISTS transfer_history_new")
            createTransferHistoryTable(database, "transfer_history_new")
            val now = System.currentTimeMillis()
            val serverNameExpr = textColumnOrDefault(columns, "serverName", "Unknown")
            val serverNodeExpr = if ("serverNodeName" in columns) {
                textColumnOrDefault(columns, "serverNodeName", "Unknown")
            } else {
                "CASE WHEN instr($serverNameExpr, ' / ') > 0 THEN substr($serverNameExpr, 1, instr($serverNameExpr, ' / ') - 1) ELSE $serverNameExpr END"
            }
            val workspaceExpr = if ("workspaceName" in columns) {
                textColumnOrDefault(columns, "workspaceName", "Unknown")
            } else {
                "CASE WHEN instr($serverNameExpr, ' / ') > 0 THEN substr($serverNameExpr, instr($serverNameExpr, ' / ') + 3) ELSE $serverNameExpr END"
            }

            database.execSQL(
                """
                INSERT INTO transfer_history_new (
                    id, fileName, direction, serverName, status, localUri, remotePath, detail,
                    serverNodeName, workspaceName, createdAt, updateTime
                )
                SELECT
                    ${columnOrDefault(columns, "id", "NULL")},
                    ${textColumnOrDefault(columns, "fileName", "Transfer")},
                    ${textColumnOrDefault(columns, "direction", "Transfer")},
                    $serverNameExpr,
                    ${textColumnOrDefault(columns, "status", "Unknown")},
                    ${nullableColumn(columns, "localUri")},
                    ${textColumnOrDefault(columns, "remotePath", "")},
                    ${textColumnOrDefault(columns, "detail", "")},
                    $serverNodeExpr,
                    $workspaceExpr,
                    ${columnOrDefault(columns, "createdAt", now.toString())},
                    ${columnOrDefault(columns, "updateTime", now.toString())}
                FROM transfer_history
                """.trimIndent()
            )
            database.execSQL("DROP TABLE transfer_history")
            database.execSQL("ALTER TABLE transfer_history_new RENAME TO transfer_history")
        }

        private fun normalizeSshHierarchyTables(database: SupportSQLiteDatabase) {
            createSshServerNodesTable(database)
            createSshWorkspacesTable(database)

            if (!tableExists(database, "ssh_configurations")) return
            if (tableRowCount(database, "ssh_workspaces") > 0) {
                database.execSQL("DROP TABLE ssh_configurations")
                return
            }

            database.execSQL(
                """
                INSERT INTO ssh_server_nodes (
                    displayName, host, port, username, authType, encryptedPassword,
                    encryptedPrivateKey, updateTime
                )
                SELECT
                    picked.username || '@' || picked.host || ':' || picked.port,
                    picked.host,
                    picked.port,
                    picked.username,
                    picked.authType,
                    picked.encryptedPassword,
                    picked.encryptedPrivateKey,
                    picked.updateTime
                FROM ssh_configurations AS picked
                WHERE picked.id IN (
                    SELECT (
                        SELECT candidate.id
                        FROM ssh_configurations AS candidate
                        WHERE lower(candidate.host) = lower(grouped.host)
                            AND candidate.port = grouped.port
                            AND candidate.username = grouped.username
                            AND candidate.authType = grouped.authType
                        ORDER BY candidate.updateTime DESC, candidate.id DESC
                        LIMIT 1
                    )
                    FROM ssh_configurations AS grouped
                    GROUP BY lower(grouped.host), grouped.port, grouped.username, grouped.authType
                )
                """.trimIndent()
            )

            database.execSQL(
                """
                INSERT INTO ssh_workspaces (
                    id, serverNodeId, name, workDirectory, postConnectCommand,
                    terminalFontSizeSp, terminalWrapEnabled, terminalTerm,
                    terminalShortcuts, updateTime
                )
                SELECT
                    legacy.id,
                    (
                        SELECT node.id
                        FROM ssh_server_nodes AS node
                        WHERE lower(node.host) = lower(legacy.host)
                            AND node.port = legacy.port
                            AND node.username = legacy.username
                            AND node.authType = legacy.authType
                        ORDER BY node.updateTime DESC, node.id DESC
                        LIMIT 1
                    ),
                    legacy.name,
                    legacy.workDirectory,
                    legacy.postConnectCommand,
                    legacy.terminalFontSizeSp,
                    legacy.terminalWrapEnabled,
                    legacy.terminalTerm,
                    legacy.terminalShortcuts,
                    legacy.updateTime
                FROM ssh_configurations AS legacy
                """.trimIndent()
            )

            database.execSQL("DROP TABLE ssh_configurations")
        }

        private fun normalizeSshSortOrderColumns(database: SupportSQLiteDatabase) {
            createSshServerNodesTable(database)
            createSshWorkspacesTable(database)

            if ("sortOrder" !in columnNames(database, "ssh_server_nodes")) {
                database.execSQL("ALTER TABLE ssh_server_nodes ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
            if ("sortOrder" !in columnNames(database, "ssh_workspaces")) {
                database.execSQL("ALTER TABLE ssh_workspaces ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }

            if (!sortOrderHasValues(database, "ssh_server_nodes")) {
                val orderedServerIds = queryLongIds(
                    database,
                    "SELECT id FROM ssh_server_nodes ORDER BY updateTime DESC, id DESC"
                )
                orderedServerIds.forEachIndexed { index, id ->
                    database.execSQL(
                        "UPDATE ssh_server_nodes SET sortOrder = ? WHERE id = ?",
                        arrayOf(orderedServerIds.size - index, id)
                    )
                }
            }

            if (!sortOrderHasValues(database, "ssh_workspaces")) {
                val serverIds = queryLongIds(
                    database,
                    "SELECT id FROM ssh_server_nodes ORDER BY sortOrder DESC, id DESC"
                )
                serverIds.forEach { serverId ->
                    val orderedWorkspaceIds = queryLongIds(
                        database,
                        "SELECT id FROM ssh_workspaces WHERE serverNodeId = ? ORDER BY updateTime DESC, id DESC",
                        arrayOf(serverId)
                    )
                    orderedWorkspaceIds.forEachIndexed { index, id ->
                        database.execSQL(
                            "UPDATE ssh_workspaces SET sortOrder = ? WHERE id = ?",
                            arrayOf(orderedWorkspaceIds.size - index, id)
                        )
                    }
                }
            }
        }

        private fun normalizeTunnelPresetTable(database: SupportSQLiteDatabase) {
            if (!tableExists(database, "ssh_tunnel_presets")) {
                createTunnelPresetTable(database)
                return
            }

            val columns = columnNames(database, "ssh_tunnel_presets")
            database.execSQL("DROP TABLE IF EXISTS ssh_tunnel_presets_new")
            createTunnelPresetTable(database, "ssh_tunnel_presets_new")
            val now = System.currentTimeMillis()
            database.execSQL(
                """
                INSERT INTO ssh_tunnel_presets_new (
                    id, workspaceId, name, note, remoteHost, remotePort, localPort, updateTime
                )
                SELECT
                    ${columnOrDefault(columns, "id", "NULL")},
                    ${columnOrDefault(columns, "workspaceId", "0")},
                    ${textColumnOrDefault(columns, "name", "Tunnel")},
                    ${nullableColumn(columns, "note")},
                    ${textColumnOrDefault(columns, "remoteHost", "127.0.0.1")},
                    ${columnOrDefault(columns, "remotePort", "3000")},
                    ${columnOrDefault(columns, "localPort", "0")},
                    ${columnOrDefault(columns, "updateTime", now.toString())}
                FROM ssh_tunnel_presets
                WHERE ${columnOrDefault(columns, "workspaceId", "0")} > 0
                """.trimIndent()
            )
            database.execSQL("DROP TABLE ssh_tunnel_presets")
            database.execSQL("ALTER TABLE ssh_tunnel_presets_new RENAME TO ssh_tunnel_presets")
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_ssh_tunnel_presets_workspaceId
                ON ssh_tunnel_presets(workspaceId)
                """.trimIndent()
            )
        }

        private fun createSshConfigurationsTable(database: SupportSQLiteDatabase, tableName: String) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
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
                    terminalFontSizeSp INTEGER NOT NULL,
                    terminalWrapEnabled INTEGER,
                    terminalTerm TEXT NOT NULL,
                    terminalShortcuts TEXT,
                    updateTime INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        private fun createTransferHistoryTable(database: SupportSQLiteDatabase, tableName: String) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fileName TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    serverName TEXT NOT NULL,
                    status TEXT NOT NULL,
                    localUri TEXT,
                    remotePath TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    serverNodeName TEXT NOT NULL,
                    workspaceName TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updateTime INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        private fun createSshServerNodesTable(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ssh_server_nodes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    displayName TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    authType TEXT NOT NULL,
                    encryptedPassword TEXT,
                    encryptedPrivateKey TEXT,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    updateTime INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_ssh_server_nodes_host_port_username_authType
                ON ssh_server_nodes(host, port, username, authType)
                """.trimIndent()
            )
        }

        private fun createSshWorkspacesTable(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ssh_workspaces (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    serverNodeId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    workDirectory TEXT,
                    postConnectCommand TEXT,
                    terminalFontSizeSp INTEGER NOT NULL,
                    terminalWrapEnabled INTEGER,
                    terminalTerm TEXT NOT NULL,
                    terminalShortcuts TEXT,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    updateTime INTEGER NOT NULL,
                    FOREIGN KEY(serverNodeId) REFERENCES ssh_server_nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_ssh_workspaces_serverNodeId
                ON ssh_workspaces(serverNodeId)
                """.trimIndent()
            )
        }

        private fun createTunnelPresetTable(database: SupportSQLiteDatabase, tableName: String = "ssh_tunnel_presets") {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $tableName (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    workspaceId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    note TEXT,
                    remoteHost TEXT NOT NULL,
                    remotePort INTEGER NOT NULL,
                    localPort INTEGER NOT NULL,
                    updateTime INTEGER NOT NULL,
                    FOREIGN KEY(workspaceId) REFERENCES ssh_workspaces(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_${tableName}_workspaceId
                ON $tableName(workspaceId)
                """.trimIndent()
            )
        }

        private fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean {
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName)
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }

        private fun columnNames(database: SupportSQLiteDatabase, tableName: String): Set<String> {
            database.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return emptySet()
                return buildSet {
                    while (cursor.moveToNext()) {
                        cursor.getString(nameIndex)?.let(::add)
                    }
                }
            }
        }

        private fun tableRowCount(database: SupportSQLiteDatabase, tableName: String): Int {
            database.query("SELECT COUNT(*) FROM `$tableName`").use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

        private fun sortOrderHasValues(database: SupportSQLiteDatabase, tableName: String): Boolean {
            database.query("SELECT COUNT(*) FROM `$tableName` WHERE sortOrder != 0").use { cursor ->
                return cursor.moveToFirst() && cursor.getInt(0) > 0
            }
        }

        private fun queryLongIds(
            database: SupportSQLiteDatabase,
            sql: String,
            bindArgs: Array<out Any?> = emptyArray()
        ): List<Long> {
            database.query(sql, bindArgs).use { cursor ->
                return buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(0))
                    }
                }
            }
        }

        private fun nullableColumn(columns: Set<String>, columnName: String): String {
            return if (columnName in columns) columnName else "NULL"
        }

        private fun columnOrDefault(columns: Set<String>, columnName: String, defaultSql: String): String {
            return if (columnName in columns) "COALESCE($columnName, $defaultSql)" else defaultSql
        }

        private fun textColumnOrDefault(columns: Set<String>, columnName: String, defaultValue: String): String {
            val escapedDefault = defaultValue.replace("'", "''")
            return if (columnName in columns) {
                "COALESCE(NULLIF($columnName, ''), '$escapedDefault')"
            } else {
                "'$escapedDefault'"
            }
        }
    }
}

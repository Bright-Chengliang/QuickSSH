package com.quickssh.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class SshConfigBackupRecord(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val password: String?,
    val privateKey: String?,
    val workDirectory: String?,
    val postConnectCommand: String?,
    val terminalFontSizeSp: Int,
    val terminalWrapEnabled: Boolean?,
    val terminalTerm: String,
    val terminalShortcuts: String?,
    val tunnelPresets: List<SshTunnelPresetBackupRecord> = emptyList()
)

data class SshTunnelPresetBackupRecord(
    val name: String,
    val note: String?,
    val remoteHost: String,
    val remotePort: Int,
    val localPort: Int
)

object SshConfigBackupCodec {
    private const val APP_NAME = "QuickSSH"
    private const val FORMAT_VERSION = 2
    private const val AUTH_TYPE_PASSWORD = "PASSWORD"
    private const val AUTH_TYPE_PRIVATE_KEY = "PRIVATE_KEY"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_SIZE_BITS = 256
    private const val TAG_SIZE_BITS = 128
    private const val SALT_SIZE_BYTES = 16
    private const val IV_SIZE_BYTES = 12
    private const val PBKDF2_ITERATIONS = 120_000

    fun encode(records: List<SshConfigBackupRecord>, password: String? = null): String {
        val plainJson = encodePlain(records)
        val backupPassword = password?.takeIf { it.isNotEmpty() } ?: return plainJson
        return encryptBackupJson(plainJson, backupPassword)
    }

    fun decode(json: String, password: String? = null): List<SshConfigBackupRecord> {
        val root = JSONObject(json)
        val plainJson = if (root.optBoolean("encrypted", false) || root.has("encryption")) {
            val backupPassword = password?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Backup password is required.")
            decryptBackupJson(root, backupPassword)
        } else {
            json
        }
        return decodePlain(plainJson)
    }

    private fun encodePlain(records: List<SshConfigBackupRecord>): String {
        val servers = JSONArray()
        records.forEach { record ->
            val tunnelPresets = JSONArray()
            record.tunnelPresets.forEach { preset ->
                tunnelPresets.put(
                    JSONObject()
                        .put("name", preset.name.ifBlank { tunnelPresetDefaultName(preset.remoteHost, preset.remotePort) })
                        .putNullable("note", preset.note)
                        .put("remoteHost", preset.remoteHost.ifBlank { "127.0.0.1" })
                        .put("remotePort", preset.remotePort.coerceIn(1, 65535))
                        .put("localPort", preset.localPort.coerceIn(0, 65535))
                )
            }
            servers.put(
                JSONObject()
                    .put("name", record.name)
                    .put("host", record.host)
                    .put("port", record.port)
                    .put("username", record.username)
                    .put("authType", normalizedAuthType(record.authType))
                    .putNullable("password", record.password)
                    .putNullable("privateKey", record.privateKey)
                    .putNullable("workDirectory", record.workDirectory)
                    .putNullable("postConnectCommand", record.postConnectCommand)
                    .put("terminalFontSizeSp", record.terminalFontSizeSp.coerceIn(10, 20))
                    .putNullable("terminalWrapEnabled", record.terminalWrapEnabled)
                    .put("terminalTerm", record.terminalTerm.ifBlank { "xterm-256color" })
                    .putNullable("terminalShortcuts", record.terminalShortcuts)
                    .put("tunnelPresets", tunnelPresets)
            )
        }

        return JSONObject()
            .put("app", APP_NAME)
            .put("formatVersion", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("servers", servers)
            .toString(2)
    }

    private fun decodePlain(json: String): List<SshConfigBackupRecord> {
        val root = JSONObject(json)
        require(root.optString("app") == APP_NAME) { "This is not a QuickSSH backup file." }
        require(root.optInt("formatVersion", FORMAT_VERSION) <= FORMAT_VERSION) {
            "This QuickSSH backup format is newer than this app supports."
        }

        val servers = root.optJSONArray("servers")
            ?: throw IllegalArgumentException("Backup file has no servers list.")
        return buildList {
            for (index in 0 until servers.length()) {
                val item = servers.optJSONObject(index) ?: continue
                add(item.toBackupRecord())
            }
        }
    }

    fun mergeKey(record: SshConfigBackupRecord): String {
        return listOf(
            record.host.trim().lowercase(),
            record.port.toString(),
            record.username.trim(),
            normalizedAuthType(record.authType),
            record.name.trim(),
            record.workDirectory.orEmpty().trim(),
            record.postConnectCommand.orEmpty().trim()
        ).joinToString("|")
    }

    fun mergeKey(config: SshConfig): String {
        return listOf(
            config.host.trim().lowercase(),
            config.port.toString(),
            config.username.trim(),
            normalizedAuthType(config.authType),
            config.name.trim(),
            config.workDirectory.orEmpty().trim(),
            config.postConnectCommand.orEmpty().trim()
        ).joinToString("|")
    }

    private fun encryptBackupJson(plainJson: String, password: String): String {
        val salt = randomBytes(SALT_SIZE_BYTES)
        val iv = randomBytes(IV_SIZE_BYTES)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_SIZE_BITS, iv))
        val encryptedPayload = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        return JSONObject()
            .put("app", APP_NAME)
            .put("formatVersion", FORMAT_VERSION)
            .put("encrypted", true)
            .put(
                "encryption",
                JSONObject()
                    .put("kdf", KDF_ALGORITHM)
                    .put("cipher", CIPHER_ALGORITHM)
                    .put("iterations", PBKDF2_ITERATIONS)
                    .put("salt", encodeBytes(salt))
                    .put("iv", encodeBytes(iv))
            )
            .put("payload", encodeBytes(encryptedPayload))
            .toString(2)
    }

    private fun decryptBackupJson(root: JSONObject, password: String): String {
        val encryption = root.optJSONObject("encryption")
            ?: throw IllegalArgumentException("Encrypted backup is missing metadata.")
        require(encryption.optString("kdf") == KDF_ALGORITHM) { "Unsupported backup key format." }
        require(encryption.optString("cipher") == CIPHER_ALGORITHM) { "Unsupported backup encryption format." }
        val iterations = encryption.optInt("iterations", PBKDF2_ITERATIONS)
        val salt = decodeBytes(encryption.optString("salt"))
        val iv = decodeBytes(encryption.optString("iv"))
        val payload = decodeBytes(root.optString("payload"))

        return try {
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_SIZE_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("Backup password is incorrect or the file is corrupted.", e)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE_BITS)
        val keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    private fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also { SecureRandom().nextBytes(it) }
    }

    private fun encodeBytes(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeBytes(value: String): ByteArray {
        return Base64.getDecoder().decode(value)
    }

    private fun JSONObject.toBackupRecord(): SshConfigBackupRecord {
        val host = optString("host").trim()
        val username = optString("username").trim()
        val port = optInt("port", 22)
        require(host.isNotBlank()) { "A server entry is missing host." }
        require(username.isNotBlank()) { "A server entry is missing username." }
        require(port in 1..65535) { "A server entry has an invalid port." }

        val name = optString("name").trim().ifBlank { "$username@$host:$port" }
        val terminalTerm = optString("terminalTerm", "xterm-256color").trim().ifBlank { "xterm-256color" }

        return SshConfigBackupRecord(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = normalizedAuthType(optString("authType", AUTH_TYPE_PASSWORD)),
            password = optNullableString("password"),
            privateKey = optNullableString("privateKey"),
            workDirectory = optNullableString("workDirectory"),
            postConnectCommand = optNullableString("postConnectCommand"),
            terminalFontSizeSp = optInt("terminalFontSizeSp", 12).coerceIn(10, 20),
            terminalWrapEnabled = optNullableBoolean("terminalWrapEnabled"),
            terminalTerm = terminalTerm,
            terminalShortcuts = optNullableString("terminalShortcuts"),
            tunnelPresets = optTunnelPresets()
        )
    }

    private fun JSONObject.optTunnelPresets(): List<SshTunnelPresetBackupRecord> {
        val array = optJSONArray("tunnelPresets") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val remoteHost = item.optString("remoteHost", "127.0.0.1").trim().ifBlank { "127.0.0.1" }
                val remotePort = item.optInt("remotePort", -1)
                val localPort = item.optInt("localPort", 0)
                if (remotePort !in 1..65535 || localPort !in 0..65535) continue
                add(
                    SshTunnelPresetBackupRecord(
                        name = item.optString("name").trim().ifBlank { tunnelPresetDefaultName(remoteHost, remotePort) },
                        note = item.optNullableString("note"),
                        remoteHost = remoteHost,
                        remotePort = remotePort,
                        localPort = localPort
                    )
                )
            }
        }
    }

    private fun normalizedAuthType(authType: String): String {
        return if (authType == AUTH_TYPE_PRIVATE_KEY) AUTH_TYPE_PRIVATE_KEY else AUTH_TYPE_PASSWORD
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optBoolean(name)
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullable(name: String, value: Boolean?): JSONObject {
        return put(name, value ?: JSONObject.NULL)
    }
}

internal fun tunnelPresetDefaultName(remoteHost: String, remotePort: Int): String {
    val host = remoteHost.trim().ifBlank { "127.0.0.1" }
    return "$host:$remotePort"
}

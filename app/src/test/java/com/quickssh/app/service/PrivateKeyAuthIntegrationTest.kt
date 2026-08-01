package com.quickssh.app.service

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.Security
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

class PrivateKeyAuthIntegrationTest {
    @Test
    fun privateKeyAuthConnectsToDebugServerWhenCredentialEnvironmentIsAvailable() {
        val host = env("QUICKSSH_DEBUG_HOST")
        val username = env("QUICKSSH_DEBUG_USERNAME")
        val password = env("QUICKSSH_DEBUG_PASSWORD")
        val workDir = env("QUICKSSH_DEBUG_WORKDIR")
        assumeTrue(
            "Set QUICKSSH_DEBUG_HOST, QUICKSSH_DEBUG_USERNAME, QUICKSSH_DEBUG_PASSWORD, and QUICKSSH_DEBUG_WORKDIR to run real private-key SSH validation.",
            listOf(host, username, password, workDir).all { !it.isNullOrBlank() }
        )

        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        val tempDir = Files.createTempDirectory("quickssh-key-auth-")
        val marker = "quickssh-integration-${UUID.randomUUID()}"
        val keyPath = tempDir.resolve("id_rsa")
        val publicKeyPath = tempDir.resolve("id_rsa.pub")
        val targetFiles = linkedSetOf(
            authorizedKeysInUserHome(requireNotNull(workDir), requireNotNull(username)),
            "C:/ProgramData/ssh/administrators_authorized_keys"
        )

        try {
            generateRsaKeyPair(keyPath.toFile())
            val publicKeyLine = publicKeyPath.readText().trim() + " $marker"
            val privateKey = keyPath.readText()

            withPasswordClient(requireNotNull(host), requireNotNull(username), requireNotNull(password)) { client ->
                targetFiles.forEach { path ->
                    runCatching { appendAuthorizedKey(client, path, publicKeyLine) }
                }
                runCatching { tightenProgramDataAuthorizedKeysAcl(client) }
            }

            withPrivateKeyClient(host, username, privateKey) { client ->
                val output = execAndRead(client, "echo QUICKSSH_KEY_OK")
                assert(output.contains("QUICKSSH_KEY_OK")) {
                    "Private-key command output did not contain QUICKSSH_KEY_OK: $output"
                }
            }
        } finally {
            runCatching {
                withPasswordClient(requireNotNull(host), requireNotNull(username), requireNotNull(password)) { client ->
                    targetFiles.forEach { path ->
                        runCatching { removeAuthorizedKeyMarker(client, path, marker) }
                    }
                }
            }
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun generateRsaKeyPair(keyFile: File) {
        val process = ProcessBuilder(
            "ssh-keygen",
            "-t", "rsa",
            "-b", "3072",
            "-N", "",
            "-f", keyFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        check(finished && process.exitValue() == 0) { "ssh-keygen failed: $output" }
    }

    private fun withPasswordClient(host: String, username: String, password: String, block: (SSHClient) -> Unit) {
        SSHClient().use { client ->
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect(host, 22)
            client.authPassword(username, password)
            block(client)
        }
    }

    private fun withPrivateKeyClient(host: String, username: String, privateKey: String, block: (SSHClient) -> Unit) {
        SSHClient().use { client ->
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect(host, 22)
            client.authPublickey(username, client.loadKeys(privateKey, null, null))
            block(client)
        }
    }

    private fun appendAuthorizedKey(client: SSHClient, remotePath: String, publicKeyLine: String) {
        val tempFile = Files.createTempFile("quickssh-authorized-keys-", ".tmp").toFile()
        try {
            client.newSFTPClient().use { sftp ->
                runCatching { sftp.get(remotePath, tempFile.absolutePath) }
                    .onFailure { tempFile.writeText("", StandardCharsets.UTF_8) }
                val existing = tempFile.readText(StandardCharsets.UTF_8)
                if (!existing.contains(publicKeyLine)) {
                    tempFile.writeText(
                        existing.trimEnd() + System.lineSeparator() + publicKeyLine + System.lineSeparator(),
                        StandardCharsets.UTF_8
                    )
                    sftp.mkdirs(remotePath.substringBeforeLast('/'))
                    sftp.put(tempFile.absolutePath, remotePath)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun removeAuthorizedKeyMarker(client: SSHClient, remotePath: String, marker: String) {
        val tempFile = Files.createTempFile("quickssh-authorized-keys-cleanup-", ".tmp").toFile()
        try {
            client.newSFTPClient().use { sftp ->
                sftp.get(remotePath, tempFile.absolutePath)
                val cleaned = tempFile.readLines(StandardCharsets.UTF_8)
                    .filterNot { it.contains(marker) }
                    .joinToString(System.lineSeparator(), postfix = System.lineSeparator())
                tempFile.writeText(cleaned, StandardCharsets.UTF_8)
                sftp.put(tempFile.absolutePath, remotePath)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun execAndRead(client: SSHClient, commandLine: String): String {
        client.startSession().use { session ->
            val command = session.exec(commandLine)
            val stdout = command.inputStream.bufferedReader().readText()
            val stderr = command.errorStream.bufferedReader().readText()
            command.join(10, TimeUnit.SECONDS)
            check(command.exitStatus == 0) { "Command failed with ${command.exitStatus}: $stderr" }
            return stdout + stderr
        }
    }

    private fun tightenProgramDataAuthorizedKeysAcl(client: SSHClient) {
        execAndRead(
            client,
            "powershell -NoProfile -ExecutionPolicy Bypass -Command \"if (Test-Path 'C:\\ProgramData\\ssh\\administrators_authorized_keys') { icacls 'C:\\ProgramData\\ssh\\administrators_authorized_keys' /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F' | Out-Null }\""
        )
    }

    private fun authorizedKeysInUserHome(workDir: String, username: String): String {
        val normalized = workDir.replace('\\', '/')
        val marker = "/WorkBuddy/"
        val home = if (normalized.contains(marker)) {
            normalized.substringBefore(marker)
        } else {
            "C:/Users/$username"
        }
        return "${home.trimEnd('/')}/.ssh/authorized_keys"
    }
}

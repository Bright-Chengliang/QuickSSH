package com.quickssh.app.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.quickssh.app.data.SshConfig
import com.quickssh.app.security.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.TransferListener
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min
import java.security.Security
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

class FileTransferHelper(private val context: Context) {
    companion object {
        init {
            try {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            } catch (_: Exception) {}
        }
    }

    data class TransferProgress(
        val transferredBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSecond: Long
    )

    data class ProgressSample(
        val shouldEmit: Boolean,
        val bytesPerSecond: Long,
        val nextLastEmitAtMs: Long
    )

    data class RemoteEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long
    )

    data class RemoteTreeEntry(
        val name: String,
        val path: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long
    )

    enum class UploadConflictPolicy {
        RENAME,
        OVERWRITE,
        FAIL
    }

    suspend fun uploadFile(
        config: SshConfig,
        localUri: Uri,
        remotePathInput: String,
        conflictPolicy: UploadConflictPolicy = UploadConflictPolicy.RENAME,
        onProgress: (TransferProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val localName = safeTransferFileName(displayName(localUri).ifBlank { "quickssh-upload.bin" })
            val localTemp = File(context.cacheDir, "quickssh-upload-${System.currentTimeMillis()}-$localName")
            context.contentResolver.openInputStream(localUri).use { input ->
                requireNotNull(input) { "\u65e0\u6cd5\u8bfb\u53d6\u672c\u5730\u6587\u4ef6" }
                localTemp.outputStream().use { output -> input.copyTo(output) }
            }

            try {
                var selectedRemoteTarget: String? = null
                retryTransientSftpTransfer {
                    withSftp(config) { sftp ->
                        val remoteTarget = selectedRemoteTarget
                            ?: uploadTargetPath(remotePathInput, config.workDirectory, localName, sftp, conflictPolicy)
                                .also { selectedRemoteTarget = it }
                        sftp.fileTransfer.transferListener = progressListener(onProgress) { coroutineContext.isActive }
                        coroutineContext.ensureActive()
                        sftp.put(localTemp.absolutePath, remoteTarget)
                        remoteTarget
                    }
                }
            } finally {
                localTemp.delete()
            }
        }.mapError()
    }

    suspend fun downloadFile(
        config: SshConfig,
        remotePathInput: String,
        destinationUri: Uri,
        onProgress: (TransferProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val remotePath = remotePathInput.trim()
            require(remotePath.isNotBlank()) { "\u4e0b\u8f7d\u65f6\u5fc5\u987b\u586b\u5199\u8fdc\u7aef\u6587\u4ef6\u8def\u5f84" }

            val localTemp = File(context.cacheDir, "quickssh-download-${System.currentTimeMillis()}-${remotePath.substringAfterLast('/').ifBlank { "file" }}")
            try {
                retryTransientSftpTransfer {
                    localTemp.delete()
                    withSftp(config) { sftp ->
                        sftp.fileTransfer.transferListener = progressListener(onProgress) { coroutineContext.isActive }
                        coroutineContext.ensureActive()
                        require(!isRemoteDirectory(sftp, remotePath)) {
                            "Directory download is not supported by a single-file transfer. Select the folder from the browser to download it recursively."
                        }
                        sftp.get(remotePath, localTemp.absolutePath)
                    }
                }
                coroutineContext.ensureActive()
                val destinationOutput = runCatching {
                    context.contentResolver.openOutputStream(destinationUri, "wt")
                }.getOrNull() ?: context.contentResolver.openOutputStream(destinationUri)
                destinationOutput.use { output ->
                    requireNotNull(output) { "\u65e0\u6cd5\u8bfb\u53d6\u672c\u5730\u6587\u4ef6" }
                    localTemp.inputStream().use { input -> input.copyTo(output) }
                }
                remotePath
            } finally {
                localTemp.delete()
            }
        }.mapError()
    }

    suspend fun listRemoteDirectory(config: SshConfig, remotePathInput: String): Result<List<RemoteEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val remotePath = remotePathInput.trim().ifBlank { config.workDirectory?.trim().orEmpty() }.ifBlank { "." }
            withSftp(config) { sftp ->
                sftp.ls(remotePath)
                    .filter { it.name != "." && it.name != ".." }
                    .sortedWith(compareByDescending<RemoteResourceInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
                    .map {
                        RemoteEntry(
                            name = it.name,
                            path = it.path,
                            isDirectory = it.isDirectory,
                            size = if (it.isRegularFile) it.attributes.size else 0L
                        )
                    }
            }
        }.mapError()
    }

    suspend fun listRemoteTree(config: SshConfig, roots: List<RemoteEntry>): Result<List<RemoteTreeEntry>> = withContext(Dispatchers.IO) {
        try {
            Result.success(
                withSftp(config) { sftp ->
                buildList {
                    roots.distinctBy { normalizedRemotePathKey(it.path) ?: it.path }.forEach { root ->
                        addRemoteTreeEntry(
                            sftp = sftp,
                            remotePath = root.path,
                            relativePath = safeRemoteRelativeSegment(root.name.ifBlank { remotePathName(root.path) }.ifBlank { "remote-item" }),
                            isDirectoryHint = root.isDirectory,
                            sizeHint = root.size,
                            entries = this,
                            isActive = { coroutineContext.isActive }
                        )
                    }
                }
            }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(IOException(friendlyError(error), error))
        }
    }

    suspend fun testConnection(config: SshConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withSftp(config) { sftp ->
                val remotePath = config.workDirectory?.trim().orEmpty().ifBlank { "." }
                sftp.stat(remotePath)
                Unit
            }
        }.mapError()
    }

    suspend fun uploadFileToDirectory(
        config: SshConfig,
        localUri: Uri,
        remoteDirectoryInput: String,
        conflictPolicy: UploadConflictPolicy = UploadConflictPolicy.RENAME,
        onProgress: (TransferProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val localName = safeTransferFileName(displayName(localUri).ifBlank { "quickssh-upload.bin" })
            val localTemp = File(context.cacheDir, "quickssh-upload-${System.currentTimeMillis()}-$localName")
            context.contentResolver.openInputStream(localUri).use { input ->
                requireNotNull(input) { "\u65e0\u6cd5\u8bfb\u53d6\u672c\u5730\u6587\u4ef6" }
                localTemp.outputStream().use { output -> input.copyTo(output) }
            }

            try {
                var selectedRemoteTarget: String? = null
                retryTransientSftpTransfer {
                    withSftp(config) { sftp ->
                        val remoteDirectory = normalizeSftpPath(remoteDirectoryInput.trim().ifBlank { "." })
                        ensureRemoteDirectory(sftp, remoteDirectory)
                        val remoteTarget = selectedRemoteTarget
                            ?: uploadTargetFilePath(remoteDirectory, localName, sftp, conflictPolicy)
                                .also { selectedRemoteTarget = it }
                        sftp.fileTransfer.transferListener = progressListener(onProgress) { coroutineContext.isActive }
                        coroutineContext.ensureActive()
                        sftp.put(localTemp.absolutePath, remoteTarget)
                        remoteTarget
                    }
                }
            } finally {
                localTemp.delete()
            }
        }.mapError()
    }

    fun friendlyError(throwable: Throwable): String {
        val text = listOfNotNull(throwable.message, throwable.cause?.message).joinToString(" ").lowercase()
        return when {
            throwable is UnknownHostException -> "\u627e\u4e0d\u5230\u670d\u52a1\u5668\uff0c\u8bf7\u68c0\u67e5 host"
            throwable is ConnectException -> "\u670d\u52a1\u5668\u62d2\u7edd\u8fde\u63a5\u6216\u7f51\u7edc\u4e0d\u53ef\u8fbe"
            throwable is SocketTimeoutException -> "\u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u548c\u7aef\u53e3"
            text.contains("directory download is not supported") -> "Directory download is not supported yet. Choose a file or browse into the directory."
            text.contains("auth") || text.contains("authentication") || text.contains("password") || text.contains("private key") -> "Authentication failed. Check password or private key."
            text.contains("permission denied") || text.contains("permission") -> "\u6ca1\u6709\u6743\u9650\u8bbf\u95ee\u8be5\u8def\u5f84"
            text.contains("no such file") || text.contains("does not exist") || text.contains("not found") -> "\u8fdc\u7aef\u6587\u4ef6\u4e0d\u5b58\u5728"
            text.contains("connection refused") -> "\u670d\u52a1\u5668\u62d2\u7edd\u8fde\u63a5"
            text.contains("network is unreachable") || text.contains("software caused connection abort") -> "\u7f51\u7edc\u8fde\u63a5\u4e2d\u65ad"
            text.contains("cancel") || text.contains("job was cancelled") -> "\u4f20\u8f93\u5df2\u53d6\u6d88"
            throwable.message.isNullOrBlank() -> throwable.javaClass.simpleName
            else -> throwable.message ?: throwable.javaClass.simpleName
        }
    }

    private fun progressListener(onProgress: (TransferProgress) -> Unit, isActive: () -> Boolean): TransferListener {
        return object : TransferListener {
            override fun directory(name: String): TransferListener = this

            override fun file(name: String, size: Long): StreamCopier.Listener {
                val startedAt = System.currentTimeMillis()
                var lastEmitAt = 0L
                return StreamCopier.Listener { transferred ->
                    if (!isActive()) throw IOException("\u4f20\u8f93\u5df2\u53d6\u6d88")
                    val now = System.currentTimeMillis()
                    val sample = progressSample(transferred, size, startedAt, lastEmitAt, now)
                    if (sample.shouldEmit) {
                        lastEmitAt = sample.nextLastEmitAtMs
                        onProgress(
                            TransferProgress(
                                transferredBytes = transferred,
                                totalBytes = size,
                                speedBytesPerSecond = sample.bytesPerSecond
                            )
                        )
                    }
                }
            }
        }
    }

    private fun <T> withSftp(config: SshConfig, block: (SFTPClient) -> T): T {
        SSHClient().use { client ->
            client.addHostKeyVerifier(KnownHostsVerifier(context))
            client.connect(config.host, config.port)
            client.connection.keepAlive.keepAliveInterval = 20
            authenticate(client, config)
            client.newSFTPClient().use { sftp ->
                return block(sftp)
            }
        }
    }

    private fun authenticate(client: SSHClient, config: SshConfig) {
        if (config.authType == AUTH_TYPE_PRIVATE_KEY) {
            val encryptedPrivateKey = config.encryptedPrivateKey
            require(!encryptedPrivateKey.isNullOrBlank()) { "Server profile has no saved private key" }
            val privateKey = KeystoreManager.decrypt(encryptedPrivateKey)
            require(privateKey.isNotBlank()) { "Private key decrypted to an empty value. Re-edit the server key." }
            client.authPublickey(config.username, client.loadKeys(privateKey, null, null))
        } else {
            val encryptedPassword = config.encryptedPassword
            require(!encryptedPassword.isNullOrBlank()) { "Server profile has no saved password" }
            val password = KeystoreManager.decrypt(encryptedPassword)
            require(password.isNotEmpty()) { "Password decrypted to an empty value. Re-edit the server password." }
            client.authPassword(config.username, password)
        }
    }

    private fun uploadTargetPath(
        remotePathInput: String,
        workDirectory: String?,
        fileName: String,
        sftp: SFTPClient,
        conflictPolicy: UploadConflictPolicy
    ): String {
        val base = remotePathInput.trim().ifBlank { workDirectory?.trim().orEmpty() }.ifBlank { "." }
        val initialTarget = when {
            base.endsWith("/") -> base + fileName
            isRemoteDirectory(sftp, base) -> "$base/$fileName"
            else -> base
        }
        if (!remotePathExists(sftp, initialTarget)) return initialTarget

        return when (conflictPolicy) {
            UploadConflictPolicy.OVERWRITE -> initialTarget
            UploadConflictPolicy.FAIL -> throw IOException("Remote file already exists: $initialTarget")
            UploadConflictPolicy.RENAME -> nextAvailableRemotePath(sftp, initialTarget)
        }
    }

    private fun uploadTargetFilePath(
        remoteDirectory: String,
        fileName: String,
        sftp: SFTPClient,
        conflictPolicy: UploadConflictPolicy
    ): String {
        val initialTarget = remoteChildPath(remoteDirectory, fileName)
        if (!remotePathExists(sftp, initialTarget)) return initialTarget

        return when (conflictPolicy) {
            UploadConflictPolicy.OVERWRITE -> initialTarget
            UploadConflictPolicy.FAIL -> throw IOException("Remote file already exists: $initialTarget")
            UploadConflictPolicy.RENAME -> nextAvailableRemotePath(sftp, initialTarget)
        }
    }

    private fun isRemoteDirectory(sftp: SFTPClient, remotePath: String): Boolean {
        return runCatching { sftp.type(remotePath).name == "DIRECTORY" }.getOrDefault(false)
    }

    private fun ensureRemoteDirectory(sftp: SFTPClient, remoteDirectory: String) {
        val normalized = normalizeSftpPath(remoteDirectory).trimEnd('/').ifBlank { "." }
        if (normalized == "." || isRemoteDirectory(sftp, normalized)) return
        val mkdirResult = runCatching { sftp.mkdirs(normalized) }
        if (mkdirResult.isSuccess || isRemoteDirectory(sftp, normalized)) return
        throw mkdirResult.exceptionOrNull() ?: IOException("Unable to create remote directory: $normalized")
    }

    private fun addRemoteTreeEntry(
        sftp: SFTPClient,
        remotePath: String,
        relativePath: String,
        isDirectoryHint: Boolean,
        sizeHint: Long,
        entries: MutableList<RemoteTreeEntry>,
        isActive: () -> Boolean
    ) {
        if (!isActive()) throw CancellationException("\u4f20\u8f93\u5df2\u53d6\u6d88")
        val isDirectory = isDirectoryHint || isRemoteDirectory(sftp, remotePath)
        entries.add(
            RemoteTreeEntry(
                name = remotePathName(remotePath).ifBlank { relativePath.substringAfterLast('/') },
                path = remotePath,
                relativePath = relativePath,
                isDirectory = isDirectory,
                size = if (isDirectory) 0L else sizeHint.coerceAtLeast(0L)
            )
        )
        if (isDirectory) {
            sftp.ls(remotePath)
                .filter { it.name != "." && it.name != ".." }
                .sortedWith(compareByDescending<RemoteResourceInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { child ->
                    addRemoteTreeEntry(
                        sftp = sftp,
                        remotePath = child.path,
                        relativePath = relativePath.trimEnd('/') + "/" + safeRemoteRelativeSegment(child.name),
                        isDirectoryHint = child.isDirectory,
                        sizeHint = if (child.isRegularFile) child.attributes.size else 0L,
                        entries = entries,
                        isActive = isActive
                    )
                }
        }
    }

    private fun remotePathName(remotePath: String): String {
        val normalized = remotePath.trim().trimEnd('/', '\\')
        val separatorIndex = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
        return if (separatorIndex >= 0) normalized.substring(separatorIndex + 1) else normalized
    }

    private fun normalizedRemotePathKey(remotePath: String): String? {
        val normalized = remotePath.trim().replace('\\', '/')
        if (normalized.isBlank()) return null
        return normalized.trimEnd('/').ifBlank { normalized }
    }

    private fun safeRemoteRelativeSegment(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "item" }
            .take(120)
    }

    private fun remotePathExists(sftp: SFTPClient, remotePath: String): Boolean {
        return runCatching { sftp.stat(remotePath) }.isSuccess
    }

    private fun nextAvailableRemotePath(sftp: SFTPClient, remotePath: String): String {
        val slashIndex = remotePath.lastIndexOf('/')
        val directory = if (slashIndex >= 0) remotePath.substring(0, slashIndex + 1) else ""
        val name = if (slashIndex >= 0) remotePath.substring(slashIndex + 1) else remotePath
        val dotIndex = name.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < name.lastIndex
        val baseName = if (hasExtension) name.substring(0, dotIndex) else name
        val extension = if (hasExtension) name.substring(dotIndex) else ""

        var index = 2
        while (true) {
            val candidate = "$directory$baseName ($index)$extension"
            if (!remotePathExists(sftp, candidate)) return candidate
            index++
        }
    }

    private fun <T> retryTransientSftpTransfer(block: () -> T): T {
        return retryTransientTransfer(
            maxAttempts = 3,
            shouldRetry = ::isTransientTransferError,
            sleepMillis = { Thread.sleep(it) },
            block = block
        )
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
        }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private fun safeTransferFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "quickssh-upload.bin" }
            .take(160)
    }

    private fun <T> Result<T>.mapError(): Result<T> {
        return fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(IOException(friendlyError(it), it)) }
        )
    }
}

internal enum class TransferQueueAction {
    ENQUEUE,
    PUMP
}

internal enum class TransferQueueDecision {
    START_NOW,
    QUEUED,
    START_NEXT
}

internal data class TransferQueueResult(
    val queueSize: Int,
    val decision: TransferQueueDecision?
)

internal fun nextTransferQueueState(
    busy: Boolean,
    paused: Boolean,
    queueSize: Int,
    action: TransferQueueAction
): TransferQueueResult {
    val safeQueueSize = queueSize.coerceAtLeast(0)
    return when (action) {
        TransferQueueAction.ENQUEUE -> {
            if (!busy && !paused && safeQueueSize == 0) {
                TransferQueueResult(queueSize = 0, decision = TransferQueueDecision.START_NOW)
            } else {
                TransferQueueResult(queueSize = safeQueueSize + 1, decision = TransferQueueDecision.QUEUED)
            }
        }
        TransferQueueAction.PUMP -> {
            if (!busy && !paused && safeQueueSize > 0) {
                TransferQueueResult(queueSize = safeQueueSize - 1, decision = TransferQueueDecision.START_NEXT)
            } else {
                TransferQueueResult(queueSize = safeQueueSize, decision = null)
            }
        }
    }
}

internal fun progressSample(
    transferredBytes: Long,
    totalBytes: Long,
    startedAtMs: Long,
    lastEmitAtMs: Long,
    nowMs: Long,
    minIntervalMs: Long = 250L
): FileTransferHelper.ProgressSample {
    val elapsedMs = abs(nowMs - startedAtMs).coerceAtLeast(1L)
    val speed = transferredBytes.coerceAtLeast(0L) * 1000L / elapsedMs
    val shouldEmit = transferredBytes >= totalBytes || nowMs - lastEmitAtMs >= minIntervalMs
    return FileTransferHelper.ProgressSample(shouldEmit, speed, if (shouldEmit) nowMs else lastEmitAtMs)
}

internal fun uploadConflictPolicyFromName(name: String?): FileTransferHelper.UploadConflictPolicy {
    return FileTransferHelper.UploadConflictPolicy.values().firstOrNull { policy ->
        policy.name.equals(name, ignoreCase = true)
    } ?: FileTransferHelper.UploadConflictPolicy.RENAME
}

internal fun remoteChildPath(parent: String, child: String): String {
    val trimmedParent = parent.trim().trimEnd('/', '\\')
    val trimmedChild = child.trim().trimStart('/', '\\')
    if (trimmedParent.isBlank()) return trimmedChild
    val separator = remotePathSeparator(trimmedParent)
    return "$trimmedParent$separator$trimmedChild"
}

internal fun remotePathSeparator(parent: String): String {
    val trimmedParent = parent.trim()
    return if (isWindowsRemotePath(trimmedParent) && trimmedParent.contains('\\') && !trimmedParent.contains('/')) {
        "\\"
    } else {
        "/"
    }
}

internal fun terminalQuickUploadDirectory(workDirectory: String?): String {
    val base = normalizeSftpPath(workDirectory?.trim().orEmpty().ifBlank { "." })
    if (base == ".") return ".QuickSSH/upload"
    return remoteChildPath(remoteChildPath(base, ".QuickSSH"), "upload")
}

internal fun normalizeSftpPath(path: String): String {
    return path.trim().replace('\\', '/')
}

internal fun isWindowsRemotePath(path: String): Boolean {
    val trimmed = path.trim()
    return Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed) || trimmed.startsWith("\\\\")
}

internal fun isTransientTransferError(error: Throwable): Boolean {
    val text = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    return error is SocketTimeoutException ||
        text.contains("timeout") ||
        text.contains("timed out") ||
        text.contains("connection reset") ||
        text.contains("connection abort") ||
        text.contains("broken pipe") ||
        text.contains("network") ||
        text.contains("socket") ||
        text.contains("transport closed") ||
        text.contains("connection closed")
}

internal fun <T> retryTransientTransfer(
    maxAttempts: Int = 3,
    shouldRetry: (Throwable) -> Boolean,
    sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    block: () -> T
): T {
    val attempts = maxAttempts.coerceAtLeast(1)
    var lastError: Throwable? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (error: Exception) {
            lastError = error
            if (attempt == attempts - 1 || !shouldRetry(error)) throw error
            sleepMillis(min(1_000L, 250L * (attempt + 1)))
        }
    }
    throw lastError ?: IOException("Transfer failed")
}


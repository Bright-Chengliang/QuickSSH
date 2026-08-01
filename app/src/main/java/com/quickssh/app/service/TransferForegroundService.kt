package com.quickssh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.quickssh.app.MainActivity
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.TRANSFER_HISTORY_RETAIN_LIMIT
import com.quickssh.app.data.TransferHistoryEntry
import com.quickssh.app.data.serverNodeLabel
import com.quickssh.app.data.transferContextLabel
import com.quickssh.app.data.workspaceLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class TransferServiceState(
    val isRunning: Boolean = false,
    val taskId: Long? = null,
    val direction: String = "",
    val fileName: String = "",
    val statusText: String = "等待中",
    val progressText: String = "",
    val progressFraction: Float? = null
)

class TransferForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var transferJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRANSFER -> {
                val notification = createNotification(
                    title = "QuickSSH transfer",
                    message = "Preparing transfer...",
                    progressFraction = null,
                    ongoing = true
                )
                if (!startForegroundSafely(notification)) {
                    publishState(TransferServiceState(statusText = "失败：无法启动后台传输服务"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTransfer(intent)
            }
            ACTION_CANCEL_TRANSFER -> cancelTransfer()
        }
        return START_NOT_STICKY
    }

    private fun startTransfer(intent: Intent) {
        if (transferJob?.isActive == true) {
            publishState(
                transferState.value.copy(
                    statusText = "失败：已有传输正在后台运行",
                    progressText = ""
                )
            )
            return
        }

        val request = TransferRequest.from(intent) ?: run {
            publishState(TransferServiceState(statusText = "失败：传输参数不完整"))
            stopForegroundCompat(removeNotification = true)
            stopSelf()
            return
        }

        transferJob = serviceScope.launch {
            runTransfer(request)
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return runCatching {
            startForeground(NOTIFICATION_ID, notification)
        }.isSuccess
    }

    private suspend fun runTransfer(request: TransferRequest) {
        val database = AppDatabase.getDatabase(applicationContext)
        val config = database.sshConfigDao().getConfigById(request.configId)
        if (config == null) {
            val message = "Saved server profile was not found"
            publishState(TransferServiceState(statusText = "失败：$message"))
            showFinalNotification(request, "Transfer failed: $message")
            stopForegroundCompat(removeNotification = false)
            stopSelf()
            return
        }

        val dao = database.transferHistoryDao()
        val taskId = dao.insert(request.toHistoryEntry(config))
        dao.trimToLimit(TRANSFER_HISTORY_RETAIN_LIMIT)
        publishState(
            TransferServiceState(
                isRunning = true,
                taskId = taskId,
                direction = request.direction,
                fileName = request.fileName,
                statusText = transferRunningStatus(request.direction),
                progressText = "Preparing ${request.direction.lowercase()}...",
                progressFraction = null
            )
        )

        try {
            persistIncomingUriPermission(request)
            val helper = FileTransferHelper(applicationContext)
            val result = if (request.isUpload) {
                val sourceUri = requireNotNull(request.localUri) { "Missing local file for upload" }
                helper.uploadFile(config, sourceUri, request.remotePath, request.uploadConflictPolicy) { progress ->
                    publishProgress(request, taskId, progress)
                }
            } else {
                val destinationUri = requireNotNull(request.destinationUri) { "Missing destination file for download" }
                helper.downloadFile(config, request.remotePath, destinationUri) { progress ->
                    publishProgress(request, taskId, progress)
                }
            }

            result.fold(
                onSuccess = { completedPath ->
                    updateTaskStatus(
                        taskId = taskId,
                        status = "Success",
                        completedRemotePath = completedPath
                    )
                    val status = transferSuccessStatus(request.direction, completedPath)
                    publishState(
                        transferState.value.copy(
                            isRunning = false,
                            statusText = status,
                            progressText = "",
                            progressFraction = 1f
                        )
                    )
                    showFinalNotification(request, "${request.direction} complete: $completedPath")
                },
                onFailure = { error ->
                    failTransfer(taskId, request, error)
                }
            )
        } catch (error: CancellationException) {
            updateTaskStatus(taskId, "Canceled", "传输已取消")
            publishState(
                transferState.value.copy(
                    isRunning = false,
                    statusText = "传输已取消",
                    progressText = "",
                    progressFraction = null
                )
            )
            showFinalNotification(request, "Transfer canceled")
        } catch (error: Throwable) {
            failTransfer(taskId, request, error)
        } finally {
            transferJob = null
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        }
    }

    private fun persistIncomingUriPermission(request: TransferRequest) {
        val uri = if (request.isUpload) request.localUri else request.destinationUri
        val flags = transferUriGrantFlags(request.localUri, request.destinationUri)
        if (uri == null || flags == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    private fun publishProgress(
        request: TransferRequest,
        taskId: Long,
        progress: FileTransferHelper.TransferProgress
    ) {
        val total = progress.totalBytes
        val percent = if (total > 0L) {
            progress.transferredBytes.toFloat() / total.toFloat()
        } else {
            null
        }
        val text = transferProgressText(progress)

        publishState(
            TransferServiceState(
                isRunning = true,
                taskId = taskId,
                direction = request.direction,
                fileName = request.fileName,
                statusText = transferRunningStatus(request.direction),
                progressText = text,
                progressFraction = percent
            )
        )
        notifyProgress(request, text, percent)
    }

    private suspend fun failTransfer(taskId: Long, request: TransferRequest, error: Throwable) {
        val message = error.localizedMessage ?: error.javaClass.simpleName
        updateTaskStatus(taskId, "Failed", message)
        publishState(
            transferState.value.copy(
                isRunning = false,
                statusText = "失败：$message",
                progressText = "",
                progressFraction = null
            )
        )
        showFinalNotification(request, "Transfer failed: $message")
    }

    private suspend fun updateTaskStatus(
        taskId: Long,
        status: String,
        detail: String = "",
        completedRemotePath: String? = null
    ) {
        val dao = AppDatabase.getDatabase(applicationContext).transferHistoryDao()
        val existing = dao.getById(taskId) ?: return
        dao.update(
            completedTransferHistoryEntry(
                existing = existing,
                status = status,
                detail = detail,
                completedRemotePath = completedRemotePath,
                updateTime = System.currentTimeMillis()
            )
        )
    }

    private fun cancelTransfer() {
        transferJob?.cancel()
    }

    private fun notifyProgress(request: TransferRequest, message: String, progressFraction: Float?) {
        notificationManager().notify(
            NOTIFICATION_ID,
            createNotification(
                title = "QuickSSH ${request.direction.lowercase()}",
                message = message,
                progressFraction = progressFraction,
                ongoing = true
            )
        )
    }

    private fun showFinalNotification(request: TransferRequest, message: String) {
        notificationManager().notify(
            NOTIFICATION_ID,
            createNotification(
                title = "QuickSSH ${request.direction.lowercase()}",
                message = message,
                progressFraction = null,
                ongoing = false
            )
        )
    }

    private fun createNotification(
        title: String,
        message: String,
        progressFraction: Float?,
        ongoing: Boolean
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)

        if (ongoing) {
            val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
                action = ACTION_CANCEL_TRANSFER
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                1,
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
        }

        when {
            ongoing && progressFraction == null -> builder.setProgress(0, 0, true)
            progressFraction != null -> builder.setProgress(100, (progressFraction.coerceIn(0f, 1f) * 100).roundToInt(), false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Transfer Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(removeNotification)
        }
    }

    override fun onDestroy() {
        transferJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class TransferRequest(
        val direction: String,
        val configId: Long,
        val localUri: Uri?,
        val destinationUri: Uri?,
        val remotePath: String,
        val fileName: String,
        val uploadConflictPolicy: FileTransferHelper.UploadConflictPolicy
    ) {
        val isUpload: Boolean = direction == DIRECTION_UPLOAD

        fun toHistoryEntry(config: SshConfig): TransferHistoryEntry {
            return TransferHistoryEntry(
                fileName = fileName,
                direction = direction,
                serverName = config.transferContextLabel(),
                status = "Transferring",
                serverNodeName = config.serverNodeLabel(),
                workspaceName = config.workspaceLabel(),
                localUri = (localUri ?: destinationUri)?.toString(),
                remotePath = remotePath
            )
        }

        companion object {
            fun from(intent: Intent): TransferRequest? {
                val direction = intent.getStringExtra(EXTRA_DIRECTION)?.takeIf {
                    it == DIRECTION_UPLOAD || it == DIRECTION_DOWNLOAD
                } ?: return null
                val configId = intent.getLongExtra(EXTRA_CONFIG_ID, -1L).takeIf { it > 0L } ?: return null
                val remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH).orEmpty()
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty().ifBlank { "quickssh-transfer" }
                val uploadConflictPolicy = uploadConflictPolicyFromName(intent.getStringExtra(EXTRA_UPLOAD_CONFLICT_POLICY))
                val localUri = intent.getStringExtra(EXTRA_LOCAL_URI)?.let(Uri::parse)
                val destinationUri = intent.getStringExtra(EXTRA_DESTINATION_URI)?.let(Uri::parse)
                if (direction == DIRECTION_UPLOAD && localUri == null) return null
                if (direction == DIRECTION_DOWNLOAD && destinationUri == null) return null
                return TransferRequest(direction, configId, localUri, destinationUri, remotePath, fileName, uploadConflictPolicy)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "QuickSshTransferForegroundChannel"
        private const val NOTIFICATION_ID = 4043
        private const val ACTION_START_TRANSFER = "com.quickssh.app.action.START_TRANSFER"
        private const val ACTION_CANCEL_TRANSFER = "com.quickssh.app.action.CANCEL_TRANSFER"
        private const val EXTRA_DIRECTION = "extra_direction"
        private const val EXTRA_CONFIG_ID = "extra_config_id"
        private const val EXTRA_LOCAL_URI = "extra_local_uri"
        private const val EXTRA_DESTINATION_URI = "extra_destination_uri"
        private const val EXTRA_REMOTE_PATH = "extra_remote_path"
        private const val EXTRA_FILE_NAME = "extra_file_name"
        private const val EXTRA_UPLOAD_CONFLICT_POLICY = "extra_upload_conflict_policy"
        const val DIRECTION_UPLOAD = "Upload"
        const val DIRECTION_DOWNLOAD = "Download"

        private val _transferState = MutableStateFlow(TransferServiceState())
        val transferState: StateFlow<TransferServiceState> = _transferState.asStateFlow()

        fun startTransfer(
            context: Context,
            direction: String,
            configId: Long,
            localUri: Uri?,
            destinationUri: Uri?,
            remotePath: String,
            fileName: String,
            uploadConflictPolicy: FileTransferHelper.UploadConflictPolicy = FileTransferHelper.UploadConflictPolicy.RENAME
        ) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_START_TRANSFER
                putExtra(EXTRA_DIRECTION, direction)
                putExtra(EXTRA_CONFIG_ID, configId)
                localUri?.let { putExtra(EXTRA_LOCAL_URI, it.toString()) }
                destinationUri?.let { putExtra(EXTRA_DESTINATION_URI, it.toString()) }
                putExtra(EXTRA_REMOTE_PATH, remotePath)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_UPLOAD_CONFLICT_POLICY, uploadConflictPolicy.name)
                val grantUri = transferGrantUri(localUri, destinationUri)
                val grantFlags = transferUriGrantFlags(localUri, destinationUri)
                if (grantUri != null && grantFlags != 0) {
                    clipData = ClipData.newUri(context.contentResolver, "QuickSSH transfer", grantUri)
                    addFlags(grantFlags)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelTransfer(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_CANCEL_TRANSFER
            }
            context.startService(intent)
        }

        private fun publishState(state: TransferServiceState) {
            _transferState.value = state
        }

        internal fun transferUriGrantFlags(localUri: Uri?, destinationUri: Uri?): Int {
            return transferUriGrantFlags(hasLocalUri = localUri != null, hasDestinationUri = destinationUri != null)
        }

        internal fun transferUriGrantFlags(hasLocalUri: Boolean, hasDestinationUri: Boolean): Int {
            return when {
                hasDestinationUri -> Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                hasLocalUri -> Intent.FLAG_GRANT_READ_URI_PERMISSION
                else -> 0
            }
        }

        private fun transferGrantUri(localUri: Uri?, destinationUri: Uri?): Uri? {
            return destinationUri ?: localUri
        }
    }
}

internal fun transferRunningStatus(direction: String): String {
    return if (direction == TransferForegroundService.DIRECTION_UPLOAD) {
        "传输中：正在上传..."
    } else {
        "传输中：正在下载..."
    }
}

internal fun transferSuccessStatus(direction: String, completedPath: String): String {
    return if (direction == TransferForegroundService.DIRECTION_UPLOAD) {
        "成功：已上传到 $completedPath"
    } else {
        "成功：已下载 $completedPath"
    }
}

internal fun transferProgressText(progress: FileTransferHelper.TransferProgress): String {
    val total = progress.totalBytes
    return if (total > 0L) {
        val percent = progress.transferredBytes.toFloat() / total.toFloat()
        "${(percent * 100).roundToInt()}% - ${formatTransferBytes(progress.transferredBytes)} / ${formatTransferBytes(total)} - ${formatTransferBytes(progress.speedBytesPerSecond)}/s"
    } else {
        "${formatTransferBytes(progress.transferredBytes)} - ${formatTransferBytes(progress.speedBytesPerSecond)}/s"
    }
}

internal fun completedTransferHistoryEntry(
    existing: TransferHistoryEntry,
    status: String,
    detail: String = "",
    completedRemotePath: String? = null,
    updateTime: Long = System.currentTimeMillis()
): TransferHistoryEntry {
    return existing.copy(
        status = status,
        detail = detail.ifBlank { existing.detail },
        remotePath = completedRemotePath?.takeIf { it.isNotBlank() } ?: existing.remotePath,
        updateTime = updateTime
    )
}

private fun formatTransferBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

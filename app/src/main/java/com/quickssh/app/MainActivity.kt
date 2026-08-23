// QuickSSH - Android SSH client
// Copyright (c) 2026 Chengliang Liu
// Author: https://github.com/Bright-Chengliang
// License: MIT License (see LICENSE)

package com.quickssh.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.BackHandler
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executor
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.SshConfigBackupCodec
import com.quickssh.app.data.SshConfigBackupRecord
import com.quickssh.app.data.SshTunnelPreset
import com.quickssh.app.data.SshTunnelPresetBackupRecord
import com.quickssh.app.data.TransferHistoryEntry
import com.quickssh.app.data.TRANSFER_HISTORY_RETAIN_LIMIT
import com.quickssh.app.data.copiedWorkspaceName
import com.quickssh.app.data.serverIdentityKey
import com.quickssh.app.data.serverNodeLabel
import com.quickssh.app.data.tunnelPresetDefaultName
import com.quickssh.app.data.transferContextLabel
import com.quickssh.app.data.workspaceLabel
import com.quickssh.app.security.KeystoreManager
import com.quickssh.app.service.FileTransferHelper
import com.quickssh.app.service.KnownHostsVerifier
import com.quickssh.app.service.SshForegroundService
import com.quickssh.app.service.AUTH_TYPE_PASSWORD
import com.quickssh.app.service.AUTH_TYPE_PRIVATE_KEY
import com.quickssh.app.service.SshSessionInfo
import com.quickssh.app.service.TransferForegroundService
import com.quickssh.app.service.TunnelForegroundService
import com.quickssh.app.service.TunnelStatus
import com.quickssh.app.service.terminalQuickUploadDirectory
import com.quickssh.app.ui.screens.ActiveSessionsScreen
import com.quickssh.app.ui.screens.AppLanguage
import com.quickssh.app.ui.screens.LocalQuickSshLanguage
import com.quickssh.app.ui.screens.QuickSshBottomBar
import com.quickssh.app.ui.screens.SettingsScreen
import com.quickssh.app.ui.screens.SshAddScreen
import com.quickssh.app.ui.screens.SshListScreen
import com.quickssh.app.ui.screens.TerminalScreen
import com.quickssh.app.ui.screens.TransferScreen
import com.quickssh.app.ui.screens.RemoteDownloadPlanConfirmationUiState
import com.quickssh.app.ui.screens.TransferTaskUiState
import com.quickssh.app.ui.screens.TunnelScreen
import com.quickssh.app.ui.screens.TunnelWebScreen
import com.quickssh.app.ui.screens.parseTunnelPort
import com.quickssh.app.ui.screens.shellSafePathReference
import com.quickssh.app.ui.screens.transferQueueTaskUiState
import com.quickssh.app.ui.screens.transferTaskProgressDetail
import com.quickssh.app.ui.screens.transferTasksWithBatchState
import com.quickssh.app.ui.theme.QuickSshTheme
import com.quickssh.app.utils.TerminalBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val TERMINAL_SCROLLBACK_MAX_LINES = 12000
private const val DATABASE_FLOW_LOG_TAG = "QuickSSH-Database"

internal fun databaseRetryDelayMillis(attempt: Long): Long {
    return ((attempt + 1L) * 250L).coerceAtMost(5_000L)
}

private fun <T> Flow<T>.retryDatabaseFlow(label: String): Flow<T> {
    return retryWhen { cause, attempt ->
        Log.e(DATABASE_FLOW_LOG_TAG, "$label flow failed; retrying", cause)
        delay(databaseRetryDelayMillis(attempt))
        true
    }
}

internal fun resolveTunnelConfig(
    configs: List<SshConfig>,
    currentConfigId: Long?,
    savedConfigId: Long?
): SshConfig? {
    return configs.firstOrNull { it.id == currentConfigId }
        ?: configs.firstOrNull { it.id == savedConfigId }
        ?: configs.firstOrNull()
}

private class TerminalSessionUiState(
    val buffer: TerminalBuffer = TerminalBuffer(maxLines = TERMINAL_SCROLLBACK_MAX_LINES)
) {
    val bufferMutex = Mutex()
    var config by mutableStateOf<SshConfig?>(null)
    var logs by mutableStateOf<List<String>>(emptyList())
    var alternateScreen by mutableStateOf(false)
    var applicationCursorKeys by mutableStateOf(false)
    var bracketedPasteMode by mutableStateOf(false)
    var quickUploadStatus by mutableStateOf("")
    var pendingInputInsertion by mutableStateOf<String?>(null)
    var collectorJob: Job? = null
}

private data class ParsedTerminalOutput(
    val responses: List<String>,
    val alternateScreen: Boolean,
    val applicationCursorKeys: Boolean,
    val bracketedPasteMode: Boolean,
    val synchronizedOutput: Boolean,
    val synchronizedOutputEnded: Boolean
)

private data class TerminalUiSnapshot(
    val logs: List<String>,
    val alternateScreen: Boolean,
    val applicationCursorKeys: Boolean,
    val bracketedPasteMode: Boolean
)

private data class QueuedTransferRequest(
    val direction: String,
    val config: SshConfig,
    val localUri: Uri?,
    val destinationUri: Uri?,
    val remotePath: String,
    val fileName: String,
    val uploadConflictPolicy: FileTransferHelper.UploadConflictPolicy = FileTransferHelper.UploadConflictPolicy.RENAME
)

private data class RemoteDownloadPlan(
    val requests: List<QueuedTransferRequest>,
    val createdDirectoryCount: Int,
    val createFailedCount: Int
)

private data class PendingRemoteDownloadConfirmation(
    val config: SshConfig,
    val directoryUri: Uri,
    val treeEntries: List<FileTransferHelper.RemoteTreeEntry>,
    val conflictPolicy: FileTransferHelper.UploadConflictPolicy,
    val fileCount: Int,
    val directoryCount: Int
)

internal data class RemoteDownloadPlanStats(
    val fileCount: Int,
    val directoryCount: Int
)

private data class PendingSingleDownload(
    val config: SshConfig,
    val remotePath: String,
    val fileName: String,
    val conflictPolicy: FileTransferHelper.UploadConflictPolicy
)

private data class DocumentChild(
    val uri: Uri,
    val mimeType: String?
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

private sealed class UploadPickerTarget {
    object Transfer : UploadPickerTarget()
    data class Terminal(val sessionId: String) : UploadPickerTarget()
}

internal fun remoteSelectionKey(path: String): String? {
    val normalized = path.trim().replace('\\', '/')
    if (normalized.isBlank()) return null
    return normalized.trimEnd('/').ifBlank { normalized }
}

internal fun remoteDownloadFileName(remotePath: String, entryName: String = ""): String {
    val fallbackName = remotePath
        .trim()
        .replace('\\', '/')
        .trimEnd('/')
        .substringAfterLast('/')
    val rawName = entryName.ifBlank { fallbackName }.ifBlank { "quickssh-download" }
    val safeName = rawName
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .ifBlank { "quickssh-download" }
        .take(160)
    return safeName
}

internal fun remoteRelativeParentPath(relativePath: String): String {
    val normalized = relativePath.trim().replace('\\', '/').trim('/')
    if (!normalized.contains('/')) return ""
    return normalized.substringBeforeLast('/')
}

internal fun remoteRelativeLeafName(relativePath: String): String {
    val normalized = relativePath.trim().replace('\\', '/').trim('/')
    return normalized.substringAfterLast('/').ifBlank { "quickssh-download" }
}

internal fun remoteRelativeDepth(relativePath: String): Int {
    val normalized = relativePath.trim().replace('\\', '/').trim('/')
    if (normalized.isBlank()) return 0
    return normalized.count { it == '/' }
}

internal fun remoteDownloadPlanStats(treeEntries: List<FileTransferHelper.RemoteTreeEntry>): RemoteDownloadPlanStats {
    return RemoteDownloadPlanStats(
        fileCount = treeEntries.count { !it.isDirectory },
        directoryCount = treeEntries.count { it.isDirectory }
    )
}

internal fun remoteDownloadPlanNeedsConfirmation(
    fileCount: Int,
    directoryCount: Int
): Boolean {
    return fileCount >= REMOTE_DOWNLOAD_CONFIRM_FILE_THRESHOLD ||
        directoryCount >= REMOTE_DOWNLOAD_CONFIRM_DIRECTORY_THRESHOLD
}

internal fun remoteDownloadPlanConfirmationStatus(fileCount: Int, directoryCount: Int): String {
    return "已扫描到 $fileCount 个文件、$directoryCount 个文件夹，请确认后继续创建下载任务"
}

internal fun remoteDownloadPlanSummary(
    createdDirectoryCount: Int,
    enqueuedFileCount: Int,
    createFailedCount: Int
): String {
    return when {
        enqueuedFileCount > 0 && createFailedCount == 0 ->
            "已创建 $createdDirectoryCount 个文件夹，加入下载队列：$enqueuedFileCount 个文件"
        enqueuedFileCount > 0 ->
            "已加入下载队列：$enqueuedFileCount 个文件，$createFailedCount 项创建失败"
        createdDirectoryCount > 0 && createFailedCount == 0 ->
            "已创建 $createdDirectoryCount 个文件夹，未发现可下载文件"
        else ->
            "失败：无法创建本地目录或文件"
    }
}

internal fun transferQueueClearStatus(clearedCount: Int): String {
    return if (clearedCount > 0) {
        "已取消 $clearedCount 个等待传输任务"
    } else {
        "没有等待中的传输任务"
    }
}

internal fun rememberedTransferDownloadConfig(
    configs: List<SshConfig>,
    savedConfigId: Long
): SshConfig? {
    if (configs.isEmpty()) return null
    return configs.firstOrNull { it.id == savedConfigId } ?: configs.first()
}

internal fun rememberedTransferDownloadRemotePath(
    config: SshConfig,
    savedConfigId: Long,
    savedPath: String
): String {
    return if (config.id == savedConfigId && savedPath.isNotBlank()) {
        savedPath
    } else {
        config.workDirectory.orEmpty()
    }
}

internal data class RememberedTransferDownloadSelection(
    val config: SshConfig?,
    val remotePath: String
)

internal fun rememberedTransferDownloadSelection(
    configs: List<SshConfig>,
    savedConfigId: Long,
    savedPath: String
): RememberedTransferDownloadSelection {
    val config = rememberedTransferDownloadConfig(configs, savedConfigId)
    return RememberedTransferDownloadSelection(
        config = config,
        remotePath = config?.let { rememberedTransferDownloadRemotePath(it, savedConfigId, savedPath) }.orEmpty()
    )
}

internal enum class BiometricToggleAction {
    ENABLE,
    DISABLE_WITH_AUTH,
    NO_CHANGE
}

internal fun biometricToggleAction(currentEnabled: Boolean, requestedEnabled: Boolean): BiometricToggleAction {
    return when {
        !currentEnabled && requestedEnabled -> BiometricToggleAction.ENABLE
        currentEnabled && !requestedEnabled -> BiometricToggleAction.DISABLE_WITH_AUTH
        else -> BiometricToggleAction.NO_CHANGE
    }
}

private const val REMOTE_DOWNLOAD_CONFIRM_FILE_THRESHOLD = 200
private const val REMOTE_DOWNLOAD_CONFIRM_DIRECTORY_THRESHOLD = 50

class MainActivity : FragmentActivity() {
    private var uiLanguage by mutableStateOf(AppLanguage.ZH)

    private lateinit var mainExecutor: Executor

    private var boundService by mutableStateOf<SshForegroundService?>(null)
    private var isBound = false
    private var onUploadFilePicked: ((List<Uri>) -> Unit)? = null
    private var onDownloadDestinationPicked: ((Uri?) -> Unit)? = null
    private var onDownloadDirectoryPicked: ((Uri?) -> Unit)? = null
    private var onExportConfigsDestinationPicked: ((Uri?) -> Unit)? = null
    private var onImportConfigsFilePicked: ((Uri?) -> Unit)? = null

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val configsFlow by lazy {
        db.sshConfigDao().getAllConfigsFlow().retryDatabaseFlow("SSH configs")
    }
    private val tunnelPresetsFlow by lazy {
        db.sshConfigDao().getAllTunnelPresetsFlow().retryDatabaseFlow("Tunnel presets")
    }
    private val transferHistoryFlow by lazy {
        db.transferHistoryDao().getAllFlow().retryDatabaseFlow("Transfer history")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SshForegroundService.LocalBinder
            boundService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data.takeIf { resultCode == Activity.RESULT_OK }
        when (requestCode) {
            REQUEST_PICK_UPLOAD_FILE -> {
                onUploadFilePicked?.invoke(selectedUploadUris(resultCode, data))
                return
            }
            REQUEST_CREATE_DOWNLOAD_FILE -> {
                onDownloadDestinationPicked?.invoke(uri)
                return
            }
            REQUEST_PICK_DOWNLOAD_DIRECTORY -> {
                onDownloadDirectoryPicked?.invoke(uri)
                return
            }
            REQUEST_CREATE_CONFIG_BACKUP -> {
                onExportConfigsDestinationPicked?.invoke(uri)
                return
            }
            REQUEST_PICK_CONFIG_BACKUP -> {
                onImportConfigsFilePicked?.invoke(uri)
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        // Keep system chrome aligned with the app's dark top and bottom bars.
        window.statusBarColor = AndroidColor.rgb(247, 246, 242)
        window.navigationBarColor = AndroidColor.rgb(239, 237, 231)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        mainExecutor = ContextCompat.getMainExecutor(this)
        uiLanguage = AppLanguage.fromCode(
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, AppLanguage.ZH.code)
        )

        setContent {
            QuickSshTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalQuickSshLanguage provides uiLanguage) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavigation()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindSshService(createIfMissing = false)
    }

    @Composable
    fun AppNavigation() {
        val configs by configsFlow.collectAsState(initial = emptyList())
        val tunnelPresets by tunnelPresetsFlow.collectAsState(initial = emptyList())
        val settings = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
        val transferHistory by transferHistoryFlow.collectAsState(initial = emptyList())
        val service = boundService
        val emptySessionsFlow = remember { MutableStateFlow(emptyList<SshSessionInfo>()) }
        val sessions by (service?.sessionInfos ?: emptySessionsFlow).collectAsState(initial = emptyList())
        val transferServiceState by TransferForegroundService.transferState.collectAsState()
        val tunnelStates by TunnelForegroundService.tunnelStates.collectAsState()
        val activeTunnelCount = tunnelStates.count { it.status == TunnelStatus.CONNECTING || it.status == TunnelStatus.RUNNING }
        var currentScreen by remember { mutableStateOf("LIST") } // LIST | ADD | TERMINAL | SETTINGS | SESSIONS | TRANSFER | TUNNELS | TUNNEL_WEB
        var activeSessionId by remember { mutableStateOf<String?>(null) }
        var lastTerminalSessionId by remember { mutableStateOf<String?>(null) }
        var configToEdit by remember { mutableStateOf<SshConfig?>(null) }
        var isCopyMode by remember { mutableStateOf(false) }
        var decryptedPassword by remember { mutableStateOf<String?>(null) }
        var connectionTestStatus by remember { mutableStateOf("") }
        var isTestingConnection by remember { mutableStateOf(false) }
        var showConnectingDialog by remember { mutableStateOf(false) }
        var connectingConfig by remember { mutableStateOf<SshConfig?>(null) }
        var autoWrapEnabled by remember {
            mutableStateOf(settings.getBoolean(KEY_TERMINAL_AUTO_WRAP, true))
        }
        var privacyModeEnabled by remember {
            mutableStateOf(settings.getBoolean(KEY_PRIVACY_MODE, false))
        }
        var biometricUnlockEnabled by remember {
            mutableStateOf(settings.getBoolean(KEY_BIOMETRIC_UNLOCK, false))
        }
        var strictHostKeyVerificationEnabled by remember {
            mutableStateOf(settings.getBoolean(KnownHostsVerifier.KEY_STRICT_HOST_KEY_VERIFICATION, false))
        }
        var downloadDirectoryUriText by remember {
            mutableStateOf(settings.getString(KEY_DOWNLOAD_DIRECTORY_URI, "").orEmpty())
        }
        var configBackupStatus by remember { mutableStateOf("") }
        var pendingExportBackupPassword by remember { mutableStateOf<String?>(null) }
        var pendingImportBackupPassword by remember { mutableStateOf<String?>(null) }
        var transferConfig by remember { mutableStateOf<SshConfig?>(null) }
        var transferLocalUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var uploadPickerTarget by remember { mutableStateOf<UploadPickerTarget>(UploadPickerTarget.Transfer) }
        var uploadRemotePath by remember { mutableStateOf("") }
        var transferRemotePath by remember { mutableStateOf("") }
        var transferStatus by remember { mutableStateOf("\u7b49\u5f85\u4e2d") }
        var transferBusy by remember { mutableStateOf(false) }
        var transferProgressText by remember { mutableStateOf("") }
        var transferProgressFraction by remember { mutableStateOf<Float?>(null) }
        var uploadConflictPolicy by remember { mutableStateOf(FileTransferHelper.UploadConflictPolicy.RENAME) }
        var downloadConflictPolicy by remember { mutableStateOf(FileTransferHelper.UploadConflictPolicy.RENAME) }
        var tunnelConfig by remember { mutableStateOf<SshConfig?>(null) }
        var tunnelRemoteHost by remember { mutableStateOf("127.0.0.1") }
        var tunnelRemotePort by remember { mutableStateOf("3000") }
        var tunnelLocalPort by remember { mutableStateOf("") }
        var tunnelPresetName by remember { mutableStateOf("") }
        var tunnelPresetNote by remember { mutableStateOf("") }
        var selectedTunnelPresetId by remember { mutableStateOf<Long?>(null) }
        var tunnelStatusText by remember { mutableStateOf("等待启动隧道") }
        var tunnelWebUrl by remember { mutableStateOf<String?>(null) }
        val queuedTransfers = remember { mutableStateListOf<QueuedTransferRequest>() }
        var transferQueuePaused by remember { mutableStateOf(false) }
        var wasTransferServiceRunning by remember { mutableStateOf(false) }
        val transferTasks = transferHistory.map { it.toUiState() }
        val activeTransferHistoryTask = transferServiceState.taskId?.let { taskId ->
            transferTasks.firstOrNull { task -> task.id == taskId }
        }
        val activeTransferTask = if (transferServiceState.isRunning) {
            val progressDetail = transferTaskProgressDetail(
                progressText = transferServiceState.progressText,
                progressFraction = transferServiceState.progressFraction
            )
            activeTransferHistoryTask?.copy(
                status = transferServiceState.statusText.ifBlank { activeTransferHistoryTask.status },
                detail = progressDetail
            ) ?: TransferTaskUiState(
                id = -100_000L,
                fileName = transferServiceState.fileName.ifBlank { "当前传输" },
                direction = transferServiceState.direction.ifBlank { "Transfer" },
                serverName = transferConfig?.transferContextLabel() ?: "当前服务器",
                status = transferServiceState.statusText.ifBlank { "传输中" },
                detail = progressDetail,
                serverNodeName = transferConfig?.serverNodeLabel() ?: "当前服务器",
                workspaceName = transferConfig?.workspaceLabel() ?: "当前工作区"
            )
        } else {
            null
        }
        val queuedTransferTasks = queuedTransfers.mapIndexed { index, request ->
            transferQueueTaskUiState(
                index = index,
                totalWaiting = queuedTransfers.size,
                queuePaused = transferQueuePaused,
                direction = request.direction,
                fileName = request.fileName,
                serverName = request.config.transferContextLabel(),
                serverNodeName = request.config.serverNodeLabel(),
                workspaceName = request.config.workspaceLabel(),
                localUri = request.localUri,
                destinationUri = request.destinationUri,
                remotePath = request.remotePath
            )
        }
        val displayedTransferTasks = transferTasksWithBatchState(
            historyTasks = transferTasks,
            activeTask = activeTransferTask,
            queuedTasks = queuedTransferTasks
        )
        val remoteEntries = remember { mutableStateListOf<FileTransferHelper.RemoteEntry>() }
        val selectedRemoteEntries = remember { mutableStateMapOf<String, FileTransferHelper.RemoteEntry>() }
        var remoteMultiSelectEnabled by remember { mutableStateOf(false) }
        var pendingRemoteDownloadEntries by remember { mutableStateOf<List<FileTransferHelper.RemoteEntry>>(emptyList()) }
        var pendingSingleDownload by remember { mutableStateOf<PendingSingleDownload?>(null) }
        var pendingRemoteDownloadConfirmation by remember { mutableStateOf<PendingRemoteDownloadConfirmation?>(null) }
        var remoteDownloadPlanJob by remember { mutableStateOf<Job?>(null) }
        var remoteDownloadPlanStartedAtMillis by remember { mutableStateOf<Long?>(null) }
        var remoteDownloadPlanElapsedSeconds by remember { mutableStateOf(0L) }
        var showRemoteDownloadPlanSlowWarning by remember { mutableStateOf(false) }
        var remoteDownloadPlanSlowWarningDismissed by remember { mutableStateOf(false) }
        var remoteBrowserStatus by remember { mutableStateOf("") }
        var isBrowsingRemote by remember { mutableStateOf(false) }
        val terminalStates = remember { mutableStateMapOf<String, TerminalSessionUiState>() }
        val scope = rememberCoroutineScope()
        fun transferHelper(): FileTransferHelper = FileTransferHelper(this@MainActivity)

        fun savedTransferDownloadSelection(configs: List<SshConfig>): RememberedTransferDownloadSelection {
            val savedConfigId = settings.getLong(KEY_TRANSFER_DOWNLOAD_CONFIG_ID, -1L)
            val savedPath = settings.getString(KEY_TRANSFER_DOWNLOAD_REMOTE_PATH, "").orEmpty()
            return rememberedTransferDownloadSelection(configs, savedConfigId, savedPath)
        }

        fun savedTransferDownloadConfig(configs: List<SshConfig>): SshConfig? {
            return savedTransferDownloadSelection(configs).config
        }

        fun savedTransferDownloadRemotePath(config: SshConfig): String {
            val savedConfigId = settings.getLong(KEY_TRANSFER_DOWNLOAD_CONFIG_ID, -1L)
            val savedPath = settings.getString(KEY_TRANSFER_DOWNLOAD_REMOTE_PATH, "").orEmpty()
            return rememberedTransferDownloadRemotePath(config, savedConfigId, savedPath)
        }

        fun rememberTransferDownloadSelection(config: SshConfig?, remotePath: String = transferRemotePath) {
            if (config == null) return
            settings.edit()
                .putLong(KEY_TRANSFER_DOWNLOAD_CONFIG_ID, config.id)
                .putString(KEY_TRANSFER_DOWNLOAD_REMOTE_PATH, remotePath.trim())
                .apply()
        }

        fun selectTransferConfig(config: SshConfig, restoreSavedDownloadPath: Boolean) {
            transferConfig = config
            uploadRemotePath = config.workDirectory.orEmpty()
            transferRemotePath = if (restoreSavedDownloadPath) {
                savedTransferDownloadRemotePath(config)
            } else {
                config.workDirectory.orEmpty()
            }
            rememberTransferDownloadSelection(config, transferRemotePath)
        }

        LaunchedEffect(Unit) {
            runCatching { migrateLegacyTransferTasks(settings) }
        }

        LaunchedEffect(privacyModeEnabled) {
            applyPrivacyMode(privacyModeEnabled)
        }

        LaunchedEffect(remoteDownloadPlanStartedAtMillis) {
            val startedAt = remoteDownloadPlanStartedAtMillis ?: return@LaunchedEffect
            showRemoteDownloadPlanSlowWarning = false
            while (true) {
                val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
                remoteDownloadPlanElapsedSeconds = elapsedSeconds
                if (elapsedSeconds >= 60L && remoteDownloadPlanJob?.isActive == true && !remoteDownloadPlanSlowWarningDismissed) {
                    showRemoteDownloadPlanSlowWarning = true
                }
                delay(1000L)
            }
        }

        fun deleteTransferTask(task: TransferTaskUiState) {
            if (task.id <= 0L) return
            scope.launch {
                db.transferHistoryDao().deleteById(task.id)
            }
        }

        fun openTransferTask(task: TransferTaskUiState) {
            val uri = task.localUri
            if (uri != null) {
                if (!ensureInstallPermissionIfNeeded(uri)) return
                runCatching {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(viewIntent, "Open with"))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Unable to open this file. Check whether an app is available.", Toast.LENGTH_SHORT).show()
                }
            } else if (task.remotePath.isNotBlank()) {
                transferRemotePath = task.remotePath
                rememberTransferDownloadSelection(transferConfig, transferRemotePath)
                transferStatus = "\u5df2\u5b9a\u4f4d\u5230\u8fdc\u7aef\u8def\u5f84\uff0c\u53ef\u7ee7\u7eed\u4e0b\u8f7d\u6216\u6d4f\u89c8"
            }
        }

        fun resetTransferProgress() {
            transferProgressFraction = null
            transferProgressText = ""
        }

        fun finishTransfer() {
            transferBusy = false
        }

        var pumpTransferQueue: () -> Unit = {}

        fun runQueuedTransfer(request: QueuedTransferRequest) {
            transferBusy = true
            resetTransferProgress()
            transferStatus = if (request.direction == "Upload") "传输中：正在上传..." else "传输中：正在下载..."
            requestNotificationPermissionIfNeeded()
            runCatching {
                TransferForegroundService.startTransfer(
                    context = this@MainActivity,
                    direction = request.direction,
                    configId = request.config.id,
                    localUri = request.localUri,
                    destinationUri = request.destinationUri,
                    remotePath = request.remotePath,
                    fileName = request.fileName,
                    uploadConflictPolicy = request.uploadConflictPolicy
                )
            }.onFailure { error ->
                finishTransfer()
                transferStatus = "失败：无法启动后台传输服务：${error.localizedMessage ?: error.javaClass.simpleName}"
                pumpTransferQueue()
            }
        }

        pumpTransferQueue = pump@{
            if (transferBusy || transferQueuePaused || queuedTransfers.isEmpty()) return@pump
            val next = queuedTransfers.removeAt(0)
            runQueuedTransfer(next)
        }

        LaunchedEffect(transferServiceState) {
            transferBusy = transferServiceState.isRunning
            transferStatus = transferServiceState.statusText
            transferProgressText = transferServiceState.progressText
            transferProgressFraction = transferServiceState.progressFraction

            val justFinished = wasTransferServiceRunning && !transferServiceState.isRunning
            wasTransferServiceRunning = transferServiceState.isRunning
            if (justFinished) {
                pumpTransferQueue()
            }
        }

        fun enqueueTransfer(request: QueuedTransferRequest) {
            if (!transferBusy && !transferQueuePaused && queuedTransfers.isEmpty()) {
                runQueuedTransfer(request)
            } else {
                queuedTransfers.add(request)
                transferStatus = "已加入队列：${request.direction} ${request.fileName}"
            }
        }

        LaunchedEffect(transferBusy, transferQueuePaused, queuedTransfers.size) {
            pumpTransferQueue()
        }

        fun browseRemote(path: String = transferRemotePath) {
            val config = transferConfig ?: run {
                remoteBrowserStatus = "\u8bf7\u5148\u9009\u62e9\u670d\u52a1\u5668"
                return
            }
            isBrowsingRemote = true
            remoteBrowserStatus = "\u6b63\u5728\u8bfb\u53d6\u8fdc\u7aef\u76ee\u5f55..."
            scope.launch {
                val result = transferHelper().listRemoteDirectory(config, path)
                remoteEntries.clear()
                result.fold(
                    onSuccess = { entries ->
                        remoteEntries.addAll(entries)
                        remoteBrowserStatus = if (entries.isEmpty()) "\u76ee\u5f55\u4e3a\u7a7a" else "\u5df2\u5217\u51fa ${entries.size} \u9879"
                    },
                    onFailure = { remoteBrowserStatus = "\u8bfb\u53d6\u5931\u8d25\uff1a${it.localizedMessage ?: it.javaClass.simpleName}" }
                )
                isBrowsingRemote = false
            }
        }

        fun enqueueSingleDownload(
            config: SshConfig,
            remotePath: String,
            fileName: String,
            destinationUri: Uri
        ) {
            grantTransferUriAccess(destinationUri, writable = true)
            rememberTransferDownloadSelection(config, remotePath)
            enqueueTransfer(
                QueuedTransferRequest(
                    direction = "Download",
                    config = config,
                    localUri = null,
                    destinationUri = destinationUri,
                    remotePath = remotePath,
                    fileName = fileName
                )
            )
        }

        fun startDownload(destinationUri: Uri) {
            val config = transferConfig
            if (config == null) {
                transferStatus = "\u5931\u8d25\uff1a\u8bf7\u5148\u9009\u62e9\u670d\u52a1\u5668"
                return
            }
            val remotePath = transferRemotePath.trim()
            if (remotePath.isBlank()) {
                transferStatus = "\u5931\u8d25\uff1a\u8bf7\u586b\u5199\u8fdc\u7aef\u6587\u4ef6\u8def\u5f84"
                return
            }

            enqueueSingleDownload(
                config = config,
                remotePath = remotePath,
                fileName = remoteDownloadFileName(remotePath),
                destinationUri = destinationUri
            )
        }

        fun createAndStartSingleDownload(
            pending: PendingSingleDownload,
            directoryUri: Uri
        ) {
            val destinationUri = createDocumentInTree(
                treeUri = directoryUri,
                fileName = pending.fileName,
                conflictPolicy = pending.conflictPolicy
            )
            if (destinationUri == null) {
                transferStatus = downloadTargetCreationFailureMessage(pending.conflictPolicy)
                return
            }
            transferConfig = pending.config
            transferRemotePath = pending.remotePath
            enqueueSingleDownload(
                config = pending.config,
                remotePath = pending.remotePath,
                fileName = pending.fileName,
                destinationUri = destinationUri
            )
        }

        fun selectedRemoteEntryKey(entry: FileTransferHelper.RemoteEntry): String {
            return remoteSelectionKey(entry.path) ?: entry.path
        }

        fun setRemoteEntrySelected(entry: FileTransferHelper.RemoteEntry, selected: Boolean) {
            val key = selectedRemoteEntryKey(entry)
            if (selected) {
                selectedRemoteEntries[key] = entry
                transferRemotePath = entry.path
                rememberTransferDownloadSelection(transferConfig, transferRemotePath)
            } else {
                selectedRemoteEntries.remove(key)
                if (selectedRemoteEntries.isEmpty() && !remoteMultiSelectEnabled) {
                    transferStatus = "等待中"
                }
            }
        }

        fun toggleRemoteEntrySelection(entry: FileTransferHelper.RemoteEntry) {
            val key = selectedRemoteEntryKey(entry)
            setRemoteEntrySelected(entry, selectedRemoteEntries[key] == null)
        }

        fun clearRemoteSelection() {
            selectedRemoteEntries.clear()
            remoteMultiSelectEnabled = false
        }

        suspend fun createAndEnqueueRemoteDownloadPlan(
            config: SshConfig,
            directoryUri: Uri,
            treeEntries: List<FileTransferHelper.RemoteTreeEntry>,
            conflictPolicy: FileTransferHelper.UploadConflictPolicy
        ) {
            val planContext = currentCoroutineContext()
            val rootDocumentUri = treeRootDocumentUri(directoryUri)
            if (rootDocumentUri == null) {
                transferStatus = "失败：无法打开下载目录"
                return
            }
            transferStatus = "正在创建本地目录和下载任务..."
            val downloadPlan = createRemoteDownloadPlan(
                config = config,
                treeEntries = treeEntries,
                rootDocumentUri = rootDocumentUri,
                conflictPolicy = conflictPolicy
            )
            planContext.ensureActive()
            downloadPlan.requests.forEachIndexed { index, request ->
                if (index % 25 == 0) planContext.ensureActive()
                enqueueTransfer(request)
            }
            clearRemoteSelection()
            transferStatus = remoteDownloadPlanSummary(
                createdDirectoryCount = downloadPlan.createdDirectoryCount,
                enqueuedFileCount = downloadPlan.requests.size,
                createFailedCount = downloadPlan.createFailedCount
            )
        }

        fun startLocalRemoteDownloadPlanCreation(pending: PendingRemoteDownloadConfirmation) {
            if (remoteDownloadPlanJob?.isActive == true) {
                transferStatus = "正在创建下载计划，请稍候..."
                return
            }
            remoteDownloadPlanElapsedSeconds = 0L
            remoteDownloadPlanStartedAtMillis = System.currentTimeMillis()
            showRemoteDownloadPlanSlowWarning = false
            remoteDownloadPlanSlowWarningDismissed = false
            remoteDownloadPlanJob = scope.launch {
                try {
                    createAndEnqueueRemoteDownloadPlan(
                        config = pending.config,
                        directoryUri = pending.directoryUri,
                        treeEntries = pending.treeEntries,
                        conflictPolicy = pending.conflictPolicy
                    )
                } catch (_: CancellationException) {
                    transferStatus = "已取消下载计划创建"
                } finally {
                    remoteDownloadPlanJob = null
                    remoteDownloadPlanStartedAtMillis = null
                    remoteDownloadPlanElapsedSeconds = 0L
                    showRemoteDownloadPlanSlowWarning = false
                    remoteDownloadPlanSlowWarningDismissed = false
                }
            }
        }

        fun enqueueRemoteEntryDownloads(
            entries: List<FileTransferHelper.RemoteEntry>,
            directoryUri: Uri
        ) {
            val config = transferConfig
            if (config == null) {
                transferStatus = "失败：请先选择服务器"
                return
            }
            val uniqueEntries = entries.distinctBy { selectedRemoteEntryKey(it) }
            if (uniqueEntries.isEmpty()) {
                transferStatus = "失败：请先选择要下载的远端文件"
                return
            }
            if (remoteDownloadPlanJob?.isActive == true) {
                transferStatus = "正在创建下载计划，请稍候..."
                return
            }
            if (pendingRemoteDownloadConfirmation != null) {
                transferStatus = "请先确认或取消当前大目录下载计划"
                return
            }
            rememberTransferDownloadSelection(config, uniqueEntries.firstOrNull()?.path ?: transferRemotePath)

            transferStatus = if (uniqueEntries.any { it.isDirectory }) {
                "正在扫描远端文件夹..."
            } else {
                "正在准备下载任务..."
            }
            remoteDownloadPlanElapsedSeconds = 0L
            remoteDownloadPlanStartedAtMillis = System.currentTimeMillis()
            showRemoteDownloadPlanSlowWarning = false
            remoteDownloadPlanSlowWarningDismissed = false
            remoteDownloadPlanJob = scope.launch {
                val planContext = currentCoroutineContext()
                try {
                    val treeResult = transferHelper().listRemoteTree(config, uniqueEntries)
                    planContext.ensureActive()
                    treeResult.fold(
                        onFailure = { error ->
                            transferStatus = "失败：无法扫描远端路径：${error.localizedMessage ?: error.javaClass.simpleName}"
                        },
                        onSuccess = { treeEntries ->
                            val stats = remoteDownloadPlanStats(treeEntries)
                            if (remoteDownloadPlanNeedsConfirmation(stats.fileCount, stats.directoryCount)) {
                                pendingRemoteDownloadConfirmation = PendingRemoteDownloadConfirmation(
                                    config = config,
                                    directoryUri = directoryUri,
                                    treeEntries = treeEntries,
                                    conflictPolicy = downloadConflictPolicy,
                                    fileCount = stats.fileCount,
                                    directoryCount = stats.directoryCount
                                )
                                transferStatus = remoteDownloadPlanConfirmationStatus(
                                    fileCount = stats.fileCount,
                                    directoryCount = stats.directoryCount
                                )
                                return@fold
                            }
                            createAndEnqueueRemoteDownloadPlan(
                                config = config,
                                directoryUri = directoryUri,
                                treeEntries = treeEntries,
                                conflictPolicy = downloadConflictPolicy
                            )
                        }
                    )
                } catch (_: CancellationException) {
                    transferStatus = "已取消下载计划创建"
                } finally {
                    remoteDownloadPlanJob = null
                    remoteDownloadPlanStartedAtMillis = null
                    remoteDownloadPlanElapsedSeconds = 0L
                    showRemoteDownloadPlanSlowWarning = false
                    remoteDownloadPlanSlowWarningDismissed = false
                }
            }
        }

        fun startSelectedRemoteDownloads(entries: List<FileTransferHelper.RemoteEntry>) {
            val uniqueEntries = entries.distinctBy { selectedRemoteEntryKey(it) }
            if (uniqueEntries.isEmpty()) {
                transferStatus = "失败：请先选择要下载的远端文件"
                return
            }
            val directoryUri = downloadDirectoryUriText.takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (directoryUri == null) {
                pendingRemoteDownloadEntries = uniqueEntries
                transferStatus = "请选择下载目录后开始批量下载"
                openDownloadDirectoryPicker().onFailure { error ->
                    pendingRemoteDownloadEntries = emptyList()
                    transferStatus = "失败：无法打开系统目录选择器：${error.localizedMessage ?: error.javaClass.simpleName}"
                }
            } else {
                enqueueRemoteEntryDownloads(uniqueEntries, directoryUri)
            }
        }

        fun startTerminalQuickUpload(sessionId: String, uris: List<Uri>) {
            val state = terminalStates[sessionId] ?: return
            val config = state.config ?: return
            val remoteDirectory = terminalQuickUploadDirectory(config.workDirectory)
            state.quickUploadStatus = "Uploading ${uris.size} file${if (uris.size == 1) "" else "s"} to $remoteDirectory..."
            scope.launch {
                val helper = transferHelper()
                val dao = db.transferHistoryDao()
                val uploadedReferences = mutableListOf<String>()
                var failedCount = 0
                var lastFailureMessage = ""

                uris.forEachIndexed { index, uri ->
                    val fileName = displayNameFromUri(uri).ifBlank { "quickssh-upload-${index + 1}" }
                    withContext(Dispatchers.Main) {
                        state.quickUploadStatus = "Uploading ${index + 1}/${uris.size}: $fileName"
                    }
                    val taskId = dao.insert(
                        TransferHistoryEntry(
                            fileName = fileName,
                            direction = "Upload",
                            serverName = config.transferContextLabel(),
                            status = "Transferring",
                            localUri = uri.toString(),
                            remotePath = remoteDirectory,
                            detail = "Terminal quick upload",
                            serverNodeName = config.serverNodeLabel(),
                            workspaceName = config.workspaceLabel()
                        )
                    )
                    dao.trimToLimit(TRANSFER_HISTORY_RETAIN_LIMIT)

                    val result = helper.uploadFileToDirectory(
                        config = config,
                        localUri = uri,
                        remoteDirectoryInput = remoteDirectory,
                        conflictPolicy = FileTransferHelper.UploadConflictPolicy.RENAME
                    ) { progress ->
                        runOnUiThread {
                            state.quickUploadStatus = "Uploading ${index + 1}/${uris.size}: $fileName ${formatBytes(progress.transferredBytes)}"
                        }
                    }

                    result.fold(
                        onSuccess = { remotePath ->
                            uploadedReferences.add(shellSafePathReference(remotePath))
                            dao.getById(taskId)?.let { entry ->
                                dao.update(
                                    entry.copy(
                                        status = "Success",
                                        remotePath = remotePath,
                                        detail = "Terminal quick upload complete",
                                        updateTime = System.currentTimeMillis()
                                    )
                                )
                            }
                        },
                        onFailure = { error ->
                            failedCount++
                            lastFailureMessage = error.localizedMessage ?: error.javaClass.simpleName
                            dao.getById(taskId)?.let { entry ->
                                dao.update(
                                    entry.copy(
                                        status = "Failed",
                                        detail = error.localizedMessage ?: error.javaClass.simpleName,
                                        updateTime = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    )
                }

                withContext(Dispatchers.Main) {
                    if (uploadedReferences.isNotEmpty()) {
                        state.pendingInputInsertion = uploadedReferences.joinToString(" ")
                    }
                        state.quickUploadStatus = when {
                            uploadedReferences.isNotEmpty() && failedCount == 0 -> "Uploaded ${uploadedReferences.size} file${if (uploadedReferences.size == 1) "" else "s"}; path inserted"
                            uploadedReferences.isNotEmpty() -> "Uploaded ${uploadedReferences.size}, failed $failedCount; successful path${if (uploadedReferences.size == 1) "" else "s"} inserted"
                            else -> "Upload failed: ${lastFailureMessage.ifBlank { "unknown error" }}"
                        }
                    }
                }
        }

        SideEffect {
            onUploadFilePicked = { uris ->
                val target = uploadPickerTarget
                uploadPickerTarget = UploadPickerTarget.Transfer
                if (uris.isNotEmpty()) {
                    uris.forEach { uri ->
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
                when (target) {
                    UploadPickerTarget.Transfer -> {
                        if (uris.isNotEmpty()) {
                            transferLocalUris = uris
                            transferStatus = if (uris.size == 1) {
                                "Waiting: local file selected"
                            } else {
                                "Waiting: ${uris.size} local files selected"
                            }
                        } else {
                            transferStatus = "Waiting: local file selection canceled"
                        }
                    }
                    is UploadPickerTarget.Terminal -> {
                        if (uris.isNotEmpty()) {
                            startTerminalQuickUpload(target.sessionId, uris)
                        } else {
                            terminalStates[target.sessionId]?.quickUploadStatus = "Upload canceled"
                        }
                    }
                }
            }

            onDownloadDestinationPicked = { uri ->
                if (uri != null) {
                    grantTransferUriAccess(uri, writable = true)
                    startDownload(uri)
                } else {
                    transferStatus = "Waiting: save location selection canceled"
                }
            }

            onDownloadDirectoryPicked = { uri ->
                if (uri != null) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                    downloadDirectoryUriText = uri.toString()
                    settings.edit().putString(KEY_DOWNLOAD_DIRECTORY_URI, downloadDirectoryUriText).apply()
                    val pendingEntries = pendingRemoteDownloadEntries
                    pendingRemoteDownloadEntries = emptyList()
                    val pendingSingle = pendingSingleDownload
                    pendingSingleDownload = null
                    if (pendingEntries.isNotEmpty()) {
                        enqueueRemoteEntryDownloads(pendingEntries, uri)
                    } else if (pendingSingle != null) {
                        createAndStartSingleDownload(pendingSingle, uri)
                    } else {
                        configBackupStatus = "下载目录已更新"
                        transferStatus = "下载目录已更新：${downloadDirectoryLabel(downloadDirectoryUriText)}"
                    }
                } else if (pendingRemoteDownloadEntries.isNotEmpty()) {
                    pendingRemoteDownloadEntries = emptyList()
                    transferStatus = "等待：批量下载目录选择已取消"
                } else if (pendingSingleDownload != null) {
                    pendingSingleDownload = null
                    transferStatus = "等待：下载目录选择已取消"
                }
            }

            onExportConfigsDestinationPicked = { uri ->
                if (uri == null) {
                    configBackupStatus = "Export canceled"
                    pendingExportBackupPassword = null
                } else {
                    configBackupStatus = "Exporting server backup..."
                    val exportPassword = pendingExportBackupPassword
                    scope.launch {
                        val result = runCatching { exportServerConfigs(uri, exportPassword) }
                        configBackupStatus = result.fold(
                            onSuccess = { count ->
                                val mode = if (exportPassword.isNullOrEmpty()) "" else " protected"
                                "Exported $count$mode server profile${if (count == 1) "" else "s"}"
                            },
                            onFailure = { error -> "Export failed: ${error.localizedMessage ?: error.javaClass.simpleName}" }
                        )
                        pendingExportBackupPassword = null
                    }
                }
            }

            onImportConfigsFilePicked = { uri ->
                if (uri == null) {
                    configBackupStatus = "Import canceled"
                    pendingImportBackupPassword = null
                } else {
                    configBackupStatus = "Importing server backup..."
                    val importPassword = pendingImportBackupPassword
                    scope.launch {
                        val result = runCatching { importServerConfigs(uri, importPassword) }
                        configBackupStatus = result.fold(
                            onSuccess = { count -> "Imported $count server profile${if (count == 1) "" else "s"}" },
                            onFailure = { error -> "Import failed: ${error.localizedMessage ?: error.javaClass.simpleName}" }
                        )
                        pendingImportBackupPassword = null
                    }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                onUploadFilePicked = null
                onDownloadDestinationPicked = null
                onDownloadDirectoryPicked = null
                onExportConfigsDestinationPicked = null
                onImportConfigsFilePicked = null
            }
        }

        fun SshSessionInfo.toConfig(): SshConfig = SshConfig(
            id = configId,
            name = name,
            host = host,
            port = port,
            username = username,
            authType = "PASSWORD",
            workDirectory = workDirectory,
            terminalFontSizeSp = terminalFontSizeSp,
            terminalWrapEnabled = terminalWrapEnabled,
            terminalTerm = terminalTerm,
            terminalShortcuts = terminalShortcuts
        )

        fun ensureTerminalState(sessionId: String, config: SshConfig): TerminalSessionUiState {
            return terminalStates.getOrPut(sessionId) { TerminalSessionUiState() }.also { state ->
                if (state.config == null) state.config = config
            }
        }

        fun attachSessionCollector(
            sessionId: String,
            state: TerminalSessionUiState,
            openTerminalOnFirstOutput: Boolean = false
        ) {
            if (state.collectorJob?.isActive == true) return
            state.collectorJob = scope.launch(Dispatchers.Default) {
                suspend fun currentHelper() = withContext(Dispatchers.Main) {
                    boundService?.getSshHelper(sessionId)
                }

                while (currentHelper() == null) {
                    delay(100)
                }

                val helper = currentHelper() ?: return@launch
                var hasOpenedTerminal = !openTerminalOnFirstOutput
                var latestSnapshot: List<String> = withContext(Dispatchers.Main) { state.logs }
                var lastUiUpdateAt = 0L
                var pendingUiFlushJob: Job? = null
                val replayChunksToSkip = ArrayDeque<String>()

                suspend fun publishSnapshot(
                    snapshot: List<String>,
                    alternateScreen: Boolean,
                    applicationCursorKeys: Boolean,
                    bracketedPasteMode: Boolean
                ) {
                    withContext(Dispatchers.Main) {
                        state.alternateScreen = alternateScreen
                        state.applicationCursorKeys = applicationCursorKeys
                        state.bracketedPasteMode = bracketedPasteMode
                        state.logs = snapshot
                    }
                }

                suspend fun snapshotTerminalState(): TerminalUiSnapshot {
                    return state.bufferMutex.withLock {
                        TerminalUiSnapshot(
                            logs = state.buffer.snapshot(),
                            alternateScreen = state.buffer.isInAlternateScreen,
                            applicationCursorKeys = state.buffer.applicationCursorKeys,
                            bracketedPasteMode = state.buffer.bracketedPasteMode
                        )
                    }
                }

                suspend fun flushTerminalUi() {
                    val snapshot = snapshotTerminalState()
                    latestSnapshot = snapshot.logs
                    lastUiUpdateAt = System.currentTimeMillis()
                    publishSnapshot(
                        snapshot.logs,
                        snapshot.alternateScreen,
                        snapshot.applicationCursorKeys,
                        snapshot.bracketedPasteMode
                    )
                }

                fun scheduleTerminalUiFlush(delayMillis: Long) {
                    if (pendingUiFlushJob?.isActive == true) return
                    pendingUiFlushJob = launch {
                        delay(delayMillis.coerceAtLeast(1L))
                        flushTerminalUi()
                    }
                }

                if (latestSnapshot.isEmpty()) {
                    val snapshot = state.bufferMutex.withLock {
                        helper.recentOutputSnapshot().forEach { output ->
                            state.buffer.appendRaw(output)
                            replayChunksToSkip.addLast(output)
                        }
                        state.buffer.drainResponses()
                        TerminalUiSnapshot(
                            logs = state.buffer.snapshot(),
                            alternateScreen = state.buffer.isInAlternateScreen,
                            applicationCursorKeys = state.buffer.applicationCursorKeys,
                            bracketedPasteMode = state.buffer.bracketedPasteMode
                        )
                    }
                    latestSnapshot = snapshot.logs
                    if (snapshot.logs.isNotEmpty()) {
                        publishSnapshot(
                            snapshot.logs,
                            snapshot.alternateScreen,
                            snapshot.applicationCursorKeys,
                            snapshot.bracketedPasteMode
                        )
                    }
                }
                try {
                    helper.terminalOutput.collect { output ->
                        if (replayChunksToSkip.firstOrNull() == output) {
                            replayChunksToSkip.removeFirst()
                            return@collect
                        }
                        if (!hasOpenedTerminal) {
                            hasOpenedTerminal = true
                            withContext(Dispatchers.Main) {
                                showConnectingDialog = false
                                connectingConfig = null
                                activeSessionId = sessionId
                                lastTerminalSessionId = sessionId
                                currentScreen = "TERMINAL"
                            }
                        }

                        val parsedOutput = state.bufferMutex.withLock {
                            val wasSynchronizedOutput = state.buffer.isSynchronizedOutputMode
                            state.buffer.appendRaw(output)
                            ParsedTerminalOutput(
                                responses = state.buffer.drainResponses(),
                                alternateScreen = state.buffer.isInAlternateScreen,
                                applicationCursorKeys = state.buffer.applicationCursorKeys,
                                bracketedPasteMode = state.buffer.bracketedPasteMode,
                                synchronizedOutput = state.buffer.isSynchronizedOutputMode,
                                synchronizedOutputEnded = wasSynchronizedOutput && !state.buffer.isSynchronizedOutputMode
                            )
                        }
                        parsedOutput.responses.forEach { response ->
                            currentHelper()?.sendRawInput(response)
                        }
                        withContext(Dispatchers.Main) {
                            state.alternateScreen = parsedOutput.alternateScreen
                            state.applicationCursorKeys = parsedOutput.applicationCursorKeys
                            state.bracketedPasteMode = parsedOutput.bracketedPasteMode
                        }

                        val now = System.currentTimeMillis()
                        val updateInterval = if (parsedOutput.synchronizedOutput) {
                            TERMINAL_SYNCHRONIZED_UI_UPDATE_INTERVAL_MS
                        } else if (parsedOutput.alternateScreen) {
                            TERMINAL_ALTERNATE_UI_UPDATE_INTERVAL_MS
                        } else {
                            TERMINAL_UI_UPDATE_INTERVAL_MS
                        }
                        val elapsed = now - lastUiUpdateAt
                        if (parsedOutput.synchronizedOutputEnded || elapsed >= updateInterval) {
                            pendingUiFlushJob?.cancel()
                            pendingUiFlushJob = null
                            flushTerminalUi()
                        } else {
                            scheduleTerminalUiFlush(updateInterval - elapsed)
                        }
                    }
                } finally {
                    pendingUiFlushJob?.cancel()
                }
            }
        }

        fun openTerminal(sessionId: String, config: SshConfig) {
            val state = ensureTerminalState(sessionId, config)
            attachSessionCollector(sessionId, state)
            activeSessionId = sessionId
            lastTerminalSessionId = sessionId
            currentScreen = "TERMINAL"
        }

        fun openTerminalFromSession(session: SshSessionInfo) {
            scope.launch {
                val savedConfig = db.sshConfigDao().getConfigById(session.configId)
                openTerminal(session.sessionId, savedConfig ?: session.toConfig())
            }
        }

        fun openMostRecentTerminal(): Boolean {
            lastTerminalSessionId?.let { sessionId ->
                terminalStates[sessionId]?.config?.let { config ->
                    openTerminal(sessionId, config)
                    return true
                }
                sessions.firstOrNull { it.sessionId == sessionId }?.let { session ->
                    openTerminalFromSession(session)
                    return true
                }
            }

            sessions.lastOrNull()?.let { session ->
                openTerminalFromSession(session)
                return true
            }
            return false
        }

        fun openSessionsScreen() {
            bindSshService(createIfMissing = false)
            currentScreen = "SESSIONS"
        }

        fun openTunnelUrlInternal(url: String) {
            tunnelWebUrl = url
            currentScreen = "TUNNEL_WEB"
        }

        fun openTunnelUrlExternal(url: String) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure { error ->
                tunnelStatusText = "无法打开浏览器：${error.localizedMessage ?: error.javaClass.simpleName}"
            }
        }

        BackHandler(enabled = currentScreen == "TERMINAL") {
            currentScreen = "LIST"
        }
        BackHandler(enabled = currentScreen == "TUNNEL_WEB") {
            tunnelWebUrl = null
            currentScreen = "TUNNELS"
        }
        BackHandler(enabled = currentScreen == "ADD") {
            configToEdit = null
            isCopyMode = false
            decryptedPassword = null
            connectionTestStatus = ""
            currentScreen = "LIST"
        }
        BackHandler(
            enabled = currentScreen != "TERMINAL" &&
                currentScreen != "ADD" &&
                currentScreen != "TUNNEL_WEB" &&
                (terminalStates.isNotEmpty() || sessions.isNotEmpty())
        ) {
            openMostRecentTerminal()
        }

        when (currentScreen) {
            "LIST" -> {
                SshListScreen(
                    configs = configs,
                    bottomBar = {
                        QuickSshBottomBar(
                            selectedTab = "LIST",
                            activeSessionCount = sessions.size,
                            activeTunnelCount = activeTunnelCount,
                            onHomeClicked = { currentScreen = "LIST" },
                            onSessionsClicked = { openSessionsScreen() },
                            onTransferClicked = { currentScreen = "TRANSFER" },
                            onTunnelsClicked = { currentScreen = "TUNNELS" },
                            onSettingsClicked = { currentScreen = "SETTINGS" }
                        )
                    },
                    onAddClicked = {
                        configToEdit = null
                        isCopyMode = false
                        decryptedPassword = null
                        connectionTestStatus = ""
                        currentScreen = "ADD"
                    },
                    onAddWorkspaceClicked = { serverConfig ->
                        configToEdit = serverConfig.copy(
                            id = 0,
                            name = "New Workspace",
                            workDirectory = null,
                            postConnectCommand = null,
                            terminalFontSizeSp = serverConfig.terminalFontSizeSp,
                            terminalWrapEnabled = serverConfig.terminalWrapEnabled,
                            terminalTerm = serverConfig.terminalTerm,
                            terminalShortcuts = serverConfig.terminalShortcuts,
                            updateTime = System.currentTimeMillis()
                        )
                        isCopyMode = true
                        decryptedPassword = KeystoreManager.decrypt(serverConfig.encryptedPassword ?: "")
                        connectionTestStatus = ""
                        currentScreen = "ADD"
                    },
                    onEditClicked = { config ->
                        runAfterSensitiveUnlock(biometricUnlockEnabled, "Edit saved server credentials") {
                            configToEdit = config
                            isCopyMode = false
                            decryptedPassword = KeystoreManager.decrypt(config.encryptedPassword ?: "")
                            connectionTestStatus = ""
                            currentScreen = "ADD"
                        }
                    },
                    onCopyClicked = { config ->
                        runAfterSensitiveUnlock(biometricUnlockEnabled, "Copy saved server credentials") {
                            configToEdit = config.copy(
                                id = 0,
                                name = copiedWorkspaceName(
                                    sourceName = config.name,
                                    existingNames = configs.filter { it.serverIdentityKey() == config.serverIdentityKey() }.map { it.name }
                                ),
                                updateTime = System.currentTimeMillis()
                            )
                            isCopyMode = true
                            decryptedPassword = KeystoreManager.decrypt(config.encryptedPassword ?: "")
                            connectionTestStatus = ""
                            currentScreen = "ADD"
                        }
                    },
                    onReorderServers = { orderedServerNodeIds ->
                        scope.launch(Dispatchers.IO) {
                            db.sshConfigDao().reorderServerNodes(orderedServerNodeIds)
                        }
                    },
                    onReorderWorkspaces = { serverNodeId, orderedWorkspaceIds ->
                        scope.launch(Dispatchers.IO) {
                            db.sshConfigDao().reorderWorkspaces(serverNodeId, orderedWorkspaceIds)
                        }
                    },
                    onConnectClicked = { config ->
                        runAfterSensitiveUnlock(biometricUnlockEnabled, "Start SSH session") {
                            val sessionId = createSessionId(config)
                            val state = ensureTerminalState(sessionId, config)
                            scope.launch {
                                state.bufferMutex.withLock {
                                    state.buffer.clear()
                                }
                                state.logs = emptyList()
                                state.alternateScreen = false
                                state.applicationCursorKeys = false
                                state.bracketedPasteMode = false
                                activeSessionId = sessionId
                                lastTerminalSessionId = sessionId
                                connectingConfig = config
                                showConnectingDialog = true
                                startSshService(sessionId, config)
                                attachSessionCollector(sessionId, state, openTerminalOnFirstOutput = true)
                            }
                        }
                    },
                    onDeleteClicked = { config ->
                        scope.launch {
                            db.sshConfigDao().deleteConfig(config)
                            Toast.makeText(this@MainActivity, "Configuration removed from database", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            "SETTINGS" -> {
                SettingsScreen(
                    language = uiLanguage,
                    autoWrapEnabled = autoWrapEnabled,
                    privacyModeEnabled = privacyModeEnabled,
                    biometricUnlockEnabled = biometricUnlockEnabled,
                    strictHostKeyVerificationEnabled = strictHostKeyVerificationEnabled,
                    biometricAvailable = isBiometricAvailable(),
                    downloadDirectoryLabel = downloadDirectoryLabel(downloadDirectoryUriText),
                    serverProfileCount = configs.size,
                    backupStatusText = configBackupStatus,
                    bottomBar = {
                        QuickSshBottomBar(
                            selectedTab = "SETTINGS",
                            activeSessionCount = sessions.size,
                            activeTunnelCount = activeTunnelCount,
                            onHomeClicked = { currentScreen = "LIST" },
                            onSessionsClicked = { openSessionsScreen() },
                            onTransferClicked = { currentScreen = "TRANSFER" },
                            onTunnelsClicked = { currentScreen = "TUNNELS" },
                            onSettingsClicked = { currentScreen = "SETTINGS" }
                        )
                    },
                    onAutoWrapChange = { enabled ->
                        autoWrapEnabled = enabled
                        settings.edit().putBoolean(KEY_TERMINAL_AUTO_WRAP, enabled).apply()
                    },
                    onPrivacyModeChange = { enabled ->
                        privacyModeEnabled = enabled
                        settings.edit().putBoolean(KEY_PRIVACY_MODE, enabled).apply()
                    },
                    onBiometricUnlockChange = { enabled ->
                        when (biometricToggleAction(biometricUnlockEnabled, enabled)) {
                            BiometricToggleAction.ENABLE -> {
                                if (!isBiometricAvailable()) {
                                    Toast.makeText(this@MainActivity, "Biometric unlock is not available on this device", Toast.LENGTH_SHORT).show()
                                } else {
                                    biometricUnlockEnabled = true
                                    settings.edit().putBoolean(KEY_BIOMETRIC_UNLOCK, true).apply()
                                }
                            }
                            BiometricToggleAction.DISABLE_WITH_AUTH -> {
                                runAfterSensitiveUnlock(true, "Disable biometric unlock") {
                                    biometricUnlockEnabled = false
                                    settings.edit().putBoolean(KEY_BIOMETRIC_UNLOCK, false).apply()
                                }
                            }
                            BiometricToggleAction.NO_CHANGE -> Unit
                        }
                    },
                    onStrictHostKeyVerificationChange = { enabled ->
                        strictHostKeyVerificationEnabled = enabled
                        settings.edit().putBoolean(KnownHostsVerifier.KEY_STRICT_HOST_KEY_VERIFICATION, enabled).apply()
                    },
                    onChooseDownloadDirectory = {
                        openDownloadDirectoryPicker().onFailure { error ->
                            configBackupStatus = "选择下载目录失败：${error.localizedMessage ?: error.javaClass.simpleName}"
                        }
                    },
                    onExportConfigs = { password ->
                        configBackupStatus = ""
                        pendingExportBackupPassword = password
                        openConfigBackupExportPicker(defaultBackupFileName()).onFailure { error ->
                            configBackupStatus = "Export failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                            pendingExportBackupPassword = null
                        }
                    },
                    onImportConfigs = { password ->
                        configBackupStatus = ""
                        pendingImportBackupPassword = password
                        openConfigBackupImportPicker().onFailure { error ->
                            configBackupStatus = "Import failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
                            pendingImportBackupPassword = null
                        }
                    },
                    onLanguageChange = { language ->
                        uiLanguage = language
                        settings.edit().putString(KEY_LANGUAGE, language.code).apply()
                    }
                )
            }
            "TRANSFER" -> {
                LaunchedEffect(configs, transferConfig?.id) {
                    if (configs.isEmpty()) {
                        transferConfig = null
                        uploadRemotePath = ""
                        transferRemotePath = ""
                    } else {
                        val currentConfig = transferConfig
                        val matchingConfig = currentConfig?.let { current ->
                            configs.firstOrNull { it.id == current.id }
                        }
                        when {
                            currentConfig == null -> {
                                savedTransferDownloadConfig(configs)?.let { config ->
                                    selectTransferConfig(config, restoreSavedDownloadPath = true)
                                }
                            }
                            matchingConfig == null -> {
                                savedTransferDownloadConfig(configs)?.let { config ->
                                    selectTransferConfig(config, restoreSavedDownloadPath = true)
                                }
                            }
                            matchingConfig != currentConfig -> {
                                transferConfig = matchingConfig
                            }
                        }
                    }
                }
                TransferScreen(
                    configs = configs,
                    selectedConfig = transferConfig,
                    selectedLocalUris = transferLocalUris,
                    uploadRemotePath = uploadRemotePath,
                    downloadRemotePath = transferRemotePath,
                    downloadDirectoryLabel = downloadDirectoryLabel(downloadDirectoryUriText),
                    statusText = transferStatus,
                    isTransferring = transferBusy,
                    progressText = transferProgressText,
                    progressFraction = transferProgressFraction,
                    queuedCount = queuedTransfers.size,
                    queuePaused = transferQueuePaused,
                    uploadConflictPolicy = uploadConflictPolicy,
                    downloadConflictPolicy = downloadConflictPolicy,
                    tasks = displayedTransferTasks,
                    remoteEntries = remoteEntries,
                    remoteBrowserStatus = remoteBrowserStatus,
                    isBrowsingRemote = isBrowsingRemote,
                    selectedRemotePaths = selectedRemoteEntries.keys.toSet(),
                    remoteMultiSelectEnabled = remoteMultiSelectEnabled,
                    isPreparingRemoteDownloadPlan = remoteDownloadPlanJob?.isActive == true,
                    remoteDownloadPlanElapsedSeconds = remoteDownloadPlanElapsedSeconds,
                    showRemoteDownloadPlanSlowWarning = showRemoteDownloadPlanSlowWarning,
                    remoteDownloadPlanConfirmation = pendingRemoteDownloadConfirmation?.let { pending ->
                        RemoteDownloadPlanConfirmationUiState(
                            fileCount = pending.fileCount,
                            directoryCount = pending.directoryCount
                        )
                    },
                    bottomBar = {
                        QuickSshBottomBar(
                            selectedTab = "TRANSFER",
                            activeSessionCount = sessions.size,
                            activeTunnelCount = activeTunnelCount,
                            onHomeClicked = { currentScreen = "LIST" },
                            onSessionsClicked = { openSessionsScreen() },
                            onTransferClicked = { currentScreen = "TRANSFER" },
                            onTunnelsClicked = { currentScreen = "TUNNELS" },
                            onSettingsClicked = { currentScreen = "SETTINGS" }
                        )
                    },
                    onConfigSelected = { config ->
                        selectTransferConfig(config, restoreSavedDownloadPath = true)
                        remoteEntries.clear()
                        remoteBrowserStatus = ""
                        clearRemoteSelection()
                        pendingRemoteDownloadConfirmation = null
                    },
                    onUploadRemotePathChange = { uploadRemotePath = it },
                    onDownloadRemotePathChange = {
                        transferRemotePath = it
                        rememberTransferDownloadSelection(transferConfig, it)
                    },
                    onChooseDownloadDirectory = {
                        openDownloadDirectoryPicker().onFailure { error ->
                            transferStatus = "选择下载目录失败：${error.localizedMessage ?: error.javaClass.simpleName}"
                        }
                    },
                    onPickLocalFile = {
                        uploadPickerTarget = UploadPickerTarget.Transfer
                        openUploadFilePicker().onFailure { error ->
                            transferStatus = "失败：无法打开系统文件选择器：${error.localizedMessage ?: error.javaClass.simpleName}"
                        }
                    },
                    onUploadClicked = {
                        val config = transferConfig
                        val uris = transferLocalUris
                        if (config == null || uris.isEmpty()) {
                            transferStatus = "\u5931\u8d25\uff1a\u8bf7\u5148\u9009\u62e9\u670d\u52a1\u5668\u548c\u672c\u673a\u6587\u4ef6"
                        } else {
                            uris.forEachIndexed { index, uri ->
                                val fileName = displayNameFromUri(uri).ifBlank { "Local file ${index + 1}" }
                                enqueueTransfer(
                                    QueuedTransferRequest(
                                        direction = "Upload",
                                        config = config,
                                        localUri = uri,
                                        destinationUri = null,
                                        remotePath = uploadRemotePath,
                                        fileName = fileName,
                                        uploadConflictPolicy = uploadConflictPolicy
                                    )
                                )
                            }
                            transferStatus = if (uris.size == 1) {
                                "已开始上传：${displayNameFromUri(uris.first()).ifBlank { "Local file" }}"
                            } else {
                                "已加入上传队列：${uris.size} 个文件"
                            }
                        }
                    },
                    onDownloadClicked = {
                        val config = transferConfig
                        if (config == null) {
                            transferStatus = "失败：请先选择服务器"
                            return@TransferScreen
                        }
                        val remotePath = transferRemotePath.trim()
                        if (remotePath.isBlank()) {
                            transferStatus = "失败：请填写远端文件路径"
                            return@TransferScreen
                        }
                        val remoteName = remoteDownloadFileName(transferRemotePath)
                        val directoryUri = downloadDirectoryUriText.takeIf { it.isNotBlank() }?.let(Uri::parse)
                        if (directoryUri == null) {
                            pendingSingleDownload = PendingSingleDownload(
                                config = config,
                                remotePath = remotePath,
                                fileName = remoteName,
                                conflictPolicy = downloadConflictPolicy
                            )
                            transferStatus = "请选择下载目录；之后会自动复用该目录"
                            openDownloadDirectoryPicker().onFailure { error ->
                                pendingSingleDownload = null
                                transferStatus = "失败：无法打开系统目录选择器：${error.localizedMessage ?: error.javaClass.simpleName}"
                            }
                        } else {
                            val destinationUri = createDocumentInTree(directoryUri, remoteName, downloadConflictPolicy)
                            if (destinationUri == null) {
                                transferStatus = downloadTargetCreationFailureMessage(downloadConflictPolicy)
                            } else {
                                enqueueSingleDownload(
                                    config = config,
                                    remotePath = remotePath,
                                    fileName = remoteName,
                                    destinationUri = destinationUri
                                )
                            }
                        }
                    },
                    onPauseQueue = {
                        transferQueuePaused = true
                        transferStatus = "队列已暂停"
                    },
                    onResumeQueue = {
                        transferQueuePaused = false
                        transferStatus = "队列已继续"
                        pumpTransferQueue()
                    },
                    onClearQueue = {
                        val clearedCount = queuedTransfers.size
                        queuedTransfers.clear()
                        transferStatus = transferQueueClearStatus(clearedCount)
                    },
                    onUploadConflictPolicyChange = { policy ->
                        uploadConflictPolicy = policy
                    },
                    onDownloadConflictPolicyChange = { policy ->
                        downloadConflictPolicy = policy
                    },
                    onCancelTransfer = {
                        TransferForegroundService.cancelTransfer(this@MainActivity)
                        transferStatus = "正在取消传输..."
                    },
                    onBrowseRemote = { browseRemote() },
                    onRemoteEntryClicked = { entry ->
                        clearRemoteSelection()
                        transferRemotePath = entry.path
                        rememberTransferDownloadSelection(transferConfig, transferRemotePath)
                        if (entry.isDirectory) browseRemote(entry.path)
                    },
                    onRemoteEntrySelectionToggle = { entry ->
                        toggleRemoteEntrySelection(entry)
                    },
                    onRemoteMultiSelectStarted = { entry ->
                        remoteMultiSelectEnabled = true
                        setRemoteEntrySelected(entry, true)
                    },
                    onRemoteSelectionCleared = {
                        clearRemoteSelection()
                    },
                    onDownloadSelectedRemoteEntries = {
                        startSelectedRemoteDownloads(selectedRemoteEntries.values.toList())
                    },
                    onCancelRemoteDownloadPlan = {
                        showRemoteDownloadPlanSlowWarning = false
                        remoteDownloadPlanSlowWarningDismissed = false
                        remoteDownloadPlanJob?.cancel()
                    },
                    onKeepPreparingRemoteDownloadPlan = {
                        showRemoteDownloadPlanSlowWarning = false
                        remoteDownloadPlanSlowWarningDismissed = true
                    },
                    onConfirmRemoteDownloadPlan = {
                        val pending = pendingRemoteDownloadConfirmation
                        if (pending != null) {
                            pendingRemoteDownloadConfirmation = null
                            transferStatus = "正在继续创建下载计划..."
                            startLocalRemoteDownloadPlanCreation(pending)
                        }
                    },
                    onCancelPendingRemoteDownloadPlan = {
                        pendingRemoteDownloadConfirmation = null
                        clearRemoteSelection()
                        transferStatus = "已取消大目录下载计划"
                    },
                    onRemoteParentClicked = {
                        val parent = transferRemotePath.trimEnd('/').substringBeforeLast('/', "")
                        transferRemotePath = parent.ifBlank { "." }
                        rememberTransferDownloadSelection(transferConfig, transferRemotePath)
                        browseRemote(transferRemotePath)
                        clearRemoteSelection()
                    },
                    onTaskClicked = { task -> openTransferTask(task) },
                    onTaskDeleted = { task -> deleteTransferTask(task) }
                )
            }
            "TUNNELS" -> {
                val visibleTunnelPresets = tunnelPresets.filter { preset ->
                    preset.workspaceId == tunnelConfig?.id
                }
                LaunchedEffect(configs, tunnelConfig?.id) {
                    if (configs.isNotEmpty()) {
                        val savedConfigId = settings
                            .getLong(KEY_TUNNEL_CONFIG_ID, -1L)
                            .takeIf { it > 0L }
                        val resolved = resolveTunnelConfig(
                            configs = configs,
                            currentConfigId = tunnelConfig?.id,
                            savedConfigId = savedConfigId
                        )
                        if (resolved?.id != tunnelConfig?.id) {
                            tunnelConfig = resolved
                        }
                        if (resolved != null && resolved.id != savedConfigId) {
                            settings.edit().putLong(KEY_TUNNEL_CONFIG_ID, resolved.id).apply()
                        }
                    }
                }
                LaunchedEffect(tunnelConfig?.id, tunnelPresets) {
                    val selectedStillVisible = visibleTunnelPresets.any { it.id == selectedTunnelPresetId }
                    if (!selectedStillVisible) {
                        selectedTunnelPresetId = null
                    }
                }
                LaunchedEffect(tunnelStates) {
                    val latest = tunnelStates.firstOrNull()
                    if (latest != null) {
                        tunnelStatusText = when (latest.status) {
                            TunnelStatus.CONNECTING -> if (latest.statusText.startsWith("Reconnecting")) {
                                "正在重连：${latest.serverLabel}"
                            } else {
                                "正在连接：${latest.serverLabel}"
                            }
                            TunnelStatus.RUNNING -> "隧道已启动：${latest.browserUrl}"
                            TunnelStatus.FAILED -> "失败：${latest.statusText}"
                            TunnelStatus.STOPPED -> latest.statusText
                        }
                    }
                }
                TunnelScreen(
                    configs = configs,
                    selectedConfig = tunnelConfig,
                    remoteHost = tunnelRemoteHost,
                    remotePort = tunnelRemotePort,
                    localPort = tunnelLocalPort,
                    presetName = tunnelPresetName,
                    presetNote = tunnelPresetNote,
                    selectedPresetId = selectedTunnelPresetId,
                    tunnelPresets = visibleTunnelPresets,
                    statusText = tunnelStatusText,
                    tunnelStates = tunnelStates,
                    bottomBar = {
                        QuickSshBottomBar(
                            selectedTab = "TUNNELS",
                            activeSessionCount = sessions.size,
                            activeTunnelCount = activeTunnelCount,
                            onHomeClicked = { currentScreen = "LIST" },
                            onSessionsClicked = { openSessionsScreen() },
                            onTransferClicked = { currentScreen = "TRANSFER" },
                            onTunnelsClicked = { currentScreen = "TUNNELS" },
                            onSettingsClicked = { currentScreen = "SETTINGS" }
                        )
                    },
                    onConfigSelected = { config ->
                        tunnelConfig = config
                        selectedTunnelPresetId = null
                        settings.edit().putLong(KEY_TUNNEL_CONFIG_ID, config.id).apply()
                    },
                    onRemoteHostChange = { tunnelRemoteHost = it },
                    onRemotePortChange = { tunnelRemotePort = it },
                    onLocalPortChange = { tunnelLocalPort = it },
                    onPresetNameChange = { tunnelPresetName = it },
                    onPresetNoteChange = { tunnelPresetNote = it },
                    onPresetSelected = { preset ->
                        selectedTunnelPresetId = preset.id
                        tunnelPresetName = preset.name
                        tunnelPresetNote = preset.note.orEmpty()
                        tunnelRemoteHost = preset.remoteHost
                        tunnelRemotePort = preset.remotePort.toString()
                        tunnelLocalPort = preset.localPort.takeIf { it > 0 }?.toString().orEmpty()
                    },
                    onNewPreset = {
                        selectedTunnelPresetId = null
                        tunnelPresetName = ""
                        tunnelPresetNote = ""
                        tunnelRemoteHost = TunnelForegroundService.LOOPBACK_HOST
                        tunnelRemotePort = "3000"
                        tunnelLocalPort = ""
                        tunnelStatusText = "\u5DF2\u5207\u6362\u5230\u65B0\u5EFA\u96A7\u9053\u9884\u8BBE"
                    },
                    onSavePreset = {
                        val config = tunnelConfig
                        val remotePortValue = parseTunnelPort(tunnelRemotePort, allowAuto = false)
                        val localPortValue = parseTunnelPort(tunnelLocalPort, allowAuto = true)
                        when {
                            config == null -> tunnelStatusText = "失败：请先选择服务器"
                            remotePortValue == null -> tunnelStatusText = "失败：远端端口必须是 1-65535"
                            localPortValue == null -> tunnelStatusText = "失败：手机端口必须为空或 1-65535"
                            else -> {
                                val remoteHost = tunnelRemoteHost.trim().ifBlank { TunnelForegroundService.LOOPBACK_HOST }
                                val now = System.currentTimeMillis()
                                val preset = SshTunnelPreset(
                                    id = selectedTunnelPresetId ?: 0L,
                                    workspaceId = config.id,
                                    name = tunnelPresetName.trim().ifBlank { tunnelPresetDefaultName(remoteHost, remotePortValue) },
                                    note = tunnelPresetNote.trim().takeIf { it.isNotEmpty() },
                                    remoteHost = remoteHost,
                                    remotePort = remotePortValue,
                                    localPort = localPortValue,
                                    updateTime = now
                                )
                                scope.launch {
                                    val dao = db.sshConfigDao()
                                    val savedId = dao.saveTunnelPreset(preset)
                                    selectedTunnelPresetId = savedId
                                    tunnelPresetName = preset.name
                                    tunnelPresetNote = preset.note.orEmpty()
                                    tunnelStatusText = "已保存隧道预设：${preset.name}"
                                }
                            }
                        }
                    },
                    onDeletePreset = {
                        val presetId = selectedTunnelPresetId
                        if (presetId != null) {
                            scope.launch {
                                db.sshConfigDao().deleteTunnelPresetById(presetId)
                                selectedTunnelPresetId = null
                                tunnelPresetName = ""
                                tunnelPresetNote = ""
                                tunnelStatusText = "已删除隧道预设"
                            }
                        }
                    },
                    onStartTunnel = {
                        val config = tunnelConfig
                        val remotePortValue = parseTunnelPort(tunnelRemotePort, allowAuto = false)
                        val localPortValue = parseTunnelPort(tunnelLocalPort, allowAuto = true)
                        when {
                            config == null -> tunnelStatusText = "失败：请先选择服务器"
                            remotePortValue == null -> tunnelStatusText = "失败：远端端口必须是 1-65535"
                            localPortValue == null -> tunnelStatusText = "失败：手机端口必须为空或 1-65535"
                            else -> {
                                runAfterSensitiveUnlock(biometricUnlockEnabled, "Start SSH tunnel") {
                                    requestNotificationPermissionIfNeeded()
                                    val remoteHost = tunnelRemoteHost.trim().ifBlank { TunnelForegroundService.LOOPBACK_HOST }
                                    TunnelForegroundService.startTunnel(
                                        context = this@MainActivity,
                                        configId = config.id,
                                        remoteHost = remoteHost,
                                        remotePort = remotePortValue,
                                        localPort = localPortValue
                                    )
                                    tunnelStatusText = "正在启动隧道：$remoteHost:$remotePortValue"
                                }
                            }
                        }
                    },
                    onStopTunnel = { tunnelId ->
                        TunnelForegroundService.stopTunnel(this@MainActivity, tunnelId)
                    },
                    onStopAllTunnels = {
                        TunnelForegroundService.stopAllTunnels(this@MainActivity)
                    },
                    onOpenInternal = ::openTunnelUrlInternal,
                    onOpenExternal = ::openTunnelUrlExternal
                )
            }
            "SESSIONS" -> {
                ActiveSessionsScreen(
                    sessions = sessions,
                    bottomBar = {
                        QuickSshBottomBar(
                            selectedTab = "SESSIONS",
                            activeSessionCount = sessions.size,
                            activeTunnelCount = activeTunnelCount,
                            onHomeClicked = { currentScreen = "LIST" },
                            onSessionsClicked = { openSessionsScreen() },
                            onTransferClicked = { currentScreen = "TRANSFER" },
                            onTunnelsClicked = { currentScreen = "TUNNELS" },
                            onSettingsClicked = { currentScreen = "SETTINGS" }
                        )
                    },
                    onBackClicked = {
                        if (!openMostRecentTerminal()) currentScreen = "LIST"
                    },
                    onOpenSession = { session ->
                        openTerminalFromSession(session)
                    },
                    onRenameSession = { sessionId, name ->
                        boundService?.renameSession(sessionId, name)
                        terminalStates[sessionId]?.let { state ->
                            val currentConfig = state.config
                            if (currentConfig != null) {
                                state.config = currentConfig.copy(name = name.trim().ifBlank { currentConfig.name })
                            }

                    }
                    },
                    onReconnectSession = { sessionId ->
                        boundService?.reconnectSession(sessionId)
                    },
                    onDisconnectSession = { sessionId ->
                        stopSshService(sessionId)
                        terminalStates.remove(sessionId)?.collectorJob?.cancel()
                        if (activeSessionId == sessionId) activeSessionId = null
                        if (lastTerminalSessionId == sessionId) lastTerminalSessionId = null
                    }
                )
            }
            "ADD" -> {
                SshAddScreen(
                    configToEdit = configToEdit,
                    isCopyMode = isCopyMode,
                    decryptedPassword = decryptedPassword,
                    connectionTestStatus = connectionTestStatus,
                    isTestingConnection = isTestingConnection,
                    onBackClicked = {
                        configToEdit = null
                        isCopyMode = false
                        decryptedPassword = null
                        connectionTestStatus = ""
                        currentScreen = "LIST"
                    },
                    onTestConnectionClicked = { sName, sHost, sPort, sUser, sAuthType, sPass, sPrivateKey, sWorkDir, sPostCommand, sFontSize, sWrapEnabled, sTerm, sShortcuts ->
                        val existingEncryptedPassword = configToEdit?.encryptedPassword
                        val existingEncryptedPrivateKey = configToEdit?.encryptedPrivateKey
                        scope.launch {
                            isTestingConnection = true
                            connectionTestStatus = "Testing connection..."
                            val encryptedPassword = if (sAuthType == AUTH_TYPE_PASSWORD) {
                                when {
                                    sPass.isNotEmpty() -> KeystoreManager.encrypt(sPass)
                                    !existingEncryptedPassword.isNullOrBlank() -> existingEncryptedPassword
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val encryptedPrivateKey = if (sAuthType == AUTH_TYPE_PRIVATE_KEY) {
                                when {
                                    sPrivateKey.isNotBlank() -> KeystoreManager.encrypt(sPrivateKey)
                                    !existingEncryptedPrivateKey.isNullOrBlank() -> existingEncryptedPrivateKey
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val testConfig = SshConfig(
                                name = sName,
                                host = sHost,
                                port = sPort,
                                username = sUser,
                                authType = sAuthType,
                                encryptedPassword = encryptedPassword,
                                encryptedPrivateKey = encryptedPrivateKey,
                                workDirectory = if (sWorkDir.isNotBlank()) sWorkDir else null,
                                postConnectCommand = if (sPostCommand.isNotBlank()) sPostCommand else null,
                                terminalFontSizeSp = sFontSize,
                                terminalWrapEnabled = sWrapEnabled,
                                terminalTerm = sTerm,
                                terminalShortcuts = if (sShortcuts.isNotBlank()) sShortcuts else null
                            )
                            val result = transferHelper().testConnection(testConfig)
                            connectionTestStatus = result.fold(
                                onSuccess = { "Connection test passed" },
                                onFailure = { "Connection test failed: ${it.localizedMessage ?: it.javaClass.simpleName}" }
                            )
                            isTestingConnection = false
                        }
                    },
                    onSaveClicked = { sName, sHost, sPort, sUser, sAuthType, sPass, sPrivateKey, sWorkDir, sPostCommand, sFontSize, sWrapEnabled, sTerm, sShortcuts ->
                        scope.launch {
                            val existingEncryptedPassword = configToEdit?.encryptedPassword
                            val existingEncryptedPrivateKey = configToEdit?.encryptedPrivateKey
                            val encryptedPassword = if (sAuthType == AUTH_TYPE_PASSWORD) {
                                when {
                                    sPass.isNotEmpty() -> KeystoreManager.encrypt(sPass)
                                    !existingEncryptedPassword.isNullOrBlank() -> existingEncryptedPassword
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val encryptedPrivateKey = if (sAuthType == AUTH_TYPE_PRIVATE_KEY) {
                                when {
                                    sPrivateKey.isNotBlank() -> KeystoreManager.encrypt(sPrivateKey)
                                    !existingEncryptedPrivateKey.isNullOrBlank() -> existingEncryptedPrivateKey
                                    else -> null
                                }
                            } else {
                                null
                            }
                            if (configToEdit == null || isCopyMode) {
                                val newConfig = SshConfig(
                                    name = sName,
                                    host = sHost,
                                    port = sPort,
                                    username = sUser,
                                    authType = sAuthType,
                                    encryptedPassword = encryptedPassword,
                                    encryptedPrivateKey = encryptedPrivateKey,
                                    workDirectory = if (sWorkDir.isNotBlank()) sWorkDir else null,
                                    postConnectCommand = if (sPostCommand.isNotBlank()) sPostCommand else null,
                                    terminalFontSizeSp = sFontSize,
                                    terminalWrapEnabled = sWrapEnabled,
                                    terminalTerm = sTerm,
                                    terminalShortcuts = if (sShortcuts.isNotBlank()) sShortcuts else null,
                                    serverNodeId = configToEdit?.serverNodeId ?: 0L
                                )
                                db.sshConfigDao().insertConfig(newConfig)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (isCopyMode) "Workspace saved" else "Server saved",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val originalConfig = configToEdit!!
                                val updatedAt = System.currentTimeMillis()
                                val updatedConfig = originalConfig.copy(
                                    name = sName,
                                    host = sHost,
                                    port = sPort,
                                    username = sUser,
                                    authType = sAuthType,
                                    encryptedPassword = encryptedPassword,
                                    encryptedPrivateKey = encryptedPrivateKey,
                                    workDirectory = if (sWorkDir.isNotBlank()) sWorkDir else null,
                                    postConnectCommand = if (sPostCommand.isNotBlank()) sPostCommand else null,
                                    terminalFontSizeSp = sFontSize,
                                    terminalWrapEnabled = sWrapEnabled,
                                    terminalTerm = sTerm,
                                    terminalShortcuts = if (sShortcuts.isNotBlank()) sShortcuts else null,
                                    updateTime = updatedAt
                                )
                                db.sshConfigDao().updateConfig(updatedConfig)
                                Toast.makeText(this@MainActivity, "Configuration updated", Toast.LENGTH_SHORT).show()
                            }
                            configToEdit = null
                            isCopyMode = false
                            decryptedPassword = null
                            connectionTestStatus = ""
                            currentScreen = "LIST"
                        }
                    }
                )
            }
            "TUNNEL_WEB" -> {
                val url = tunnelWebUrl
                if (url.isNullOrBlank()) {
                    LaunchedEffect(Unit) {
                        currentScreen = "TUNNELS"
                    }
                } else {
                    TunnelWebScreen(
                        url = url,
                        onBackClicked = {
                            tunnelWebUrl = null
                            currentScreen = "TUNNELS"
                        },
                        onOpenExternal = ::openTunnelUrlExternal
                    )
                }
            }
            "TERMINAL" -> {
                val sessionId = activeSessionId
                val state = sessionId?.let { terminalStates[it] }
                val stateConfig = state?.config
                if (sessionId == null || state == null || stateConfig == null) {
                    LaunchedEffect(sessionId) {
                        currentScreen = "LIST"
                    }
                } else {
                    TerminalScreen(
                        config = stateConfig,
                        logs = state.logs,
                        alternateScreen = state.alternateScreen,
                        applicationCursorKeys = state.applicationCursorKeys,
                        bracketedPasteMode = state.bracketedPasteMode,
                        autoWrapEnabled = autoWrapEnabled,
                        onHomeClicked = {
                            currentScreen = "LIST"
                        },
                        onLineSend = { line ->
                            boundService?.getSshHelper(sessionId)?.sendLine(line)
                        },
                        onCodexResumeShortcut = { command ->
                            boundService?.getSshHelper(sessionId)
                                ?.runCodexResumeShortcut(command, stateConfig.workDirectory)
                        },
                        onRawInputSend = { data ->
                            boundService?.getSshHelper(sessionId)?.sendRawInput(data)
                        },
                        onTerminalResize = { size ->
                            scope.launch(Dispatchers.Default) {
                                val resized = state.bufferMutex.withLock {
                                    TerminalUiSnapshot(
                                        logs = state.buffer.resize(size),
                                        alternateScreen = state.buffer.isInAlternateScreen,
                                        applicationCursorKeys = state.buffer.applicationCursorKeys,
                                        bracketedPasteMode = state.buffer.bracketedPasteMode
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    state.alternateScreen = resized.alternateScreen
                                    state.applicationCursorKeys = resized.applicationCursorKeys
                                    state.bracketedPasteMode = resized.bracketedPasteMode
                                    state.logs = resized.logs
                                    boundService?.getSshHelper(sessionId)?.resizeTerminal(size)
                                }
                            }
                        },
                        quickUploadStatus = state.quickUploadStatus,
                        pendingInputInsertion = state.pendingInputInsertion,
                        onPendingInputInsertionConsumed = {
                            state.pendingInputInsertion = null
                        },
                        onQuickUploadClicked = {
                            uploadPickerTarget = UploadPickerTarget.Terminal(sessionId)
                            openUploadFilePicker().onFailure { error ->
                                state.quickUploadStatus = "Unable to open file picker: ${error.localizedMessage ?: error.javaClass.simpleName}"
                            }
                        },
                        onDisconnectClicked = {
                            stopSshService(sessionId)
                            terminalStates.remove(sessionId)?.collectorJob?.cancel()
                            activeSessionId = null
                            if (lastTerminalSessionId == sessionId) lastTerminalSessionId = null
                            currentScreen = if (sessions.size > 1) "SESSIONS" else "LIST"
                        }
                    )
                }
            }
        }
        if (showConnectingDialog) {
            AlertDialog(
                onDismissRequest = { 
                },
                confirmButton = { },
                title = {
                    Text(text = "Connecting")
                },
                text = {
                    Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(16.dp)
                        )
                        Text(
                            text = "Opening SSH connection: ${connectingConfig?.name}...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun openUploadFilePicker(): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_UPLOAD_FILE)
    }

    private fun selectedUploadUris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || data == null) return emptyList()
        val uris = linkedSetOf<Uri>()
        data.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        data.data?.let(uris::add)
        return uris.toList()
    }

    @Suppress("DEPRECATION")
    private fun openDownloadDestinationPicker(fileName: String): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_CREATE_DOWNLOAD_FILE)
    }

    @Suppress("DEPRECATION")
    private fun openDownloadDirectoryPicker(): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_PICK_DOWNLOAD_DIRECTORY)
    }

    @Suppress("DEPRECATION")
    private fun openConfigBackupExportPicker(fileName: String): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_CREATE_CONFIG_BACKUP)
    }

    @Suppress("DEPRECATION")
    private fun openConfigBackupImportPicker(): Result<Unit> = runCatching {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_CONFIG_BACKUP)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    private fun createDocumentInTree(
        treeUri: Uri,
        fileName: String,
        conflictPolicy: FileTransferHelper.UploadConflictPolicy
    ): Uri? {
        return runCatching {
            val parentUri = treeRootDocumentUri(treeUri) ?: return@runCatching null
            createDocumentInDocument(parentUri, fileName, conflictPolicy)
        }.getOrNull()
    }

    private suspend fun createRemoteDownloadPlan(
        config: SshConfig,
        treeEntries: List<FileTransferHelper.RemoteTreeEntry>,
        rootDocumentUri: Uri,
        conflictPolicy: FileTransferHelper.UploadConflictPolicy
    ): RemoteDownloadPlan = withContext(Dispatchers.IO) {
        val planContext = currentCoroutineContext()
        val localDirectoryUris = mutableMapOf("" to rootDocumentUri)
        val requests = mutableListOf<QueuedTransferRequest>()
        var createdDirectoryCount = 0
        var createFailedCount = 0

        treeEntries
            .filter { it.isDirectory }
            .sortedBy { remoteRelativeDepth(it.relativePath) }
            .forEach { directory ->
                planContext.ensureActive()
                val parentUri = localDirectoryUris[remoteRelativeParentPath(directory.relativePath)]
                if (parentUri == null) {
                    createFailedCount++
                    return@forEach
                }
                val directoryName = remoteRelativeLeafName(directory.relativePath)
                val createdUri = createDirectoryInDocument(parentUri, directoryName, conflictPolicy)
                if (createdUri == null) {
                    createFailedCount++
                } else {
                    localDirectoryUris[directory.relativePath] = createdUri
                    createdDirectoryCount++
                }
            }

        treeEntries
            .filterNot { it.isDirectory }
            .forEach { file ->
                planContext.ensureActive()
                val parentUri = localDirectoryUris[remoteRelativeParentPath(file.relativePath)]
                if (parentUri == null) {
                    createFailedCount++
                    return@forEach
                }
                val fileName = remoteDownloadFileName(file.path, remoteRelativeLeafName(file.relativePath))
                val destinationUri = createDocumentInDocument(parentUri, fileName, conflictPolicy)
                if (destinationUri == null) {
                    createFailedCount++
                } else {
                    grantTransferUriAccess(destinationUri, writable = true)
                    requests.add(
                        QueuedTransferRequest(
                            direction = "Download",
                            config = config,
                            localUri = null,
                            destinationUri = destinationUri,
                            remotePath = file.path,
                            fileName = fileName
                        )
                    )
                }
            }

        RemoteDownloadPlan(
            requests = requests,
            createdDirectoryCount = createdDirectoryCount,
            createFailedCount = createFailedCount
        )
    }

    private fun treeRootDocumentUri(treeUri: Uri): Uri? {
        return runCatching {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        }.getOrNull()
    }

    private fun createDirectoryInDocument(
        parentDocumentUri: Uri,
        directoryName: String,
        conflictPolicy: FileTransferHelper.UploadConflictPolicy
    ): Uri? {
        return createChildDocument(
            parentDocumentUri = parentDocumentUri,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            displayName = directoryName,
            conflictPolicy = conflictPolicy
        )
    }

    private fun createDocumentInDocument(
        parentDocumentUri: Uri,
        fileName: String,
        conflictPolicy: FileTransferHelper.UploadConflictPolicy
    ): Uri? {
        return createChildDocument(
            parentDocumentUri = parentDocumentUri,
            mimeType = "application/octet-stream",
            displayName = fileName,
            conflictPolicy = conflictPolicy
        )
    }

    private fun createChildDocument(
        parentDocumentUri: Uri,
        mimeType: String,
        displayName: String,
        conflictPolicy: FileTransferHelper.UploadConflictPolicy
    ): Uri? {
        return runCatching {
            val normalizedName = displayName.trim().ifBlank { "quickssh-download" }
            val existing = findChildDocument(parentDocumentUri, normalizedName)
            when (conflictPolicy) {
                FileTransferHelper.UploadConflictPolicy.RENAME -> {
                    val finalName = findAvailableDocumentNameInDocument(parentDocumentUri, normalizedName)
                    DocumentsContract.createDocument(
                        contentResolver,
                        parentDocumentUri,
                        mimeType,
                        finalName
                    )
                }
                FileTransferHelper.UploadConflictPolicy.OVERWRITE -> {
                    when {
                        existing == null -> DocumentsContract.createDocument(
                            contentResolver,
                            parentDocumentUri,
                            mimeType,
                            normalizedName
                        )
                        mimeType == DocumentsContract.Document.MIME_TYPE_DIR && existing.isDirectory -> existing.uri
                        mimeType != DocumentsContract.Document.MIME_TYPE_DIR && !existing.isDirectory -> existing.uri
                        else -> null
                    }
                }
                FileTransferHelper.UploadConflictPolicy.FAIL -> {
                    if (existing != null) {
                        null
                    } else {
                        DocumentsContract.createDocument(
                            contentResolver,
                            parentDocumentUri,
                            mimeType,
                            normalizedName
                        )
                    }
                }
            }
        }.getOrNull()
    }

    private fun downloadTargetCreationFailureMessage(policy: FileTransferHelper.UploadConflictPolicy): String {
        return when (policy) {
            FileTransferHelper.UploadConflictPolicy.RENAME -> "失败：无法在设置的下载目录创建文件"
            FileTransferHelper.UploadConflictPolicy.OVERWRITE -> "失败：无法覆盖同名本地项目，请检查目标是否为文件夹或无写入权限"
            FileTransferHelper.UploadConflictPolicy.FAIL -> "失败：下载目录中已存在同名项目"
        }
    }

    private fun grantTransferUriAccess(uri: Uri, writable: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        runCatching { grantUriPermission(packageName, uri, flags) }
    }

    private fun findAvailableDocumentName(treeUri: Uri, fileName: String): String {
        val rootDocumentUri = treeRootDocumentUri(treeUri) ?: return fileName.trim().ifBlank { "quickssh-download" }
        return findAvailableDocumentNameInDocument(rootDocumentUri, fileName)
    }

    private fun findAvailableDocumentNameInDocument(parentDocumentUri: Uri, fileName: String): String {
        val normalizedName = fileName.trim().ifBlank { "quickssh-download" }
        val existingNames = runCatching {
            val parentDocumentId = DocumentsContract.getDocumentId(parentDocumentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDocumentUri, parentDocumentId)
            buildSet {
                contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        while (cursor.moveToNext()) {
                            cursor.getString(nameIndex)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
            }
        }.getOrDefault(emptySet())

        if (normalizedName !in existingNames) return normalizedName

        val dotIndex = normalizedName.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < normalizedName.lastIndex
        val baseName = if (hasExtension) normalizedName.substring(0, dotIndex) else normalizedName
        val extension = if (hasExtension) normalizedName.substring(dotIndex) else ""

        var copyIndex = 2
        while (true) {
            val candidate = "$baseName ($copyIndex)$extension"
            if (candidate !in existingNames) return candidate
            copyIndex++
        }
    }

    private fun findChildDocument(parentDocumentUri: Uri, displayName: String): DocumentChild? {
        val targetName = displayName.trim()
        if (targetName.isBlank()) return null
        return runCatching {
            val parentDocumentId = DocumentsContract.getDocumentId(parentDocumentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDocumentUri, parentDocumentId)
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idIndex < 0 || nameIndex < 0) return@use null
                while (cursor.moveToNext()) {
                    val childName = cursor.getString(nameIndex)?.trim().orEmpty()
                    if (childName == targetName) {
                        val documentId = cursor.getString(idIndex)
                        return@use DocumentChild(
                            uri = DocumentsContract.buildDocumentUriUsingTree(parentDocumentUri, documentId),
                            mimeType = if (mimeTypeIndex >= 0) cursor.getString(mimeTypeIndex) else null
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun ensureInstallPermissionIfNeeded(uri: Uri): Boolean {
        if (!isApkUri(uri) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (packageManager.canRequestPackageInstalls()) return true

        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
        Toast.makeText(this, "Allow QuickSSH to install APK files, then open this file again", Toast.LENGTH_LONG).show()
        return false

    }

    private fun isApkUri(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri).orEmpty()
        if (mimeType == "application/vnd.android.package-archive") return true
        return displayNameFromUri(uri).lowercase().endsWith(".apk")
    }

    private fun displayNameFromUri(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    private suspend fun exportServerConfigs(uri: Uri, backupPassword: String?): Int = withContext(Dispatchers.IO) {
        val tunnelPresetsByWorkspace = db.sshConfigDao()
            .getAllTunnelPresets()
            .groupBy { preset -> preset.workspaceId }
        val records = db.sshConfigDao().getAllConfigs().map { config ->
            SshConfigBackupRecord(
                name = config.name,
                host = config.host,
                port = config.port,
                username = config.username,
                authType = config.authType,
                password = decryptBackupValue(config.encryptedPassword),
                privateKey = decryptBackupValue(config.encryptedPrivateKey),
                workDirectory = config.workDirectory,
                postConnectCommand = config.postConnectCommand,
                terminalFontSizeSp = config.terminalFontSizeSp,
                terminalWrapEnabled = config.terminalWrapEnabled,
                terminalTerm = config.terminalTerm,
                terminalShortcuts = config.terminalShortcuts,
                tunnelPresets = tunnelPresetsByWorkspace[config.id].orEmpty().map { preset ->
                    preset.toBackupRecord()
                }
            )
        }
        val json = SshConfigBackupCodec.encode(records, backupPassword)
        val stream = contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Unable to open export file")
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json)
        }
        records.size
    }

    private suspend fun importServerConfigs(uri: Uri, backupPassword: String?): Int = withContext(Dispatchers.IO) {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open import file")
        val json = stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        val records = SshConfigBackupCodec.decode(json, backupPassword)
        val dao = db.sshConfigDao()
        val existingByKey = dao.getAllConfigs()
            .associateBy { config -> SshConfigBackupCodec.mergeKey(config) }
            .toMutableMap()
        val importedConfigs = mutableListOf<SshConfig>()

        records.forEach { record ->
            val key = SshConfigBackupCodec.mergeKey(record)
            val existingConfig = existingByKey[key]
            val importedConfig = record.toSshConfig(System.currentTimeMillis()).let { config ->
                if (existingConfig == null) config else config.copy(id = existingConfig.id)
            }
            val savedConfig = if (existingConfig == null) {
                val id = dao.insertConfig(importedConfig)
                dao.getConfigById(id) ?: importedConfig.copy(id = id)
            } else {
                dao.updateConfig(importedConfig)
                dao.getConfigById(importedConfig.id) ?: importedConfig
            }
            upsertImportedTunnelPresets(savedConfig.id, record.tunnelPresets)
            existingByKey[key] = savedConfig
            importedConfigs.add(savedConfig)
        }
        restoreImportedConfigOrder(importedConfigs)
        records.size
    }

    private suspend fun restoreImportedConfigOrder(importedConfigs: List<SshConfig>) {
        if (importedConfigs.isEmpty()) return
        val dao = db.sshConfigDao()
        val currentConfigs = dao.getAllConfigs()

        val currentServerOrder = currentConfigs.map { it.serverNodeId }.filter { it > 0L }.distinct()
        val desiredServerOrder = importedConfigs.map { it.serverNodeId }.filter { it > 0L }.distinct()
        val reorderedServerOrder = stableReorderSubset(currentServerOrder, desiredServerOrder)
        if (reorderedServerOrder.isNotEmpty()) {
            dao.reorderServerNodes(reorderedServerOrder)
        }

        val currentWorkspaceOrders = currentConfigs
            .groupBy { it.serverNodeId }
            .mapValues { entry -> entry.value.map { it.id } }
        val desiredWorkspaceOrders = importedConfigs
            .groupBy { it.serverNodeId }
            .mapValues { entry -> entry.value.map { it.id } }
        desiredWorkspaceOrders.forEach { (serverNodeId, desiredIds) ->
            val reordered = stableReorderSubset(currentWorkspaceOrders[serverNodeId].orEmpty(), desiredIds)
            if (reordered.isNotEmpty()) {
                dao.reorderWorkspaces(serverNodeId, reordered)
            }
        }
    }

    private fun stableReorderSubset(fullOrder: List<Long>, desiredSubsetOrder: List<Long>): List<Long> {
        if (fullOrder.isEmpty() || desiredSubsetOrder.isEmpty()) return fullOrder
        val desiredQueue = ArrayDeque(desiredSubsetOrder.distinct())
        val desiredSet = desiredQueue.toSet()
        return fullOrder.map { id ->
            if (id in desiredSet && desiredQueue.isNotEmpty()) {
                desiredQueue.removeFirst()
            } else {
                id
            }
        }
    }

    private suspend fun upsertImportedTunnelPresets(workspaceId: Long, records: List<SshTunnelPresetBackupRecord>) {
        if (workspaceId <= 0L || records.isEmpty()) return
        val dao = db.sshConfigDao()
        records.forEach { record ->
            val remoteHost = record.remoteHost.trim().ifBlank { TunnelForegroundService.LOOPBACK_HOST }
            val remotePort = record.remotePort.coerceIn(1, TunnelForegroundService.MAX_PORT)
            val localPort = record.localPort.coerceIn(0, TunnelForegroundService.MAX_PORT)
            val name = record.name.trim().ifBlank { tunnelPresetDefaultName(remoteHost, remotePort) }
            val existing = dao.findTunnelPreset(
                workspaceId = workspaceId,
                name = name,
                remoteHost = remoteHost,
                remotePort = remotePort,
                localPort = localPort
            )
            val preset = SshTunnelPreset(
                id = existing?.id ?: 0L,
                workspaceId = workspaceId,
                name = name,
                note = record.note?.trim()?.takeIf { it.isNotEmpty() },
                remoteHost = remoteHost,
                remotePort = remotePort,
                localPort = localPort,
                updateTime = System.currentTimeMillis()
            )
            if (existing == null) {
                dao.insertTunnelPreset(preset)
            } else {
                dao.saveTunnelPreset(preset)
            }
        }
    }

    private fun SshConfigBackupRecord.toSshConfig(updateTime: Long): SshConfig {
        val normalizedAuthType = if (authType == AUTH_TYPE_PRIVATE_KEY) AUTH_TYPE_PRIVATE_KEY else AUTH_TYPE_PASSWORD
        return SshConfig(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = normalizedAuthType,
            encryptedPassword = password?.takeIf { it.isNotEmpty() }?.let(KeystoreManager::encrypt),
            encryptedPrivateKey = privateKey?.takeIf { it.isNotBlank() }?.let(KeystoreManager::encrypt),
            workDirectory = workDirectory,
            postConnectCommand = postConnectCommand,
            terminalFontSizeSp = terminalFontSizeSp,
            terminalWrapEnabled = terminalWrapEnabled,
            terminalTerm = terminalTerm,
            terminalShortcuts = terminalShortcuts,
            updateTime = updateTime
        )
    }

    private fun decryptBackupValue(encryptedValue: String?): String? {
        val value = encryptedValue?.takeIf { it.isNotBlank() } ?: return null
        return KeystoreManager.decrypt(value).takeIf { it.isNotEmpty() }
    }

    private fun SshTunnelPreset.toBackupRecord(): SshTunnelPresetBackupRecord {
        return SshTunnelPresetBackupRecord(
            name = name,
            note = note,
            remoteHost = remoteHost,
            remotePort = remotePort,
            localPort = localPort
        )
    }

    private fun defaultBackupFileName(): String {
        return "quickssh-servers-${System.currentTimeMillis()}.json"
    }

    private suspend fun migrateLegacyTransferTasks(settings: android.content.SharedPreferences) {
        val legacyTasks = loadTransferTasks(settings)
        if (legacyTasks.isEmpty()) return
        val dao = db.transferHistoryDao()
        if (dao.count() == 0) {
            legacyTasks.reversed().forEach { dao.insert(it.toEntity()) }
            dao.trimToLimit(TRANSFER_HISTORY_RETAIN_LIMIT)
        }
        settings.edit().remove(KEY_TRANSFER_TASKS).apply()
    }

    private fun TransferHistoryEntry.toUiState(): TransferTaskUiState {
        return TransferTaskUiState(
            id = id,
            fileName = fileName,
            direction = direction,
            serverName = serverName,
            status = status,
            localUri = localUri?.let(Uri::parse),
            remotePath = remotePath,
            detail = detail,
            serverNodeName = serverNodeName,
            workspaceName = workspaceName
        )
    }

    private fun TransferTaskUiState.toEntity(): TransferHistoryEntry {
        val now = System.currentTimeMillis()
        return TransferHistoryEntry(
            id = if (id > 0L) id else 0,
            fileName = fileName,
            direction = direction,
            serverName = serverName,
            status = status,
            localUri = localUri?.toString(),
            remotePath = remotePath,
            detail = detail,
            serverNodeName = serverNodeName,
            workspaceName = workspaceName,
            createdAt = now,
            updateTime = now
        )
    }

    private fun loadTransferTasks(settings: android.content.SharedPreferences): List<TransferTaskUiState> {
        val raw = settings.getString(KEY_TRANSFER_TASKS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TransferTaskUiState(
                            fileName = item.optString("fileName"),
                            direction = item.optString("direction"),
                            serverName = item.optString("serverName"),
                            status = item.optString("status"),
                            localUri = item.optString("localUri").takeIf { it.isNotBlank() }?.let(Uri::parse),
                            remotePath = item.optString("remotePath"),
                            detail = item.optString("detail"),
                            serverNodeName = item.optString("serverNodeName").ifBlank { item.optString("serverName").substringBefore(" / ") },
                            workspaceName = item.optString("workspaceName").ifBlank { item.optString("serverName").substringAfter(" / ", item.optString("serverName")) }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun downloadDirectoryLabel(uriText: String): String {
        if (uriText.isBlank()) return "Not set; downloads use the system save picker"
        return runCatching {
            val uri = Uri.parse(uriText)
            val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
            URLDecoder.decode(treeDocumentId.substringAfter(':'), StandardCharsets.UTF_8.name()).ifBlank { treeDocumentId }
        }.getOrDefault("Download directory set")
    }

    private fun startSshService(sessionId: String, config: SshConfig) {
        val serviceIntent = Intent(this, SshForegroundService::class.java).apply {
            action = SshForegroundService.ACTION_START_SSH
            putExtra(SshForegroundService.EXTRA_SESSION_ID, sessionId)
            putExtra(SshForegroundService.EXTRA_CONFIG_ID, config.id)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindSshService(createIfMissing = true)
    }

    private fun stopSshService(sessionId: String? = null) {
        val serviceIntent = Intent(this, SshForegroundService::class.java).apply {
            action = SshForegroundService.ACTION_STOP_SSH
            sessionId?.let { putExtra(SshForegroundService.EXTRA_SESSION_ID, it) }
        }
        startService(serviceIntent)
    }

    private fun createSessionId(config: SshConfig): String {
        return "ssh-${config.id}-${System.currentTimeMillis()}"
    }

    private fun runAfterSensitiveUnlock(enabled: Boolean, reason: String, onUnlocked: () -> Unit) {
        if (!enabled) {
            onUnlocked()
            return
        }
        if (!isBiometricAvailable()) {
            Toast.makeText(this, "Biometric unlock is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("QuickSSH unlock")
            .setSubtitle(reason)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun isBiometricAvailable(): Boolean {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    private fun applyPrivacyMode(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun bindSshService(createIfMissing: Boolean) {
        if (isBound) return
        val flags = if (createIfMissing) Context.BIND_AUTO_CREATE else 0
        runCatching {
            bindService(Intent(this, SshForegroundService::class.java), serviceConnection, flags)
        }
    }

    private companion object {
        const val PREFS_NAME = "quickssh_settings"
        const val KEY_LANGUAGE = "ui_language"
        const val KEY_TERMINAL_AUTO_WRAP = "terminal_auto_wrap"
        const val KEY_PRIVACY_MODE = "privacy_mode"
        const val KEY_BIOMETRIC_UNLOCK = "biometric_unlock"
        const val KEY_DOWNLOAD_DIRECTORY_URI = "download_directory_uri"
        const val KEY_TRANSFER_DOWNLOAD_CONFIG_ID = "transfer_download_config_id"
        const val KEY_TRANSFER_DOWNLOAD_REMOTE_PATH = "transfer_download_remote_path"
        const val KEY_TUNNEL_CONFIG_ID = "tunnel_config_id"
        const val KEY_TRANSFER_TASKS = "transfer_tasks"
        const val TERMINAL_UI_UPDATE_INTERVAL_MS = 80L
        const val TERMINAL_ALTERNATE_UI_UPDATE_INTERVAL_MS = 48L
        const val TERMINAL_SYNCHRONIZED_UI_UPDATE_INTERVAL_MS = 180L
        const val TRANSFER_NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
        const val REQUEST_PICK_UPLOAD_FILE = 1101
        const val REQUEST_CREATE_DOWNLOAD_FILE = 1102
        const val REQUEST_PICK_DOWNLOAD_DIRECTORY = 1103
        const val REQUEST_CREATE_CONFIG_BACKUP = 1104
        const val REQUEST_PICK_CONFIG_BACKUP = 1105
        const val REQUEST_POST_NOTIFICATIONS = 1106
    }
}





















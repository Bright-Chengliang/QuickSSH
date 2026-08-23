package com.quickssh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.quickssh.app.MainActivity
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.data.SshConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SshSessionInfo(
    val sessionId: String,
    val configId: Long,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val workDirectory: String?,
    val terminalFontSizeSp: Int = 12,
    val terminalWrapEnabled: Boolean? = null,
    val terminalTerm: String = "xterm-256color",
    val terminalShortcuts: String? = null,
    val startedAt: Long,
    val status: SshSessionStatus = SshSessionStatus.CONNECTING,
    val statusMessage: String = "Connecting"
)

class SshForegroundService : Service() {

    private val binder = LocalBinder()
    private val sshHelpers = mutableMapOf<String, SshClientHelper>()
    private val sessionInfosById = linkedMapOf<String, SshSessionInfo>()
    private val _sessionInfos = MutableStateFlow<List<SshSessionInfo>>(emptyList())
    val sessionInfos: StateFlow<List<SshSessionInfo>> = _sessionInfos.asStateFlow()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            sshHelpers.values.forEach { it.reconnectWhenNetworkAvailable() }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SshForegroundService = this@SshForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START_SSH) {
            val configId = intent.getLongExtra(EXTRA_CONFIG_ID, -1L)
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                ?: "ssh-$configId-${System.currentTimeMillis()}"
            startForeground(NOTIFICATION_ID, createNotification())
            serviceScope.launch {
                val config = AppDatabase.getDatabase(applicationContext).sshConfigDao().getConfigById(configId)
                if (config == null) {
                    publishMissingConfig(sessionId, configId)
                } else {
                    startSshSession(sessionId, config)
                }
            }
        } else if (action == ACTION_STOP_SSH) {
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            if (sessionId.isNullOrBlank()) {
                stopAllSshSessions()
            } else {
                stopSshSession(sessionId)
            }
        }
        return START_STICKY
    }

    private fun publishMissingConfig(sessionId: String, configId: Long) {
        sessionInfosById[sessionId] = SshSessionInfo(
            sessionId = sessionId,
            configId = configId,
            name = "Missing profile",
            host = "",
            port = 0,
            username = "",
            workDirectory = null,
            startedAt = System.currentTimeMillis(),
            status = SshSessionStatus.FAILED,
            statusMessage = "Saved server profile was not found"
        )
        publishSessions()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun startSshSession(sessionId: String, config: SshConfig) {
        val existingSession = sessionInfosById[sessionId]
        if (
            existingSession?.configId == config.id &&
            sshHelpers[sessionId] != null &&
            sshSessionCanBeReused(existingSession.status)
        ) {
            publishSessions()
            startForeground(NOTIFICATION_ID, createNotification())
            return
        }

        sshHelpers[sessionId]?.disconnect()
        val helper = SshClientHelper(config, applicationContext)
        sshHelpers[sessionId] = helper
        sessionInfosById[sessionId] = SshSessionInfo(
            sessionId = sessionId,
            configId = config.id,
            name = config.name,
            host = config.host,
            port = config.port,
            username = config.username,
            workDirectory = config.workDirectory,
            terminalFontSizeSp = config.terminalFontSizeSp,
            terminalWrapEnabled = config.terminalWrapEnabled,
            terminalTerm = config.terminalTerm,
            terminalShortcuts = config.terminalShortcuts,
            startedAt = System.currentTimeMillis(),
            status = SshSessionStatus.CONNECTING,
            statusMessage = "Connecting"
        )
        observeSessionStatus(sessionId, helper)
        publishSessions()
        helper.connect()

        // Keep the foreground notification alive while SSH sessions are active.
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    fun getSshHelper(sessionId: String): SshClientHelper? {
        return sshHelpers[sessionId]
    }

    fun findReusableSessionForConfig(configId: Long): SshSessionInfo? {
        return reusableSshSessionForConfig(configId, _sessionInfos.value)
    }

    private fun observeSessionStatus(sessionId: String, helper: SshClientHelper) {
        serviceScope.launch {
            helper.status.collect { status ->
                val current = sessionInfosById[sessionId] ?: return@collect
                sessionInfosById[sessionId] = current.copy(status = status)
                publishSessions()
                startForeground(NOTIFICATION_ID, createNotification())
            }
        }
        serviceScope.launch {
            helper.statusMessage.collect { message ->
                val current = sessionInfosById[sessionId] ?: return@collect
                sessionInfosById[sessionId] = current.copy(statusMessage = message)
                publishSessions()
            }
        }
    }
    fun reconnectSession(sessionId: String) {
        sshHelpers[sessionId]?.reconnectNow()
    }

    fun renameSession(sessionId: String, name: String) {
        val current = sessionInfosById[sessionId] ?: return
        val nextName = name.trim().ifBlank { current.name }
        sessionInfosById[sessionId] = current.copy(name = nextName)
        publishSessions()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun stopSshSession(sessionId: String) {
        sshHelpers.remove(sessionId)?.disconnect()
        sessionInfosById.remove(sessionId)
        publishSessions()
        if (sshHelpers.isEmpty()) {
            stopForegroundCompat(removeNotification = true)
            stopSelf()
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun stopAllSshSessions() {
        sshHelpers.values.forEach { it.disconnect() }
        sshHelpers.clear()
        sessionInfosById.clear()
        publishSessions()
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun publishSessions() {
        _sessionInfos.value = sessionInfosById.values.toList()
    }
    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = manager
        runCatching {
            manager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        }
    }

    override fun onDestroy() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        sshHelpers.values.forEach { it.disconnect() }
        sshHelpers.clear()
        sessionInfosById.clear()
        publishSessions()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun createNotification(): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        val sessions = _sessionInfos.value
        val contentText = sshNotificationContentText(sessions)

        val stopIntent = Intent(this, SshForegroundService::class.java).apply {
            action = ACTION_STOP_SSH
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickSSH session active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect all", stopPendingIntent)
            .setOngoing(sessions.isNotEmpty())

        sshNotificationDisconnectTarget(sessions)?.let { currentSession ->
            val stopCurrentIntent = Intent(this, SshForegroundService::class.java).apply {
                action = ACTION_STOP_SSH
                putExtra(EXTRA_SESSION_ID, currentSession.sessionId)
            }
            val stopCurrentPendingIntent = PendingIntent.getService(
                this,
                2,
                stopCurrentIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val label = if (sessions.size == 1) "Disconnect current" else "Disconnect latest"
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, label, stopCurrentPendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "SSH Foreground Session Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(removeNotification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "QuickSshServiceChannel"
        private const val NOTIFICATION_ID = 4041

        const val ACTION_START_SSH = "com.quickssh.app.action.START_SSH"
        const val ACTION_STOP_SSH = "com.quickssh.app.action.STOP_SSH"
        
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_CONFIG_ID = "extra_config_id"
    }
}

internal fun sshNotificationContentText(sessions: List<SshSessionInfo>): String {
    return when (sessions.size) {
        0 -> "No active SSH sessions"
        1 -> {
            val session = sessions.first()
            "${session.status.displayText()}: ${session.username}@${session.host}:${session.port}"
        }
        else -> "Keeping ${sessions.size} SSH sessions active in the background"
    }
}

internal fun sshNotificationDisconnectTarget(sessions: List<SshSessionInfo>): SshSessionInfo? {
    return sessions.maxByOrNull { it.startedAt }
}

internal fun sshSessionCanBeReused(status: SshSessionStatus): Boolean {
    return when (status) {
        SshSessionStatus.CONNECTING,
        SshSessionStatus.CONNECTED,
        SshSessionStatus.RECONNECTING -> true
        SshSessionStatus.FAILED,
        SshSessionStatus.DISCONNECTED -> false
    }
}

internal fun reusableSshSessionForConfig(configId: Long, sessions: List<SshSessionInfo>): SshSessionInfo? {
    if (configId <= 0L) return null
    return sessions
        .asSequence()
        .filter { it.configId == configId && sshSessionCanBeReused(it.status) }
        .maxByOrNull { it.startedAt }
}





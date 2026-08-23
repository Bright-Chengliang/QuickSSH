package com.quickssh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.quickssh.app.MainActivity
import com.quickssh.app.data.AppDatabase
import com.quickssh.app.data.SshConfig
import com.quickssh.app.data.transferContextLabel
import com.quickssh.app.security.KeystoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.Security

private const val TUNNEL_RECONNECT_MAX_ATTEMPTS = 3

enum class TunnelStatus {
    CONNECTING,
    RUNNING,
    FAILED,
    STOPPED
}

data class TunnelServiceState(
    val tunnelId: String,
    val configId: Long,
    val serverLabel: String,
    val sshHost: String,
    val remoteHost: String,
    val remotePort: Int,
    val localHost: String,
    val localPort: Int,
    val status: TunnelStatus,
    val statusText: String,
    val startedAt: Long = System.currentTimeMillis()
) {
    val localEndpoint: String = "$localHost:$localPort"
    val browserUrl: String = "http://$localEndpoint"
}

class TunnelForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTunnels = linkedMapOf<String, TunnelRuntime>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TUNNEL -> startTunnel(intent)
            ACTION_STOP_TUNNEL -> stopTunnel(intent.getStringExtra(EXTRA_TUNNEL_ID))
            ACTION_STOP_ALL_TUNNELS -> stopAllTunnels()
        }
        return START_STICKY
    }

    private fun startTunnel(intent: Intent) {
        val request = TunnelRequest.from(intent) ?: run {
            publishTransientFailure("Invalid tunnel request")
            return
        }
        val notification = createNotification("QuickSSH tunnel", "Starting SSH tunnel...", ongoing = true)
        if (!startForegroundSafely(notification)) {
            publishTransientFailure("Unable to start tunnel foreground service")
            stopSelf()
            return
        }

        val job = serviceScope.launch {
            runTunnel(request)
        }
        activeTunnels[request.tunnelId] = TunnelRuntime(job = job)
        publishAll()
    }

    private suspend fun runTunnel(request: TunnelRequest) {
        val database = AppDatabase.getDatabase(applicationContext)
        val config = database.sshConfigDao().getConfigById(request.configId)
        if (config == null) {
            publishState(request.failedState("Saved server profile was not found"))
            cleanupTunnel(request.tunnelId, removeStoppedState = false)
            return
        }

        var desiredLocalPort = request.localPort
        var lastKnownLocalPort = request.localPort
        var reconnectAttempt = 0

        try {
            while (request.tunnelId in activeTunnels) {
                var client: SSHClient? = null
                var forwarder: LocalPortForwarder? = null
                var serverSocket: ServerSocket? = null
                try {
                    publishState(
                        if (reconnectAttempt == 0) {
                            request.connectingState(config, lastKnownLocalPort)
                        } else {
                            request.reconnectingState(config, lastKnownLocalPort, reconnectAttempt)
                        }
                    )
                    client = SSHClient().apply {
                        addHostKeyVerifier(KnownHostsVerifier(applicationContext))
                        connect(config.host, config.port)
                        connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
                    }
                    authenticate(client, config)

                    serverSocket = createLoopbackServerSocket(desiredLocalPort)
                    val actualLocalPort = serverSocket.localPort
                    lastKnownLocalPort = actualLocalPort
                    if (desiredLocalPort == 0) {
                        desiredLocalPort = actualLocalPort
                    }
                    val parameters = localTunnelParameters(
                        LOOPBACK_HOST,
                        actualLocalPort,
                        request.remoteHost,
                        request.remotePort
                    )
                    forwarder = client.newLocalPortForwarder(parameters, serverSocket)
                    activeTunnels[request.tunnelId] = TunnelRuntime(
                        job = activeTunnels[request.tunnelId]?.job,
                        client = client,
                        forwarder = forwarder,
                        serverSocket = serverSocket
                    )
                    reconnectAttempt = 0
                    publishState(request.runningState(config, actualLocalPort))
                    notificationManager().notify(
                        NOTIFICATION_ID,
                        createNotification("QuickSSH tunnel active", "Forwarding ${request.remoteHost}:${request.remotePort} to 127.0.0.1:$actualLocalPort", ongoing = true)
                    )
                    forwarder.listen()
                    publishState(request.stoppedState(config, actualLocalPort, "Tunnel stopped"))
                    return
                } catch (error: Throwable) {
                    val actualLocalPort = serverSocket?.localPort ?: lastKnownLocalPort
                    if (error is CancellationException || request.tunnelId !in activeTunnels) {
                        publishState(request.stoppedState(config, actualLocalPort, "Tunnel stopped"))
                        return
                    }

                    val nextAttempt = reconnectAttempt + 1
                    if (tunnelShouldRetry(error, nextAttempt)) {
                        reconnectAttempt = nextAttempt
                        val delayMillis = tunnelReconnectDelayMillis(reconnectAttempt)
                        publishState(request.reconnectingState(config, actualLocalPort, reconnectAttempt, delayMillis))
                        notificationManager().notify(
                            NOTIFICATION_ID,
                            createNotification(
                                "QuickSSH tunnel reconnecting",
                                tunnelReconnectStatusText(reconnectAttempt, delayMillis),
                                ongoing = true
                            )
                        )
                        activeTunnels[request.tunnelId] = TunnelRuntime(job = activeTunnels[request.tunnelId]?.job)
                        delay(delayMillis)
                    } else {
                        val message = friendlyTunnelError(error)
                        publishState(request.failedState(message, config, actualLocalPort))
                        return
                    }
                } finally {
                    runCatching { forwarder?.close() }
                    runCatching { serverSocket?.close() }
                    runCatching { client?.disconnect() }
                }
            }
        } finally {
            cleanupTunnel(request.tunnelId, removeStoppedState = false)
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess
    }

    private fun stopTunnel(tunnelId: String?) {
        if (tunnelId.isNullOrBlank()) return
        val runtime = activeTunnels[tunnelId] ?: return
        runtime.job?.cancel()
        runCatching { runtime.forwarder?.close() }
        runCatching { runtime.serverSocket?.close() }
        runCatching { runtime.client?.disconnect() }
        val current = tunnelStates.value.firstOrNull { it.tunnelId == tunnelId }
        if (current != null) {
            publishState(current.copy(status = TunnelStatus.STOPPED, statusText = "Tunnel stopped"))
        }
        cleanupTunnel(tunnelId, removeStoppedState = false)
    }

    private fun stopAllTunnels() {
        activeTunnels.keys.toList().forEach(::stopTunnel)
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun cleanupTunnel(tunnelId: String, removeStoppedState: Boolean) {
        activeTunnels.remove(tunnelId)
        if (removeStoppedState) {
            _tunnelStates.value = _tunnelStates.value.filterNot { it.tunnelId == tunnelId }
        }
        publishAll()
        if (activeTunnels.isEmpty()) {
            stopForegroundCompat(removeNotification = false)
            stopSelf()
        }
    }

    private fun publishAll() {
        val runningCount = _tunnelStates.value.count { it.status == TunnelStatus.RUNNING || it.status == TunnelStatus.CONNECTING }
        if (runningCount > 0) {
            notificationManager().notify(
                NOTIFICATION_ID,
                createNotification("QuickSSH tunnel active", tunnelNotificationText(_tunnelStates.value), ongoing = true)
            )
        }
    }

    private fun publishTransientFailure(message: String) {
        _tunnelStates.value = _tunnelStates.value + TunnelServiceState(
            tunnelId = "failed-${System.currentTimeMillis()}",
            configId = -1,
            serverLabel = "Tunnel",
            sshHost = "",
            remoteHost = LOOPBACK_HOST,
            remotePort = 0,
            localHost = LOOPBACK_HOST,
            localPort = 0,
            status = TunnelStatus.FAILED,
            statusText = message
        )
    }

    private fun publishState(state: TunnelServiceState) {
        val states = _tunnelStates.value.toMutableList()
        val index = states.indexOfFirst { it.tunnelId == state.tunnelId }
        if (index >= 0) {
            states[index] = state
        } else {
            states.add(0, state)
        }
        _tunnelStates.value = states
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

    private fun createLoopbackServerSocket(localPort: Int): ServerSocket {
        val bindPort = localPort.coerceIn(0, MAX_PORT)
        return ServerSocket(bindPort, 50, InetAddress.getByName(LOOPBACK_HOST))
    }

    private fun createNotification(title: String, message: String, ongoing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAllIntent = Intent(this, TunnelForegroundService::class.java).apply {
            action = ACTION_STOP_ALL_TUNNELS
        }
        val stopAllPendingIntent = PendingIntent.getService(
            this,
            1,
            stopAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop tunnels", stopAllPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SSH Tunnel Channel",
                NotificationManager.IMPORTANCE_LOW
            )
        )
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
        activeTunnels.values.forEach { runtime ->
            runtime.job?.cancel()
            runCatching { runtime.forwarder?.close() }
            runCatching { runtime.serverSocket?.close() }
            runCatching { runtime.client?.disconnect() }
        }
        activeTunnels.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class TunnelRuntime(
        val job: Job? = null,
        val client: SSHClient? = null,
        val forwarder: LocalPortForwarder? = null,
        val serverSocket: ServerSocket? = null
    )

    private data class TunnelRequest(
        val tunnelId: String,
        val configId: Long,
        val remoteHost: String,
        val remotePort: Int,
        val localPort: Int
    ) {
        fun connectingState(config: SshConfig, actualLocalPort: Int = localPort): TunnelServiceState {
            return baseState(config, actualLocalPort, TunnelStatus.CONNECTING, "Connecting SSH tunnel...")
        }

        fun reconnectingState(
            config: SshConfig,
            actualLocalPort: Int,
            attempt: Int,
            delayMillis: Long? = null
        ): TunnelServiceState {
            val message = if (delayMillis == null) {
                "Reconnecting SSH tunnel (attempt $attempt/$TUNNEL_RECONNECT_MAX_ATTEMPTS)..."
            } else {
                tunnelReconnectStatusText(attempt, delayMillis)
            }
            return baseState(config, actualLocalPort, TunnelStatus.CONNECTING, message)
        }

        fun runningState(config: SshConfig, actualLocalPort: Int): TunnelServiceState {
            return baseState(config, actualLocalPort, TunnelStatus.RUNNING, "Tunnel running")
        }

        fun stoppedState(config: SshConfig, actualLocalPort: Int, message: String): TunnelServiceState {
            return baseState(config, actualLocalPort, TunnelStatus.STOPPED, message)
        }

        fun failedState(message: String, config: SshConfig? = null, actualLocalPort: Int = localPort): TunnelServiceState {
            return if (config == null) {
                TunnelServiceState(
                    tunnelId = tunnelId,
                    configId = configId,
                    serverLabel = "Missing profile",
                    sshHost = "",
                    remoteHost = remoteHost,
                    remotePort = remotePort,
                    localHost = LOOPBACK_HOST,
                    localPort = actualLocalPort,
                    status = TunnelStatus.FAILED,
                    statusText = message
                )
            } else {
                baseState(config, actualLocalPort, TunnelStatus.FAILED, message)
            }
        }

        private fun baseState(
            config: SshConfig,
            actualLocalPort: Int,
            status: TunnelStatus,
            statusText: String
        ): TunnelServiceState {
            return TunnelServiceState(
                tunnelId = tunnelId,
                configId = configId,
                serverLabel = config.transferContextLabel(),
                sshHost = config.host,
                remoteHost = remoteHost,
                remotePort = remotePort,
                localHost = LOOPBACK_HOST,
                localPort = actualLocalPort,
                status = status,
                statusText = statusText
            )
        }

        companion object {
            fun from(intent: Intent): TunnelRequest? {
                val configId = intent.getLongExtra(EXTRA_CONFIG_ID, -1L).takeIf { it > 0L } ?: return null
                val remoteHost = intent.getStringExtra(EXTRA_REMOTE_HOST).orEmpty().trim().ifBlank { LOOPBACK_HOST }
                val remotePort = intent.getIntExtra(EXTRA_REMOTE_PORT, -1).takeIf { it in 1..MAX_PORT } ?: return null
                val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 0).takeIf { it in 0..MAX_PORT } ?: 0
                val tunnelId = intent.getStringExtra(EXTRA_TUNNEL_ID)?.takeIf { it.isNotBlank() }
                    ?: "tunnel-$configId-${System.currentTimeMillis()}"
                return TunnelRequest(tunnelId, configId, remoteHost, remotePort, localPort)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "QuickSshTunnelForegroundChannel"
        private const val NOTIFICATION_ID = 4044
        private const val KEEPALIVE_INTERVAL_SECONDS = 20
        const val LOOPBACK_HOST = "127.0.0.1"
        const val MAX_PORT = 65535
        private const val ACTION_START_TUNNEL = "com.quickssh.app.action.START_TUNNEL"
        private const val ACTION_STOP_TUNNEL = "com.quickssh.app.action.STOP_TUNNEL"
        private const val ACTION_STOP_ALL_TUNNELS = "com.quickssh.app.action.STOP_ALL_TUNNELS"
        private const val EXTRA_TUNNEL_ID = "extra_tunnel_id"
        private const val EXTRA_CONFIG_ID = "extra_config_id"
        private const val EXTRA_REMOTE_HOST = "extra_remote_host"
        private const val EXTRA_REMOTE_PORT = "extra_remote_port"
        private const val EXTRA_LOCAL_PORT = "extra_local_port"

        private val _tunnelStates = MutableStateFlow<List<TunnelServiceState>>(emptyList())
        val tunnelStates: StateFlow<List<TunnelServiceState>> = _tunnelStates.asStateFlow()

        init {
            runCatching {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
        }

        fun startTunnel(
            context: Context,
            configId: Long,
            remoteHost: String,
            remotePort: Int,
            localPort: Int
        ): String {
            val tunnelId = "tunnel-$configId-${System.currentTimeMillis()}"
            val intent = Intent(context, TunnelForegroundService::class.java).apply {
                action = ACTION_START_TUNNEL
                putExtra(EXTRA_TUNNEL_ID, tunnelId)
                putExtra(EXTRA_CONFIG_ID, configId)
                putExtra(EXTRA_REMOTE_HOST, remoteHost)
                putExtra(EXTRA_REMOTE_PORT, remotePort)
                putExtra(EXTRA_LOCAL_PORT, localPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return tunnelId
        }

        fun stopTunnel(context: Context, tunnelId: String) {
            val intent = Intent(context, TunnelForegroundService::class.java).apply {
                action = ACTION_STOP_TUNNEL
                putExtra(EXTRA_TUNNEL_ID, tunnelId)
            }
            context.startService(intent)
        }

        fun stopAllTunnels(context: Context) {
            val intent = Intent(context, TunnelForegroundService::class.java).apply {
                action = ACTION_STOP_ALL_TUNNELS
            }
            context.startService(intent)
        }
    }
}

internal fun tunnelNotificationText(states: List<TunnelServiceState>): String {
    val active = states.filter { it.status == TunnelStatus.CONNECTING || it.status == TunnelStatus.RUNNING }
    return when (active.size) {
        0 -> "No active tunnels"
        1 -> {
            val tunnel = active.first()
            "${tunnel.localEndpoint} -> ${tunnel.remoteHost}:${tunnel.remotePort}"
        }
        else -> "Keeping ${active.size} SSH tunnels active"
    }
}

internal fun localTunnelParameters(
    localHost: String,
    localPort: Int,
    remoteHost: String,
    remotePort: Int
): Parameters {
    return Parameters(localHost, localPort, remoteHost, remotePort)
}

internal fun tunnelReconnectDelayMillis(attempt: Int): Long {
    val safeAttempt = attempt.coerceAtLeast(1)
    return when (safeAttempt) {
        1 -> 1_000L
        2 -> 2_000L
        else -> 4_000L
    }
}

internal fun tunnelReconnectStatusText(attempt: Int, delayMillis: Long): String {
    val safeAttempt = attempt.coerceIn(1, TUNNEL_RECONNECT_MAX_ATTEMPTS)
    return "Reconnecting SSH tunnel (attempt $safeAttempt/$TUNNEL_RECONNECT_MAX_ATTEMPTS) in ${delayMillis / 1000}s..."
}

internal fun tunnelShouldRetry(error: Throwable, nextAttempt: Int): Boolean {
    return nextAttempt in 1..TUNNEL_RECONNECT_MAX_ATTEMPTS && tunnelErrorIsRetryable(error)
}

internal fun tunnelErrorIsRetryable(error: Throwable): Boolean {
    val text = tunnelErrorText(error)
    return when {
        error is CancellationException -> false
        error is BindException || text.contains("address already in use") -> false
        text.contains("auth") || text.contains("password") || text.contains("private key") -> false
        text.contains("permission denied") -> false
        else -> true
    }
}

internal fun friendlyTunnelError(error: Throwable): String {
    val text = tunnelErrorText(error)
    return when {
        error is BindException || text.contains("address already in use") -> "Local port is already in use. Try another phone-local port."
        error is IOException && text.contains("permission denied") -> "Unable to bind local port. Try a port above 1024."
        text.contains("auth") || text.contains("password") || text.contains("private key") -> "Authentication failed. Check password or private key."
        text.contains("connection refused") -> "SSH or remote target refused the connection."
        text.contains("timeout") || text.contains("timed out") -> "Connection timed out."
        error.message.isNullOrBlank() -> error.javaClass.simpleName
        else -> error.message ?: error.javaClass.simpleName
    }
}

private fun tunnelErrorText(error: Throwable): String {
    return generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
}

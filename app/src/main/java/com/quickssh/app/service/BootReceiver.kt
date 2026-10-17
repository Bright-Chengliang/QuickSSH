package com.quickssh.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts resident tunnels ([com.quickssh.app.data.SshTunnelPreset.autoStart]) after boot and after the app is
 * updated, so a reverse tunnel to the phone survives reboots without Termux:Boot. Starting the foreground service
 * from these broadcasts is an allowed background-start exemption. Some ROMs (MIUI/HyperOS) additionally require
 * the user to allow "autostart" for the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { TunnelForegroundService.startAutoStartTunnels(appContext) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}

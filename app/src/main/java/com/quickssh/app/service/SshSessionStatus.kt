package com.quickssh.app.service

enum class SshSessionStatus {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
    DISCONNECTED
}

fun SshSessionStatus.displayText(): String = when (this) {
    SshSessionStatus.CONNECTING -> "Connecting"
    SshSessionStatus.CONNECTED -> "Connected"
    SshSessionStatus.RECONNECTING -> "Reconnecting"
    SshSessionStatus.FAILED -> "Failed"
    SshSessionStatus.DISCONNECTED -> "Disconnected"
}
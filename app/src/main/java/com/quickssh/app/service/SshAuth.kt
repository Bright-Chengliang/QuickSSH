package com.quickssh.app.service

import com.quickssh.app.data.SshConfig

const val AUTH_TYPE_PASSWORD = "PASSWORD"
const val AUTH_TYPE_PRIVATE_KEY = "PRIVATE_KEY"
const val AUTH_TYPE_LOCAL = "LOCAL"

internal fun requiresPassword(authType: String): Boolean = authType == AUTH_TYPE_PASSWORD

internal fun isLocalAuth(authType: String): Boolean = authType == AUTH_TYPE_LOCAL

internal fun hasUsableCredential(config: SshConfig): Boolean {
    if (config.isLocalSession || config.authType == AUTH_TYPE_LOCAL) {
        return true
    }
    return if (config.authType == AUTH_TYPE_PRIVATE_KEY) {
        !config.encryptedPrivateKey.isNullOrBlank()
    } else {
        !config.encryptedPassword.isNullOrBlank()
    }
}

internal fun authTypeLabel(authType: String): String {
    return when (authType) {
        AUTH_TYPE_PRIVATE_KEY -> "私钥"
        AUTH_TYPE_LOCAL -> "本地"
        else -> "密码"
    }
}
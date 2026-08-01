package com.quickssh.app.service

import com.quickssh.app.data.SshConfig

const val AUTH_TYPE_PASSWORD = "PASSWORD"
const val AUTH_TYPE_PRIVATE_KEY = "PRIVATE_KEY"

internal fun requiresPassword(authType: String): Boolean = authType != AUTH_TYPE_PRIVATE_KEY

internal fun hasUsableCredential(config: SshConfig): Boolean {
    return if (config.authType == AUTH_TYPE_PRIVATE_KEY) {
        !config.encryptedPrivateKey.isNullOrBlank()
    } else {
        !config.encryptedPassword.isNullOrBlank()
    }
}

internal fun authTypeLabel(authType: String): String {
    return if (authType == AUTH_TYPE_PRIVATE_KEY) "私钥" else "密码"
}
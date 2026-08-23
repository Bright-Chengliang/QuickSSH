package com.quickssh.app.ui.screens

import androidx.compose.runtime.compositionLocalOf

enum class AppLanguage(val code: String) {
    ZH("zh"),
    EN("en");

    companion object {
        fun fromCode(code: String?): AppLanguage = values().firstOrNull { it.code == code } ?: ZH
    }
}

val LocalQuickSshLanguage = compositionLocalOf { AppLanguage.ZH }

fun AppLanguage.text(chinese: String, english: String): String =
    if (this == AppLanguage.ZH) chinese else english


package com.quickssh.app.data

internal fun copiedSshConfigName(sourceName: String, existingNames: Collection<String>): String {
    val baseName = sourceName.ifBlank { "Server" }
    val copyName = "$baseName Copy"
    if (copyName !in existingNames) return copyName

    var index = 2
    while ("$copyName $index" in existingNames) {
        index++
    }
    return "$copyName $index"
}
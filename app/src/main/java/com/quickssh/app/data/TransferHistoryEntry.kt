package com.quickssh.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_history")
data class TransferHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val direction: String,
    val serverName: String,
    val status: String,
    val localUri: String? = null,
    val remotePath: String = "",
    val detail: String = "",
    val serverNodeName: String = serverName.substringBefore(" / ").ifBlank { serverName },
    val workspaceName: String = serverName.substringAfter(" / ", "").ifBlank { serverName },
    val createdAt: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis()
)

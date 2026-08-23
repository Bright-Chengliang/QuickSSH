package com.quickssh.app.service

import android.content.Intent
import com.quickssh.app.data.TransferHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferForegroundServiceTest {
    @Test
    fun transferRunningStatusMatchesDirection() {
        assertEquals(
            "传输中：正在上传...",
            transferRunningStatus(TransferForegroundService.DIRECTION_UPLOAD)
        )
        assertEquals(
            "传输中：正在下载...",
            transferRunningStatus(TransferForegroundService.DIRECTION_DOWNLOAD)
        )
    }

    @Test
    fun transferSuccessStatusIncludesCompletedPath() {
        assertEquals(
            "成功：已上传到 /srv/app/file.zip",
            transferSuccessStatus(TransferForegroundService.DIRECTION_UPLOAD, "/srv/app/file.zip")
        )
        assertEquals(
            "成功：已下载 /downloads/file.zip",
            transferSuccessStatus(TransferForegroundService.DIRECTION_DOWNLOAD, "/downloads/file.zip")
        )
    }

    @Test
    fun transferProgressTextFormatsPercentBytesAndSpeed() {
        val text = transferProgressText(
            FileTransferHelper.TransferProgress(
                transferredBytes = 512L * 1024L,
                totalBytes = 1024L * 1024L,
                speedBytesPerSecond = 128L * 1024L
            )
        )

        assertEquals("50% - 512.0 KB / 1.0 MB - 128.0 KB/s", text)
    }

    @Test
    fun transferProgressTextHandlesUnknownTotal() {
        val text = transferProgressText(
            FileTransferHelper.TransferProgress(
                transferredBytes = 1024L,
                totalBytes = 0L,
                speedBytesPerSecond = 512L
            )
        )

        assertEquals("1.0 KB - 512 B/s", text)
    }

    @Test
    fun completedTransferHistoryEntryStoresFinalRemotePath() {
        val existing = TransferHistoryEntry(
            id = 7L,
            fileName = "local.txt",
            direction = TransferForegroundService.DIRECTION_UPLOAD,
            serverName = "root@example.com:22 / Deploy",
            status = "Transferring",
            localUri = "content://local/file",
            remotePath = "/srv/app",
            detail = "",
            serverNodeName = "root@example.com:22",
            workspaceName = "Deploy",
            createdAt = 10L,
            updateTime = 20L
        )

        val updated = completedTransferHistoryEntry(
            existing = existing,
            status = "Success",
            completedRemotePath = "/srv/app/local (2).txt",
            updateTime = 30L
        )

        assertEquals("Success", updated.status)
        assertEquals("/srv/app/local (2).txt", updated.remotePath)
        assertEquals(30L, updated.updateTime)
    }

    @Test
    fun transferUriGrantFlagsMatchDirectionUriNeeds() {
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            TransferForegroundService.transferUriGrantFlags(hasLocalUri = true, hasDestinationUri = false)
        )
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            TransferForegroundService.transferUriGrantFlags(hasLocalUri = false, hasDestinationUri = true)
        )
        assertEquals(0, TransferForegroundService.transferUriGrantFlags(hasLocalUri = false, hasDestinationUri = false))
    }
}

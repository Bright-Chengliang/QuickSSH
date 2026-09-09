package com.quickssh.app

import com.quickssh.app.service.FileTransferHelper
import com.quickssh.app.data.SshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityDownloadHelperTest {
    @Test
    fun remoteSelectionKeyNormalizesSlashesAndTrailingSeparators() {
        assertEquals("/srv/app/file.apk", remoteSelectionKey("/srv/app/file.apk/"))
        assertEquals("C:/Users/example/file.apk", remoteSelectionKey("C:\\Users\\example\\file.apk"))
        assertNull(remoteSelectionKey("   "))
    }

    @Test
    fun remoteDownloadFileNameHandlesWindowsPathsAndDirectoryNames() {
        assertEquals("file.apk", remoteDownloadFileName("C:\\Users\\example\\file.apk"))
        assertEquals("folder", remoteDownloadFileName("/srv/app/folder"))
        assertEquals("unsafe_name_.txt", remoteDownloadFileName("/srv/app/ignored", "unsafe:name?.txt"))
    }

    @Test
    fun browseTargetDirectoryDoesNotTreatDottedFolderAsFile() {
        assertEquals("D:/xxx/xxx/xxx", browseTargetDirectory("D:/xxx/xxx/xxx"))
        assertEquals("D:/xxx/xxx/xxx", browseTargetDirectory("D:\\xxx\\xxx\\xxx\\"))
        assertEquals("/srv/release.v2/build", browseTargetDirectory("/srv/release.v2/build"))
    }

    @Test
    fun remoteParentPathPreservesWindowsDriveRoot() {
        assertEquals("D:/xxx/xxx", remoteParentPath("D:/xxx/xxx/xxx"))
        assertEquals("D:/", remoteParentPath("D:/xxx"))
        assertEquals("/", remoteParentPath("/xxx"))
        assertEquals(".", remoteParentPath("D:/"))
    }

    @Test
    fun remoteRelativePathHelpersSplitDirectoryTreePaths() {
        assertEquals("", remoteRelativeParentPath("folder"))
        assertEquals("folder/sub", remoteRelativeParentPath("folder/sub/file.txt"))
        assertEquals("file.txt", remoteRelativeLeafName("folder/sub/file.txt"))
        assertEquals(2, remoteRelativeDepth("folder/sub/file.txt"))
    }

    @Test
    fun remoteDownloadPlanSummaryExplainsPlanOutcome() {
        assertEquals(
            "已创建 2 个文件夹，加入下载队列：3 个文件",
            remoteDownloadPlanSummary(createdDirectoryCount = 2, enqueuedFileCount = 3, createFailedCount = 0)
        )
        assertEquals(
            "已加入下载队列：3 个文件，1 项创建失败",
            remoteDownloadPlanSummary(createdDirectoryCount = 2, enqueuedFileCount = 3, createFailedCount = 1)
        )
        assertEquals(
            "已创建 1 个文件夹，未发现可下载文件",
            remoteDownloadPlanSummary(createdDirectoryCount = 1, enqueuedFileCount = 0, createFailedCount = 0)
        )
        assertEquals(
            "失败：无法创建本地目录或文件",
            remoteDownloadPlanSummary(createdDirectoryCount = 0, enqueuedFileCount = 0, createFailedCount = 2)
        )
    }

    @Test
    fun remoteDownloadPlanStatsCountsFilesAndDirectories() {
        val entries = listOf(
            remoteTreeEntry("folder", isDirectory = true),
            remoteTreeEntry("folder/a.txt", isDirectory = false),
            remoteTreeEntry("folder/sub", isDirectory = true),
            remoteTreeEntry("folder/sub/b.txt", isDirectory = false)
        )

        assertEquals(
            RemoteDownloadPlanStats(fileCount = 2, directoryCount = 2),
            remoteDownloadPlanStats(entries)
        )
    }

    @Test
    fun remoteDownloadPlanConfirmationUsesLargeDirectoryThresholds() {
        assertFalse(remoteDownloadPlanNeedsConfirmation(fileCount = 199, directoryCount = 49))
        assertTrue(remoteDownloadPlanNeedsConfirmation(fileCount = 200, directoryCount = 0))
        assertTrue(remoteDownloadPlanNeedsConfirmation(fileCount = 0, directoryCount = 50))
        assertEquals(
            "已扫描到 200 个文件、1 个文件夹，请确认后继续创建下载任务",
            remoteDownloadPlanConfirmationStatus(fileCount = 200, directoryCount = 1)
        )
    }

    @Test
    fun transferQueueClearStatusShowsCanceledWaitingTaskCount() {
        assertEquals("已取消 3 个等待传输任务", transferQueueClearStatus(3))
        assertEquals("没有等待中的传输任务", transferQueueClearStatus(0))
        assertEquals("没有等待中的传输任务", transferQueueClearStatus(-1))
    }

    @Test
    fun rememberedTransferDownloadConfigReusesSavedServerOrFallsBackToFirst() {
        val first = sshConfig(id = 1, name = "first", workDirectory = "/srv/first")
        val second = sshConfig(id = 2, name = "second", workDirectory = "/srv/second")

        assertEquals(second, rememberedTransferDownloadConfig(listOf(first, second), savedConfigId = 2))
        assertEquals(first, rememberedTransferDownloadConfig(listOf(first, second), savedConfigId = 99))
        assertNull(rememberedTransferDownloadConfig(emptyList(), savedConfigId = 2))
    }

    @Test
    fun rememberedTransferDownloadRemotePathUsesSavedPathOnlyForSavedServer() {
        val config = sshConfig(id = 2, name = "workspace", workDirectory = "/srv/default")

        assertEquals(
            "/srv/last-output.apk",
            rememberedTransferDownloadRemotePath(config, savedConfigId = 2, savedPath = "/srv/last-output.apk")
        )
        assertEquals(
            "/srv/default",
            rememberedTransferDownloadRemotePath(config, savedConfigId = 7, savedPath = "/srv/other")
        )
        assertEquals(
            "/srv/default",
            rememberedTransferDownloadRemotePath(config, savedConfigId = 2, savedPath = "")
        )
    }

    @Test
    fun rememberedTransferDownloadSelectionRestoresServerAndPathTogether() {
        val first = sshConfig(id = 1, name = "first", workDirectory = "/srv/first")
        val second = sshConfig(id = 2, name = "second", workDirectory = "/srv/second")

        val restored = rememberedTransferDownloadSelection(
            configs = listOf(first, second),
            savedConfigId = 2,
            savedPath = "/srv/second/app-debug.apk"
        )
        assertEquals(second, restored.config)
        assertEquals("/srv/second/app-debug.apk", restored.remotePath)

        val fallback = rememberedTransferDownloadSelection(
            configs = listOf(first, second),
            savedConfigId = 99,
            savedPath = "/srv/deleted-server/app-debug.apk"
        )
        assertEquals(first, fallback.config)
        assertEquals("/srv/first", fallback.remotePath)

        val empty = rememberedTransferDownloadSelection(
            configs = emptyList(),
            savedConfigId = 2,
            savedPath = "/srv/second/app-debug.apk"
        )
        assertNull(empty.config)
        assertEquals("", empty.remotePath)
    }

    private fun remoteTreeEntry(relativePath: String, isDirectory: Boolean): FileTransferHelper.RemoteTreeEntry {
        return FileTransferHelper.RemoteTreeEntry(
            name = remoteRelativeLeafName(relativePath),
            path = "/srv/$relativePath",
            relativePath = relativePath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else 123L
        )
    }

    private fun sshConfig(id: Long, name: String, workDirectory: String): SshConfig {
        return SshConfig(
            id = id,
            name = name,
            host = "host-$id.example.com",
            username = "user",
            authType = "PASSWORD",
            workDirectory = workDirectory
        )
    }
}

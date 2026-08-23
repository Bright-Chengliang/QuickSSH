package com.quickssh.app.ui.screens

import com.quickssh.app.service.FileTransferHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferScreenTest {
    @Test
    fun uploadConflictPolicyLabelsAreUserFacing() {
        assertEquals("自动改名", uploadConflictPolicyLabel(FileTransferHelper.UploadConflictPolicy.RENAME))
        assertEquals("覆盖", uploadConflictPolicyLabel(FileTransferHelper.UploadConflictPolicy.OVERWRITE))
        assertEquals("失败", uploadConflictPolicyLabel(FileTransferHelper.UploadConflictPolicy.FAIL))
        assertEquals("自动改名", transferConflictPolicyLabel(FileTransferHelper.UploadConflictPolicy.RENAME))
    }

    @Test
    fun selectedLocalFilesLabelShowsMultiSelectCount() {
        assertEquals("选择本机文件", selectedLocalFilesLabel(0, null))
        assertEquals("one.apk", selectedLocalFilesLabel(1, "one.apk"))
        assertEquals("已选择 3 个文件", selectedLocalFilesLabel(3, null))
    }

    @Test
    fun transferTopProgressModeTracksCurrentTransferState() {
        assertEquals(
            TransferTopProgressMode.Idle,
            transferTopProgressMode(isTransferring = false, progressText = "", progressFraction = null)
        )
        assertEquals(
            TransferTopProgressMode.Indeterminate,
            transferTopProgressMode(isTransferring = true, progressText = "Preparing download...", progressFraction = null)
        )
        assertEquals(
            TransferTopProgressMode.Determinate,
            transferTopProgressMode(isTransferring = true, progressText = "42%", progressFraction = 0.42f)
        )
    }

    @Test
    fun transferTopProgressModeIsSharedByUploadAndDownloadProgress() {
        assertEquals(
            TransferTopProgressMode.Determinate,
            transferTopProgressMode(isTransferring = true, progressText = "Upload 25%", progressFraction = 0.25f)
        )
        assertEquals(
            TransferTopProgressMode.Determinate,
            transferTopProgressMode(isTransferring = true, progressText = "Download 75%", progressFraction = 0.75f)
        )
    }

    @Test
    fun transferDownloadDirectoryActionLabelShowsReuseState() {
        assertEquals("选择保存目录", transferDownloadDirectoryActionLabel(""))
        assertEquals(
            "选择保存目录",
            transferDownloadDirectoryActionLabel("Not set; downloads use the system save picker")
        )
        assertEquals("保存到：Download/QuickSSH", transferDownloadDirectoryActionLabel("Download/QuickSSH"))
    }

    @Test
    fun transferTaskProgressDetailShowsProgressOrPreparingState() {
        assertEquals("当前进度：42% - 1.0 MB / 2.0 MB", transferTaskProgressDetail("42% - 1.0 MB / 2.0 MB", null))
        assertEquals("当前进度：64%", transferTaskProgressDetail("", 0.64f))
        assertEquals("当前进度：准备中", transferTaskProgressDetail("", null))
    }

    @Test
    fun elapsedSecondsLabelShowsShortWaitTime() {
        assertEquals("0秒", formatElapsedSeconds(-1))
        assertEquals("59秒", formatElapsedSeconds(59))
        assertEquals("1分1秒", formatElapsedSeconds(61))
    }

    @Test
    fun remoteEntrySelectionMatchesSelectedDownloadPath() {
        assertEquals(true, remoteEntryMatchesSelectedPath("/srv/app/app-debug.apk", "/srv/app/app-debug.apk"))
        assertEquals(true, remoteEntryMatchesSelectedPath("/srv/app/app-debug.apk", "/srv/app/app-debug.apk/"))
        assertEquals(true, remoteEntryMatchesSelectedPath("C:\\Users\\example\\file.zip", "C:/Users/example/file.zip"))
        assertEquals(false, remoteEntryMatchesSelectedPath("/srv/app/app-debug.apk", "/srv/app/output-metadata.json"))
        assertEquals(false, remoteEntryMatchesSelectedPath("/srv/app/app-debug.apk", ""))
    }

    @Test
    fun remoteEntrySelectionChecksSinglePathAndMultiSelectSet() {
        assertEquals(
            true,
            remoteEntryIsSelected(
                entryPath = "/srv/app/app-debug.apk",
                selectedRemotePath = "/srv/app/app-debug.apk",
                selectedRemotePaths = emptySet()
            )
        )
        assertEquals(
            true,
            remoteEntryIsSelected(
                entryPath = "C:\\Users\\example\\file.zip",
                selectedRemotePath = "",
                selectedRemotePaths = setOf("C:/Users/example/file.zip")
            )
        )
        assertEquals(
            false,
            remoteEntryIsSelected(
                entryPath = "/srv/app/app-debug.apk",
                selectedRemotePath = "/srv/app/output-metadata.json",
                selectedRemotePaths = setOf("/srv/app/readme.txt")
            )
        )
    }

    @Test
    fun directorySelectionIgnoresSingleClickPathUnlessExplicitlySelected() {
        assertEquals(
            false,
            remoteEntryIsSelected(
                entryPath = "/srv/app/.QuickSSH",
                selectedRemotePath = "/srv/app/.QuickSSH",
                selectedRemotePaths = emptySet(),
                isDirectory = true
            )
        )
        assertEquals(
            true,
            remoteEntryIsSelected(
                entryPath = "/srv/app/.QuickSSH",
                selectedRemotePath = "/srv/app/.QuickSSH",
                selectedRemotePaths = setOf("/srv/app/.QuickSSH"),
                isDirectory = true,
                selectionMode = true
            )
        )
    }

    @Test
    fun recentTransferTasksDefaultToTenItems() {
        val tasks = transferTasks(12)

        assertEquals(10, visibleRecentTransferTasks(tasks, expanded = false, pageIndex = 0).size)
        assertEquals("显示最近 10 / 12 个任务", recentTransferTaskRangeLabel(12, 10, expanded = false, pageIndex = 0))
    }

    @Test
    fun recentTransferTasksExpandToThirtyItemsPerPage() {
        val tasks = transferTasks(65)

        assertEquals(3, recentTransferTaskPageCount(tasks.size))
        assertEquals("task-1", visibleRecentTransferTasks(tasks, expanded = true, pageIndex = 0).first().fileName)
        assertEquals(30, visibleRecentTransferTasks(tasks, expanded = true, pageIndex = 0).size)
        assertEquals("task-31", visibleRecentTransferTasks(tasks, expanded = true, pageIndex = 1).first().fileName)
        assertEquals(5, visibleRecentTransferTasks(tasks, expanded = true, pageIndex = 2).size)
        assertEquals("显示第 31-60 / 65 个任务", recentTransferTaskRangeLabel(65, 30, expanded = true, pageIndex = 1))
    }

    @Test
    fun recentTransferTasksClampOutOfRangePage() {
        val tasks = transferTasks(31)

        val visible = visibleRecentTransferTasks(tasks, expanded = true, pageIndex = 99)

        assertEquals(1, visible.size)
        assertEquals("task-31", visible.first().fileName)
        assertEquals("显示第 31-31 / 31 个任务", recentTransferTaskRangeLabel(31, 1, expanded = true, pageIndex = 99))
    }

    @Test
    fun queuedTransferTaskShowsPerFileWaitingStatusAndTargetPath() {
        val task = transferQueueTaskUiState(
            index = 1,
            totalWaiting = 3,
            queuePaused = true,
            direction = "Download",
            fileName = "app-debug.apk",
            serverName = "server / workspace",
            serverNodeName = "server",
            workspaceName = "workspace",
            localUri = null,
            destinationUri = null,
            remotePath = "/srv/app/app-debug.apk"
        )

        assertEquals(-2L, task.id)
        assertEquals("已暂停等待", task.status)
        assertEquals("server", task.serverNodeName)
        assertEquals("workspace", task.workspaceName)
        assertTrue(task.detail.contains("等待传输：第 2 / 3 个"))
        assertTrue(task.detail.contains("远端：/srv/app/app-debug.apk"))
    }

    @Test
    fun transferTasksWithBatchStatePlacesActiveAndQueuedBeforeHistory() {
        val historyTasks = transferTasks(3)
        val activeTask = historyTasks[1].copy(status = "传输中：正在下载...", detail = "当前进度：50%")
        val queuedTask = transferQueueTaskUiState(
            index = 0,
            totalWaiting = 1,
            queuePaused = false,
            direction = "Upload",
            fileName = "queued.txt",
            serverName = "server / workspace",
            serverNodeName = "server",
            workspaceName = "workspace",
            localUri = null,
            destinationUri = null,
            remotePath = "/srv/app"
        )

        val merged = transferTasksWithBatchState(
            historyTasks = historyTasks,
            activeTask = activeTask,
            queuedTasks = listOf(queuedTask)
        )

        assertEquals(listOf("task-2", "queued.txt", "task-1", "task-3"), merged.map { it.fileName })
        assertEquals("传输中：正在下载...", merged.first().status)
        assertEquals("当前进度：50%", merged.first().detail)
        assertEquals("等待传输", merged[1].status)
    }

    @Test
    fun remoteBrowserDefaultsToFirstThirtyEntriesWithExpandHint() {
        val entries = remoteEntries(42)

        val visible = visibleRemoteBrowserEntries(entries, expanded = false, pageIndex = 0)

        assertEquals(30, visible.size)
        assertEquals("file-1.txt", visible.first().name)
        assertEquals("file-30.txt", visible.last().name)
        assertEquals("显示前 30 / 42 项", remoteBrowserRangeLabel(42, visible.size, expanded = false, pageIndex = 0))
    }

    @Test
    fun remoteBrowserExpandedModePaginatesAllEntries() {
        val entries = remoteEntries(65)

        assertEquals(3, remoteBrowserPageCount(entries.size))
        assertEquals("file-31.txt", visibleRemoteBrowserEntries(entries, expanded = true, pageIndex = 1).first().name)
        assertEquals(30, visibleRemoteBrowserEntries(entries, expanded = true, pageIndex = 1).size)
        assertEquals("file-61.txt", visibleRemoteBrowserEntries(entries, expanded = true, pageIndex = 2).first().name)
        assertEquals(5, visibleRemoteBrowserEntries(entries, expanded = true, pageIndex = 2).size)
        assertEquals("显示第 31-60 / 65 项", remoteBrowserRangeLabel(65, 30, expanded = true, pageIndex = 1))
    }

    @Test
    fun remoteBrowserClampsOutOfRangePage() {
        val entries = remoteEntries(31)

        val visible = visibleRemoteBrowserEntries(entries, expanded = true, pageIndex = 99)

        assertEquals(1, visible.size)
        assertEquals("file-31.txt", visible.first().name)
        assertEquals("显示第 31-31 / 31 项", remoteBrowserRangeLabel(31, visible.size, expanded = true, pageIndex = 99))
    }

    private fun transferTasks(count: Int): List<TransferTaskUiState> {
        return (1..count).map { index ->
            TransferTaskUiState(
                id = index.toLong(),
                fileName = "task-$index",
                direction = "Download",
                serverName = "server",
                status = "Success"
            )
        }
    }

    private fun remoteEntries(count: Int): List<FileTransferHelper.RemoteEntry> {
        return (1..count).map { index ->
            FileTransferHelper.RemoteEntry(
                name = "file-$index.txt",
                path = "/srv/app/file-$index.txt",
                isDirectory = false,
                size = index.toLong()
            )
        }
    }
}

package com.quickssh.app.ui.screens

import com.quickssh.app.data.PINNED_FOLDER_ID
import com.quickssh.app.data.SshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshListScreenTest {
    @Test
    fun moveItemMovesDownWithoutChangingOtherItems() {
        assertEquals(
            listOf("server-a", "server-c", "server-d", "server-b"),
            moveItem(listOf("server-a", "server-b", "server-c", "server-d"), 1, 3)
        )
    }

    @Test
    fun moveItemMovesUpWithoutChangingOtherItems() {
        assertEquals(
            listOf("workspace-c", "workspace-a", "workspace-b"),
            moveItem(listOf("workspace-a", "workspace-b", "workspace-c"), 2, 0)
        )
    }

    @Test
    fun adjustedDragOffsetUsesDraggedItemsNewCenterWhenHeightsDiffer() {
        assertEquals(
            -40f,
            adjustedDragOffsetAfterReorder(
                previousOffset = 170f,
                previousCenters = listOf(50f, 210f, 370f),
                reorderedCenters = listOf(100f, 260f, 370f),
                fromIndex = 0,
                targetIndex = 1
            ),
            0.001f
        )
    }

    @Test
    fun pinnedFolderFiltersWorkspacesAcrossDifferentHosts() {
        val hostAWorkspace = SshConfig(
            id = 1,
            name = "A-Project",
            host = "10.0.0.1",
            username = "root",
            authType = "PASSWORD",
            folderId = PINNED_FOLDER_ID
        )
        val hostBWorkspace = SshConfig(
            id = 2,
            name = "B-Logs",
            host = "10.0.0.2",
            username = "admin",
            authType = "PASSWORD",
            folderId = PINNED_FOLDER_ID
        )
        val hostCUnpinned = SshConfig(
            id = 3,
            name = "C-Dev",
            host = "10.0.0.3",
            username = "ubuntu",
            authType = "PASSWORD",
            folderId = null
        )

        val allConfigs = listOf(hostAWorkspace, hostBWorkspace, hostCUnpinned)
        val pinned = allConfigs.filter { it.folderId == PINNED_FOLDER_ID }

        assertEquals(2, pinned.size)
        assertTrue(pinned.any { it.id == 1L && it.host == "10.0.0.1" })
        assertTrue(pinned.any { it.id == 2L && it.host == "10.0.0.2" })
    }

    @Test
    fun customFolderFiltersWorkspacesAcrossDifferentHosts() {
        val folderId = 100L
        val config1 = SshConfig(
            id = 10,
            name = "Prod API",
            host = "api.prod.com",
            username = "deploy",
            authType = "PASSWORD",
            folderId = folderId
        )
        val config2 = SshConfig(
            id = 11,
            name = "Worker Job",
            host = "worker.prod.com",
            username = "deploy",
            authType = "PASSWORD",
            folderId = folderId
        )
        val configOther = SshConfig(
            id = 12,
            name = "Staging",
            host = "staging.com",
            username = "root",
            authType = "PASSWORD",
            folderId = 200L
        )

        val allConfigs = listOf(config1, config2, configOther)
        val filtered = allConfigs.filter { it.folderId == folderId }

        assertEquals(2, filtered.size)
        assertEquals(listOf(10L, 11L), filtered.map { it.id })
    }
}

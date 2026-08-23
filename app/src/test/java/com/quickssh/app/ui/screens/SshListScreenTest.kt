package com.quickssh.app.ui.screens

import org.junit.Assert.assertEquals
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
}

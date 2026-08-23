package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferQueueTest {
    @Test
    fun transferQueueStartsImmediatelyWhenIdleAndUnpaused() {
        val result = nextTransferQueueState(
            busy = false,
            paused = false,
            queueSize = 0,
            action = TransferQueueAction.ENQUEUE
        )

        assertEquals(0, result.queueSize)
        assertEquals(TransferQueueDecision.START_NOW, result.decision)
    }

    @Test
    fun transferQueueKeepsPendingWorkWhenBusyOrPaused() {
        assertEquals(
            TransferQueueResult(queueSize = 2, decision = TransferQueueDecision.QUEUED),
            nextTransferQueueState(busy = true, paused = false, queueSize = 1, action = TransferQueueAction.ENQUEUE)
        )
        assertEquals(
            TransferQueueResult(queueSize = 2, decision = TransferQueueDecision.QUEUED),
            nextTransferQueueState(busy = false, paused = true, queueSize = 1, action = TransferQueueAction.ENQUEUE)
        )
    }

    @Test
    fun transferQueuePumpsOnlyWhenIdleUnpausedAndPending() {
        assertEquals(
            TransferQueueResult(queueSize = 1, decision = TransferQueueDecision.START_NEXT),
            nextTransferQueueState(busy = false, paused = false, queueSize = 2, action = TransferQueueAction.PUMP)
        )
        assertNull(nextTransferQueueState(busy = true, paused = false, queueSize = 2, action = TransferQueueAction.PUMP).decision)
    }
}

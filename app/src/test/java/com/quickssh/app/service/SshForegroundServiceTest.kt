package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshForegroundServiceTest {
    @Test
    fun notificationContentTextSummarizesSessionCount() {
        assertEquals("No active SSH sessions", sshNotificationContentText(emptyList()))

        assertEquals(
            "Connected: root@example.com:22",
            sshNotificationContentText(
                listOf(session("one", startedAt = 1, status = SshSessionStatus.CONNECTED))
            )
        )

        assertEquals(
            "Keeping 2 SSH sessions active in the background",
            sshNotificationContentText(listOf(session("one", 1), session("two", 2)))
        )
    }

    @Test
    fun notificationDisconnectTargetUsesMostRecentSession() {
        assertNull(sshNotificationDisconnectTarget(emptyList()))

        val first = session("first", startedAt = 100)
        val second = session("second", startedAt = 200)

        assertEquals("second", sshNotificationDisconnectTarget(listOf(first, second))?.sessionId)
    }

    private fun session(
        id: String,
        startedAt: Long,
        status: SshSessionStatus = SshSessionStatus.CONNECTING
    ): SshSessionInfo {
        return SshSessionInfo(
            sessionId = id,
            configId = startedAt,
            name = id,
            host = "example.com",
            port = 22,
            username = "root",
            workDirectory = null,
            startedAt = startedAt,
            status = status,
            statusMessage = status.displayText()
        )
    }
}

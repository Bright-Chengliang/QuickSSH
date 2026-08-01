package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FileTransferProgressTest {
    @Test
    fun progressSampleThrottlesIntermediateUpdates() {
        val sample = progressSample(
            transferredBytes = 100,
            totalBytes = 1_000,
            startedAtMs = 0,
            lastEmitAtMs = 900,
            nowMs = 1_000,
            minIntervalMs = 250
        )

        assertFalse(sample.shouldEmit)
        assertEquals(100L, sample.bytesPerSecond)
        assertEquals(900L, sample.nextLastEmitAtMs)
    }

    @Test
    fun progressSampleAlwaysEmitsCompletion() {
        val sample = progressSample(
            transferredBytes = 1_000,
            totalBytes = 1_000,
            startedAtMs = 0,
            lastEmitAtMs = 950,
            nowMs = 1_000,
            minIntervalMs = 250
        )

        assertTrue(sample.shouldEmit)
        assertEquals(1_000L, sample.bytesPerSecond)
        assertEquals(1_000L, sample.nextLastEmitAtMs)
    }

    @Test
    fun transientTransferRetryReRunsOperationAfterConnectionFailure() {
        var attempts = 0

        val result = retryTransientTransfer(
            maxAttempts = 3,
            shouldRetry = ::isTransientTransferError,
            sleepMillis = {}
        ) {
            attempts++
            if (attempts == 1) throw IOException("connection reset by peer")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }

    @Test
    fun transientTransferRetryStopsAtMaxAttempts() {
        var attempts = 0

        try {
            retryTransientTransfer<Unit>(
                maxAttempts = 2,
                shouldRetry = ::isTransientTransferError,
                sleepMillis = {}
            ) {
                attempts++
                throw IOException("socket timeout")
            }
            fail("Expected transfer retry failure")
        } catch (error: IOException) {
            assertEquals("socket timeout", error.message)
        }

        assertEquals(2, attempts)
    }

    @Test
    fun transientTransferRetryDoesNotRetryPermanentErrors() {
        var attempts = 0

        try {
            retryTransientTransfer<Unit>(
                maxAttempts = 3,
                shouldRetry = ::isTransientTransferError,
                sleepMillis = {}
            ) {
                attempts++
                throw IOException("Permission denied")
            }
            fail("Expected permanent transfer failure")
        } catch (error: IOException) {
            assertEquals("Permission denied", error.message)
        }

        assertEquals(1, attempts)
        assertFalse(isTransientTransferError(IOException("Permission denied")))
        assertTrue(isTransientTransferError(IOException("broken pipe")))
    }

    @Test
    fun uploadConflictPolicyParserDefaultsToRename() {
        assertEquals(
            FileTransferHelper.UploadConflictPolicy.OVERWRITE,
            uploadConflictPolicyFromName("overwrite")
        )
        assertEquals(
            FileTransferHelper.UploadConflictPolicy.FAIL,
            uploadConflictPolicyFromName("FAIL")
        )
        assertEquals(
            FileTransferHelper.UploadConflictPolicy.RENAME,
            uploadConflictPolicyFromName(null)
        )
        assertEquals(
            FileTransferHelper.UploadConflictPolicy.RENAME,
            uploadConflictPolicyFromName("unexpected")
        )
    }

    @Test
    fun remoteChildPathUsesPlatformAppropriateSeparator() {
        assertEquals("/srv/app/file.apk", remoteChildPath("/srv/app/", "file.apk"))
        assertEquals("C:/Users/example/QuickSSH/file.apk", remoteChildPath("C:/Users/example/QuickSSH", "file.apk"))
        assertEquals("C:\\Users\\example\\QuickSSH\\file.apk", remoteChildPath("C:\\Users\\example\\QuickSSH", "file.apk"))
    }

    @Test
    fun terminalQuickUploadDirectoryTargetsManagedUploadFolder() {
        assertEquals("/srv/app/.QuickSSH/upload", terminalQuickUploadDirectory("/srv/app"))
        assertEquals("C:/Users/example/QuickSSH/.QuickSSH/upload", terminalQuickUploadDirectory("C:/Users/example/QuickSSH"))
        assertEquals("C:/Users/example/QuickSSH/.QuickSSH/upload", terminalQuickUploadDirectory("C:\\Users\\example\\QuickSSH"))
        assertEquals(".QuickSSH/upload", terminalQuickUploadDirectory(null))
    }

    @Test
    fun normalizeSftpPathUsesForwardSlashesForWindowsPaths() {
        assertEquals("C:/Users/example/QuickSSH", normalizeSftpPath("C:\\Users\\example\\QuickSSH"))
        assertEquals("/srv/app", normalizeSftpPath("/srv/app"))
    }
}

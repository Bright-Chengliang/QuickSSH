package com.quickssh.app.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream

@RunWith(AndroidJUnit4::class)
class TermuxSshSessionBridgeTest {

    @Test
    fun testTermuxSshSessionBridgeBidirectionalIO() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Simulated SSH streams:
        // Remote server outputs -> inWrite -> inPipe -> Termux TerminalSession
        val inPipe = PipedInputStream()
        val inWrite = PipedOutputStream(inPipe)

        // Termux TerminalSession user keystrokes -> outPipe -> outRead -> Remote server
        val outRead = PipedInputStream()
        val outPipe = PipedOutputStream(outRead)

        var dataReceivedTriggered = false
        val manager = TermuxSessionManager(context, scope)
        val bridge = manager.createSshSession(
            sshInputStream = inPipe,
            sshOutputStream = outPipe,
            initialCols = 80,
            initialRows = 24,
            onDataReceived = { dataReceivedTriggered = true }
        )

        assertNotNull("Termux SSH bridge session should not be null", bridge)
        val session = bridge!!.session
        assertNotNull("TerminalSession must be initialized", session)
        assertNotNull("TerminalEmulator must be initialized", session.emulator)

        // 1. Simulate remote SSH server sending output
        val remoteMessage = "Welcome to QuickSSH Remote Termux Engine\r\n"
        inWrite.write(remoteMessage.toByteArray(Charsets.UTF_8))
        inWrite.flush()

        // Wait for handler to process MSG_NEW_INPUT on Main thread
        var textFound = false
        for (i in 0..30) {
            delay(100)
            val screenText = withContext(Dispatchers.Main) {
                session.emulator.screen.getTranscriptText()
            }
            if (screenText.contains("Welcome to QuickSSH Remote Termux Engine")) {
                textFound = true
                break
            }
        }
        assertTrue("Termux emulator transcript should contain remote output", textFound)
        assertTrue("onDataReceived should have been triggered", dataReceivedTriggered)

        // 2. Simulate user typing into Termux TerminalSession
        val userCommand = "echo 12345\n"
        session.write(userCommand)

        // Verify bytes arrive on SSH output stream
        val readBuffer = ByteArray(1024)
        val bytesRead = withTimeout(3000) {
            withContext(Dispatchers.IO) {
                outRead.read(readBuffer)
            }
        }
        assertTrue(bytesRead > 0)
        val receivedOnNetwork = String(readBuffer, 0, bytesRead, Charsets.UTF_8)
        assertEquals(userCommand, receivedOnNetwork)

        // 3. Test clean shutdown
        bridge.closeAction()
        scope.cancel()
    }
}

package com.quickssh.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SshClientHelperTest {
    @Test
    fun terminalLinePayloadAppendsTerminalEnterToTypedText() {
        assertEquals("ls -la\r", terminalLinePayload("ls -la"))
    }

    @Test
    fun terminalLinePayloadSendsTerminalEnterForEmptyInput() {
        assertEquals("\r", terminalLinePayload(""))
    }

    @Test
    fun directCommandInputTrimsAndBuildsSinglePayload() {
        assertEquals("echo ready", directCommandText("echo ready"))
        assertEquals("echo ready", directCommandText("echo ready\r"))
        assertEquals("echo ready", directCommandText("echo ready\n"))
        assertEquals("\r\n", directCommandEnterPayload())
        assertEquals("echo ready\r\n", directCommandPayload("echo ready"))
        assertEquals(listOf(0x20, 0x08), directCommandNudgePayload().map { it.toInt() })
    }

    @Test
    fun postConnectCommandsSplitMultilineInputIntoSeparateCommands() {
        assertEquals(
            listOf("wsl", "cd ~"),
            postConnectCommands("wsl\ncd ~")
        )
    }

    @Test
    fun postConnectCommandsIgnoreBlankLinesAndTrimWhitespace() {
        assertEquals(
            listOf("wsl.exe", "cd /home/user/project"),
            postConnectCommands("  wsl.exe  \r\n\r\n cd /home/user/project \n")
        )
    }

    @Test
    fun shellSingleQuotedEscapesWorkspacePathForAutoCd() {
        assertEquals("'/srv/app'", shellSingleQuoted("/srv/app"))
        assertEquals("'/srv/bob'\"'\"'s app'", shellSingleQuoted("/srv/bob's app"))
    }

    @Test
    fun shellPathLiteralPreservesTildeExpansionForWorkspacePath() {
        assertEquals("~", shellPathLiteral("~"))
        assertEquals("~/'project dir'", shellPathLiteral("~/project dir"))
        assertEquals("~deploy/'project dir'", shellPathLiteral("~deploy/project dir"))
        assertEquals("'/srv/project dir'", shellPathLiteral("/srv/project dir"))
    }

    @Test
    fun autoCdCommandUsesCmdAndPowerShellCompatibleWindowsWorkspacePath() {
        assertEquals(
            "pushd \"C:\\Users\\example\\WorkBuddy\\QuickSSH\"",
            autoCdCommand("C:\\Users\\example\\WorkBuddy\\QuickSSH")
        )
        assertEquals(
            "pushd \"D:\\Project Files\\Quick SSH\"",
            autoCdCommand("D:\\Project Files\\Quick SSH")
        )
        assertEquals(
            "pushd \"\\\\server\\share\\QuickSSH\"",
            autoCdCommand("\\\\server\\share\\QuickSSH")
        )
    }

    @Test
    fun autoCdCommandKeepsShellQuotingForPosixWorkspacePath() {
        assertEquals("cd '/srv/project dir'", autoCdCommand("/srv/project dir"))
        assertEquals("cd ~/'project dir'", autoCdCommand("~/project dir"))
    }

    @Test
    fun codexTranscriptPreviewCommandIsWindowsOnlyAndDoesNotStartResume() {
        assertEquals(null, codexTranscriptPreviewCommand("/srv/app"))

        val command = codexTranscriptPreviewCommand("C:/Users/example/WorkBuddy/QuickSSH")
            ?: error("Expected Windows preview command")

        assertTrue(command.startsWith("powershell -NoProfile -ExecutionPolicy Bypass -Command "))
        assertTrue(command.contains("[QuickSSH] Codex recent transcript preview"))
        assertTrue(command.contains("gci -LiteralPath ${'$'}root -Recurse -File -Filter *.jsonl"))
        assertTrue(command.contains("Get-Date -f yyyy"))
        assertTrue(command.contains("select -First 240"))
        assertTrue(command.contains("Select-String -LiteralPath"))
        assertTrue(command.contains("select -Last 1200"))
        assertTrue(command.contains("Transcript preview prepared"))
        assertTrue(command.contains("message middle truncated for mobile scrollback"))
        assertTrue(command.contains("1200000"))
        assertFalse(command.contains("-Tail 12000"))
        assertFalse(command.contains("ConvertFrom-Json"))
        assertFalse(command.contains("Substring(0,12000)"))
        assertFalse(command.contains("codex resume --last"))
        assertTrue("preview command should stay below common Windows command-line limits", command.length < 8_000)
    }

    @Test
    fun terminalOutputDecoderPreservesUtf8CharactersSplitAcrossReads() {
        val decoder = TerminalOutputDecoder()
        val text = "当前输入框太拥挤"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val firstChunk = bytes.copyOfRange(0, 4)
        val secondChunk = bytes.copyOfRange(4, bytes.size)

        assertEquals("当", decoder.decode(firstChunk, firstChunk.size))
        assertEquals("前输入框太拥挤", decoder.decode(secondChunk, secondChunk.size))
    }

    @Test
    fun terminalOutputDecoderFallsBackToGb18030ForWindowsConsoleText() {
        val decoder = TerminalOutputDecoder()
        val charset = Charset.forName("GB18030")
        val bytes = "文件名、目录名或卷标语法不正确。".toByteArray(charset)

        assertEquals("文件名、目录名或卷标语法不正确。", decoder.decode(bytes, bytes.size))
    }

    @Test
    fun terminalOutputDecoderKeepsUtf8PrefixWhenFallingBackToGb18030() {
        val decoder = TerminalOutputDecoder()
        val charset = Charset.forName("GB18030")
        val bytes = "OK 中文".toByteArray(charset)

        assertEquals("OK 中文", decoder.decode(bytes, bytes.size))
    }

    @Test
    fun terminalOutputDecoderPreservesGb18030CharactersSplitAcrossReads() {
        val decoder = TerminalOutputDecoder()
        val charset = Charset.forName("GB18030")
        val bytes = "中文".toByteArray(charset)

        assertEquals("", decoder.decode(bytes.copyOfRange(0, 1), 1))
        assertEquals("中", decoder.decode(bytes.copyOfRange(1, 2), 1))
        assertEquals("", decoder.decode(bytes.copyOfRange(2, 3), 1))
        assertEquals("文", decoder.decode(bytes.copyOfRange(3, 4), 1))
    }

    @Test
    fun sessionStatusDisplayTextIsUserFacing() {
        assertEquals("Connecting", SshSessionStatus.CONNECTING.displayText())
        assertEquals("Connected", SshSessionStatus.CONNECTED.displayText())
        assertEquals("Reconnecting", SshSessionStatus.RECONNECTING.displayText())
        assertEquals("Failed", SshSessionStatus.FAILED.displayText())
        assertEquals("Disconnected", SshSessionStatus.DISCONNECTED.displayText())
    }

    @Test
    fun networkCallbackReconnectsOnlyForReconnectingSessions() {
        assertTrue(shouldReconnectOnNetworkAvailable(false, false, SshSessionStatus.RECONNECTING))
        assertFalse(shouldReconnectOnNetworkAvailable(false, false, SshSessionStatus.CONNECTING))
        assertFalse(shouldReconnectOnNetworkAvailable(false, true, SshSessionStatus.RECONNECTING))
        assertFalse(shouldReconnectOnNetworkAvailable(true, false, SshSessionStatus.RECONNECTING))
    }

    @Test
    fun reconnectDelaySkipsWhenAlreadyReconnectedByNetworkCallback() {
        assertTrue(shouldReconnectAfterDelay(userRequestedDisconnect = false, isConnected = false))
        assertFalse(shouldReconnectAfterDelay(userRequestedDisconnect = true, isConnected = false))
        assertFalse(shouldReconnectAfterDelay(userRequestedDisconnect = false, isConnected = true))
    }

    @Test
    fun reconnectDelayCapsAtThirtySeconds() {
        assertEquals(2_000L, reconnectDelayMillis(1))
        assertEquals(30_000L, reconnectDelayMillis(99))
    }

    @Test
    fun terminalTermFallsBackAndStripsUnsafeCharacters() {
        assertEquals("xterm-256color", sanitizedTerminalTerm(""))
        assertEquals("screen-256color", sanitizedTerminalTerm(" screen-256color "))
        assertEquals("vt100rm-rf", sanitizedTerminalTerm("vt100;rm -rf"))
    }

    @Test
    fun boundedTextReplayKeepsRecentOutputWithinChunkAndCharacterLimits() {
        val replay = BoundedTextReplay(maxChunks = 3, maxChars = 8)

        replay.append("one")
        replay.append("two")
        replay.append("three")
        replay.append("four")

        assertEquals(listOf("four"), replay.snapshot())

        replay.append("a")
        replay.append("b")
        replay.append("c")
        replay.append("d")

        assertEquals(listOf("b", "c", "d"), replay.snapshot())
    }
}


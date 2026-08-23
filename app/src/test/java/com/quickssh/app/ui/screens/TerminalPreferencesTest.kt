package com.quickssh.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPreferencesTest {
    @Test
    fun shortcutCommandsUseDefaultsWhenWorkspaceHasNoCustomList() {
        assertEquals(
            listOf(
                "ls -la",
                "uname -a",
                "top",
                "df -h",
                "free -m",
                "clear",
                "codex resume --last --no-alt-screen"
            ),
            shortcutCommands(null)
        )
    }

    @Test
    fun defaultCodexResumeShortcutUsesReadableLabelAndInlineCommand() {
        val shortcuts = terminalShortcutCommands(null)

        assertEquals(
            TerminalShortcutCommand("Codex resume", "codex resume --last --no-alt-screen"),
            shortcuts.last()
        )
    }

    @Test
    fun defaultCodexResumeShortcutTargetsWindowsWorkspaceWhenConfigured() {
        val shortcuts = terminalShortcutCommands(null, "C:/Users/example/WorkBuddy/QuickSSH")
        val shortcut = shortcuts.last()
        val command = shortcut.command

        assertEquals("Codex resume", shortcut.label)
        assertEquals("codex resume --last --no-alt-screen -C \"C:/Users/example/WorkBuddy/QuickSSH\"", command)
        assertFalse(command.startsWith("powershell"))
        assertTrue(shouldRunCodexResumeShortcutAction(shortcut, "C:/Users/example/WorkBuddy/QuickSSH"))
    }

    @Test
    fun customShortcutDoesNotTriggerCodexResumeAction() {
        val shortcut = TerminalShortcutCommand("codex resume --last --no-alt-screen", "codex resume --last --no-alt-screen")

        assertFalse(shouldRunCodexResumeShortcutAction(shortcut, null))
    }

    @Test
    fun defaultCodexResumeShortcutQuotesPosixWorkspaceWhenConfigured() {
        assertEquals(
            "codex resume --last --no-alt-screen -C '/srv/app with space'",
            codexResumeShortcutCommand("/srv/app with space")
        )
    }

    @Test
    fun shortcutCommandsTrimDedupeAndBoundCustomCommands() {
        assertEquals(
            listOf("pwd", "git status", "tail -f app.log"),
            shortcutCommands(" pwd \n\ngit status\r\npwd\ntail -f app.log ")
        )
    }

    @Test
    fun selectedTerminalTextKeepsRowOrderAndStripsAnsi() {
        assertEquals(
            "first\nsecond",
            selectedTerminalText(listOf("\u001B[31mfirst\u001B[0m", "", "second"), setOf(2, 0, 99))
        )
    }
}

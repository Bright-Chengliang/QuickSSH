package com.quickssh.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SshConfigCopiesTest {
    @Test
    fun copyNameUsesBaseNameWhenAvailable() {
        assertEquals(
            "Production Server Copy",
            copiedSshConfigName("Production Server", existingNames = emptyList())
        )
    }

    @Test
    fun copyNameAddsNumberWhenCopyAlreadyExists() {
        assertEquals(
            "Production Server Copy 3",
            copiedSshConfigName(
                "Production Server",
                existingNames = setOf("Production Server Copy", "Production Server Copy 2")
            )
        )
    }

    @Test
    fun copyNameUsesFallbackForBlankSourceName() {
        assertEquals("Server Copy", copiedSshConfigName("", existingNames = emptyList()))
    }
}
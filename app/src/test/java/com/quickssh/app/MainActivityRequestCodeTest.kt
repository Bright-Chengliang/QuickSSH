package com.quickssh.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRequestCodeTest {
    @Test
    fun appWideLegacyActivityRequestCodesStayWithinFragmentActivityLimit() {
        val requestCodes = requestCodeConstantsInMainSources()

        assertTrue("Expected at least one REQUEST_* constant in app/src/main", requestCodes.isNotEmpty())

        val invalid = requestCodes.filter { it.value !in 0..0xFFFF }
        assertTrue(
            "FragmentActivity only accepts lower 16-bit request codes: ${invalid.joinToString()}",
            invalid.isEmpty()
        )
    }

    @Test
    fun activityResultContractsAreNotUsedInMainSources() {
        val forbidden = mainSourceFiles()
            .mapNotNull { file ->
                val source = file.readText()
                val tokens = listOf("registerForActivityResult", "ActivityResultContracts")
                    .filter(source::contains)
                if (tokens.isEmpty()) null else "${pathLabel(file)}: ${tokens.joinToString()}"
            }

        assertTrue(
            "Use explicit low REQUEST_* codes for picker/permission flows instead of ActivityResultContracts: $forbidden",
            forbidden.isEmpty()
        )
    }

    @Test
    fun legacyPickerAndPermissionApisStayCentralized() {
        val allowedFiles = setOf("MainActivity.kt")
        val legacyApiTokens = listOf(
            "startActivityForResult",
            "onActivityResult",
            "ActivityCompat.requestPermissions",
            ".requestPermissions("
        )
        val unexpected = mainSourceFiles()
            .filter { it.name !in allowedFiles }
            .mapNotNull { file ->
                val source = file.readText()
                val tokens = legacyApiTokens.filter(source::contains)
                if (tokens.isEmpty()) null else "${pathLabel(file)}: ${tokens.joinToString()}"
            }

        assertTrue(
            "Legacy picker/permission APIs must stay centralized in MainActivity with reviewed low request codes: $unexpected",
            unexpected.isEmpty()
        )
    }

    @Test
    fun notificationPermissionUsesExplicitLowRequestCode() {
        val source = mainActivitySource()

        assertTrue(
            "POST_NOTIFICATIONS should use an explicit low requestCode while legacy pickers still use onActivityResult",
            source.contains("ActivityCompat.requestPermissions")
        )
        assertTrue(
            "POST_NOTIFICATIONS must route through REQUEST_POST_NOTIFICATIONS",
            source.contains("REQUEST_POST_NOTIFICATIONS")
        )
    }

    private fun requestCodeConstantsInMainSources(): List<RequestCodeConstant> {
        val kotlinRegex = Regex(
            """\b(?:private\s+|internal\s+|public\s+|const\s+)*?(?:val|var)\s+([A-Za-z0-9_]*REQUEST[A-Za-z0-9_]*)\s*=\s*(0x[0-9A-Fa-f_]+|[0-9][0-9_]*)"""
        )
        val javaRegex = Regex(
            """\b(?:public\s+|private\s+|protected\s+|static\s+|final\s+)*?(?:int|Integer)\s+([A-Za-z0-9_]*REQUEST[A-Za-z0-9_]*)\s*=\s*(0x[0-9A-Fa-f_]+|[0-9][0-9_]*)"""
        )
        return mainSourceFiles().flatMap { file ->
            val source = file.readText()
            val kotlinConstants = kotlinRegex.findAll(source)
                .map { match ->
                    RequestCodeConstant(
                        file = pathLabel(file),
                        name = match.groupValues[1],
                        value = parseIntegerLiteral(match.groupValues[2])
                    )
                }
            val javaConstants = javaRegex.findAll(source)
                .map { match ->
                    RequestCodeConstant(
                        file = pathLabel(file),
                        name = match.groupValues[1],
                        value = parseIntegerLiteral(match.groupValues[2])
                    )
                }
            (kotlinConstants + javaConstants).toList()
        }
    }

    private fun mainActivitySource(): String {
        val source = mainSourceFiles().firstOrNull { it.name == "MainActivity.kt" }
        assertTrue("Could not find MainActivity.kt from ${File(".").absolutePath}", source != null)
        return source!!.readText()
    }

    private fun mainSourceFiles(): List<File> {
        val root = mainSourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .toList()
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main"),
            File("app/src/main")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("Could not find app/src/main from ${File(".").absolutePath}", root != null)
        return root!!
    }

    private fun projectRoot(): File {
        return if (File("app").isDirectory) File(".") else File("..")
    }

    private fun pathLabel(file: File): String {
        val root = projectRoot().canonicalFile
        return file.canonicalFile.relativeToOrSelf(root).path
    }

    private fun parseIntegerLiteral(raw: String): Long {
        val normalized = raw.replace("_", "")
        return if (normalized.startsWith("0x", ignoreCase = true)) {
            normalized.drop(2).toLong(16)
        } else {
            normalized.toLong()
        }
    }

    private data class RequestCodeConstant(
        val file: String,
        val name: String,
        val value: Long
    ) {
        override fun toString(): String = "$file:$name=$value"
    }
}

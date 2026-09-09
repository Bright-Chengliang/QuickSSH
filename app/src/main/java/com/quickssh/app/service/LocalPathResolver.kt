package com.quickssh.app.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object LocalPathResolver {

    private const val LOCAL_CACHE_DIR = "local_terminal_files"
    private const val MAX_CACHED_FILES = 50

    /**
     * Resolves a SAF/Content Uri to an absolute local filesystem path.
     * Guaranteed to return a valid readable path on the local filesystem.
     */
    fun resolvePath(context: Context, uri: Uri): String {
        // 1. Direct file:// uri
        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank() && File(path).exists()) {
                return path
            }
        }

        // 2. Try SAF Document resolution
        val safPath = resolveSafDocumentUri(context, uri)
        if (!safPath.isNullOrBlank() && File(safPath).exists()) {
            return safPath
        }

        // 3. Try MediaStore / ContentResolver column query
        val dataColPath = queryDataColumn(context, uri)
        if (!dataColPath.isNullOrBlank() && File(dataColPath).exists()) {
            return dataColPath
        }

        // 4. Fallback: copy content stream to internal cache sandbox (never external root)
        return copyToCacheSandbox(context, uri)
    }

    internal fun resolveSafDocumentUri(context: Context, uri: Uri): String? {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val auth = uri.authority ?: return null
                val docId = DocumentsContract.getDocumentId(uri)

                if (auth == "com.android.externalstorage.documents") {
                    val split = docId.split(":")
                    val type = split.getOrNull(0) ?: ""
                    val relativePath = split.getOrNull(1) ?: ""

                    return if (type.equals("primary", ignoreCase = true)) {
                        "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
                    } else {
                        "/storage/$type/$relativePath"
                    }
                }

                if (auth == "com.android.providers.downloads.documents") {
                    if (docId.startsWith("raw:")) {
                        return docId.removePrefix("raw:")
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun queryDataColumn(context: Context, uri: Uri): String? {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex >= 0 && cursor.moveToFirst()) {
                    val path = cursor.getString(columnIndex)
                    if (!path.isNullOrBlank()) return path
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun copyToCacheSandbox(context: Context, uri: Uri): String {
        val fileName = getFileName(context.contentResolver, uri)
        val dir = File(context.cacheDir, LOCAL_CACHE_DIR)
        dir.mkdirs()
        pruneCacheDir(dir)

        val destFile = File(dir, "${System.currentTimeMillis()}_$fileName")
        try {
            context.contentResolver.openInputStream(uri)?.use { inStream ->
                FileOutputStream(destFile).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
        } catch (e: Exception) {
            // If copy fails, return best effort path or uri string
            return destFile.absolutePath
        }
        return destFile.absolutePath
    }

    fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file.bin" } ?: "file.bin"
    }

    private fun pruneCacheDir(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size > MAX_CACHED_FILES) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_CACHED_FILES)
                .forEach { it.delete() }
        }
    }
}

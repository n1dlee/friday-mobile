package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

class FileAnalyzer(private val context: Context) {

    private companion object {
        const val TAG = "FileAnalyzer"
    }

    data class FileContent(
        val name: String,
        val mimeType: String,
        val isImage: Boolean,
        val textContent: String?,
        val base64Content: String?
    )

    fun getFilePickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/*",
                "application/pdf",
                "application/json",
                "image/*"
            ))
        }
    }

    fun readFile(uri: Uri): FileContent? {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(uri) ?: "unknown"
        val isImage = mimeType.startsWith("image/")

        return try {
            if (isImage) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return null
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                FileContent(fileName, mimeType, true, null, base64)
            } else {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return null
                // Limit text to ~8000 chars to stay within Groq context limits
                val truncated = if (text.length > 8000) text.take(8000) + "\n\n[...truncated]" else text
                FileContent(fileName, mimeType, false, truncated, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $uri: ${e.message}")
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }
}

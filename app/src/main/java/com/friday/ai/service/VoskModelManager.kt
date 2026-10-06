package com.friday.ai.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class VoskModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskModelManager"
        private const val MODEL_DIR_NAME = "vosk-model"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        private const val MODEL_MARKER = "conf/model.conf"
    }

    sealed class DownloadState {
        data object NotDownloaded : DownloadState()
        data class Downloading(val progress: Int) : DownloadState()
        data object Extracting : DownloadState()
        data object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR_NAME)

    fun getModelPath(): String? {
        val dir = modelDir
        if (!dir.exists()) return null
        val marker = File(dir, MODEL_MARKER)
        if (!marker.exists()) {
            val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: return null
            for (subdir in subdirs) {
                if (File(subdir, MODEL_MARKER).exists()) return subdir.absolutePath
            }
            return null
        }
        return dir.absolutePath
    }

    fun isModelReady(): Boolean = getModelPath() != null

    fun checkState() {
        _downloadState.value = if (isModelReady()) DownloadState.Ready else DownloadState.NotDownloaded
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _downloadState.value = DownloadState.Ready
            return@withContext
        }

        try {
            _downloadState.value = DownloadState.Downloading(0)

            val tempFile = File(context.cacheDir, "vosk-model.zip")
            downloadFile(MODEL_URL, tempFile)

            _downloadState.value = DownloadState.Extracting
            extractZip(tempFile, modelDir)
            tempFile.delete()

            if (isModelReady()) {
                _downloadState.value = DownloadState.Ready
                Log.i(TAG, "Model downloaded and extracted: ${getModelPath()}")
            } else {
                _downloadState.value = DownloadState.Error("Model extraction failed — marker file not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
        }
    }

    private fun downloadFile(urlStr: String, target: File) {
        val url = URL(urlStr)
        val connection = url.openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        val totalSize = connection.contentLength.toLong()
        var downloaded = 0L

        connection.getInputStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalSize > 0) {
                        val progress = (downloaded * 100 / totalSize).toInt()
                        _downloadState.value = DownloadState.Downloading(progress)
                    }
                }
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

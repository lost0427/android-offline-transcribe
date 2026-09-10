package com.voiceping.offlinetranscription.service

import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.ModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val modelsDir: File) {

    companion object {
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
        private const val TEMP_SUFFIX = ".tmp"
        private const val MANAGED_MODEL_READY_MARKER = ".managed_model_ready"
        private val MANAGED_MODEL_SUFFIXES = setOf(
            ".onnx",
            ".txt",
            ".model",
            ".bin",
            TEMP_SUFFIX
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Directory for a specific model's files. */
    fun modelDir(model: ModelInfo): File = File(modelsDir, model.id)

    /** Check that all files for a model are downloaded. */
    fun isModelDownloaded(model: ModelInfo): Boolean {
        val dir = modelDir(model)
        if (model.files.isEmpty()) {
            // Engine-managed models (e.g. Cactus) don't use our file catalog.
            // We treat them as downloaded only after a successful load has been recorded.
            return File(dir, MANAGED_MODEL_READY_MARKER).exists()
        }
        return model.files.all { File(dir, it.localName).exists() }
    }

    /** Mark an engine-managed model (empty file catalog) as ready after successful engine load. */
    fun markManagedModelReady(model: ModelInfo) {
        if (model.files.isNotEmpty()) return
        val dir = modelDir(model)
        dir.mkdirs()
        File(dir, MANAGED_MODEL_READY_MARKER).writeText("ready")
    }

    /** Downloads all files for a model, emitting overall progress (0.0 to 1.0). */
    fun download(model: ModelInfo): Flow<Float> = download(model.id, model.files)

    fun download(id: String, files: List<ModelFile>): Flow<Float> = flow {
        val dir = File(modelsDir, id)
        if (files.isEmpty()) {
            dir.mkdirs()
            emit(1.0f)
            return@flow
        }

        dir.mkdirs()
        pruneStaleModelFiles(dir, files)

        val totalFiles = files.size
        for ((fileIndex, modelFile) in files.withIndex()) {
            val targetFile = File(dir, modelFile.localName)

            // Skip already downloaded files
            if (targetFile.exists()) {
                val overallProgress = (fileIndex + 1).toFloat() / totalFiles
                emit(overallProgress)
                continue
            }

            val tempFile = File(dir, "${modelFile.localName}$TEMP_SUFFIX")
            val requestBuilder = Request.Builder().url(modelFile.url)

            // Support resume if temp file exists
            if (tempFile.exists()) {
                requestBuilder.addHeader("Range", "bytes=${tempFile.length()}-")
            }

            var bytesRead: Long
            var totalBytes: Long
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Download failed: HTTP ${response.code} for ${modelFile.localName}")
                }

                val body = response.body ?: throw Exception("Empty response body")
                val contentLength = body.contentLength()
                val existingBytes = if (response.code == 206) tempFile.length() else 0L
                totalBytes = contentLength + existingBytes

                val outputStream = FileOutputStream(tempFile, response.code == 206)
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                bytesRead = existingBytes

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                // Progress within this file, scaled to overall progress
                                val fileProgress = bytesRead.toFloat() / totalBytes.toFloat()
                                val overallProgress = (fileIndex + fileProgress) / totalFiles
                                emit(overallProgress)
                            }
                        }
                    }
                }
            }

            // Verify download size matches Content-Length
            if (totalBytes > 0 && bytesRead != totalBytes) {
                tempFile.safeDelete()
                throw Exception(
                    "Download incomplete for ${modelFile.localName}: " +
                    "expected $totalBytes bytes, got $bytesRead bytes"
                )
            }

            // Rename temp to final
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                if (targetFile.exists() && targetFile.length() == tempFile.length()) {
                    tempFile.safeDelete()
                } else {
                    targetFile.safeDelete()
                    throw java.io.IOException("Failed to finalize ${modelFile.localName}: copy verification failed")
                }
            }
        }
        emit(1.0f)
    }.flowOn(Dispatchers.IO)

    /**
     * Remove stale artifacts from previous model revisions in the same model ID directory.
     * This prevents mixing incompatible ONNX file sets (e.g. old and new Zipformer variants).
     */
    private fun pruneStaleModelFiles(dir: File, files: List<ModelFile>) {
        val expected = files.map { it.localName }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach

            val name = file.name
            val baseName = name.removeSuffix(TEMP_SUFFIX)
            val isExpected = expected.contains(baseName)
            if (isExpected) return@forEach

            if (name.hasManagedModelSuffix()) {
                file.safeDelete()
            }
        }
    }

    private fun String.hasManagedModelSuffix(): Boolean {
        return MANAGED_MODEL_SUFFIXES.any { endsWith(it) }
    }

    private fun File.safeDelete() {
        if (exists() && !delete()) {
            // Best effort cleanup only; caller validates final artifacts separately.
        }
    }
}

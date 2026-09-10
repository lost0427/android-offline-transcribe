package com.voiceping.offlinetranscription.service

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ASR engine backed by whisper.cpp (GGML) via JNI.
 * Matches the iOS CactusKit approach: direct whisper.cpp inference
 * with quantized GGML model files (.bin).
 *
 * Audio is passed as Float32 [-1,1] directly to whisper_full().
 * Model files (.bin) are downloaded via the standard [ModelDownloader] flow.
 */
class CactusEngine : AsrEngine {
    companion object {
        private const val TAG = "CactusEngine"
        private const val MIN_SAMPLES = 16000 // 1 second at 16kHz

        internal fun isRuntimeSupported(): Boolean {
            return Build.SUPPORTED_ABIS?.any { it == "arm64-v8a" } == true
        }
    }

    private var contextPtr: Long = 0
    private val mutex = Mutex()
    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean get() = loaded

    override suspend fun loadModel(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isRuntimeSupported()) {
                    Log.e(TAG, "whisper.cpp requires arm64-v8a: ${Build.SUPPORTED_ABIS.joinToString()}")
                    return@withContext false
                }
                try {
                    // Release previous context
                    if (contextPtr != 0L) {
                        WhisperCppLib.freeContext(contextPtr)
                        contextPtr = 0
                        loaded = false
                    }

                    // Find the .bin model file in the model directory
                    val binFile = findModelBin(modelPath)
                    if (binFile == null) {
                        Log.e(TAG, "No .bin model file found in: $modelPath")
                        return@withContext false
                    }

                    Log.i(TAG, "Loading whisper.cpp model: ${binFile.absolutePath}")
                    val ptr = WhisperCppLib.initContext(binFile.absolutePath)
                    if (ptr == 0L) {
                        Log.e(TAG, "whisper_init_from_file failed for: ${binFile.name}")
                        return@withContext false
                    }

                    contextPtr = ptr
                    loaded = true
                    Log.i(TAG, "Loaded whisper.cpp model: ${binFile.name} (sysinfo: ${WhisperCppLib.getSystemInfo()})")
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load whisper.cpp model", e)
                    contextPtr = 0
                    loaded = false
                    false
                }
            }
        }
    }

    override suspend fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int,
        language: String
    ): List<TranscriptionSegment> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val ptr = contextPtr
                if (ptr == 0L) return@withContext emptyList()
                try {
                    if (audioSamples.size < MIN_SAMPLES) {
                        return@withContext emptyList()
                    }

                    // Pass Float32 samples directly to whisper.cpp (same as iOS CactusKit)
                    val result = WhisperCppLib.fullTranscribe(
                        ptr,
                        numThreads,
                        audioSamples,
                        language.ifBlank { "en" }
                    )

                    if (result != 0) {
                        Log.w(TAG, "whisper_full() returned error code: $result")
                        return@withContext emptyList()
                    }

                    val segmentCount = WhisperCppLib.getSegmentCount(ptr)
                    if (segmentCount <= 0) return@withContext emptyList()

                    val segments = mutableListOf<TranscriptionSegment>()
                    for (i in 0 until segmentCount) {
                        val text = WhisperCppLib.getSegmentText(ptr, i).trim()
                        if (text.isBlank()) continue
                        // whisper.cpp returns times in centiseconds (10ms units)
                        val t0 = WhisperCppLib.getSegmentT0(ptr, i) * 10
                        val t1 = WhisperCppLib.getSegmentT1(ptr, i) * 10
                        segments.add(TranscriptionSegment(text = text, startMs = t0, endMs = t1))
                    }

                    if (segments.isEmpty()) return@withContext emptyList()

                    Log.i(TAG, "Transcribed ${segments.size} segments, lang=${WhisperCppLib.getDetectedLanguage(ptr)}")
                    segments
                } catch (e: Throwable) {
                    Log.e(TAG, "whisper.cpp transcribe failed", e)
                    emptyList()
                }
            }
        }
    }

    override fun release() {
        runBlocking {
            mutex.withLock {
                loaded = false
                try {
                    if (contextPtr != 0L) {
                        WhisperCppLib.freeContext(contextPtr)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to free whisper.cpp context during release", e)
                }
                contextPtr = 0
            }
        }
    }

    /** Find the .bin GGML model file in the given directory. */
    private fun findModelBin(modelPath: String): File? {
        val dir = File(modelPath)
        if (!dir.isDirectory) {
            // modelPath might be the file itself
            if (dir.isFile && dir.name.endsWith(".bin")) return dir
            return null
        }
        return dir.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
    }
}

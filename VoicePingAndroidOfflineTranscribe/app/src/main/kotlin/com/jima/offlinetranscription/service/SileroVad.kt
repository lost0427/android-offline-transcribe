package com.voiceping.offlinetranscription.service

import com.voiceping.offlinetranscription.model.ModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class VadSegment(val startMs: Long, val endMs: Long)

class SileroVad(private val modelsDir: File, private val downloader: ModelDownloader) {
    companion object {
        private const val ID = "silero-vad-v6.2.0"
        private const val URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
        private const val FILE = "ggml-silero-v6.2.0.bin"
        const val THRESHOLD = 0.35f
        const val MIN_SPEECH_MS = 100
        const val MIN_SILENCE_MS = 700
        const val MAX_SPEECH_SECONDS = 30f
        const val SPEECH_PAD_MS = 400
        const val OVERLAP_SECONDS = 0.5f
    }

    private val _state = MutableStateFlow(ModelState.Unloaded)
    val state: StateFlow<ModelState> = _state
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var pointer = 0L

    suspend fun prepare(download: Boolean): Boolean {
        _error.value = null
        val files = listOf(com.voiceping.offlinetranscription.model.ModelFile(URL, FILE))
        if (download && !isDownloaded()) {
            _state.value = ModelState.Downloading
            downloader.download(ID, files).collect { _progress.value = it }
        }
        val model = File(modelsDir, "$ID/$FILE")
        if (!model.isFile) return false
        _state.value = ModelState.Loading
        pointer = WhisperCppLib.initVad(model.absolutePath, 2)
        if (pointer == 0L) {
            _state.value = ModelState.Unloaded
            _error.value = "Failed to load Silero VAD"
            return false
        }
        _state.value = ModelState.Loaded
        return true
    }

    fun isDownloaded() = File(modelsDir, "$ID/$FILE").isFile

    fun detect(samples: FloatArray): List<VadSegment> {
        if (pointer == 0L) return emptyList()
        val flat = WhisperCppLib.detectVad(pointer, samples, THRESHOLD, MIN_SPEECH_MS, MIN_SILENCE_MS,
            MAX_SPEECH_SECONDS, SPEECH_PAD_MS, OVERLAP_SECONDS)
        return flat.toList().chunked(2).map { (startMs, endMs) -> VadSegment(startMs.toLong(), endMs.toLong()) }
    }

    fun release() {
        if (pointer != 0L) WhisperCppLib.freeVad(pointer)
        pointer = 0L
    }
}

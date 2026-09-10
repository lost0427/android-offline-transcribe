package com.voiceping.offlinetranscription.service

/**
 * JNI bridge to whisper.cpp for direct GGML model inference.
 * Mirrors the iOS CactusKit/CactusRuntime approach.
 */
object WhisperCppLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Initialize a whisper context from a GGML model file path. Returns context pointer (0 on failure). */
    external fun initContext(modelPath: String): Long

    /** Free a whisper context. */
    external fun freeContext(contextPtr: Long)

    /** Check if a context pointer is valid. */
    external fun isContextValid(contextPtr: Long): Boolean

    /**
     * Run full transcription on Float32 audio samples (16kHz mono, [-1,1]).
     * Returns 0 on success, non-zero on failure.
     */
    external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String?): Int

    /** Get the number of text segments after transcription. */
    external fun getSegmentCount(contextPtr: Long): Int

    /** Get the text of segment at index. */
    external fun getSegmentText(contextPtr: Long, index: Int): String

    /** Get start time of segment (in centiseconds, multiply by 10 for ms). */
    external fun getSegmentT0(contextPtr: Long, index: Int): Long

    /** Get end time of segment (in centiseconds, multiply by 10 for ms). */
    external fun getSegmentT1(contextPtr: Long, index: Int): Long

    /** Get detected language after transcription. */
    external fun getDetectedLanguage(contextPtr: Long): String

    /** Get whisper.cpp system info string. */
    external fun getSystemInfo(): String
    external fun initVad(modelPath: String, numThreads: Int): Long
    external fun freeVad(contextPtr: Long)
    external fun detectVad(contextPtr: Long, audioData: FloatArray, threshold: Float, minSpeechMs: Int,
                           minSilenceMs: Int, maxSpeechSeconds: Float, speechPadMs: Int,
                           overlapSeconds: Float): Array<VadSegment>
}

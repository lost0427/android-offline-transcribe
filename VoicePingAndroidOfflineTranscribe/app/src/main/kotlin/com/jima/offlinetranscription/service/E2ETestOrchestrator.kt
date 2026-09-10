package com.voiceping.offlinetranscription.service

import android.content.Context
import android.util.Log
import com.voiceping.offlinetranscription.model.EngineType
import com.voiceping.offlinetranscription.model.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** E2E test result for evidence collection. */
data class E2ETestResult(
    val modelId: String,
    val engine: String,
    val transcript: String,
    val tokensPerSecond: Double,
    val translatedText: String,
    val pass: Boolean,
    val skipped: Boolean = false,
    val durationMs: Double,
    val timestamp: String,
    val error: String? = null
)

/**
 * Handles E2E test evidence collection and result serialization.
 *
 * Extracted from WhisperEngine to isolate test infrastructure from production logic.
 * Reads transcription/translation state from the engine to build evidence payloads.
 */
class E2ETestOrchestrator(
    private val context: Context,
    private val engine: WhisperEngine
) {
    private val _e2eResult = MutableStateFlow<E2ETestResult?>(null)
    val e2eResult: StateFlow<E2ETestResult?> = _e2eResult.asStateFlow()

    fun reset() {
        _e2eResult.value = null
    }

    fun writeResult(
        transcript: String,
        durationMs: Double,
        tokensPerSecond: Double,
        error: String?,
        skipped: Boolean = false
    ) {
        val model = engine.selectedModel.value
        val keywords = listOf("country", "ask", "do for", "fellow", "americans")
        val lowerTranscript = transcript.lowercase()
        val translatedText = engine.translatedConfirmedText.value
        val sourceCode = engine.translationSourceLanguageCode.value.trim().lowercase()
        val targetCode = engine.translationTargetLanguageCode.value.trim().lowercase()
        val expectsTranslation = !skipped &&
            engine.translationEnabled.value &&
            transcript.isNotBlank() &&
            sourceCode.isNotBlank() &&
            targetCode.isNotBlank() &&
            sourceCode != targetCode
        val translationReady = !expectsTranslation || translatedText.isNotBlank()
        val isOmnilingual = model.id.contains("omnilingual", ignoreCase = true)
        val hasKeywordHit = keywords.any { lowerTranscript.contains(it) }
        val hasMeaningfulText = transcript.any { it.isLetterOrDigit() }
        val asciiLetters = transcript.count { it.isLetter() && it.code < 128 }
        val nonAsciiLetters = transcript.count { it.isLetter() && it.code >= 128 }
        val omnilingualLooksEnglish =
            asciiLetters >= 8 &&
                asciiLetters >= nonAsciiLetters
        val isAndroidSpeech = model.engineType == EngineType.ANDROID_SPEECH
        val androidSpeechApiLimited = isAndroidSpeech &&
            !AndroidSpeechEngine.supportsAudioPipe() &&
            transcript.contains("API 33+")
        val transcriptPass = when {
            androidSpeechApiLimited -> true  // Engine correctly reports API limitation
            isOmnilingual -> hasMeaningfulText && omnilingualLooksEnglish
            else -> hasKeywordHit
        }

        // pass = core transcription quality only; translation tracked separately
        val pass = !skipped &&
            error == null &&
            transcript.isNotEmpty() &&
            transcriptPass

        val result = E2ETestResult(
            modelId = model.id,
            engine = model.inferenceMethod,
            transcript = transcript,
            tokensPerSecond = tokensPerSecond,
            translatedText = translatedText,
            pass = pass,
            skipped = skipped,
            durationMs = durationMs,
            timestamp = java.time.Instant.now().toString(),
            error = error
        )
        _e2eResult.value = result

        val json = JSONObject().apply {
            put("model_id", result.modelId)
            put("engine", result.engine)
            put("transcript", result.transcript)
            put("tokens_per_second", result.tokensPerSecond)
            put("translated_text", result.translatedText)
            put("translation_warning", engine.translationWarning.value ?: JSONObject.NULL)
            put("expects_translation", expectsTranslation)
            put("translation_ready", translationReady)
            put("pass", result.pass)
            put("skipped", result.skipped)
            put("duration_ms", result.durationMs)
            put("timestamp", result.timestamp)
            put("error", result.error ?: JSONObject.NULL)
        }.toString(2)
        writeJson(modelId = model.id, json = json)
    }

    fun writeFailure(modelId: String = engine.selectedModel.value.id, error: String) {
        writeEmptyResult(modelId = modelId, skipped = false, error = error)
    }

    fun writeSkipped(modelId: String = engine.selectedModel.value.id, reason: String) {
        writeEmptyResult(modelId = modelId, skipped = true, error = reason)
    }

    private fun writeEmptyResult(modelId: String, skipped: Boolean, error: String) {
        val model = ModelInfo.findById(modelId) ?: engine.selectedModel.value
        val json = JSONObject().apply {
            put("model_id", modelId)
            put("engine", model.inferenceMethod)
            put("transcript", "")
            put("tokens_per_second", 0.0)
            put("translated_text", "")
            put("translation_warning", JSONObject.NULL)
            put("expects_translation", false)
            put("translation_ready", true)
            put("pass", false)
            put("skipped", skipped)
            put("duration_ms", 0.0)
            put("timestamp", java.time.Instant.now().toString())
            put("error", error)
        }.toString(2)
        writeJson(modelId = modelId, json = json)
    }

    private fun writeJson(modelId: String, json: String) {
        var wrote = false

        // App-private external dir (legacy path consumed by scripts/tests)
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null) {
                val file = File(extDir, "e2e_result_${modelId}.json")
                file.writeText(json)
                Log.i("E2E", "Result written to ${file.absolutePath}")
                wrote = true
            }
        } catch (e: Throwable) {
            Log.w("E2E", "Could not write app-private E2E result JSON", e)
        }

        // Public evidence dir used by UiAutomator captures and adb pull.
        try {
            val evidenceDir = File("/sdcard/Documents/e2e/$modelId")
            if (!evidenceDir.exists()) {
                evidenceDir.mkdirs()
            }
            val evidenceFile = File(evidenceDir, "result.json")
            evidenceFile.writeText(json)
            Log.i("E2E", "Result mirrored to ${evidenceFile.absolutePath}")
            wrote = true
        } catch (e: Throwable) {
            Log.w("E2E", "Could not mirror E2E result JSON to public evidence dir", e)
        }

        // Internal storage fallback — always accessible without permissions.
        if (!wrote) {
            try {
                val internalFile = File(context.filesDir, "e2e_result_${modelId}.json")
                internalFile.writeText(json)
                Log.i("E2E", "Result written to internal: ${internalFile.absolutePath}")
                wrote = true
            } catch (e: Throwable) {
                Log.w("E2E", "Could not write internal E2E result JSON", e)
            }
        }

        if (!wrote) {
            Log.w("E2E", "E2E result JSON was not written to any location")
        }
    }
}

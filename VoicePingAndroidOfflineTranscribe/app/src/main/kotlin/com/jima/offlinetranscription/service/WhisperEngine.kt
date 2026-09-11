package com.voiceping.offlinetranscription.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.voiceping.offlinetranscription.data.AppPreferences
import com.voiceping.offlinetranscription.history.TranscriptHistoryEntry
import com.voiceping.offlinetranscription.history.TranscriptHistoryRepository
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.AppError
import com.voiceping.offlinetranscription.model.EngineType
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.ModelState
import com.voiceping.offlinetranscription.model.PerformanceProfile
import com.voiceping.offlinetranscription.model.ExecutionProviderStatus
import com.voiceping.offlinetranscription.util.TextNormalizationUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

data class TranscriptionSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val detectedLanguage: String? = null
)

/** Strict session state machine for the transcription pipeline. */
enum class SessionState {
    Idle,       // No recording, ready to start
    Recording,  // Mic active, transcription loop running
    Stopping,   // Stop requested, waiting for jobs to complete
    Error       // Error occurred, needs clearTranscription() to reset
}

class WhisperEngine(
    private val context: Context,
    private val preferences: AppPreferences,
    private val transcriptHistory: TranscriptHistoryRepository,
) {
    // NOTE: Cactus Android SDK hardcodes its own model cache under `filesDir/models/<slug>`.
    // Our app models must not share that namespace, otherwise Cactus will see our
    // whisper.cpp/sherpa directories as "downloaded" and skip its own download.
    private val modelsDir = File(context.filesDir, "asr_models")
    private val downloader = ModelDownloader(modelsDir)
    val sileroVad = SileroVad(modelsDir, downloader)
    val audioRecorder = AudioRecorder(context)

    // Model state
    private val _modelState = MutableStateFlow(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _selectedModel = MutableStateFlow(ModelInfo.defaultModel)
    val selectedModel: StateFlow<ModelInfo> = _selectedModel.asStateFlow()
    private val _executionProviderStatus = MutableStateFlow(ExecutionProviderStatus())
    val executionProviderStatus: StateFlow<ExecutionProviderStatus> = _executionProviderStatus.asStateFlow()
    val autonomousCaptureEnabled: Flow<Boolean> get() = preferences.autonomousCaptureEnabled
    val autonomousCapturePaused: Flow<Boolean> get() = preferences.autonomousCapturePaused
    val autonomousCaptureAllowlist: Flow<Set<String>> get() = preferences.autonomousCaptureAllowlist

    private val _performanceProfile = MutableStateFlow(PerformanceProfile.BALANCED)
    val performanceProfile: StateFlow<PerformanceProfile> = _performanceProfile.asStateFlow()

    // Session state machine
    private val _sessionState = MutableStateFlow(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // Derived isRecording for backward compat
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Transcription output
    private val _confirmedText = MutableStateFlow("")
    val confirmedText: StateFlow<String> = _confirmedText.asStateFlow()

    private val _hypothesisText = MutableStateFlow("")
    val hypothesisText: StateFlow<String> = _hypothesisText.asStateFlow()

    private val _bufferEnergy = MutableStateFlow<List<Float>>(emptyList())
    val bufferEnergy: StateFlow<List<Float>> = _bufferEnergy.asStateFlow()

    private val _bufferSeconds = MutableStateFlow(0.0)
    val bufferSeconds: StateFlow<Double> = _bufferSeconds.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0.0)
    val tokensPerSecond: StateFlow<Double> = _tokensPerSecond.asStateFlow()

    private val _lastError = MutableStateFlow<AppError?>(null)
    val lastError: StateFlow<AppError?> = _lastError.asStateFlow()

    private val _useVAD = MutableStateFlow(true)
    val useVAD: StateFlow<Boolean> = _useVAD.asStateFlow()

    private val _enableTimestamps = MutableStateFlow(true)
    val enableTimestamps: StateFlow<Boolean> = _enableTimestamps.asStateFlow()

    private val _audioInputMode = MutableStateFlow(AudioInputMode.MICROPHONE)
    val audioInputMode: StateFlow<AudioInputMode> = _audioInputMode.asStateFlow()
    private val _liveLanguageHint = MutableStateFlow("auto")
    val liveLanguageHint: StateFlow<String> = _liveLanguageHint.asStateFlow()

    private val _systemAudioCaptureReady = MutableStateFlow(false)
    val systemAudioCaptureReady: StateFlow<Boolean> = _systemAudioCaptureReady.asStateFlow()
    val isSystemAudioCaptureSupported: Boolean
        get() = audioRecorder.isSystemAudioCaptureSupported

    @Volatile var e2eLocked = false
    private val _translationEnabled = MutableStateFlow(false)
    val translationEnabled: StateFlow<Boolean> = _translationEnabled.asStateFlow()

    private val _translationSourceLanguageCode = MutableStateFlow("en")
    val translationSourceLanguageCode: StateFlow<String> = _translationSourceLanguageCode.asStateFlow()

    private val _translationTargetLanguageCode = MutableStateFlow("ja")
    val translationTargetLanguageCode: StateFlow<String> = _translationTargetLanguageCode.asStateFlow()

    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()

    private val _translatedConfirmedText = MutableStateFlow("")
    val translatedConfirmedText: StateFlow<String> = _translatedConfirmedText.asStateFlow()

    private val _translatedHypothesisText = MutableStateFlow("")
    val translatedHypothesisText: StateFlow<String> = _translatedHypothesisText.asStateFlow()

    private val _translationWarning = MutableStateFlow<String?>(null)
    val translationWarning: StateFlow<String?> = _translationWarning.asStateFlow()

    val translationModelReady: StateFlow<Boolean> get() = mlKitTranslator.modelReady
    val translationDownloadStatus: StateFlow<String?> get() = mlKitTranslator.downloadStatus

    // System resource metrics (always sampled)
    private val systemMetrics = SystemMetrics()
    private val _cpuPercent = MutableStateFlow(0f)
    val cpuPercent: StateFlow<Float> = _cpuPercent.asStateFlow()
    private val _memoryMB = MutableStateFlow(0f)
    val memoryMB: StateFlow<Float> = _memoryMB.asStateFlow()

    // E2E evidence collection (delegated to E2ETestOrchestrator)
    val e2eOrchestrator by lazy { E2ETestOrchestrator(context, this) }
    val e2eResult: StateFlow<E2ETestResult?> get() = e2eOrchestrator.e2eResult

    // ASR engine abstraction
    private val setupMutex = Mutex()
    internal var currentEngine: AsrEngine? = null
    private var fileTranscriptionJob: Job? = null
    private var recordingJob: Job? = null
    private var energyJob: Job? = null
    private val recorderPrewarmMutex = Mutex()
    val transcriptionCoordinator = TranscriptionCoordinator(this)
    internal var chunkManager = transcriptionCoordinator.createChunkManagerForModel(_selectedModel.value)
    private val sessionToken = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mlKitTranslator = MlKitTranslator()
    private var translationJob: Job? = null
    private var lastTranslationInput: Pair<String, String>? = null

    private fun nextSessionToken(): Long = sessionToken.incrementAndGet()

    private fun invalidateSession() {
        sessionToken.incrementAndGet()
    }

    internal fun isSessionActive(token: Long): Boolean {
        return sessionToken.get() == token && _sessionState.value == SessionState.Recording
    }

    companion object {
        private const val INFERENCE_PREWARM_AUDIO_SECONDS = 0.5f
        private const val VAD_MAX_CHUNK_MS = 30_000L
        fun normalizeLanguageCode(raw: String?): String? =
            TextNormalizationUtils.normalizeLanguageCode(raw)
    }

    // MARK: - Mutation Methods (for TranscriptionCoordinator)

    internal fun updateConfirmedText(text: String) { _confirmedText.value = text }
    internal fun updateHypothesisText(text: String) { _hypothesisText.value = text }
    internal fun updateDetectedLanguage(lang: String) { _detectedLanguage.value = lang }
    internal fun updateTokensPerSecond(value: Double) { _tokensPerSecond.value = value }
    internal fun updateBufferEnergy(energy: List<Float>) { _bufferEnergy.value = energy }
    internal fun updateBufferSeconds(seconds: Double) { _bufferSeconds.value = seconds }
    internal fun inferenceThreadCount(): Int =
        _performanceProfile.value.recommendedCpuThreads(Runtime.getRuntime().availableProcessors())

    internal fun onTranscriptionError(error: AppError) {
        _lastError.value = error
        transitionTo(SessionState.Error)
        audioRecorder.stopRecording()
        stopMicrophoneForegroundService()
    }

    internal fun onNoSignalDetected() {
        _lastError.value = AppError.NoMicrophoneSignal()
        transitionTo(SessionState.Error)
        audioRecorder.stopRecording()
        stopMicrophoneForegroundService()
        transcriptionCoordinator.cancelTranscriptionJob()
        cancelRecorderAndEnergyJobs()
    }

    val fullTranscriptionText: String
        get() {
            // Use StateFlow values directly (not chunkManager text properties)
            // so this works for both offline and streaming engines.
            val parts = listOfNotNull(
                _confirmedText.value.takeIf { it.isNotBlank() },
                _hypothesisText.value.takeIf { it.isNotBlank() }
            )
            return TextNormalizationUtils.normalizeText(parts.joinToString(" "))
        }

    val recordingDurationSeconds: Double
        get() = audioRecorder.bufferSeconds

    init {
        scope.launch {
            preferences.selectedModelId.collect { savedId ->
                if (e2eLocked) return@collect
                if (savedId != null) {
                    ModelInfo.findById(savedId)?.let {
                        _selectedModel.value = it
                    }
                }
            }
        }
        scope.launch {
            preferences.useVAD.collect { _useVAD.value = it }
        }
        scope.launch {
            preferences.enableTimestamps.collect { _enableTimestamps.value = it }
        }
        scope.launch {
            preferences.performanceProfile.collect { saved ->
                _performanceProfile.value = runCatching { PerformanceProfile.valueOf(saved) }
                    .getOrDefault(PerformanceProfile.BALANCED)
            }
        }
        scope.launch {
            preferences.translationEnabled.collect { enabled ->
                if (e2eLocked) return@collect
                _translationEnabled.value = enabled
                if (enabled) {
                    scheduleTranslationUpdate()
                } else {
                    resetTranslationState()
                }
            }
        }
        scope.launch {
            preferences.translationSourceLanguage.collect { code ->
                if (e2eLocked) return@collect
                if (code != _translationSourceLanguageCode.value) {
                    _translationSourceLanguageCode.value = code
                    lastTranslationInput = null
                    scheduleTranslationUpdate()
                }
            }
        }
        scope.launch {
            preferences.translationTargetLanguage.collect { code ->
                if (e2eLocked) return@collect
                if (code != _translationTargetLanguageCode.value) {
                    _translationTargetLanguageCode.value = code
                    lastTranslationInput = null
                    scheduleTranslationUpdate()
                }
            }
        }
        // Always-running system metrics sampling
        scope.launch(Dispatchers.Default) {
            while (true) {
                _cpuPercent.value = systemMetrics.getCpuPercent()
                _memoryMB.value = systemMetrics.getMemoryMB()
                delay(1000)
            }
        }
    }

    /** Create the appropriate ASR engine for the given model. */
    private fun createEngine(model: ModelInfo): AsrEngine {
        return when (model.engineType) {
            EngineType.SHERPA_ONNX -> SherpaOnnxEngine(
                modelType = model.sherpaModelType
                    ?: throw IllegalArgumentException("sherpaModelType required for SHERPA_ONNX models")
            )
            EngineType.SHERPA_ONNX_STREAMING -> SherpaOnnxStreamingEngine()
            EngineType.CACTUS -> {
                if (!CactusEngine.isRuntimeSupported()) {
                    throw IllegalStateException("Cactus engine requires an arm64-v8a device.")
                }
                CactusEngine()
            }
            EngineType.QWEN_ASR -> QwenASREngine()
            EngineType.QWEN_ONNX -> QwenOnnxEngine()
            EngineType.ANDROID_SPEECH -> AndroidSpeechEngine(
                context = context,
                preferOffline = model.id.contains("offline")
            )
        }
    }

    /** Resolve the path to pass to loadModel based on engine type. */
    private fun resolveModelPath(model: ModelInfo): String {
        return when (model.engineType) {
            EngineType.SHERPA_ONNX -> downloader.modelDir(model).absolutePath
            EngineType.SHERPA_ONNX_STREAMING -> downloader.modelDir(model).absolutePath
            EngineType.CACTUS -> downloader.modelDir(model).absolutePath
            EngineType.QWEN_ASR -> downloader.modelDir(model).absolutePath
            EngineType.QWEN_ONNX -> downloader.modelDir(model).absolutePath
            EngineType.ANDROID_SPEECH -> "" // System-provided, no model path
        }
    }

    /** Ensure startup model selection reflects persisted user preference before loading. */
    suspend fun syncSelectedModelFromPreferences() {
        val savedId = preferences.selectedModelId.first() ?: return
        val savedModel = ModelInfo.findById(savedId) ?: return
        _selectedModel.value = savedModel
    }

    suspend fun loadModelIfAvailable() {
        val model = _selectedModel.value
        if (!downloader.isModelDownloaded(model)) return

        _modelState.value = ModelState.Loading
        _lastError.value = null

        try {
            val engine = createEngine(model)
            val modelPath = resolveModelPath(model)
            val success = engine.loadModel(modelPath)
            if (!success) throw Exception("Failed to load model")
            downloader.markManagedModelReady(model)
            currentEngine = engine
            _executionProviderStatus.value = engine.executionProviderStatus
    
            _modelState.value = ModelState.Loaded
        } catch (e: Throwable) {
            _modelState.value = ModelState.Unloaded
        }
    }

    fun unloadModel() {
        resetTranscriptionState()
        currentEngine?.release()
        currentEngine = null
        _executionProviderStatus.value = ExecutionProviderStatus()

        _modelState.value = ModelState.Unloaded
    }

    fun isModelDownloaded(model: ModelInfo): Boolean = downloader.isModelDownloaded(model)

    fun setSelectedModel(model: ModelInfo) {
        _selectedModel.value = model
        chunkManager = transcriptionCoordinator.createChunkManagerForModel(model)
        resetTranscriptionState()
    }

    /** Launch setupModel on the engine's own scope so it survives ViewModel destruction. */
    fun launchSetup() {
        scope.launch { setupModel() }
    }

    suspend fun setupModel() = setupMutex.withLock {
        val model = _selectedModel.value
        _modelState.value = ModelState.Downloading
        _downloadProgress.value = 0f
        _lastError.value = null

        try {
            if (!downloader.isModelDownloaded(model)) {
                if (model.files.isNotEmpty() && !hasValidatedInternetConnection()) {
                    Log.w("WhisperEngine", "No validated internet connection while downloading ${model.id}")
                    _modelState.value = ModelState.Unloaded
                    _lastError.value = AppError.NetworkUnavailable()
                    return@withLock
                }

                // Check available storage before attempting download
                // Use context.filesDir (always exists) since modelsDir may not exist yet
                val available = context.filesDir.usableSpace
                val needed = parseModelSize(model.sizeOnDisk)
                if (needed > 0 && available < (needed * 1.1).toLong()) {
                    _modelState.value = ModelState.Unloaded
                    _lastError.value = AppError.InsufficientStorage(
                        needed = model.sizeOnDisk,
                        available = android.text.format.Formatter.formatFileSize(context, available)
                    )
                    return@withLock
                }

                downloader.download(model).collect { progress ->
                    _downloadProgress.value = progress
                }
            }

            _modelState.value = ModelState.Loading
            _downloadProgress.value = 1f

            val engine = createEngine(model)
            val modelPath = resolveModelPath(model)
            val success = withContext(Dispatchers.Default) {
                engine.loadModel(modelPath)
            }
            if (!success) throw Exception("Failed to load model")
            downloader.markManagedModelReady(model)

            val previousEngine = currentEngine
            currentEngine = engine
    
            _modelState.value = ModelState.Loaded
            preferences.setSelectedModelId(model.id)
            if (previousEngine != null && previousEngine !== engine) {
                withContext(Dispatchers.Default) {
                    previousEngine.release()
                }
            }
        } catch (e: Throwable) {
            val wasDownloading = _downloadProgress.value < 1f
            _modelState.value = ModelState.Unloaded
            _downloadProgress.value = 0f
            _lastError.value = if (wasDownloading) {
                mapDownloadError(e)
            } else {
                AppError.ModelLoadFailed(e)
            }
        }
    }

    suspend fun switchModel(model: ModelInfo) {
        if (_sessionState.value == SessionState.Recording) {
            stopRecordingAndWait()
        }
        _selectedModel.value = model
        chunkManager = transcriptionCoordinator.createChunkManagerForModel(model)
        resetTranscriptionState()

        val previousEngine = currentEngine
        if (previousEngine != null) {
            withContext(Dispatchers.Default) {
                previousEngine.release()
            }
        }
        currentEngine = null

        _modelState.value = ModelState.Unloaded
        setupModel()
    }

    suspend fun setUseVAD(enabled: Boolean) {
        _useVAD.value = enabled
        preferences.setUseVAD(enabled)
    }

    suspend fun setEnableTimestamps(enabled: Boolean) {
        _enableTimestamps.value = enabled
        preferences.setEnableTimestamps(enabled)
    }

    suspend fun setPerformanceProfile(profile: PerformanceProfile) {
        _performanceProfile.value = profile
        preferences.setPerformanceProfile(profile.name)
    }

    suspend fun setAutonomousCaptureEnabled(enabled: Boolean) = preferences.setAutonomousCaptureEnabled(enabled)

    suspend fun setAutonomousCapturePaused(paused: Boolean) = preferences.setAutonomousCapturePaused(paused)

    suspend fun setAutonomousCaptureAllowlist(packages: Set<String>) =
        preferences.setAutonomousCaptureAllowlist(packages)

    suspend fun setTranslationEnabled(enabled: Boolean) {
        _translationEnabled.value = enabled
        preferences.setTranslationEnabled(enabled)
        if (!enabled) {
            resetTranslationState()
        } else {
            scheduleTranslationUpdate()
        }
    }

    suspend fun setTranslationSourceLanguageCode(languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        _translationSourceLanguageCode.value = normalized
        preferences.setTranslationSourceLanguage(normalized)
        lastTranslationInput = null  // Force re-translation with new language
        scheduleTranslationUpdate()
    }

    suspend fun setTranslationTargetLanguageCode(languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode) ?: return
        _translationTargetLanguageCode.value = normalized
        preferences.setTranslationTargetLanguage(normalized)
        lastTranslationInput = null  // Force re-translation with new language
        scheduleTranslationUpdate()
    }

    /**
     * Warm up microphone recorder path ahead of first record tap so runtime setup
     * does not steal the beginning of the utterance.
     */
    suspend fun prewarmRecordingPath() {
        if (_audioInputMode.value != AudioInputMode.MICROPHONE) return
        if (!audioRecorder.hasPermission()) return
        if (_sessionState.value != SessionState.Idle) return
        recorderPrewarmMutex.withLock {
            withContext(Dispatchers.IO) {
                audioRecorder.prewarm(_audioInputMode.value)
            }
        }
    }

    fun startRecording() {
        Log.i("WhisperEngine", "startRecording: sessionState=${_sessionState.value}, engine=${currentEngine != null}, loaded=${currentEngine?.isLoaded}")
        if (_sessionState.value != SessionState.Idle) {
            Log.w("WhisperEngine", "startRecording: not idle (${_sessionState.value}), ignoring")
            return
        }

        val engine = currentEngine
        if (engine == null || !engine.isLoaded) {
            Log.e("WhisperEngine", "startRecording: model not ready")
            _lastError.value = AppError.ModelNotReady()
            transitionTo(SessionState.Error)
            return
        }

        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            if (!audioRecorder.isSystemAudioCaptureSupported) {
                _lastError.value = AppError.SystemAudioCaptureUnsupported()
                transitionTo(SessionState.Error)
                return
            }
            if (!audioRecorder.hasSystemAudioCapturePermission) {
                _lastError.value = AppError.SystemAudioCapturePermissionDenied()
                transitionTo(SessionState.Error)
                return
            }
            // Ensure foreground service is running for MediaProjection
            try {
                context.startForegroundService(
                    Intent(context, MediaProjectionService::class.java)
                )
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to start MediaProjectionService: ${e.message}")
            }
        }

        if (!audioRecorder.hasPermission()) {
            Log.e("WhisperEngine", "startRecording: no mic permission")
            _lastError.value = AppError.MicrophonePermissionDenied()
            transitionTo(SessionState.Error)
            return
        }

        resetTranscriptionState()
        if (_audioInputMode.value == AudioInputMode.MICROPHONE) {
            try {
                context.startForegroundService(Intent(context, TranscriptionForegroundService::class.java))
            } catch (e: Exception) {
                Log.e("WhisperEngine", "Unable to start microphone foreground service", e)
                _lastError.value = AppError.TranscriptionFailed(e)
                transitionTo(SessionState.Error)
                return
            }
        }
        transitionTo(SessionState.Recording)

        val activeSessionToken = nextSessionToken()
        transcriptionCoordinator.cancelTranscriptionJob()
        recordingJob?.cancel()
        energyJob?.cancel()
        recordingJob = null
        energyJob = null

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                if (!isSessionActive(activeSessionToken)) return@launch
                audioRecorder.startRecording(_audioInputMode.value)
            } catch (e: Throwable) {
                if (!isSessionActive(activeSessionToken)) return@launch
                withContext(Dispatchers.Main) {
                    onTranscriptionError(AppError.TranscriptionFailed(e))
                }
            }
        }

        transcriptionCoordinator.startLoop(scope, activeSessionToken, engine)

        energyJob = scope.launch(Dispatchers.Default) {
            while (isSessionActive(activeSessionToken)) {
                _bufferEnergy.value = audioRecorder.relativeEnergy
                _bufferSeconds.value = audioRecorder.bufferSeconds
                delay(100)
            }
        }
    }

    fun stopRecording() {
        if (_sessionState.value != SessionState.Recording) return
        transitionTo(SessionState.Stopping)
        // Cancel file transcription if running (transcribeFile uses fileTranscriptionJob)
        fileTranscriptionJob?.cancel()
        fileTranscriptionJob = null
        // Stop mic input first so no new audio arrives
        audioRecorder.stopRecording()
        cancelRecorderAndEnergyJobs()

        // For streaming engines, drain remaining audio synchronously.
        // For offline engines, the realtimeLoop finally block handles
        // transcribeFinalBuffer + finalizeCurrentChunk on the coroutine thread
        // to avoid racing with the loop's last iteration.
        if (currentEngine?.isStreaming == true) {
            transcriptionCoordinator.drainFinalStreamingAudioIfNeeded()
        }

        // Stop MediaProjection foreground service if it was running
        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            try {
                context.stopService(Intent(context, MediaProjectionService::class.java))
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to stop MediaProjectionService: ${e.message}")
            }
        }
        stopMicrophoneForegroundService()
        persistTranscriptIfMeaningful(
            source = if (_audioInputMode.value == AudioInputMode.MICROPHONE) "Microphone" else "System playback",
            durationMillis = (recordingDurationSeconds * 1000).toLong()
        )

        // Now invalidate and clean up
        invalidateSession()
        transcriptionCoordinator.cancelTranscriptionJob()
        transitionTo(SessionState.Idle)
    }

    private suspend fun stopRecordingAndWait() {
        if (_sessionState.value != SessionState.Recording) return
        transitionTo(SessionState.Stopping)
        fileTranscriptionJob?.cancelAndJoin()
        fileTranscriptionJob = null
        audioRecorder.stopRecording()
        cancelRecorderAndEnergyJobsAndWait()

        // For streaming engines, drain remaining audio synchronously.
        // For offline engines, the realtimeLoop finally block handles finalization.
        if (currentEngine?.isStreaming == true) {
            transcriptionCoordinator.drainFinalStreamingAudioIfNeeded()
        }

        if (_audioInputMode.value == AudioInputMode.SYSTEM_PLAYBACK) {
            try {
                context.stopService(Intent(context, MediaProjectionService::class.java))
            } catch (e: Exception) {
                Log.w("WhisperEngine", "Failed to stop MediaProjectionService: ${e.message}")
            }
        }
        stopMicrophoneForegroundService()

        invalidateSession()
        transcriptionCoordinator.cancelTranscriptionJobAndWait()
        transitionTo(SessionState.Idle)
    }

    private fun cancelRecorderAndEnergyJobs() {
        recordingJob?.cancel()
        energyJob?.cancel()
        recordingJob = null
        energyJob = null
    }

    private fun stopMicrophoneForegroundService() {
        try {
            context.stopService(Intent(context, TranscriptionForegroundService::class.java))
        } catch (e: Exception) {
            Log.w("WhisperEngine", "Failed to stop microphone foreground service: ${e.message}")
        }
    }

    private suspend fun cancelRecorderAndEnergyJobsAndWait() {
        recordingJob?.cancelAndJoin()
        energyJob?.cancelAndJoin()
        recordingJob = null
        energyJob = null
    }

    fun setLastError(error: AppError) {
        _lastError.value = error
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        _audioInputMode.value = mode
    }

    /** Applies to the shared live-session pipeline; `auto` preserves model detection. */
    fun setLiveLanguageHint(languageCode: String?) {
        _liveLanguageHint.value = normalizeLanguageCode(languageCode) ?: "auto"
    }

    fun setSystemAudioCapturePermission(resultCode: Int, data: Intent?) {
        if (!audioRecorder.isSystemAudioCaptureSupported) {
            _systemAudioCaptureReady.value = false
            _lastError.value = AppError.SystemAudioCaptureUnsupported()
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            _systemAudioCaptureReady.value = false
            _lastError.value = AppError.SystemAudioCapturePermissionDenied()
            return
        }
        val granted = audioRecorder.setSystemAudioCapturePermission(resultCode, data)
        _systemAudioCaptureReady.value = granted
        if (!granted) {
            _lastError.value = if (audioRecorder.isSystemAudioCaptureSupported) {
                AppError.SystemAudioCapturePermissionDenied()
            } else {
                AppError.SystemAudioCaptureUnsupported()
            }
        } else if (_lastError.value is AppError.SystemAudioCapturePermissionDenied
            || _lastError.value is AppError.SystemAudioCaptureUnsupported
        ) {
            _lastError.value = null
            if (_sessionState.value == SessionState.Error) {
                transitionTo(SessionState.Idle)
            }
        }
    }

    fun clearError() {
        _lastError.value = null
        if (_sessionState.value == SessionState.Error) {
            transitionTo(SessionState.Idle)
        }
    }

    fun clearTranscription() {
        if (_sessionState.value == SessionState.Recording) {
            invalidateSession()
            audioRecorder.stopRecording()
            transcriptionCoordinator.cancelTranscriptionJob()
            cancelRecorderAndEnergyJobs()
        }
        stopMicrophoneForegroundService()
        fileTranscriptionJob?.cancel()
        fileTranscriptionJob = null
        resetTranscriptionState()
        transitionTo(SessionState.Idle)
    }

    internal fun transitionTo(newState: SessionState) {
        _sessionState.value = newState
        _isRecording.value = (newState == SessionState.Recording)
    }



    /**
     * When the ASR engine detects a language (e.g., SenseVoice returns "en" or "ja"),
     * auto-swap translation direction so that detected speech is the source and
     * the other configured language becomes the target.
     */
    internal fun applyDetectedLanguageToTranslation(lang: String) {
        if (!_translationEnabled.value) return
        val currentSource = _translationSourceLanguageCode.value
        val currentTarget = _translationTargetLanguageCode.value

        // If detected language matches target but not source, swap them
        if (lang == currentTarget && lang != currentSource) {
            Log.i("WhisperEngine", "Detected language '$lang' matches target — swapping ($currentSource→$currentTarget becomes $currentTarget→$currentSource)")
            _translationSourceLanguageCode.value = currentTarget
            _translationTargetLanguageCode.value = currentSource
            scope.launch {
                preferences.setTranslationSourceLanguage(currentTarget)
                preferences.setTranslationTargetLanguage(currentSource)
            }
            lastTranslationInput = null
            resetTranslationState()
            scheduleTranslationUpdate()
        } else if (lang != currentSource && lang != currentTarget) {
            Log.i("WhisperEngine", "Detected language '$lang' not in pair ($currentSource→$currentTarget) — ignoring")
        }
        // If lang == currentSource, no change needed
    }

    private fun resetTranscriptionState() {
        resetTranslationState()
        transcriptionCoordinator.reset()
        chunkManager = transcriptionCoordinator.createChunkManagerForModel(_selectedModel.value)
        _confirmedText.value = ""
        _hypothesisText.value = ""
        e2eOrchestrator.reset()
        _detectedLanguage.value = null
        _bufferEnergy.value = emptyList()
        _bufferSeconds.value = 0.0
        _tokensPerSecond.value = 0.0
        _lastError.value = null
        audioRecorder.reset()
    }

    /** Clear translation-related state without affecting transcription or audio. */
    private fun resetTranslationState() {
        translationJob?.cancel()
        translationJob = null
        _translatedConfirmedText.value = ""
        _translatedHypothesisText.value = ""
        _translationWarning.value = null
        lastTranslationInput = null
    }

    /** Transcribe a WAV file. Used for testing and file import UI. */
    fun transcribeFile(filePath: String, languageHint: String = "auto") {
        val engine = currentEngine
        if (engine == null || !engine.isLoaded) {
            Log.e("WhisperEngine", "transcribeFile: model not ready")
            _lastError.value = AppError.ModelNotReady()
            writeE2EFailure(error = "model not ready")
            return
        }

        // Guard: ignore if already busy transcribing
        if (_sessionState.value == SessionState.Recording) {
            Log.w("WhisperEngine", "transcribeFile: already busy, ignoring")
            writeE2EFailure(error = "engine busy")
            return
        }

        resetTranscriptionState()
        transitionTo(SessionState.Recording)
        _hypothesisText.value = "Decoding audio…"

        // File decode can be CPU-heavy (especially omnilingual); keep it off main.
        val pcmFile = File(context.cacheDir, "transcribe-${System.currentTimeMillis()}.pcm")
        fileTranscriptionJob = scope.launch(Dispatchers.Default) {
            try {
                Log.i("WhisperEngine", "transcribeFile: reading $filePath")
                val decoded = withContext(Dispatchers.IO) {
                    decodeAudioFileTo16kPcm(filePath, pcmFile) { msg -> _hypothesisText.value = msg }
                }
                val totalSamples = decoded.totalSamples
                val peakGain = decoded.peakGain
                if (peakGain > 1f) Log.i("WhisperEngine", "transcribeFile: quiet audio, applying gain x$peakGain")
                val durationSec = totalSamples / AudioConstants.SAMPLE_RATE.toDouble()
                Log.i("WhisperEngine", "transcribeFile: $totalSamples samples (${durationSec}s)")
                _hypothesisText.value = "Transcribing ${"%.1f".format(durationSec)}s of audio..."
                _bufferSeconds.value = durationSec

                val startTime = System.nanoTime()
                val numThreads = inferenceThreadCount()
                Log.i("WhisperEngine", "transcribeFile: starting transcription with $numThreads threads")
                var progressiveText: String? = null
                val recentRates = ArrayDeque<Pair<Int, Double>>()
                var asrSeconds = 0.0
                val segments = if (engine is AndroidSpeechEngine && Build.VERSION.SDK_INT < 33 && e2eLocked) {
                    // On API < 33, SpeechRecognizer can't accept file audio directly.
                    // For E2E benchmarks, attempt acoustic loopback (speaker -> mic).
                    Log.i("WhisperEngine", "transcribeFile: Android Speech API<33, using acoustic loopback")
                    engine.transcribeViaAcousticLoopback(
                        readPcm16(pcmFile, 0, totalSamples.toInt(), peakGain), languageHint
                    )
                } else {
                    transcribePcmSlices(
                        engine, pcmFile, totalSamples, numThreads, languageHint, peakGain,
                        onProgress = { msg -> _hypothesisText.value = msg }
                    ) { sliceSegs, sliceElapsedSec ->
                        asrSeconds += sliceElapsedSec
                        chunkManager.confirmedSegments.addAll(sliceSegs)
                        val rendered = chunkManager.renderSegmentsText(chunkManager.confirmedSegments)
                        chunkManager.confirmedText = rendered
                        _confirmedText.value = rendered
                        progressiveText = rendered
                        // Rolling tokens/s over the last 5 slices; silent slices don't dilute it.
                        val words = sliceSegs.sumOf { countTokens(it.text) }
                        if (words > 0) {
                            recentRates.addLast(words to sliceElapsedSec)
                            if (recentRates.size > 5) recentRates.removeFirst()
                            val windowSec = recentRates.sumOf { it.second }
                            if (windowSec > 0) {
                                _tokensPerSecond.value = recentRates.sumOf { it.first } / windowSec
                            }
                        }
                    }
                }

                val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                val totalWords = segments.sumOf { countTokens(it.text) }
                Log.i("WhisperEngine", "transcribeFile: ${segments.size} segments, $totalWords words in ${"%.2f".format(elapsed)}s")
                // Overall tokens/s over real ASR time only (excludes decode + VAD).
                val asrElapsed = if (asrSeconds > 0) asrSeconds else elapsed
                if (asrElapsed > 0 && totalWords > 0) {
                    _tokensPerSecond.value = totalWords / asrElapsed
                }
                // Apply detected language to translation direction
                val lang = normalizeLanguageCode(segments.firstOrNull()?.detectedLanguage)
                if (lang != null && lang != _detectedLanguage.value) {
                    _detectedLanguage.value = lang
                    applyDetectedLanguageToTranslation(lang)
                }

                val renderedText = progressiveText ?: run {
                    chunkManager.confirmedSegments.addAll(segments)
                    chunkManager.renderSegmentsText(segments).also {
                        chunkManager.confirmedText = it
                        _confirmedText.value = it
                    }
                }
                _hypothesisText.value = ""
                val model = _selectedModel.value
                val skipReason = when {
                    model.engineType == EngineType.ANDROID_SPEECH &&
                        Build.VERSION.SDK_INT < 33 &&
                        renderedText.contains("requires API 33+", ignoreCase = true) ->
                        "Android Speech file transcription requires API 33+ (device API ${Build.VERSION.SDK_INT})"
                    model.engineType == EngineType.ANDROID_SPEECH &&
                        Build.VERSION.SDK_INT < 33 &&
                        renderedText.isBlank() ->
                        "Android Speech returned empty transcript on device API ${Build.VERSION.SDK_INT} (loopback may be blocked by echo cancellation; API 33+ supports direct file input)"
                    else -> null
                }
                val skipped = skipReason != null

                if (!skipped) {
                    Log.i("WhisperEngine", "E2E translation state: enabled=${_translationEnabled.value} src=${_translationSourceLanguageCode.value} tgt=${_translationTargetLanguageCode.value} translated=${_translatedConfirmedText.value.take(20)}")
                    scheduleTranslationUpdate()
                    val waitUntil = SystemClock.elapsedRealtime() + 12_000L
                    while (SystemClock.elapsedRealtime() < waitUntil) {
                        val translatedReady = !_translationEnabled.value ||
                            _confirmedText.value.isBlank() ||
                            _translatedConfirmedText.value.isNotBlank()
                        if (translatedReady) break
                        delay(250)
                    }
                }

                // Write E2E evidence result
                val transcript = _confirmedText.value
                if (!skipped) {
                    persistTranscriptIfMeaningful(
                        source = "File",
                        durationMillis = (durationSec * 1000).toLong(),
                        language = languageHint
                    )
                }
                e2eOrchestrator.writeResult(
                    transcript = transcript,
                    durationMs = if (skipped) 0.0 else elapsed * 1000,
                    tokensPerSecond = if (skipped) 0.0 else _tokensPerSecond.value,
                    error = skipReason,
                    skipped = skipped
                )
            } catch (e: CancellationException) {
                Log.i("WhisperEngine", "transcribeFile: cancelled")
            } catch (e: Throwable) {
                Log.e("WhisperEngine", "transcribeFile failed", e)
                _lastError.value = AppError.TranscriptionFailed(e)
                e2eOrchestrator.writeResult(
                    transcript = "",
                    durationMs = 0.0,
                    tokensPerSecond = 0.0,
                    error = e.message,
                    skipped = false
                )
            } finally {
                pcmFile.delete()
                // Only transition to Idle if we're still in the file-transcription session.
                // stopRecording/clearTranscription may have already transitioned us, and
                // a new recording may have started — don't clobber it.
                val state = _sessionState.value
                if (state == SessionState.Recording || state == SessionState.Stopping) {
                    transitionTo(SessionState.Idle)
                }
            }
        }
    }

    fun writeE2EFailure(modelId: String = _selectedModel.value.id, error: String) {
        e2eOrchestrator.writeFailure(modelId = modelId, error = error)
    }

    /** Transcribe a decoded 16k PCM file, sliced by Silero VAD when ready. Falls back to fixed 30 s windows.
     *  [onSlice] fires after each slice with its wall time, so the UI can show text and
     *  tokens/s progressively. */
    private suspend fun transcribePcmSlices(
        engine: AsrEngine,
        pcm: File,
        totalSamples: Long,
        numThreads: Int,
        languageHint: String,
        peakGain: Float,
        onProgress: (String) -> Unit = {},
        onSlice: (List<TranscriptionSegment>, Double) -> Unit
    ): List<TranscriptionSegment> {
        if (_useVAD.value && sileroVad.state.value == ModelState.Unloaded && sileroVad.isDownloaded()) {
            sileroVad.prepare(download = false)
        }
        // VAD setting applies to file imports too: off → fixed 30 s sliding windows.
        val slices = if (_useVAD.value && sileroVad.state.value == ModelState.Loaded) {
            onProgress("Detecting speech…")
            mergeVadSegments(
                detectVadWindows(pcm, totalSamples, peakGain, onProgress),
                VAD_MAX_CHUNK_MS
            )
        } else {
            emptyList()
        }
        val sr = AudioConstants.SAMPLE_RATE.toLong()
        val window = 30L * sr
        val search = 2L * sr
        val merged = mutableListOf<TranscriptionSegment>()
        suspend fun runSlice(samples: FloatArray, offsetMs: Long) {
            try {
                val sliceStart = System.nanoTime()
                val segs = engine.transcribe(samples, numThreads, languageHint)
                    .map { it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs) }
                onSlice(segs, (System.nanoTime() - sliceStart) / 1_000_000_000.0)
                merged += segs
            } catch (e: Throwable) {
                // One bad slice must not kill the whole transcription.
                if (e is CancellationException) throw e
                Log.w("WhisperEngine", "transcribeFile: slice @${offsetMs}ms failed, skipping", e)
            }
        }
        if (slices.isEmpty()) {
            // No VAD: cut at the quietest point near each ~30 s boundary so seams land in a
            // pause instead of mid-word. ponytail: linear scan of a small search region.
            var s = 0L
            var lastPct = -1
            while (s < totalSamples) {
                val end = energyCutEnd(pcm, s, window, search, totalSamples, sr, peakGain)
                runSlice(readPcm16(pcm, s, (end - s).toInt(), peakGain), s * 1000 / sr)
                s = end
                val pct = ((s * 100) / totalSamples).toInt().coerceIn(0, 100)
                if (pct >= lastPct + 5 || s >= totalSamples) {
                    lastPct = pct
                    onProgress("Transcribing… $pct%")
                }
            }
            return merged
        }
        onProgress("Transcribing…")
        for (slice in slices) {
            var from = (slice.startMs * sr / 1000).coerceIn(0L, totalSamples - 1)
            val to = (slice.endMs * sr / 1000).coerceIn(from, totalSamples)
            while (from < to) {
                // A merged chunk longer than the window is split at its quietest interior point.
                val end = if (to - from <= window) to
                else energyCutEnd(pcm, from, window, search, to, sr, peakGain)
                val bounded = minOf(end, to)
                runSlice(readPcm16(pcm, from, (bounded - from).toInt(), peakGain), from * 1000 / sr)
                from = bounded
            }
        }
        return merged
    }

    /** End sample for a slice starting at [start] targeting [window] samples: backed off to the
     *  centre of the quietest ~30 ms frame within [search] samples before the target, so the
     *  seam lands in a pause. Never exceeds [totalSamples] or moves past [start]. */
    private fun energyCutEnd(
        pcm: File,
        start: Long,
        window: Long,
        search: Long,
        totalSamples: Long,
        sr: Long,
        peakGain: Float
    ): Long {
        val target = minOf(start + window, totalSamples)
        if (target >= totalSamples) return target
        val searchStart = (target - search).coerceAtLeast(start + 1)
        val region = readPcm16(pcm, searchStart, (target - searchStart).toInt(), peakGain)
        val frame = (sr / 33).toInt()  // ~30 ms
        val cut = searchStart + quietestFrameOffset(region, frame)
        return if (cut > start && cut <= target) cut else target
    }

    /** Run VAD over the PCM file in 60 s windows. Windows do not overlap, so every
     *  detected segment is kept; a segment touching the window edge is clamped to it. */
    private fun detectVadWindows(
        pcm: File,
        totalSamples: Long,
        gain: Float,
        onProgress: (String) -> Unit = {}
    ): List<VadSegment> {
        val sr = AudioConstants.SAMPLE_RATE.toLong()
        val windowSamples = 60L * sr
        val results = mutableListOf<VadSegment>()
        var start = 0L
        var lastPct = -1
        while (start < totalSamples) {
            val count = minOf(windowSamples, totalSamples - start).toInt()
            val windowMs = count * 1000 / sr
            for (seg in sileroVad.detect(readPcm16(pcm, start, count, gain))) {
                // ponytail: clamp rather than drop — non-overlapping windows mean the
                // next window only sees the tail, so dropping the seam segment lost ~half
                // of continuous speech (max_speech_duration_s forces a 30 s split).
                results += VadSegment(
                    startMs = seg.startMs + start * 1000 / sr,
                    endMs = minOf(seg.endMs, windowMs) + start * 1000 / sr
                )
            }
            start += count
            val pct = ((start * 100) / totalSamples).toInt().coerceIn(0, 100)
            if (pct >= lastPct + 2 || start >= totalSamples) {
                lastPct = pct
                onProgress("Detecting speech… $pct%")
            }
        }
        return results
    }

    fun writeE2ESkipped(modelId: String = _selectedModel.value.id, reason: String) {
        e2eOrchestrator.writeSkipped(modelId = modelId, reason = reason)
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun mapDownloadError(error: Throwable): AppError {
        val root = generateSequence(error) { it.cause }.last()
        return when {
            !hasValidatedInternetConnection() -> AppError.NetworkUnavailable()
            root is UnknownHostException -> AppError.NetworkUnavailable()
            root is SocketTimeoutException -> AppError.ModelDownloadFailed(
                Exception("Network timeout while downloading. Check connection and retry.")
            )
            else -> AppError.ModelDownloadFailed(error)
        }
    }

    private fun parseModelSize(sizeStr: String): Long {
        val cleaned = sizeStr.replace("~", "").trim()
        val parts = cleaned.split(" ")
        if (parts.size != 2) return 0L
        val value = parts[0].toDoubleOrNull() ?: return 0L
        return when (parts[1].uppercase()) {
            "GB" -> (value * 1024 * 1024 * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "KB" -> (value * 1024).toLong()
            else -> 0L
        }
    }

    internal fun scheduleTranslationUpdate() {
        translationJob?.cancel()

        if (!_translationEnabled.value) {
            resetTranslationState()
            return
        }

        // Quick pre-check: skip scheduling if language codes are not configured
        val srcCheck = _translationSourceLanguageCode.value.trim()
        val tgtCheck = _translationTargetLanguageCode.value.trim()
        if (srcCheck.isEmpty() || tgtCheck.isEmpty()) return

        translationJob = scope.launch(Dispatchers.Default) {
            delay(150)  // Debounce rapid updates during fast speech

            // Re-read latest values after debounce
            val confirmedSnapshot = _confirmedText.value.trim()
            val hypothesisSnapshot = _hypothesisText.value.trim()
            val sourceLanguageCode = _translationSourceLanguageCode.value.trim().lowercase()
            val targetLanguageCode = _translationTargetLanguageCode.value.trim().lowercase()

            if (sourceLanguageCode.isEmpty() || targetLanguageCode.isEmpty()) return@launch

            val currentInput = confirmedSnapshot to hypothesisSnapshot
            if (lastTranslationInput == currentInput) return@launch

            var warningMessage: String? = null

            var translatedConfirmed: String
            var translatedHypothesis: String

            if (sourceLanguageCode == targetLanguageCode) {
                translatedConfirmed = confirmedSnapshot
                translatedHypothesis = hypothesisSnapshot
            } else {
                try {
                    translatedConfirmed = if (confirmedSnapshot.isBlank()) {
                        ""
                    } else {
                        mlKitTranslator.translate(
                            text = confirmedSnapshot,
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode
                        )
                    }

                    translatedHypothesis = if (hypothesisSnapshot.isBlank()) {
                        ""
                    } else {
                        mlKitTranslator.translate(
                            text = hypothesisSnapshot,
                            sourceLanguageCode = sourceLanguageCode,
                            targetLanguageCode = targetLanguageCode
                        )
                    }
                } catch (e: UnsupportedOperationException) {
                    translatedConfirmed = confirmedSnapshot
                    translatedHypothesis = hypothesisSnapshot
                    warningMessage = e.message ?: AppError.TranslationUnavailable().message
                } catch (e: Throwable) {
                    if (e is CancellationException) return@launch
                    translatedConfirmed = confirmedSnapshot
                    translatedHypothesis = hypothesisSnapshot
                    warningMessage = AppError.TranslationFailed(e).message
                }
            }

            _translatedConfirmedText.value = TextNormalizationUtils.normalizeText(translatedConfirmed)
            _translatedHypothesisText.value = TextNormalizationUtils.normalizeText(translatedHypothesis)
            _translationWarning.value = warningMessage
            lastTranslationInput = currentInput
        }
    }

    /** Decode any supported audio file to 16 kHz mono PCM16 on disk, streaming, in one pass.
     *  Peak Java heap stays at one codec buffer regardless of input length, so multi-hour
     *  files work within the 256 MB app heap. Input is read in bulk (no per-sample ByteBuffer
     *  calls), 16 k mono PCM16 is forwarded untouched, and the peak is measured while writing
     *  so there is no second file scan. ponytail: linear-interp resample runs per codec chunk
     *  on the global sample grid (1-sample carry) — ±1 sample error per chunk, inaudible.
     *  Returns total 16k sample count plus the measured peak. */
    private fun decodeAudioFileTo16kPcm(
        filePath: String,
        out: File,
        onProgress: (String) -> Unit = {}
    ): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(filePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track found")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Audio MIME missing")
            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            DataOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 20)).use { sink ->
                var nextOut = 0L        // next global 16k output index to emit
                var globalStart = 0L    // global source index of the chunk being processed
                var carry = 0f          // last source sample of the previous chunk (for deferred output)
                var pendingTail = false // a resampled output was deferred awaiting the next chunk
                var peak = 0
                var outBytes = ByteArray(0)     // reused PCM16 pack buffer
                var mono = FloatArray(0)        // reused downmix buffer
                var resampled = FloatArray(0)   // reused resample buffer
                var scratchShort = ShortArray(0)
                var scratchFloat = FloatArray(0)

                fun emitPcm16(samples: FloatArray, n: Int) {
                    if (outBytes.size < n * 2) outBytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        outBytes[2 * i] = (v and 0xff).toByte()
                        outBytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    sink.write(outBytes, 0, n * 2)
                }

                // Already 16 kHz mono PCM16: forward the decoded samples untouched (peak only).
                fun emitNativePcm16(shorts: ShortArray, n: Int) {
                    if (outBytes.size < n * 2) outBytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = shorts[i].toInt()
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        outBytes[2 * i] = (v and 0xff).toByte()
                        outBytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    sink.write(outBytes, 0, n * 2)
                }

                fun writeMono(samples: FloatArray, n: Int) {
                    if (n == 0) return
                    if (sampleRate == AudioConstants.SAMPLE_RATE) {
                        emitPcm16(samples, n)
                        nextOut += n
                    } else {
                        val sr = AudioConstants.SAMPLE_RATE.toLong()
                        val rate = sampleRate.toLong()
                        val est = ((globalStart + n) * sr / rate + 2 - nextOut).toInt().coerceAtLeast(0)
                        if (resampled.size < est) resampled = FloatArray(est)
                        var m = 0
                        while (true) {
                            val s = nextOut.toDouble() * rate / sr
                            val left = floor(s).toLong()
                            val last = globalStart + n - 1
                            if (left > last) break
                            val frac = (s - left).toFloat()
                            if (left == last && frac > 0f) {
                                pendingTail = true // right neighbour arrives next chunk
                                break
                            }
                            pendingTail = false
                            val li = (left - globalStart).toInt()
                            val a = if (li < 0) carry else samples[li]
                            val b = if (li + 1 <= n - 1) samples[li + 1] else a
                            resampled[m++] = a + (b - a) * frac
                            nextOut++
                        }
                        if (m > 0) emitPcm16(resampled, m)
                    }
                    if (n > 0) carry = samples[n - 1]
                    globalStart += n
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastDecodePct = -1
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = codec.getInputBuffer(index) ?: continue
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(index, 0, size, sampleTimeUs, 0)
                                extractor.advance()
                                if (trackDurationUs > 0) {
                                    val pct = ((sampleTimeUs * 100) / trackDurationUs).toInt().coerceIn(0, 100)
                                    if (pct >= lastDecodePct + 2) {
                                        lastDecodePct = pct
                                        onProgress("Decoding audio… $pct%")
                                    }
                                }
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val output = codec.outputFormat
                            sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                        }
                        in 0..Int.MAX_VALUE -> {
                            if (info.size > 0) {
                                val output = codec.getOutputBuffer(index) ?: throw IllegalStateException("Missing decoded buffer")
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                                val bytesPerSample = if (isFloat) 4 else 2
                                val frameCount = output.remaining() / bytesPerSample / channels
                                if (frameCount > 0) {
                                    val count = frameCount * channels
                                    if (isFloat) {
                                        if (scratchFloat.size < count) scratchFloat = FloatArray(count)
                                        output.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                            .get(scratchFloat, 0, count)
                                        if (mono.size < frameCount) mono = FloatArray(frameCount)
                                        var k = 0
                                        for (f in 0 until frameCount) {
                                            var sum = 0f
                                            repeat(channels) { sum += scratchFloat[k++] }
                                            mono[f] = sum / channels
                                        }
                                        writeMono(mono, frameCount)
                                    } else {
                                        if (scratchShort.size < count) scratchShort = ShortArray(count)
                                        output.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                            .get(scratchShort, 0, count)
                                        if (channels == 1 && sampleRate == AudioConstants.SAMPLE_RATE) {
                                            emitNativePcm16(scratchShort, frameCount)
                                            nextOut += frameCount
                                            globalStart += frameCount
                                        } else {
                                            if (mono.size < frameCount) mono = FloatArray(frameCount)
                                            var k = 0
                                            for (f in 0 until frameCount) {
                                                var sum = 0f
                                                repeat(channels) { sum += scratchShort[k++].toFloat() / 32768f }
                                                mono[f] = sum / channels
                                            }
                                            writeMono(mono, frameCount)
                                        }
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (outputDone && pendingTail) {
                                // The deferred tail sample clamps to the last source sample at EOF.
                                emitPcm16(floatArrayOf(carry), 1)
                                nextOut++
                            }
                        }
                    }
                }
                if (nextOut == 0L) throw IllegalArgumentException("Decoder produced no audio")
                return DecodedPcm(totalSamples = nextOut, peakGain = peakToGain(peak))
            }
        } finally {
            codec?.let { decoder ->
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
            extractor.release()
        }
    }

    /** Tokens = latin words + CJK characters; split(" ") alone reads a CJK sentence as one giant "word". */
    private fun countTokens(text: String): Int {
        var tokens = 0
        for (raw in text.split(" ")) {
            val chunk = raw.trim()
            if (chunk.isEmpty()) continue
            var cjk = 0
            for (c in chunk) {
                if (c.code in 0x3040..0x30FF || c.code in 0x3400..0x4DBF ||
                    c.code in 0x4E00..0x9FFF || c.code in 0xAC00..0xD7AF
                ) cjk++
            }
            tokens += if (cjk > 0) cjk else 1
        }
        return tokens
    }

    /** Read [count] mono 16k samples starting at [startSample] from a raw PCM16 file. */
    private fun readPcm16(pcm: File, startSample: Long, count: Int, gain: Float = 1f): FloatArray {
        RandomAccessFile(pcm, "r").use { raf ->
            raf.seek(startSample * 2L)
            val bytes = ByteArray(count * 2)
            raf.readFully(bytes)
            return FloatArray(count) { i ->
                val lo = bytes[2 * i].toInt() and 0xff
                val hi = bytes[2 * i + 1].toInt()
                (((hi shl 8) or lo) / 32768f) * gain
            }
        }
    }

    /** Gain multiplier for quiet recordings: normalize peak to ~0.9. ponytail: capped 16x —
     * beyond that the amplified noise floor hurts ASR more than the gain helps. */
    private fun peakToGain(peak: Int): Float {
        if (peak == 0) return 1f
        val p = peak / 32768f
        if (p >= 0.3f) return 1f
        return (0.9f / p).coerceAtMost(16f)
    }

    private fun persistTranscriptIfMeaningful(
        source: String,
        durationMillis: Long,
        language: String = _liveLanguageHint.value,
    ) {
        val text = fullTranscriptionText.trim()
        if (text.isBlank()) return
        val model = _selectedModel.value
        scope.launch(Dispatchers.IO) {
            runCatching {
                transcriptHistory.save(
                    TranscriptHistoryEntry(
                        createdAtMillis = System.currentTimeMillis(),
                        durationMillis = durationMillis,
                        source = source,
                        modelId = model.id,
                        backend = model.inferenceMethod,
                        language = language,
                        transcript = text,
                    )
                )
            }.onFailure { Log.w("WhisperEngine", "Unable to persist transcript history", it) }
        }
    }

    fun destroy() {
        if (_sessionState.value == SessionState.Recording) {
            audioRecorder.stopRecording()
            transcriptionCoordinator.cancelTranscriptionJob()
            recordingJob?.cancel()
            energyJob?.cancel()
        }
        audioRecorder.clearSystemAudioCapturePermission()
        _systemAudioCaptureReady.value = false
        translationJob?.cancel()
        scope.cancel()
        mlKitTranslator.close()
        currentEngine?.release()
        currentEngine = null
        sileroVad.release()
    }
}

/** Result of decoding an imported file: total 16 kHz mono samples written and the quiet-audio
 *  gain computed from the peak measured during the same pass. */
internal data class DecodedPcm(val totalSamples: Long, val peakGain: Float)

/** Merge consecutive VAD speech segments (silence between them included) into chunks of at most
 *  [maxMs]. Short fragments would otherwise reach the ASR with too little context and a
 *  per-fragment language guess; an oversize single segment is split later at a quiet point. */
internal fun mergeVadSegments(segments: List<VadSegment>, maxMs: Long): List<VadSegment> {
    if (segments.isEmpty()) return emptyList()
    val out = ArrayList<VadSegment>()
    var start = segments[0].startMs
    var end = segments[0].endMs
    for (i in 1 until segments.size) {
        val seg = segments[i]
        if (seg.endMs - start <= maxMs) {
            end = seg.endMs
        } else {
            out.add(VadSegment(start, end))
            start = seg.startMs
            end = seg.endMs
        }
    }
    out.add(VadSegment(start, end))
    return out
}

/** Offset (in samples) of the quietest [frameSamples]-long frame's centre within [samples].
 *  Returns [samples].size when there is no full frame to measure, meaning "cut at the end". */
internal fun quietestFrameOffset(samples: FloatArray, frameSamples: Int): Int {
    if (frameSamples <= 0 || samples.size < frameSamples) return samples.size
    var bestOffset = samples.size
    var bestEnergy = Double.MAX_VALUE
    var i = 0
    while (i + frameSamples <= samples.size) {
        var energy = 0.0
        for (j in i until i + frameSamples) energy += samples[j].toDouble() * samples[j]
        if (energy < bestEnergy) {
            bestEnergy = energy
            bestOffset = i + frameSamples / 2
        }
        i += frameSamples
    }
    return bestOffset
}

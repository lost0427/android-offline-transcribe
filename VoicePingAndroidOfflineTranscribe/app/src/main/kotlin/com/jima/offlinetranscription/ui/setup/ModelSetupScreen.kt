package com.voiceping.offlinetranscription.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voiceping.offlinetranscription.model.EngineType
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.model.ModelState
import com.voiceping.offlinetranscription.ui.components.ModelPickerRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSetupScreen(viewModel: ModelSetupViewModel) {
    val modelState by viewModel.modelState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val vadState by viewModel.vadState.collectAsState()
    val vadProgress by viewModel.vadProgress.collectAsState()
    val vadError by viewModel.vadError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Setup") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Header
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Offline Transcription",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Download a speech recognition model to get started. Models are stored on-device for fully offline use.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Model Picker — grouped by engine
            Text(
                text = "Select Model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(12.dp))

            val isBusy = modelState == ModelState.Downloading || modelState == ModelState.Loading

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Silero VAD v6.2.0", style = MaterialTheme.typography.titleMedium)
                    Text("864 KiB", style = MaterialTheme.typography.bodySmall)
                }
                when (vadState) {
                    ModelState.Loaded -> Icon(Icons.Filled.CheckCircle, "VAD ready")
                    ModelState.Downloading -> CircularProgressIndicator(
                        progress = { vadProgress }, modifier = Modifier.size(24.dp)
                    )
                    ModelState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> IconButton(onClick = viewModel::downloadVad, enabled = !isBusy) {
                        Icon(Icons.Filled.Download, "Download Silero VAD")
                    }
                }
            }
            vadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            ModelInfo.modelsByEngine.forEach { (engineType, models) ->
                Text(
                    text = engineLabel(engineType),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(bottom = 4.dp)
                )
                models.forEach { model ->
                    ModelPickerRow(
                        model = model,
                        isSelected = selectedModel.id == model.id,
                        isDownloaded = viewModel.isModelDownloaded(model),
                        isDownloading = modelState == ModelState.Downloading && selectedModel.id == model.id,
                        downloadProgress = downloadProgress,
                        isLoading = modelState == ModelState.Loading && selectedModel.id == model.id,
                        enabled = !isBusy,
                        onClick = { viewModel.selectAndSetup(model) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            lastError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun engineLabel(type: EngineType): String = when (type) {
    EngineType.SHERPA_ONNX -> "sherpa-onnx (ONNX Runtime)"
    EngineType.SHERPA_ONNX_STREAMING -> "Streaming (sherpa-onnx)"
    EngineType.CACTUS -> "Cactus (ARM SIMD)"
    EngineType.QWEN_ASR -> "Qwen3 ASR (Pure C/NEON)"
    EngineType.QWEN_ONNX -> "Qwen3 ASR (ONNX Runtime)"
    EngineType.ANDROID_SPEECH -> "Android SpeechRecognizer"
}

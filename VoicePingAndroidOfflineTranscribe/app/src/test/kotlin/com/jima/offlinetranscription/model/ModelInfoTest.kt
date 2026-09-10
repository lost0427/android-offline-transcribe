package com.voiceping.offlinetranscription.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelInfoTest {

    @Test
    fun availableModels_hasSixteenEntries() {
        assertEquals(16, ModelInfo.availableModels.size)
    }

    @Test
    fun defaultModel_isSenseVoiceSmall() {
        assertEquals("sensevoice-small", ModelInfo.defaultModel.id)
        assertEquals("SenseVoice Small", ModelInfo.defaultModel.displayName)
        assertEquals(EngineType.SHERPA_ONNX, ModelInfo.defaultModel.engineType)
    }

    @Test
    fun availableModels_containsExpectedIds() {
        val ids = ModelInfo.availableModels.map { it.id }
        assertTrue("whisper-tiny" in ids)
        assertTrue("whisper-base" in ids)
        assertTrue("whisper-base-en" in ids)
        assertTrue("whisper-small" in ids)
        assertTrue("whisper-large-v3-turbo" in ids)
        assertTrue("moonshine-tiny" in ids)
        assertTrue("moonshine-base" in ids)
        assertTrue("sensevoice-small" in ids)
        assertTrue("parakeet-tdt-v3" in ids)
        assertTrue("omnilingual-300m" in ids)
        assertTrue("zipformer-20m" in ids)
        assertTrue("cactus-whisper-tiny" in ids)
        assertTrue("qwen3-asr-0.6b-onnx" in ids)
    }

    @Test
    fun modelIds_areUnique() {
        val ids = ModelInfo.availableModels.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Model IDs must be unique")
    }

    // -- Engine type classification --

    @Test
    fun whisperModels_haveSherpaOnnxWhisperType() {
        ModelInfo.availableModels
            .filter { it.id.startsWith("whisper-") }
            .forEach { model ->
                assertEquals(EngineType.SHERPA_ONNX, model.engineType, "Expected SHERPA_ONNX for ${model.id}")
                assertEquals(SherpaModelType.WHISPER, model.sherpaModelType, "Expected WHISPER for ${model.id}")
            }
    }

    @Test
    fun moonshineModels_haveSherpaOnnxEngine_andMoonshineType() {
        ModelInfo.availableModels
            .filter { it.id.startsWith("moonshine-") }
            .forEach { model ->
                assertEquals(EngineType.SHERPA_ONNX, model.engineType, "Expected SHERPA_ONNX for ${model.id}")
                assertEquals(SherpaModelType.MOONSHINE, model.sherpaModelType, "Expected MOONSHINE for ${model.id}")
            }
    }

    @Test
    fun sensevoiceModels_haveSherpaOnnxEngine_andSenseVoiceType() {
        ModelInfo.availableModels
            .filter { it.id.startsWith("sensevoice-") }
            .forEach { model ->
                assertEquals(EngineType.SHERPA_ONNX, model.engineType, "Expected SHERPA_ONNX for ${model.id}")
                assertEquals(SherpaModelType.SENSE_VOICE, model.sherpaModelType, "Expected SENSE_VOICE for ${model.id}")
            }
    }

    @Test
    fun omnilingualModels_haveSherpaOnnxEngine_andOmnilingualType() {
        ModelInfo.availableModels
            .filter { it.id.startsWith("omnilingual-") }
            .forEach { model ->
                assertEquals(EngineType.SHERPA_ONNX, model.engineType, "Expected SHERPA_ONNX for ${model.id}")
                assertEquals(SherpaModelType.OMNILINGUAL_CTC, model.sherpaModelType, "Expected OMNILINGUAL_CTC for ${model.id}")
            }
    }

    @Test
    fun zipformerModels_haveSherpaOnnxStreamingEngine_andTransducerType() {
        ModelInfo.availableModels
            .filter { it.id.startsWith("zipformer-") }
            .forEach { model ->
                assertEquals(EngineType.SHERPA_ONNX_STREAMING, model.engineType, "Expected SHERPA_ONNX_STREAMING for ${model.id}")
                assertEquals(SherpaModelType.ZIPFORMER_TRANSDUCER, model.sherpaModelType, "Expected ZIPFORMER_TRANSDUCER for ${model.id}")
            }
    }

    // -- File lists --

    @Test
    fun whisperModels_haveThreeFiles() {
        ModelInfo.availableModels
            .filter { it.sherpaModelType == SherpaModelType.WHISPER }
            .forEach { model ->
                assertEquals(3, model.files.size, "${model.id} should have 3 files")
                val names = model.files.map { it.localName }
                assertTrue("encoder.int8.onnx" in names, "Missing encoder.int8.onnx in ${model.id}")
                assertTrue("decoder.int8.onnx" in names, "Missing decoder.int8.onnx in ${model.id}")
                assertTrue("tokens.txt" in names, "Missing tokens.txt in ${model.id}")
            }
    }

    @Test
    fun moonshineModels_haveFiveFiles() {
        ModelInfo.availableModels
            .filter { it.sherpaModelType == SherpaModelType.MOONSHINE }
            .forEach { model ->
                assertEquals(5, model.files.size, "${model.id} should have 5 files")
                val names = model.files.map { it.localName }
                assertTrue("preprocess.onnx" in names, "Missing preprocess.onnx in ${model.id}")
                assertTrue("encode.int8.onnx" in names, "Missing encode.int8.onnx in ${model.id}")
                assertTrue("uncached_decode.int8.onnx" in names)
                assertTrue("cached_decode.int8.onnx" in names)
                assertTrue("tokens.txt" in names, "Missing tokens.txt in ${model.id}")
            }
    }

    @Test
    fun sensevoiceModels_haveTwoFiles() {
        ModelInfo.availableModels
            .filter { it.sherpaModelType == SherpaModelType.SENSE_VOICE }
            .forEach { model ->
                assertEquals(2, model.files.size, "${model.id} should have 2 files")
                val names = model.files.map { it.localName }
                assertTrue("model.int8.onnx" in names, "Missing model.int8.onnx in ${model.id}")
                assertTrue("tokens.txt" in names, "Missing tokens.txt in ${model.id}")
            }
    }

    @Test
    fun omnilingualModels_haveTwoFiles() {
        ModelInfo.availableModels
            .filter { it.sherpaModelType == SherpaModelType.OMNILINGUAL_CTC }
            .forEach { model ->
                assertEquals(2, model.files.size, "${model.id} should have 2 files")
                val names = model.files.map { it.localName }
                assertTrue("model.int8.onnx" in names, "Missing model.int8.onnx in ${model.id}")
                assertTrue("tokens.txt" in names, "Missing tokens.txt in ${model.id}")
            }
    }

    @Test
    fun zipformerModels_haveFourFiles() {
        ModelInfo.availableModels
            .filter { it.sherpaModelType == SherpaModelType.ZIPFORMER_TRANSDUCER }
            .forEach { model ->
                assertEquals(4, model.files.size, "${model.id} should have 4 files")
                val names = model.files.map { it.localName }
                assertTrue(names.any { it.contains("encoder") && it.endsWith(".onnx") }, "Missing encoder in ${model.id}")
                assertTrue(names.any { it.contains("decoder") && it.endsWith(".onnx") }, "Missing decoder in ${model.id}")
                assertTrue(names.any { it.contains("joiner") && it.endsWith(".onnx") }, "Missing joiner in ${model.id}")
                assertTrue("tokens.txt" in names, "Missing tokens.txt in ${model.id}")
            }
    }

    @Test
    fun allFiles_haveValidUrls() {
        ModelInfo.availableModels
            .filter { it.files.isNotEmpty() } // Skip system-provided models (Android Speech)
            .forEach { model ->
                model.files.forEach { file ->
                    assertTrue(file.url.startsWith("https://"), "URL should start with https:// for ${file.localName} in ${model.id}")
                    assertTrue(file.url.contains("huggingface.co"), "URL should be on huggingface for ${file.localName} in ${model.id}")
                }
            }
    }

    @Test
    fun allFiles_haveNonEmptyLocalName() {
        ModelInfo.availableModels.forEach { model ->
            model.files.forEach { file ->
                assertTrue(file.localName.isNotEmpty(), "localName should not be empty in ${model.id}")
            }
        }
    }

    // -- Metadata completeness --

    @Test
    fun eachModel_hasNonEmptyDisplayName() {
        ModelInfo.availableModels.forEach { model ->
            assertTrue(model.displayName.isNotEmpty(), "displayName should not be empty for ${model.id}")
        }
    }

    @Test
    fun eachModel_hasNonEmptyDescription() {
        ModelInfo.availableModels.forEach { model ->
            assertTrue(model.description.isNotEmpty(), "description should not be empty for ${model.id}")
        }
    }

    @Test
    fun eachModel_hasNonEmptyParameterCount() {
        ModelInfo.availableModels.forEach { model ->
            assertTrue(model.parameterCount.isNotEmpty(), "parameterCount should not be empty for ${model.id}")
        }
    }

    @Test
    fun eachModel_hasNonEmptySizeOnDisk() {
        ModelInfo.availableModels.forEach { model ->
            assertTrue(model.sizeOnDisk.isNotEmpty(), "sizeOnDisk should not be empty for ${model.id}")
        }
    }

    // -- Grouped display --

    @Test
    fun modelsByEngine_containsExpectedEngineTypes() {
        val grouped = ModelInfo.modelsByEngine
        assertTrue(grouped.containsKey(EngineType.SHERPA_ONNX))
        assertTrue(grouped.containsKey(EngineType.SHERPA_ONNX_STREAMING))
        assertTrue(grouped.containsKey(EngineType.CACTUS))
        assertTrue(grouped.containsKey(EngineType.QWEN_ONNX))
    }

    @Test
    fun modelsByEngine_sherpaOnnx_hasTenModels() {
        val sherpaModels = ModelInfo.modelsByEngine[EngineType.SHERPA_ONNX]
        assertNotNull(sherpaModels)
        // 5 whisper + 2 moonshine + 1 sensevoice + 1 omnilingual + 1 parakeet = 10
        assertEquals(10, sherpaModels.size)
    }

    @Test
    fun modelsByEngine_sherpaOnnxStreaming_hasOneModel() {
        val streamingModels = ModelInfo.modelsByEngine[EngineType.SHERPA_ONNX_STREAMING]
        assertNotNull(streamingModels)
        assertEquals(1, streamingModels.size)
        assertEquals("zipformer-20m", streamingModels.first().id)
    }

    // -- Data class behavior --

    @Test
    fun modelInfo_isDataClass_equalityWorks() {
        val a = ModelInfo(
            id = "test", displayName = "Test", engineType = EngineType.SHERPA_ONNX,
            sherpaModelType = SherpaModelType.WHISPER,
            parameterCount = "1M", sizeOnDisk = "~1 MB", description = "A test model.",
            files = listOf(ModelFile("https://example.com/encoder.int8.onnx", "encoder.int8.onnx"))
        )
        val b = ModelInfo(
            id = "test", displayName = "Test", engineType = EngineType.SHERPA_ONNX,
            sherpaModelType = SherpaModelType.WHISPER,
            parameterCount = "1M", sizeOnDisk = "~1 MB", description = "A test model.",
            files = listOf(ModelFile("https://example.com/encoder.int8.onnx", "encoder.int8.onnx"))
        )
        assertEquals(a, b)
    }

    @Test
    fun modelInfo_isDataClass_copyWorks() {
        val original = ModelInfo.defaultModel
        val copied = original.copy(id = "custom")
        assertEquals("custom", copied.id)
        assertEquals(original.displayName, copied.displayName)
        assertEquals(original.engineType, copied.engineType)
    }

    @Test
    fun modelFile_isDataClass_equalityWorks() {
        val a = ModelFile("https://example.com/file.bin", "file.bin")
        val b = ModelFile("https://example.com/file.bin", "file.bin")
        assertEquals(a, b)
    }

    // -- Enum completeness --

    @Test
    fun engineType_hasSixValues() {
        assertEquals(6, EngineType.entries.size)
    }

    @Test
    fun sherpaModelType_hasSixValues() {
        assertEquals(6, SherpaModelType.entries.size)
    }

    // -- Cactus engine --

    @Test
    fun cactusModels_haveCactusEngineType() {
        ModelInfo.availableModels
            .filter { it.id.startsWith("cactus-") }
            .forEach { model ->
                assertEquals(EngineType.CACTUS, model.engineType, "Expected CACTUS for ${model.id}")
            }
    }

    @Test
    fun cactusModels_haveGgmlBinFile() {
        ModelInfo.availableModels
            .filter { it.engineType == EngineType.CACTUS }
            .forEach { model ->
                assertTrue(model.files.isNotEmpty(), "${model.id} should have GGML .bin file")
                assertTrue(model.files.any { it.localName.endsWith(".bin") }, "${model.id} needs .bin file")
            }
    }

    @Test
    fun modelsByEngine_cactus_hasOneModel() {
        val cactusModels = ModelInfo.modelsByEngine[EngineType.CACTUS]
        assertNotNull(cactusModels)
        assertEquals(1, cactusModels.size)
    }

    @Test
    fun modelsByEngine_qwen_hasOneModel() {
        val qwenModels = ModelInfo.modelsByEngine[EngineType.QWEN_ONNX]
        assertNotNull(qwenModels)
        assertEquals(1, qwenModels.size)
        assertEquals("qwen3-asr-0.6b-onnx", qwenModels.first().id)
    }
}

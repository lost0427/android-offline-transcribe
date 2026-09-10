#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void)thiz;
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading whisper model from: %s", model_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU-only on Android for maximum compatibility

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);

    if (!ctx) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }

    LOGI("Whisper context initialized successfully");
    return (jlong)ctx;
}

JNIEXPORT void JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env;
    (void)thiz;
    if (context_ptr != 0) {
        struct whisper_context *ctx = (struct whisper_context *)context_ptr;
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_isContextValid(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env;
    (void)thiz;
    return context_ptr != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str) {
    (void)thiz;
    if (context_ptr == 0) return -1;

    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize audio_len = (*env)->GetArrayLength(env, audio_data);

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = num_threads;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.single_segment = false;
    params.no_timestamps = false;
    params.no_context = true;
    params.translate = false;

    if (language != NULL && strlen(language) > 0) {
        params.language = language;
    } else {
        params.language = "en";
    }

    LOGI("Transcribing %d samples with %d threads, language=%s",
         audio_len, num_threads, params.language);

    whisper_reset_timings(ctx);
    int result = whisper_full(ctx, params, audio, audio_len);

    if (language_str != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full() failed with code %d", result);
    } else {
        whisper_print_timings(ctx);
    }

    return result;
}

JNIEXPORT jint JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_getSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env;
    (void)thiz;
    if (context_ptr == 0) return 0;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    return whisper_full_n_segments(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_getSegmentText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)thiz;
    if (context_ptr == 0) return (*env)->NewStringUTF(env, "");
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    const char *text = whisper_full_get_segment_text(ctx, index);
    return (*env)->NewStringUTF(env, text ? text : "");
}

JNIEXPORT jlong JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_getSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)env;
    (void)thiz;
    if (context_ptr == 0) return 0;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    return (jlong)whisper_full_get_segment_t0(ctx, index);
}

JNIEXPORT jlong JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_getSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)env;
    (void)thiz;
    if (context_ptr == 0) return 0;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    return (jlong)whisper_full_get_segment_t1(ctx, index);
}

JNIEXPORT jstring JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_getDetectedLanguage(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)thiz;
    if (context_ptr == 0) return (*env)->NewStringUTF(env, "");
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    int lang_id = whisper_full_lang_id(ctx);
    if (lang_id >= 0) {
        const char *lang = whisper_lang_str(lang_id);
        if (lang) return (*env)->NewStringUTF(env, lang);
    }
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_voiceping_offlinetranscription_service_WhisperCppLib_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    (void)thiz;
    const char *info = whisper_print_system_info();
    return (*env)->NewStringUTF(env, info);
}

JNIEXPORT jlong JNICALL Java_com_voiceping_offlinetranscription_service_WhisperCppLib_initVad(JNIEnv *env, jobject thiz, jstring path, jint threads) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_vad_context_params cp = whisper_vad_default_context_params();
    cp.n_threads = threads;
    struct whisper_vad_context *ctx = whisper_vad_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong)ctx;
}

JNIEXPORT void JNICALL Java_com_voiceping_offlinetranscription_service_WhisperCppLib_freeVad(JNIEnv *env, jobject thiz, jlong ptr) {
    (void)env; (void)thiz; whisper_vad_free((struct whisper_vad_context *)ptr);
}

JNIEXPORT jfloatArray JNICALL Java_com_voiceping_offlinetranscription_service_WhisperCppLib_detectVad(
        JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data) {
    (void)thiz;
    if (context_ptr == 0) return (*env)->NewFloatArray(env, 0);
    struct whisper_vad_context *vctx = (struct whisper_vad_context *)context_ptr;
    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    struct whisper_vad_params params = whisper_vad_default_params();
    params.threshold = 0.35f;
    params.min_speech_duration_ms = 100;
    params.min_silence_duration_ms = 700;
    params.max_speech_duration_s = 30.0f;
    params.speech_pad_ms = 400;
    params.samples_overlap = 0.5f;

    jfloatArray result = (*env)->NewFloatArray(env, 0);
    struct whisper_vad_segments *segs =
        whisper_vad_segments_from_samples(vctx, params, audio, n_samples);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
    if (segs) {
        int count = whisper_vad_segments_n_segments(segs);
        result = (*env)->NewFloatArray(env, count * 2);
        jfloat *buf = (jfloat *)malloc(sizeof(jfloat) * count * 2);
        if (buf) {
            for (int i = 0; i < count; i++) {
                buf[i * 2]     = whisper_vad_segments_get_segment_t0(segs, i) * 1000.0f;
                buf[i * 2 + 1] = whisper_vad_segments_get_segment_t1(segs, i) * 1000.0f;
            }
            (*env)->SetFloatArrayRegion(env, result, 0, count * 2, buf);
            free(buf);
        }
        whisper_vad_free_segments(segs);
    }
    return result;
}

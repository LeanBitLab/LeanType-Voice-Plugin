// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void whisper_android_log_callback(enum ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr) return;
    int android_level = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) android_level = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) android_level = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) android_level = ANDROID_LOG_DEBUG;
    __android_log_print(android_level, "WhisperNativeLog", "%s", text);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_leanbitlab_leantype_voice_offline_engine_WhisperNative_init(
        JNIEnv *env,
        jobject /* this */,
        jstring model_path) {
    if (model_path == nullptr) {
        LOGE("Model path is null");
        return 0;
    }

    whisper_log_set(whisper_android_log_callback, nullptr);

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing whisper from file: %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from file");
        return 0;
    }

    LOGI("Whisper context initialized successfully: %p (multilingual: %d)", ctx, whisper_is_multilingual(ctx));
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_leanbitlab_leantype_voice_offline_engine_WhisperNative_free(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong context_ptr) {
    if (context_ptr != 0) {
        auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
        LOGI("Freeing whisper context: %p", ctx);
        whisper_free(ctx);
    }
}

JNIEXPORT jstring JNICALL
Java_com_leanbitlab_leantype_voice_offline_engine_WhisperNative_transcribe(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr,
        jfloatArray pcm_data,
        jstring language,
        jint threads) {
    if (context_ptr == 0 || pcm_data == nullptr) {
        return env->NewStringUTF("");
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    jsize len = env->GetArrayLength(pcm_data);
    if (len <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(pcm_data, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.single_segment = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.temperature_inc = 0.0f;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.suppress_nst = true;

    const bool is_multilingual = whisper_is_multilingual(ctx) != 0;
    const char *lang_str = nullptr;
    if (language != nullptr) {
        lang_str = env->GetStringUTFChars(language, nullptr);
    }

    std::string active_lang = "en";
    if (!is_multilingual) {
        params.language = "en";
        params.detect_language = false;
    } else if (lang_str != nullptr && std::string(lang_str) != "auto" && std::string(lang_str).length() > 0) {
        active_lang = lang_str;
        params.language = active_lang.c_str();
        params.detect_language = false;
    } else {
        params.language = "auto";
        params.detect_language = false;
    }

    LOGI("Starting whisper_full: samples=%d (%.2f sec), language=%s, is_multilingual=%d, threads=%d",
         len, (float)len / 16000.0f, params.language, is_multilingual, params.n_threads);

    int ret = whisper_full(ctx, params, samples, len);

    LOGI("whisper_full completed with code: %d", ret);

    if (lang_str != nullptr) {
        env->ReleaseStringUTFChars(language, lang_str);
    }
    env->ReleaseFloatArrayElements(pcm_data, samples, JNI_ABORT);

    if (ret != 0) {
        LOGE("whisper_full failed with code: %d", ret);
        return env->NewStringUTF("");
    }

    std::string result;
    const int n_segments = whisper_full_n_segments(ctx);
    LOGI("whisper_full segment count: %d", n_segments);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            std::string s(text);
            if (s.find("[BLANK_AUDIO]") == std::string::npos &&
                s.find("(blank audio)") == std::string::npos) {
                result += s;
            }
        }
    }
    LOGI("whisper_full accumulated result: '%s'", result.c_str());

    return env->NewStringUTF(result.c_str());
}

} // extern "C"

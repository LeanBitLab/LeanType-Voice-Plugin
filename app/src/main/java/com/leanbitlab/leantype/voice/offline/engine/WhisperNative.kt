// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.util.Log

object WhisperNative {
    private const val TAG = "WhisperNative"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("whisper_jni")
            isLoaded = true
            Log.i(TAG, "whisper_jni native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load whisper_jni library", e)
            isLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    external fun init(modelPath: String): Long
    external fun free(contextPtr: Long)
    external fun transcribe(
        contextPtr: Long,
        pcmData: FloatArray,
        language: String?,
        threads: Int
    ): String
}

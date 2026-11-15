// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.util.Log

object WhisperNative {
    private const val TAG = "WhisperNative"
    private var isLoaded = false

    /** False if the CPU cannot run this build; the library is not loaded then, as using it would crash the process */
    val isCpuSupported: Boolean = CpuSupport.isSupported()

    init {
        if (!isCpuSupported) {
            Log.e(TAG, "Not loading whisper_jni: ${CpuSupport.UNSUPPORTED_MESSAGE}")
        } else try {
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
        threads: Int,
        initialPrompt: String? = null
    ): String
}

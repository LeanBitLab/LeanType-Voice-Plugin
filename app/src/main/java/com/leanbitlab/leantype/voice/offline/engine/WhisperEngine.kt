// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class WhisperEngine {

    private var contextPtr: Long = 0L
    private var loadedModelPath: String? = null
    private val isRunning = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WhisperAudioThread").apply { isDaemon = true }
    }

    fun isModelLoaded(): Boolean = contextPtr != 0L

    fun loadModel(modelFile: File): Boolean {
        if (!WhisperNative.isNativeLoaded()) {
            Log.e(TAG, "Native whisper library is not loaded")
            return false
        }
        if (!modelFile.exists() || !modelFile.isFile) {
            Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
            return false
        }

        if (contextPtr != 0L && loadedModelPath == modelFile.absolutePath) {
            return true
        }

        releaseContextSync()

        return try {
            val ptr = WhisperNative.init(modelFile.absolutePath)
            if (ptr != 0L) {
                contextPtr = ptr
                loadedModelPath = modelFile.absolutePath
                Log.i(TAG, "Whisper model loaded successfully: ${modelFile.name}")
                true
            } else {
                Log.e(TAG, "WhisperNative.init returned 0 for: ${modelFile.absolutePath}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading Whisper model", e)
            false
        }
    }

    fun releaseContext() {
        audioExecutor.execute {
            releaseContextSync()
        }
    }

    private fun releaseContextSync() {
        if (contextPtr != 0L) {
            try {
                Log.i(TAG, "Releasing whisper context: $contextPtr")
                WhisperNative.free(contextPtr)
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing whisper context", e)
            } finally {
                contextPtr = 0L
                loadedModelPath = null
            }
        }
    }

    fun transcribeSync(samples: List<Short>, language: String?): String {
        if (contextPtr == 0L || samples.isEmpty()) return ""
        val pcm = ShortArray(samples.size) { samples[it] }

        var sumSq = 0.0
        for (s in pcm) {
            val norm = s / 32768.0
            sumSq += norm * norm
        }
        val segmentRms = sqrt(sumSq / pcm.size)
        if (segmentRms < SILENCE_RMS) {
            Log.d(TAG, "transcribeSync: RMS $segmentRms below threshold $SILENCE_RMS")
            return ""
        }

        val floatPcm = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        val numThreads = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))
        val lang = normalizeLanguageTag(language)
        return try {
            val raw = WhisperNative.transcribe(contextPtr, floatPcm, lang, numThreads)
            raw.trim()
        } catch (e: Exception) {
            Log.e(TAG, "transcribeSync failed", e)
            ""
        }
    }

    fun startSession(
        audioInput: ParcelFileDescriptor,
        callback: IVoiceCallback,
        config: VoiceSessionConfig?
    ) {
        if (contextPtr == 0L) {
            try { audioInput.close() } catch (_: Exception) {}
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Whisper model not loaded")
            return
        }

        isRunning.set(true)
        isCancelled.set(false)
        val language = normalizeLanguageTag(config?.languageTag)
        val numThreads = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))

        audioExecutor.execute {
            var inputStream: FileInputStream? = null
            val utteranceBuffer = ArrayList<Short>()
            var totalReadBytes = 0L

            try {
                Log.i(TAG, "Dispatching callback.onSessionStarted")
                callback.onSessionStarted()
                inputStream = FileInputStream(audioInput.fileDescriptor)
                val byteBuffer = ByteArray(FRAME_SIZE_BYTES)
                val shortBuffer = ShortArray(FRAME_SIZE_BYTES / 2)

                // Continuous non-blocking read loop: drains entire audio stream from host pipe
                while (isRunning.get()) {
                    val bytesRead = inputStream.read(byteBuffer)
                    if (bytesRead <= 0) {
                        Log.i(TAG, "EOF reached on audio input stream ($bytesRead). Total read bytes: $totalReadBytes")
                        break
                    }

                    totalReadBytes += bytesRead
                    ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuffer, 0, bytesRead / 2)

                    for (i in 0 until bytesRead / 2) {
                        utteranceBuffer.add(shortBuffer[i])
                    }
                }

                // Process full speech utterance on EOF
                Log.i(TAG, "Processing utterance on EOF (samples=${utteranceBuffer.size}, isCancelled=${isCancelled.get()})")
                if (!isCancelled.get() && utteranceBuffer.isNotEmpty()) {
                    Log.i(TAG, "EOF flush: transcribing ${utteranceBuffer.size} samples (%.2f sec)".format(utteranceBuffer.size / 16000.0))
                    val text = transcribeAndEmit(utteranceBuffer, language, numThreads, callback)
                    Log.i(TAG, "EOF result: '$text'")
                    utteranceBuffer.clear()
                } else if (!isCancelled.get()) {
                    Log.i(TAG, "EOF with empty buffer, emitting empty final")
                    try {
                        callback.onFinal("")
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote exception during empty onFinal", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper audio loop error", e)
                if (!isCancelled.get()) {
                    try {
                        callback.onError(VoiceConstants.VOICE_ERROR_AUDIO_START_FAILED, e.message ?: "Audio processing error")
                    } catch (_: Exception) {}
                }
            } finally {
                isRunning.set(false)
                if (!isCancelled.get()) {
                    try {
                        Log.i(TAG, "Emitting onSessionEnded")
                        callback.onSessionEnded()
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote exception during onSessionEnded", e)
                    }
                }
                try { inputStream?.close() } catch (_: Exception) {}
                try { audioInput.close() } catch (_: Exception) {}
                Log.i(TAG, "Whisper session finished. Total read: $totalReadBytes bytes (cancelled=${isCancelled.get()})")
            }
        }
    }

    private fun transcribeAndEmit(
        samples: List<Short>,
        language: String?,
        threads: Int,
        callback: IVoiceCallback
    ): String {
        if (isCancelled.get() || contextPtr == 0L || samples.isEmpty()) return ""

        val pcm = ShortArray(samples.size) { samples[it] }

        // RMS Hallucination Gate: discard silent audio chunks without inference
        var sumSq = 0.0
        for (s in pcm) {
            val norm = s / 32768.0
            sumSq += norm * norm
        }
        val segmentRms = sqrt(sumSq / pcm.size)
        Log.i(TAG, "Utterance samples: ${pcm.size}, RMS: $segmentRms (threshold: $SILENCE_RMS)")

        if (segmentRms < SILENCE_RMS) {
            Log.w(TAG, "RMS gate triggered! Audio discarded as silence (RMS: $segmentRms < $SILENCE_RMS)")
            if (!isCancelled.get()) {
                try { callback.onFinal("") } catch (_: Exception) {}
            }
            return ""
        }

        val floatPcm = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        return try {
            val rawText = WhisperNative.transcribe(contextPtr, floatPcm, language, threads)
            Log.i(TAG, "JNI transcribe returned: '$rawText'")
            val finalText = rawText.trim()
            if (!isCancelled.get()) {
                Log.i(TAG, "Emitting onFinal: '$finalText'")
                callback.onFinal(finalText)
            }
            finalText
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription failed", e)
            ""
        }
    }

    fun cancelSession() {
        isCancelled.set(true)
        isRunning.set(false)
    }

    private fun normalizeLanguageTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val code = tag.substringBefore('-').lowercase().trim()
        return if (code.length == 2) code else null
    }

    companion object {
        private const val TAG = "WhisperEngine"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_BYTES = 960 // 30ms @ 16kHz 16-bit mono
        private const val SILENCE_RMS = 0.005f // Low threshold to prevent dropping quiet speech
    }
}

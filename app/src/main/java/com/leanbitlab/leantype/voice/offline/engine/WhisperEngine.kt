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
import kotlin.math.pow
import kotlin.math.sqrt

class WhisperEngine {

    private var contextPtr: Long = 0L
    private var loadedModelPath: String? = null
    private val isRunning = AtomicBoolean(false)
    private val audioExecutor = Executors.newSingleThreadExecutor()

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
        val language = normalizeLanguageTag(config?.languageTag)
        val numThreads = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))

        audioExecutor.execute {
            var inputStream: FileInputStream? = null
            val segmentBuffer = ArrayList<Short>()
            var totalReadBytes = 0L
            var silenceFrames = 0

            try {
                callback.onSessionStarted()
                inputStream = FileInputStream(audioInput.fileDescriptor)
                val byteBuffer = ByteArray(FRAME_SIZE_BYTES)
                val shortBuffer = ShortArray(FRAME_SIZE_BYTES / 2)

                while (isRunning.get()) {
                    val bytesRead = inputStream.read(byteBuffer)
                    if (bytesRead <= 0) break

                    totalReadBytes += bytesRead
                    ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuffer, 0, bytesRead / 2)

                    for (i in 0 until bytesRead / 2) {
                        segmentBuffer.add(shortBuffer[i])
                    }

                    // Compute short-term frame energy
                    var frameSumSq = 0.0
                    for (i in 0 until bytesRead / 2) {
                        val norm = shortBuffer[i] / 32768.0
                        frameSumSq += norm * norm
                    }
                    val frameRms = sqrt(frameSumSq / (bytesRead / 2))

                    if (frameRms < SILENCE_RMS) {
                        silenceFrames++
                    } else {
                        silenceFrames = 0
                    }

                    // Trigger transcription if silence detected after speech or hard duration cap reached
                    val reachedSilence = silenceFrames >= VAD_SILENCE_FRAMES && segmentBuffer.size >= MIN_SEGMENT_SAMPLES
                    val reachedHardCap = segmentBuffer.size >= MAX_SEGMENT_SAMPLES

                    if (reachedSilence || reachedHardCap) {
                        transcribeAndEmit(segmentBuffer, language, numThreads, callback)
                        segmentBuffer.clear()
                        silenceFrames = 0
                    }
                }

                // Process remaining audio in buffer at the end of session
                if (segmentBuffer.isNotEmpty() && segmentBuffer.size >= (SAMPLE_RATE * 0.3).toInt()) {
                    transcribeAndEmit(segmentBuffer, language, numThreads, callback)
                    segmentBuffer.clear()
                }

                callback.onSessionEnded()
            } catch (e: Exception) {
                Log.e(TAG, "Whisper audio loop error", e)
                callback.onError(VoiceConstants.VOICE_ERROR_AUDIO_START_FAILED, e.message ?: "Audio processing error")
            } finally {
                isRunning.set(false)
                try { inputStream?.close() } catch (_: Exception) {}
                try { audioInput.close() } catch (_: Exception) {}
                Log.i(TAG, "Whisper session ended. Total read: $totalReadBytes bytes")
            }
        }
    }

    private fun transcribeAndEmit(
        samples: List<Short>,
        language: String?,
        threads: Int,
        callback: IVoiceCallback
    ) {
        if (contextPtr == 0L || samples.isEmpty()) return

        val pcm = ShortArray(samples.size) { samples[it] }

        // RMS Hallucination Gate: discard silent audio chunks without inference
        var sumSq = 0.0
        for (s in pcm) {
            val norm = s / 32768.0
            sumSq += norm * norm
        }
        val segmentRms = sqrt(sumSq / pcm.size)

        if (segmentRms < SILENCE_RMS) {
            Log.d(TAG, "Skipping silent segment (RMS: $segmentRms < $SILENCE_RMS)")
            return
        }

        val floatPcm = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        try {
            val text = WhisperNative.transcribe(contextPtr, floatPcm, language, threads).trim()
            if (text.isNotBlank()) {
                Log.i(TAG, "Whisper transcribed: '$text'")
                callback.onFinal(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription failed", e)
        }
    }

    fun cancelSession() {
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
        private const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE * 1 // 1.0 second minimum
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 8 // 8.0 seconds hard cap
        private const val VAD_SILENCE_FRAMES = 15 // ~450ms silence hangover
        private const val SILENCE_RMS = 0.01f // Tunable silence threshold for hallucination gating
    }
}

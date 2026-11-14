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
        val rawPcm = ShortArray(samples.size) { samples[it] }
        val pcm = trimLeadingAndTrailingSilence(rawPcm)
        if (pcm.isEmpty()) {
            Log.d(TAG, "transcribeSync: Audio discarded as silence after trimming")
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
        val numThreads = if (config != null && config.cpuThreads > 0) {
            config.cpuThreads
        } else {
            minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))
        }
        val customPrompt = config?.customPrompt?.takeIf { it.isNotBlank() }

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
                    val text = transcribeAndEmit(utteranceBuffer, language, numThreads, customPrompt, callback)
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
        prompt: String?,
        callback: IVoiceCallback
    ): String {
        if (isCancelled.get() || contextPtr == 0L || samples.isEmpty()) return ""

        val rawPcm = ShortArray(samples.size) { samples[it] }
        val pcm = trimLeadingAndTrailingSilence(rawPcm)

        if (pcm.isEmpty()) {
            Log.w(TAG, "Peak RMS gate triggered! Audio discarded as silence after trimming")
            if (!isCancelled.get()) {
                try { callback.onFinal("") } catch (_: Exception) {}
            }
            return ""
        }

        val floatPcm = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        return try {
            val rawText = WhisperNative.transcribe(contextPtr, floatPcm, language, threads, prompt)
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

    internal fun trimLeadingAndTrailingSilence(pcm: ShortArray, sampleRate: Int = SAMPLE_RATE): ShortArray {
        if (pcm.isEmpty()) return pcm
        val windowSize = (sampleRate * 0.03).toInt() // 30ms = 480 samples at 16kHz
        if (pcm.size <= windowSize * 2) return pcm

        val peakRms = calculatePeakWindowRms(pcm, windowSize)
        if (peakRms < SILENCE_RMS) {
            Log.w(TAG, "Utterance peak RMS ($peakRms) below silence threshold ($SILENCE_RMS)")
            return ShortArray(0)
        }

        // Sensitive speech threshold adaptively scaled to peak RMS, bounded between 0.003 and 0.010
        val speechThreshold = maxOf(0.003, minOf(0.010, peakRms * 0.12))
        var firstSpeechSample = -1
        var lastSpeechSample = -1

        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + windowSize, pcm.size)
            val count = end - i
            var sumSq = 0.0
            for (j in i until end) {
                val norm = pcm[j] / 32768.0
                sumSq += norm * norm
            }
            val frameRms = sqrt(sumSq / count)
            if (frameRms >= speechThreshold) {
                if (firstSpeechSample == -1) {
                    firstSpeechSample = i
                }
                lastSpeechSample = end
            }
            i += windowSize
        }

        if (firstSpeechSample == -1 || lastSpeechSample == -1) {
            return pcm
        }

        // 300ms safety padding on both ends ensures quiet consonants and vocal onsets/tails are preserved
        val paddingSamples = (sampleRate * 0.30).toInt()
        val start = maxOf(0, firstSpeechSample - paddingSamples)
        val end = minOf(pcm.size, lastSpeechSample + paddingSamples)

        Log.i(TAG, "trimSilence: trimmed from ${pcm.size} samples (%.2fs) to ${end - start} samples (%.2fs)"
            .format(pcm.size.toDouble() / sampleRate, (end - start).toDouble() / sampleRate))

        return pcm.copyOfRange(start, end)
    }

    private fun calculatePeakWindowRms(pcm: ShortArray, windowSize: Int = 1600): Double {
        if (pcm.isEmpty()) return 0.0
        var maxRms = 0.0
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + windowSize, pcm.size)
            val count = end - i
            var sumSq = 0.0
            for (j in i until end) {
                val norm = pcm[j] / 32768.0
                sumSq += norm * norm
            }
            val windowRms = sqrt(sumSq / count)
            if (windowRms > maxRms) {
                maxRms = windowRms
            }
            i += windowSize
        }
        return maxRms
    }

    fun cancelSession() {
        isCancelled.set(true)
        isRunning.set(false)
    }

    private fun normalizeLanguageTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return "auto"
        val trimmed = tag.trim().lowercase()
        if (trimmed == "auto") return "auto"
        val code = trimmed.substringBefore('-').trim()
        return if (code.length == 2 || code.length == 3) code else "auto"
    }

    companion object {
        private const val TAG = "WhisperEngine"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_BYTES = 960 // 30ms @ 16kHz 16-bit mono
        private const val SILENCE_RMS = 0.001f // Sensitive peak threshold to reliably detect short words
    }
}

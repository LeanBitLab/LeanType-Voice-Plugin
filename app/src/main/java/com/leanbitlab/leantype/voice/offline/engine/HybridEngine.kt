// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import org.vosk.Recognizer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class HybridEngine(
    private val voskEngine: VoskEngine,
    private val whisperEngine: WhisperEngine
) {

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HybridAudioThread").apply { isDaemon = true }
    }

    private val isRunning = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    fun startSession(
        audioInput: ParcelFileDescriptor,
        callback: IVoiceCallback,
        config: VoiceSessionConfig?
    ) {
        if (!voskEngine.isModelLoaded() || !whisperEngine.isModelLoaded()) {
            try { audioInput.close() } catch (_: Exception) {}
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Vosk or Whisper model not ready for Hybrid mode")
            return
        }

        isRunning.set(true)
        isCancelled.set(false)
        val language = config?.languageTag

        audioExecutor.execute {
            var recognizer: Recognizer? = null
            var inputStream: FileInputStream? = null
            val segmentBuffer = ArrayList<Short>()
            var voskAccumulatedPartial = ""
            var totalReadBytes = 0L
            var lastSpeechTimeMs = 0L
            var speechDetected = false

            try {
                recognizer = voskEngine.createRecognizer()
                if (recognizer == null) {
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to create Vosk recognizer")
                    return@execute
                }

                Log.i(TAG, "Hybrid session starting")
                callback.onSessionStarted()
                inputStream = FileInputStream(audioInput.fileDescriptor)
                val byteBuffer = ByteArray(FRAME_SIZE_BYTES)
                val shortBuffer = ShortArray(FRAME_SIZE_BYTES / 2)

                while (isRunning.get()) {
                    val bytesRead = inputStream.read(byteBuffer)
                    if (bytesRead <= 0) {
                        Log.i(TAG, "Hybrid EOF reached on audio stream ($bytesRead). Total: $totalReadBytes bytes")
                        break
                    }

                    totalReadBytes += bytesRead

                    // 1. Feed Vosk synchronously on audio thread for real-time live partials
                    if (recognizer.acceptWaveForm(byteBuffer, bytesRead)) {
                        val text = voskEngine.parseJsonText(recognizer.result, "text")
                        if (text.isNotBlank() && !isCancelled.get()) {
                            voskAccumulatedPartial = text
                            Log.d(TAG, "Hybrid Vosk partial: '$text'")
                            callback.onPartial(text)
                        }
                    } else {
                        val partial = voskEngine.parseJsonText(recognizer.partialResult, "partial")
                        if (partial.isNotBlank() && !isCancelled.get()) {
                            voskAccumulatedPartial = partial
                            Log.d(TAG, "Hybrid Vosk partial: '$partial'")
                            callback.onPartial(partial)
                        }
                    }

                    // 2. Accumulate PCM samples for Whisper refinement
                    ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuffer, 0, bytesRead / 2)

                    for (i in 0 until bytesRead / 2) {
                        segmentBuffer.add(shortBuffer[i])
                    }

                    // 3. Timestamp-based VAD Energy Endpoint Check
                    var frameSumSq = 0.0
                    for (i in 0 until bytesRead / 2) {
                        val norm = shortBuffer[i] / 32768.0
                        frameSumSq += norm * norm
                    }
                    val frameRms = sqrt(frameSumSq / (bytesRead / 2))
                    val now = System.currentTimeMillis()

                    if (frameRms > SPEECH_RMS) {
                        speechDetected = true
                        lastSpeechTimeMs = now
                    }

                    val reachedSilence = speechDetected && lastSpeechTimeMs > 0 &&
                            (now - lastSpeechTimeMs > VAD_SILENCE_THRESHOLD_MS) &&
                            segmentBuffer.size >= MIN_SEGMENT_SAMPLES
                    val reachedHardCap = segmentBuffer.size >= MAX_SEGMENT_SAMPLES

                    if (reachedSilence || reachedHardCap) {
                        Log.i(TAG, "Hybrid VAD triggered (silence=${now - lastSpeechTimeMs}ms, hardCap=$reachedHardCap, samples=${segmentBuffer.size})")

                        // Synchronous Whisper refinement on audio thread (guarantees context thread-safety)
                        val refined = whisperEngine.transcribeSync(segmentBuffer, language)
                        if (refined.isNotBlank() && !isCancelled.get()) {
                            Log.i(TAG, "Hybrid segment refined with Whisper: '$refined'")
                            callback.onFinal(refined)
                            recognizer.reset()
                            voskAccumulatedPartial = ""
                        } else if (voskAccumulatedPartial.isNotBlank() && !isCancelled.get()) {
                            Log.i(TAG, "Hybrid segment fallback to Vosk: '$voskAccumulatedPartial'")
                            callback.onFinal(voskAccumulatedPartial)
                            recognizer.reset()
                            voskAccumulatedPartial = ""
                        }

                        segmentBuffer.clear()
                        speechDetected = false
                        lastSpeechTimeMs = 0L
                    }
                }

                // 4. EOF Flush (synchronously executed before onSessionEnded)
                if (!isCancelled.get() && segmentBuffer.isNotEmpty()) {
                    Log.i(TAG, "Hybrid EOF flush: transcribing ${segmentBuffer.size} samples")
                    val refined = whisperEngine.transcribeSync(segmentBuffer, language)
                    if (refined.isNotBlank()) {
                        Log.i(TAG, "Hybrid EOF Whisper result: '$refined'")
                        callback.onFinal(refined)
                    } else if (voskAccumulatedPartial.isNotBlank()) {
                        Log.i(TAG, "Hybrid EOF fallback to Vosk: '$voskAccumulatedPartial'")
                        callback.onFinal(voskAccumulatedPartial)
                    } else {
                        callback.onFinal("")
                    }
                    segmentBuffer.clear()
                } else if (!isCancelled.get()) {
                    callback.onFinal("")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hybrid audio loop error", e)
                if (!isCancelled.get()) {
                    try {
                        callback.onError(VoiceConstants.VOICE_ERROR_AUDIO_START_FAILED, e.message ?: "Hybrid processing error")
                    } catch (_: Exception) {}
                }
            } finally {
                isRunning.set(false)
                try { recognizer?.close() } catch (_: Exception) {}
                if (!isCancelled.get()) {
                    try {
                        Log.i(TAG, "Hybrid emitting onSessionEnded")
                        callback.onSessionEnded()
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote exception during onSessionEnded", e)
                    }
                }
                try { inputStream?.close() } catch (_: Exception) {}
                try { audioInput.close() } catch (_: Exception) {}
                Log.i(TAG, "Hybrid session ended. Total bytes: $totalReadBytes (cancelled=${isCancelled.get()})")
            }
        }
    }

    fun stopSession() {
        // Graceful stop: Host closing pipe sends EOF, triggering final flush
    }

    fun cancelSession() {
        isCancelled.set(true)
        isRunning.set(false)
    }

    fun release() {
        cancelSession()
    }

    companion object {
        private const val TAG = "HybridEngine"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_BYTES = 960 // 30ms @ 16kHz
        private const val MIN_SEGMENT_SAMPLES = (SAMPLE_RATE * 0.8).toInt() // 800ms minimum speech
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 8 // 8.0 seconds hard cap
        private const val VAD_SILENCE_THRESHOLD_MS = 300L // 300ms silence after speech onset
        private const val SPEECH_RMS = 0.015f // Energy threshold for speech onset
    }
}

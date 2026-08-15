// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import org.vosk.Recognizer
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.log10
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

    // Pre-allocated frame short buffer (up to 100ms at 16kHz) to guarantee zero heap allocations in audio loop
    private val pcmFrameBuffer = ShortArray(FRAME_BUFFER_MAX_SAMPLES)

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
            var lastEmittedPartial = ""
            var totalReadBytes = 0L

            // Adaptive DSP VAD state variables
            var noiseFloor = 0.001f
            var smoothedState = 0f
            var utteranceLengthMs = 0L
            var silenceDurationMs = 0L
            var isSpeaking = false

            try {
                recognizer = voskEngine.createRecognizer()
                if (recognizer == null) {
                    Log.e(TAG, "Failed to create Vosk recognizer! Vosk model may not be loaded properly.")
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to create Vosk recognizer")
                    return@execute
                }

                Log.i(TAG, "Hybrid session starting, recognizer created successfully")
                callback.onSessionStarted()
                inputStream = FileInputStream(audioInput.fileDescriptor)
                val byteBuffer = ByteArray(FRAME_SIZE_BYTES)

                while (isRunning.get()) {
                    val bytesRead = inputStream.read(byteBuffer)
                    if (bytesRead <= 0) {
                        Log.i(TAG, "Hybrid EOF reached on audio stream ($bytesRead). Total: $totalReadBytes bytes")
                        break
                    }

                    totalReadBytes += bytesRead

                    // 1. Feed Vosk synchronously on audio thread for real-time live partials
                    val accepted = recognizer.acceptWaveForm(byteBuffer, bytesRead)
                    val partial = if (accepted) {
                        voskEngine.parseJsonText(recognizer.result, "text")
                    } else {
                        voskEngine.parseJsonText(recognizer.partialResult, "partial")
                    }

                    if (partial.isNotBlank() && partial != lastEmittedPartial && !isCancelled.get()) {
                        lastEmittedPartial = partial
                        voskAccumulatedPartial = partial
                        Log.i(TAG, "Emitting Vosk partial: '$partial'")
                        callback.onPartial(partial)
                    }

                    // 2. Zero-Allocation PCM16 Little-Endian manual extraction & single-pass metrics
                    val sampleCount = minOf(bytesRead / 2, FRAME_BUFFER_MAX_SAMPLES)
                    var sumSquares = 0L
                    var crossings = 0

                    for (i in 0 until sampleCount) {
                        val b0 = byteBuffer[i * 2].toInt() and 0xFF
                        val b1 = byteBuffer[i * 2 + 1].toInt()
                        val sample = ((b1 shl 8) or b0).toShort()
                        pcmFrameBuffer[i] = sample
                        segmentBuffer.add(sample)

                        sumSquares += sample.toLong() * sample.toLong()
                        if (i > 0) {
                            val prev = pcmFrameBuffer[i - 1]
                            if ((sample >= 0 && prev < 0) || (sample < 0 && prev >= 0)) {
                                crossings++
                            }
                        }
                    }

                    val energy = if (sampleCount > 0) sqrt((sumSquares / sampleCount).toDouble()).toFloat() / 32768f else 0f
                    val zcr = if (sampleCount > 1) crossings.toFloat() / (sampleCount - 1) else 0f

                    // 3. Stage 1: Adaptive Noise Floor (Martin's Asymmetric Smoothing)
                    noiseFloor = 0.98f * noiseFloor + 0.02f * minOf(energy, 1.5f * noiseFloor)

                    // 4. Stage 3: Signal-to-Noise Ratio & Fast Sigmoid Probability Mapping
                    val snr = if (noiseFloor > 1e-6f) 20f * log10(energy / noiseFloor) else 0f
                    val probSpeech = if (snr > 0f) snr / (snr + 6.0f) else 0f

                    // 5. Stage 4: Temporal Hysteresis (1st-order IIR Low-Pass Filter)
                    smoothedState = 0.85f * smoothedState + 0.15f * probSpeech

                    // 6. Stage 2 & VAD State Decision
                    val isHighFreqSpeech = (zcr > 0.15f && energy > (noiseFloor * 2f))
                    val isFrameSpeech = smoothedState > 0.6f || isHighFreqSpeech
                    val frameDurationMs = (sampleCount * 1000L) / SAMPLE_RATE

                    if (isFrameSpeech) {
                        isSpeaking = true
                        silenceDurationMs = 0L
                        utteranceLengthMs += frameDurationMs
                    } else if (isSpeaking) {
                        silenceDurationMs += frameDurationMs

                        // 7. Stage 5: Dynamic Segmentation Timeout (Exponential decay based on utterance length)
                        // T_min = 250ms, T_max = 600ms, tau = 3000ms
                        val tau = 3000f
                        val dynamicTimeout = 250f + 350f * exp(-utteranceLengthMs / tau)

                        val reachedSilence = silenceDurationMs >= dynamicTimeout && utteranceLengthMs >= MIN_UTTERANCE_MS
                        val reachedHardCap = utteranceLengthMs >= MAX_UTTERANCE_MS

                        if (reachedSilence || reachedHardCap) {
                            Log.i(TAG, "Adaptive VAD triggered (silence=${silenceDurationMs}ms, utterance=${utteranceLengthMs}ms, cutoff=${dynamicTimeout.toInt()}ms, samples=${segmentBuffer.size})")

                            // Synchronous Whisper refinement on audio thread (guarantees context thread-safety)
                            val refined = whisperEngine.transcribeSync(segmentBuffer, language)
                            if (refined.isNotBlank() && !isCancelled.get()) {
                                Log.i(TAG, "Hybrid segment refined with Whisper: '$refined'")
                                callback.onFinal(refined)
                                recognizer.reset()
                                voskAccumulatedPartial = ""
                                lastEmittedPartial = ""
                            } else if (voskAccumulatedPartial.isNotBlank() && !isCancelled.get()) {
                                Log.i(TAG, "Hybrid segment fallback to Vosk: '$voskAccumulatedPartial'")
                                callback.onFinal(voskAccumulatedPartial)
                                recognizer.reset()
                                voskAccumulatedPartial = ""
                                lastEmittedPartial = ""
                            }

                            segmentBuffer.clear()
                            isSpeaking = false
                            silenceDurationMs = 0L
                            utteranceLengthMs = 0L
                            smoothedState = 0f
                        }
                    }
                }

                // 8. EOF Flush (synchronously executed before onSessionEnded)
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
        private const val FRAME_BUFFER_MAX_SAMPLES = 1600 // 100ms @ 16kHz
        private const val MIN_UTTERANCE_MS = 200L // 200ms minimum speech for fast command endpointing
        private const val MAX_UTTERANCE_MS = 8000L // 8.0s hard cap per segment
    }
}

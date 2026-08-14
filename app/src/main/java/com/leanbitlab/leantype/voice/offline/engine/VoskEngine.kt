// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.VoiceConstants
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoskEngine(private val context: Context) {

    private var model: Model? = null
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VoskAudioThread").apply { isDaemon = true }
    }

    @Volatile
    private var isCancelled = false

    @Volatile
    private var activePfd: ParcelFileDescriptor? = null

    fun loadModel(modelDir: File): Boolean {
        return try {
            if (!modelDir.exists()) return false
            if (model == null) {
                model = Model(modelDir.absolutePath)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startSession(pfd: ParcelFileDescriptor, callback: IVoiceCallback) {
        val currentModel = model
        if (currentModel == null) {
            callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Vosk model not loaded")
            return
        }

        val dupPfd = try { pfd.dup() } catch (e: Exception) { pfd }
        isCancelled = false
        activePfd = dupPfd

        audioExecutor.execute {
            var recognizer: Recognizer? = null
            var totalRead = 0L
            try {
                android.util.Log.i("VoskEngine", "startSession modelLoaded=${currentModel != null}")
                recognizer = Recognizer(currentModel, 16000f)
                callback.onSessionStarted()

                ParcelFileDescriptor.AutoCloseInputStream(dupPfd).use { input ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int

                    var isFirstRead = true
                    while (!isCancelled) {
                        bytesRead = input.read(buffer)
                        if (bytesRead <= 0) break
                        totalRead += bytesRead

                        if (isFirstRead && bytesRead >= 4) {
                            isFirstRead = false
                            val sample0 = java.nio.ByteBuffer.wrap(buffer, 0, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short
                            val sample1 = java.nio.ByteBuffer.wrap(buffer, 2, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short
                            android.util.Log.i("VoskEngine", "First audio samples: s0=$sample0, s1=$sample1 (0 means mic is muted/silence)")
                        }

                        if (totalRead % 32000L < bytesRead) {
                            android.util.Log.i("VoskEngine", "pipe read totalRead=$totalRead")
                        }

                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            val text = parseJsonText(recognizer.result, "text")
                            if (text.isNotBlank()) {
                                android.util.Log.i("VoskEngine", "vosk acceptWaveForm text=$text")
                                callback.onPartial(text)
                            }
                        } else {
                            val partialText = parseJsonText(recognizer.partialResult, "partial")
                            if (partialText.isNotBlank()) {
                                android.util.Log.i("VoskEngine", "vosk partial=$partialText")
                                callback.onPartial(partialText)
                            }
                        }
                    }

                    if (!isCancelled) {
                        val finalText = parseJsonText(recognizer.result, "text")
                        val finalAlt = parseJsonText(recognizer.finalResult, "text")
                        val resultText = if (finalText.isNotBlank()) finalText else finalAlt
                        android.util.Log.i("VoskEngine", "vosk final=$resultText")
                        callback.onFinal(resultText)
                    }
                    callback.onSessionEnded()
                }
            } catch (e: Exception) {
                android.util.Log.e("VoskEngine", "Vosk engine error", e)
                if (!isCancelled) {
                    callback.onError(VoiceConstants.VOICE_ERROR_UNKNOWN, e.message ?: "Vosk engine error")
                }
            } finally {
                android.util.Log.i("VoskEngine", "Vosk session ended. Total read: $totalRead bytes")
                try {
                    recognizer?.close()
                } catch (_: Exception) {}
                activePfd = null
            }
        }
    }

    fun cancelSession() {
        isCancelled = true
        try {
            activePfd?.close()
        } catch (_: Exception) {}
        activePfd = null
    }

    fun release() {
        cancelSession()
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
    }

    fun destroy() {
        release()
        audioExecutor.shutdownNow()
    }

    private fun parseJsonText(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(jsonStr)
            json.optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.IVoiceEngine
import com.leanbitlab.leantype.voice.ModelImportRequest
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceEngineInfo
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import com.leanbitlab.leantype.voice.offline.engine.HybridEngine
import com.leanbitlab.leantype.voice.offline.engine.VoskEngine
import com.leanbitlab.leantype.voice.offline.engine.WhisperEngine
import com.leanbitlab.leantype.voice.offline.model.ModelManager

class VoiceEngineService : Service() {

    private lateinit var modelManager: ModelManager
    private lateinit var voskEngine: VoskEngine
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var hybridEngine: HybridEngine
    @Volatile private var isSessionActive = false

    private val modelExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ModelImportThread").apply { isDaemon = true }
    }

    private val binder = object : IVoiceEngine.Stub() {

        override fun getInfo(): VoiceEngineInfo {
            return VoiceEngineInfo(
                contractVersion = VoiceConstants.VOICE_CONTRACT_VERSION,
                pluginId = "com.leanbitlab.leantype.voice.offline",
                displayName = "LeanType Voice Plugin",
                supportsVosk = true,
                supportsWhisper = true,
                supportsHybrid = true
            )
        }

        override fun getModelState(engineType: String?): ModelState {
            val type = engineType ?: VoiceConstants.ENGINE_VOSK
            return modelManager.getModelState(type)
        }

        override fun importModel(request: ModelImportRequest?) {
            if (request != null) {
                modelExecutor.execute {
                    try {
                        modelManager.importModelSafely(request)
                    } catch (e: Exception) {
                        Log.e("VoiceEngineService", "Background import failed", e)
                    }
                }
            }
        }

        override fun unloadModel(engineType: String?) {
            val type = engineType ?: VoiceConstants.ENGINE_VOSK
            if (type == VoiceConstants.ENGINE_VOSK) {
                voskEngine.release()
            } else if (type == VoiceConstants.ENGINE_WHISPER) {
                whisperEngine.releaseContext()
            }
        }

        override fun deleteModel(engineType: String?) {
            val type = engineType ?: VoiceConstants.ENGINE_VOSK
            unloadModel(type)
            modelManager.deleteModel(type)
        }

        override fun startSession(
            config: VoiceSessionConfig?,
            audioInput: ParcelFileDescriptor?,
            callback: IVoiceCallback?
        ) {
            if (audioInput == null || callback == null) return

            if (isSessionActive) {
                voskEngine.cancelSession()
                whisperEngine.cancelSession()
                hybridEngine.cancelSession()
                isSessionActive = false
            }

            val mode = config?.mode ?: VoiceConstants.MODE_FAST
            Log.i(TAG, "Starting session with mode: $mode (active=$isSessionActive)")

            val wrappedCallback = object : IVoiceCallback.Stub() {
                override fun onSessionStarted() {
                    callback.onSessionStarted()
                }

                override fun onPartial(partialText: String?) {
                    callback.onPartial(partialText)
                }

                override fun onFinal(finalText: String?) {
                    isSessionActive = false
                    callback.onFinal(finalText)
                }

                override fun onError(code: Int, message: String?) {
                    isSessionActive = false
                    callback.onError(code, message)
                }

                override fun onSessionEnded() {
                    isSessionActive = false
                    callback.onSessionEnded()
                }
            }

            if (mode == VoiceConstants.MODE_ACCURATE) {
                val whisperModelFile = modelManager.getModelDir(VoiceConstants.ENGINE_WHISPER)
                if (!modelManager.isModelReady(VoiceConstants.ENGINE_WHISPER)) {
                    try { audioInput.close() } catch (_: Exception) {}
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Whisper model not ready")
                    return
                }

                if (!whisperEngine.loadModel(whisperModelFile)) {
                    try { audioInput.close() } catch (_: Exception) {}
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to initialize Whisper model")
                    return
                }

                isSessionActive = true
                whisperEngine.startSession(audioInput, wrappedCallback, config)
            } else if (mode == VoiceConstants.MODE_HYBRID) {
                val voskModelDir = modelManager.getModelDir(VoiceConstants.ENGINE_VOSK)
                val whisperModelFile = modelManager.getModelDir(VoiceConstants.ENGINE_WHISPER)

                if (!modelManager.isModelReady(VoiceConstants.ENGINE_VOSK) ||
                    !modelManager.isModelReady(VoiceConstants.ENGINE_WHISPER)) {
                    try { audioInput.close() } catch (_: Exception) {}
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Vosk and Whisper models required for Hybrid mode")
                    return
                }

                if (!voskEngine.loadModel(voskModelDir) || !whisperEngine.loadModel(whisperModelFile)) {
                    try { audioInput.close() } catch (_: Exception) {}
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to initialize models for Hybrid mode")
                    return
                }

                isSessionActive = true
                hybridEngine.startSession(audioInput, wrappedCallback, config)
            } else {
                // MODE_FAST
                val voskModelDir = modelManager.getModelDir(VoiceConstants.ENGINE_VOSK)
                if (!modelManager.isModelReady(VoiceConstants.ENGINE_VOSK)) {
                    try { audioInput.close() } catch (_: Exception) {}
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Vosk model not ready")
                    return
                }

                if (!voskEngine.loadModel(voskModelDir)) {
                    try { audioInput.close() } catch (_: Exception) {}
                    callback.onError(VoiceConstants.VOICE_ERROR_MODEL_INVALID, "Failed to initialize Vosk model")
                    return
                }

                isSessionActive = true
                voskEngine.startSession(audioInput, wrappedCallback)
            }
        }

        override fun stopSession() {
            // Graceful stop: Host closing pipe sends EOF, triggering final flush in engines
            isSessionActive = false
        }

        override fun cancelSession() {
            voskEngine.cancelSession()
            whisperEngine.cancelSession()
            hybridEngine.cancelSession()
            isSessionActive = false
        }

        override fun release() {
            voskEngine.release()
            whisperEngine.releaseContext()
            hybridEngine.release()
            isSessionActive = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        whisperEngine = WhisperEngine()
        voskEngine = VoskEngine(applicationContext)
        hybridEngine = HybridEngine(voskEngine, whisperEngine)
        modelManager = ModelManager(applicationContext) { engineType ->
            if (engineType == VoiceConstants.ENGINE_WHISPER) {
                whisperEngine.releaseContext()
            } else if (engineType == VoiceConstants.ENGINE_VOSK) {
                voskEngine.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        hybridEngine.release()
        voskEngine.destroy()
        whisperEngine.releaseContext()
    }

    companion object {
        private const val TAG = "VoiceEngineService"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.leanbitlab.leantype.voice.IVoiceCallback
import com.leanbitlab.leantype.voice.IVoiceEngine
import com.leanbitlab.leantype.voice.ModelImportRequest
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.VoiceEngineInfo
import com.leanbitlab.leantype.voice.VoiceSessionConfig
import com.leanbitlab.leantype.voice.offline.engine.VoskEngine
import com.leanbitlab.leantype.voice.offline.model.ModelManager

class VoiceEngineService : Service() {

    private lateinit var modelManager: ModelManager
    private lateinit var voskEngine: VoskEngine

    @Volatile
    private var isSessionActive = false

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
                modelManager.importModelSafely(request)
            }
        }

        override fun unloadModel(engineType: String?) {
            val type = engineType ?: VoiceConstants.ENGINE_VOSK
            if (type == VoiceConstants.ENGINE_VOSK) {
                voskEngine.release()
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
                isSessionActive = false
            }

            val voskModelDir = modelManager.getModelDir(VoiceConstants.ENGINE_VOSK)

            if (!modelManager.isModelReady(VoiceConstants.ENGINE_VOSK)) {
                try { audioInput.close() } catch (_: Exception) {}
                callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Vosk model not ready")
                return
            }

            if (!voskEngine.loadModel(voskModelDir)) {
                try { audioInput.close() } catch (_: Exception) {}
                callback.onError(VoiceConstants.VOICE_ERROR_MODEL_MISSING, "Failed to initialize Vosk model")
                return
            }

            isSessionActive = true

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

            voskEngine.startSession(audioInput, wrappedCallback)
        }

        override fun stopSession() {
            voskEngine.cancelSession()
            isSessionActive = false
        }

        override fun cancelSession() {
            voskEngine.cancelSession()
            isSessionActive = false
        }

        override fun release() {
            voskEngine.release()
            isSessionActive = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(applicationContext)
        voskEngine = VoskEngine(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        voskEngine.destroy()
    }
}

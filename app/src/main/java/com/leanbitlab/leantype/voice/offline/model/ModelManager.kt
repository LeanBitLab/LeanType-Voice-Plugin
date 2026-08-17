// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.model

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.voice.ModelImportRequest
import com.leanbitlab.leantype.voice.ModelState
import com.leanbitlab.leantype.voice.VoiceConstants
import com.leanbitlab.leantype.voice.offline.engine.WhisperNative
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class ModelManager(
    private val context: Context,
    private val onPreDeleteModel: ((engineType: String) -> Unit)? = null
) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun getModelState(engineType: String): ModelState {
        val targetFile = File(modelsDir, engineType)
        if (!targetFile.exists()) {
            return ModelState(engineType, ModelState.STATE_MISSING, "Model not imported")
        }

        return if (engineType == VoiceConstants.ENGINE_WHISPER) {
            if (isWhisperHeaderValid(targetFile)) {
                ModelState(engineType, ModelState.STATE_READY, "Model loaded")
            } else {
                ModelState(engineType, ModelState.STATE_ERROR, "Not a valid Whisper ASR model")
            }
        } else {
            ModelState(engineType, ModelState.STATE_MISSING, "Unsupported engine")
        }
    }

    fun isModelReady(engineType: String): Boolean {
        val targetFile = File(modelsDir, engineType)
        if (!targetFile.exists()) return false

        return if (engineType == VoiceConstants.ENGINE_WHISPER) {
            isWhisperHeaderValid(targetFile)
        } else {
            false
        }
    }

    fun getModelDir(engineType: String): File {
        return File(modelsDir, engineType)
    }

    fun importModelSafely(request: ModelImportRequest): Boolean {
        val targetEngine = request.engineType
        if (targetEngine != VoiceConstants.ENGINE_WHISPER) {
            Log.e(TAG, "Unsupported engine type for import: $targetEngine")
            return false
        }

        val tmpFile = File(modelsDir, "${targetEngine}_${System.currentTimeMillis()}.tmp")

        try {
            Log.i(TAG, "Starting Whisper model import, size: ${request.sizeBytes} bytes")
            ParcelFileDescriptor.AutoCloseInputStream(request.file).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }

            val sha256 = request.sha256
            if (sha256 != null && !verifySha256(tmpFile, sha256)) {
                Log.e(TAG, "SHA256 verification failed for $targetEngine")
                tmpFile.delete()
                return false
            }

            // Release any in-flight context before overwriting model file
            onPreDeleteModel?.invoke(targetEngine)

            if (!validateWhisperModelOnImport(tmpFile)) {
                Log.e(TAG, "Imported file is not a valid Whisper model")
                tmpFile.delete()
                return false
            }

            val finalFile = File(modelsDir, targetEngine)
            finalFile.deleteRecursively()
            val success = tmpFile.renameTo(finalFile)
            if (!success) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            Log.i(TAG, "Whisper model installed successfully: ${finalFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import Whisper model", e)
            tmpFile.delete()
            return false
        }
    }

    fun deleteModel(engineType: String): Boolean {
        onPreDeleteModel?.invoke(engineType)
        val targetDir = File(modelsDir, engineType)
        return targetDir.deleteRecursively()
    }

    private fun isWhisperHeaderValid(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false

        // Fast magic header check (no JNI, no full model load)
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                if (read < 4) return false
                val isGguf = header[0] == 0x47.toByte() && header[1] == 0x47.toByte() &&
                        header[2] == 0x55.toByte() && header[3] == 0x46.toByte()
                val magic = String(header, Charsets.US_ASCII)
                val isGgml = magic == "ggml" || magic == "lmgg" || magic == "ggmf" || file.name.startsWith("ggml-")
                isGguf || isGgml
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking whisper file header", e)
            false
        }
    }

    private fun validateWhisperModelOnImport(file: File): Boolean {
        if (!isWhisperHeaderValid(file)) {
            Log.w(TAG, "File header validation failed for: ${file.name}")
            return false
        }

        // Run full native test load once upon import
        if (WhisperNative.isNativeLoaded()) {
            return try {
                val ptr = WhisperNative.init(file.absolutePath)
                if (ptr != 0L) {
                    WhisperNative.free(ptr)
                    true
                } else {
                    Log.w(TAG, "WhisperNative.init test load failed for: ${file.name}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during WhisperNative import validation", e)
                false
            }
        }
        return true
    }

    private fun verifySha256(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashBytes = digest.digest()
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            hexString.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ModelManager"
    }
}

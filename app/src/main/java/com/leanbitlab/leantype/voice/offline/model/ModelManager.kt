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
import java.util.zip.ZipInputStream

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

        return if (engineType == VoiceConstants.ENGINE_VOSK) {
            if (targetFile.isDirectory && (targetFile.listFiles()?.any { it.name == "am" || it.name == "conf" } == true)) {
                ModelState(engineType, ModelState.STATE_READY, "Model loaded")
            } else {
                ModelState(engineType, ModelState.STATE_ERROR, "Invalid Vosk model directory")
            }
        } else if (engineType == VoiceConstants.ENGINE_WHISPER) {
            if (isValidWhisperModel(targetFile)) {
                ModelState(engineType, ModelState.STATE_READY, "Model loaded")
            } else {
                ModelState(engineType, ModelState.STATE_ERROR, "Not a valid Whisper ASR model")
            }
        } else {
            ModelState(engineType, ModelState.STATE_MISSING, "Unknown engine")
        }
    }

    fun isModelReady(engineType: String): Boolean {
        val targetFile = File(modelsDir, engineType)
        if (!targetFile.exists()) return false

        return if (engineType == VoiceConstants.ENGINE_VOSK) {
            targetFile.isDirectory && (targetFile.listFiles()?.any { it.name == "am" || it.name == "conf" } == true)
        } else if (engineType == VoiceConstants.ENGINE_WHISPER) {
            isValidWhisperModel(targetFile)
        } else {
            false
        }
    }

    fun getModelDir(engineType: String): File {
        return File(modelsDir, engineType)
    }

    fun importModelSafely(request: ModelImportRequest): Boolean {
        val targetEngine = request.engineType
        val tmpFile = File(modelsDir, "${targetEngine}_${System.currentTimeMillis()}.tmp")

        try {
            Log.i(TAG, "Starting model import for $targetEngine, size: ${request.sizeBytes} bytes")
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

            return if (targetEngine == VoiceConstants.ENGINE_VOSK) {
                extractVoskModel(tmpFile, modelsDir)
            } else if (targetEngine == VoiceConstants.ENGINE_WHISPER) {
                if (!isValidWhisperModel(tmpFile)) {
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
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model for $targetEngine", e)
            tmpFile.delete()
            return false
        }
    }

    fun extractVoskModel(tmpZip: File, targetDir: File): Boolean {
        val extractTmp = File(targetDir, "extract_tmp_${System.currentTimeMillis()}")
        try {
            extractTmp.mkdirs()
            safeUnzip(tmpZip, extractTmp)
            val root = findVoskRoot(extractTmp) ?: return false
            val finalDir = File(targetDir, VoiceConstants.ENGINE_VOSK)
            finalDir.deleteRecursively()

            if (!root.renameTo(finalDir)) {
                root.copyRecursively(finalDir, overwrite = true)
                root.deleteRecursively()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting Vosk model", e)
            return false
        } finally {
            extractTmp.deleteRecursively()
            tmpZip.delete()
        }
    }

    fun deleteModel(engineType: String): Boolean {
        onPreDeleteModel?.invoke(engineType)
        val targetDir = File(modelsDir, engineType)
        return targetDir.deleteRecursively()
    }

    private fun isValidWhisperModel(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false

        // Check magic header: GGUF ("GGUF" / 0x46554747) or GGML ("ggml" / 0x67676d6c)
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                if (read < 4) return false
                val magic = String(header, Charsets.US_ASCII)
                val isGguf = magic == "GGUF"
                val isGgml = magic == "ggml" || magic == "lmgg" || magic == "ggmf"
                if (!isGguf && !isGgml) {
                    Log.w(TAG, "File magic '$magic' does not match GGUF/GGML format")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking file magic", e)
            return false
        }

        // Test load via native bridge if loaded
        if (WhisperNative.isNativeLoaded()) {
            return try {
                val ptr = WhisperNative.init(file.absolutePath)
                if (ptr != 0L) {
                    WhisperNative.free(ptr)
                    true
                } else {
                    Log.w(TAG, "WhisperNative.init test failed for: ${file.name}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during WhisperNative validation", e)
                false
            }
        }

        return true
    }

    private fun safeUnzip(zipFile: File, targetDir: File) {
        val canonicalDestDirPath = targetDir.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                val canonicalNewFilePath = newFile.canonicalPath

                if (!canonicalNewFilePath.startsWith(canonicalDestDirPath + File.separator) &&
                    canonicalNewFilePath != canonicalDestDirPath
                ) {
                    throw SecurityException("Zip entry is outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findVoskRoot(dir: File): File? {
        if (dir.isDirectory && dir.listFiles()?.any { it.name == "am" || it.name == "conf" } == true) {
            return dir
        }
        val subdirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (subdir in subdirs) {
            val root = findVoskRoot(subdir)
            if (root != null) return root
        }
        return null
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

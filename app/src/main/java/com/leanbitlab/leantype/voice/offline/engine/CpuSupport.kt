// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import android.os.Build
import android.util.Log
import com.leanbitlab.leantype.voice.offline.BuildConfig
import java.io.File

/**
 * The fast arm64 build is compiled for ARMv8.2 with dot-product and FP16 instructions (see CMakeLists.txt). On a CPU
 * without them (e.g. Cortex-A53, A57, A72 or A73 only) the first use of the library ends in an illegal instruction
 * and kills the plugin process. This checks the CPU before the library is loaded, so that a clear message can be
 * shown, and the compat build can be recommended, instead.
 */
object CpuSupport {
    private const val TAG = "CpuSupport"

    /** Kernel names of the features: asimddp is the dot-product instruction, asimdhp the FP16 vector arithmetic */
    private val requiredFeatures = listOf("asimddp", "asimdhp")

    const val UNSUPPORTED_MESSAGE =
        "This device's CPU lacks the ARMv8.2 dot-product and FP16 instructions that this build of the Voice Plugin " +
            "needs. Install the compat build (voice_plugin-arm64-v8a-compat.apk) from the plugin's GitHub releases."

    /** The features listed in the "Features" lines of /proc/cpuinfo */
    internal fun parseFeatures(cpuInfo: String): Set<String> = cpuInfo.lineSequence()
        .filter { it.startsWith("Features") }
        .flatMap { it.substringAfter(':', "").trim().split(Regex("\\s+")).asSequence() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** The required features that [features] does not contain */
    internal fun missingFeatures(features: Set<String>): List<String> = requiredFeatures.filter { it !in features }

    /**
     * Whether the library of this build can run here. True if the build does not need the instructions, the device
     * does not run the arm64 library, or the CPU features cannot be read (nothing is blocked on a guess).
     */
    fun isSupported(
        needsArmv82: Boolean = BuildConfig.ARM64_NEEDS_ARMV82,
        primaryAbi: String? = Build.SUPPORTED_ABIS.firstOrNull(),
        readCpuInfo: () -> String? = ::readProcCpuInfo,
    ): Boolean {
        if (!needsArmv82 || primaryAbi != "arm64-v8a") return true
        val info = readCpuInfo()
        if (info == null) {
            Log.w(TAG, "Cannot read /proc/cpuinfo, assuming the CPU is supported")
            return true
        }
        val features = parseFeatures(info)
        if (features.isEmpty()) return true
        val missing = missingFeatures(features)
        if (missing.isNotEmpty()) Log.e(TAG, "CPU lacks required features: $missing")
        return missing.isEmpty()
    }

    private fun readProcCpuInfo(): String? = try {
        File("/proc/cpuinfo").readText()
    } catch (e: Exception) {
        null
    }
}

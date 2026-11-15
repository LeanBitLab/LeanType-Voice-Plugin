// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuSupportTest {
    // Features lines as in /proc/cpuinfo: Cortex-A53 only (no dot product, no FP16 arithmetic) and Cortex-A55/A75
    private val cortexA53 = """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid
        CPU implementer	: 0x41
    """.trimIndent()
    private val cortexA75 = """
        processor	: 0
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        CPU implementer	: 0x41
        processor	: 1
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
    """.trimIndent()

    @Test fun featuresAreReadFromAllFeatureLines() {
        val features = CpuSupport.parseFeatures(cortexA75)
        assertTrue("asimddp" in features)
        assertTrue("asimdhp" in features)
        assertFalse("asimddp" in CpuSupport.parseFeatures(cortexA53))
    }

    @Test fun missingFeaturesAreListed() {
        assertEquals(listOf("asimddp", "asimdhp"), CpuSupport.missingFeatures(CpuSupport.parseFeatures(cortexA53)))
        assertEquals(emptyList<String>(), CpuSupport.missingFeatures(CpuSupport.parseFeatures(cortexA75)))
    }

    @Test fun oldCpuIsNotSupportedByTheFastArm64Build() {
        assertFalse(CpuSupport.isSupported(needsArmv82 = true, primaryAbi = "arm64-v8a") { cortexA53 })
    }

    @Test fun newCpuIsSupported() {
        assertTrue(CpuSupport.isSupported(needsArmv82 = true, primaryAbi = "arm64-v8a") { cortexA75 })
    }

    @Test fun compatBuildAndOtherAbisAreNeverBlocked() {
        assertTrue(CpuSupport.isSupported(needsArmv82 = false, primaryAbi = "arm64-v8a") { cortexA53 })
        assertTrue(CpuSupport.isSupported(needsArmv82 = true, primaryAbi = "armeabi-v7a") { cortexA53 })
        assertTrue(CpuSupport.isSupported(needsArmv82 = true, primaryAbi = "x86_64") { "" })
    }

    @Test fun unreadableOrEmptyCpuInfoDoesNotBlock() {
        assertTrue(CpuSupport.isSupported(needsArmv82 = true, primaryAbi = "arm64-v8a") { null })
        assertTrue(CpuSupport.isSupported(needsArmv82 = true, primaryAbi = "arm64-v8a") { "processor : 0" })
    }
}

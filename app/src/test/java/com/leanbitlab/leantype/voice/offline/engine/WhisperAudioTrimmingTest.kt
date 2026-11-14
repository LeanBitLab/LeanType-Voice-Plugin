// SPDX-License-Identifier: GPL-3.0-only
package com.leanbitlab.leantype.voice.offline.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WhisperAudioTrimmingTest {

    private val engine = WhisperEngine()
    private val sampleRate = 16000

    @Test
    fun testEmptyPcmReturnsEmpty() {
        val result = engine.trimLeadingAndTrailingSilence(ShortArray(0), sampleRate)
        assertEquals(0, result.size)
    }

    @Test
    fun testPureSilenceReturnsEmpty() {
        // 2 seconds of pure zeros
        val silence = ShortArray(sampleRate * 2) { 0 }
        val result = engine.trimLeadingAndTrailingSilence(silence, sampleRate)
        assertEquals(0, result.size)
    }

    @Test
    fun testLowAmbientNoiseDiscardedAsSilence() {
        // 2 seconds of low amplitude noise (RMS ~0.0003, well below SILENCE_RMS 0.001)
        val lowNoise = ShortArray(sampleRate * 2) {
            ((it % 10) - 5).toShort()
        }
        val result = engine.trimLeadingAndTrailingSilence(lowNoise, sampleRate)
        assertEquals(0, result.size)
    }

    @Test
    fun testLeadingAndTrailingSilenceTrimmedWithPadding() {
        // Total duration: 5 seconds
        // 0.0s - 1.5s: silence (24,000 samples)
        // 1.5s - 2.5s: synthetic speech tone (16,000 samples, ~500Hz sine wave, amplitude 10,000)
        // 2.5s - 5.0s: silence (40,000 samples, trailing silence timeout)
        val totalSamples = sampleRate * 5
        val pcm = ShortArray(totalSamples)

        val speechStart = (1.5 * sampleRate).toInt()
        val speechEnd = (2.5 * sampleRate).toInt()

        for (i in speechStart until speechEnd) {
            val angle = 2.0 * Math.PI * 500.0 * (i - speechStart) / sampleRate
            pcm[i] = (sin(angle) * 10000.0).toInt().toShort()
        }

        val trimmed = engine.trimLeadingAndTrailingSilence(pcm, sampleRate)

        // Trimmed audio should be much shorter than 5 seconds
        assertTrue("Trimmed audio must be significantly shorter than 5s", trimmed.size < totalSamples)

        // Expected speech duration = 1.0s (16,000 samples)
        // Expected padding = 300ms before + 300ms after = 600ms (9,600 samples)
        // Total expected size around 25,600 samples (~1.6s)
        val expectedSize = (speechEnd - speechStart) + (sampleRate * 0.60).toInt()
        val difference = kotlin.math.abs(trimmed.size - expectedSize)
        assertTrue("Trimmed size (${trimmed.size}) should be close to expected ($expectedSize), diff=$difference", difference < 2000)
    }

    @Test
    fun testInternalPausesPreserved() {
        // Two speech bursts separated by a 1.0s pause
        // 0.0s - 1.0s: silence
        // 1.0s - 1.5s: word 1
        // 1.5s - 2.5s: pause between words
        // 2.5s - 3.0s: word 2
        // 3.0s - 5.0s: silence
        val totalSamples = sampleRate * 5
        val pcm = ShortArray(totalSamples)

        for (i in (1.0 * sampleRate).toInt() until (1.5 * sampleRate).toInt()) {
            val angle = 2.0 * Math.PI * 400.0 * i / sampleRate
            pcm[i] = (sin(angle) * 12000.0).toInt().toShort()
        }
        for (i in (2.5 * sampleRate).toInt() until (3.0 * sampleRate).toInt()) {
            val angle = 2.0 * Math.PI * 600.0 * i / sampleRate
            pcm[i] = (sin(angle) * 12000.0).toInt().toShort()
        }

        val trimmed = engine.trimLeadingAndTrailingSilence(pcm, sampleRate)

        // Expected start: ~1.0s - 0.3s = 0.7s
        // Expected end: ~3.0s + 0.3s = 3.3s
        // Expected duration: ~2.6s (41,600 samples)
        val expectedMinDuration = (2.4 * sampleRate).toInt()
        val expectedMaxDuration = (2.8 * sampleRate).toInt()
        assertTrue("Internal pauses must be retained within speech boundaries (size=${trimmed.size})",
            trimmed.size in expectedMinDuration..expectedMaxDuration)
    }
}

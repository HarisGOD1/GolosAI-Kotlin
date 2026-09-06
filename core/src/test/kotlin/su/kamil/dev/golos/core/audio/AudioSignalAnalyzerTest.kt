package su.kamil.dev.golos.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.AudioChunk
import kotlin.math.abs
import kotlin.math.sin

class AudioSignalAnalyzerTest {
    @Test
    fun `test rmsToDb conversion with boundary values`() {
        assertEquals(-96.0f, AudioSignalAnalyzer.rmsToDb(0.0f))
        assertEquals(-96.0f, AudioSignalAnalyzer.rmsToDb(0.000001f))
        assertEquals(0.0f, AudioSignalAnalyzer.rmsToDb(1.0f))
        // 0.5 RMS is approx -6.02 dB
        val dbHalf = AudioSignalAnalyzer.rmsToDb(0.5f)
        assertTrue(dbHalf in -6.1f..-5.9f, "Expected ~ -6.0 dB, got $dbHalf")
    }

    @Test
    fun `test analyzeSignal on silent audio detects silence without clipping`() {
        val silentBytes = ByteArray(3200) { 0 }
        val chunk = AudioChunk(silentBytes)
        val stats = AudioSignalAnalyzer.analyzeSignal(chunk)

        assertEquals(0.0f, stats.rms)
        assertEquals(-96.0f, stats.rmsDb)
        assertEquals(0.0f, stats.peak)
        assertEquals(-96.0f, stats.peakDb)
        assertFalse(stats.isClipping)
    }

    @Test
    fun `test analyzeSignal on speech-like sine wave calculates realistic dB`() {
        // 16000 Hz, 1000 samples of 440Hz sine wave with 0.5 amplitude
        val samples = ByteArray(2000)
        for (i in 0 until 1000) {
            val s = (sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 16384).toInt()
            samples[i * 2] = (s and 0xFF).toByte()
            samples[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        val chunk = AudioChunk(samples)
        val stats = AudioSignalAnalyzer.analyzeSignal(chunk)

        assertTrue(stats.rms > 0.3f && stats.rms < 0.4f, "RMS should be ~0.35, got ${stats.rms}")
        assertTrue(stats.rmsDb in -10.0f..-8.0f, "RMS dB should be ~ -9 dB, got ${stats.rmsDb}")
        assertFalse(stats.isClipping)
    }

    @Test
    fun `test analyzeSignal detects clipping on overloaded audio - Criterion E-07`() {
        val clippedBytes = ByteArray(100)
        for (i in 0 until 50) {
            val sampleVal = 32767
            clippedBytes[i * 2] = (sampleVal and 0xFF).toByte()
            clippedBytes[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }
        val chunk = AudioChunk(clippedBytes)
        val stats = AudioSignalAnalyzer.analyzeSignal(chunk)

        assertTrue(stats.isClipping, "Clipped signal must be detected")
        assertTrue(stats.peak >= 0.99f)
        assertTrue(stats.peakDb >= -0.1f)
    }

    @Test
    fun `test applyGainAndSoftClip scales quiet signals and softly compresses peaks - Criterion C-09 and E-07`() {
        // Quiet signal amplified 2.0x
        val quietBytes = ByteArray(100)
        for (i in 0 until 50) {
            val sampleVal = 5000
            quietBytes[i * 2] = (sampleVal and 0xFF).toByte()
            quietBytes[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }
        val quietChunk = AudioChunk(quietBytes)
        val amplified = AudioSignalAnalyzer.applyGainAndSoftClip(quietChunk, gain = 2.0f)
        val amplifiedFloats = amplified.toNormalizedFloatArray()

        assertTrue(amplifiedFloats[0] > 0.28f, "Gain must scale up samples")

        // Overloaded signal soft-clipped to avoid integer overflow
        val overloadedBytes = ByteArray(100)
        for (i in 0 until 50) {
            val sampleVal = 32767
            overloadedBytes[i * 2] = (sampleVal and 0xFF).toByte()
            overloadedBytes[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }
        val overloadedChunk = AudioChunk(overloadedBytes)
        val processed = AudioSignalAnalyzer.applyGainAndSoftClip(overloadedChunk, gain = 1.5f)
        val processedFloats = processed.toNormalizedFloatArray()

        for (sample in processedFloats) {
            assertTrue(abs(sample) <= 1.0f, "Soft-clipped samples must not exceed 1.0")
        }
    }
}

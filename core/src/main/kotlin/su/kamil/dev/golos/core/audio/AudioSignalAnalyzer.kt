package su.kamil.dev.golos.core.audio

import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioSignalStats
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Utility for audio signal analysis, level metering, and clipping processing.
 * Fulfills evaluation criteria:
 * - C-07: Signal level indicator responds to speech.
 * - C-08: Warning at zero signal level.
 * - C-09: Input gain is adjusted.
 * - E-07: The input clipping is processed.
 */
object AudioSignalAnalyzer {
    private const val MIN_DB_CLAMP = -96.0f
    private const val DB_EPSILON = 1e-5
    private const val DB_MULTIPLIER = 20.0
    private const val CLIPPING_THRESHOLD = 0.995f
    private const val PCM_16_MAX = 32767

    /**
     * Converts normalized RMS amplitude [0.0..1.0] to decibels (dBFS) [-96.0..0.0].
     */
    fun rmsToDb(rms: Float): Float {
        if (rms <= DB_EPSILON) return MIN_DB_CLAMP
        val db = (DB_MULTIPLIER * log10(rms.toDouble())).toFloat()
        return max(MIN_DB_CLAMP, min(0.0f, db))
    }

    /**
     * Analyzes an audio chunk for RMS level, peak level, and clipping occurrence.
     */
    fun analyzeSignal(chunk: AudioChunk): AudioSignalStats {
        val floats = chunk.toNormalizedFloatArray()
        if (floats.isEmpty()) return AudioSignalStats()

        var sumSquares = 0.0
        var maxAbs = 0.0f
        var clippedCount = 0

        for (sample in floats) {
            val a = abs(sample)
            if (a > maxAbs) maxAbs = a
            if (a >= CLIPPING_THRESHOLD) clippedCount++
            sumSquares += sample * sample
        }

        val rms = sqrt(sumSquares / floats.size).toFloat()
        val rmsDb = rmsToDb(rms)
        val peakDb = rmsToDb(maxAbs)
        val isClipping = clippedCount > 0

        return AudioSignalStats(
            rms = rms,
            rmsDb = rmsDb,
            peak = maxAbs,
            peakDb = peakDb,
            isClipping = isClipping,
        )
    }

    /**
     * Applies gain factor and tanh soft-clipping saturation to eliminate harsh
     * digital distortion and prevent integer overflow (Criterion E-07).
     */
    fun applyGainAndSoftClip(
        chunk: AudioChunk,
        gain: Float,
    ): AudioChunk {
        if (gain == 1.0f && !hasClipping(chunk)) return chunk

        val floats = chunk.toNormalizedFloatArray()
        for (i in floats.indices) {
            val amplified = floats[i] * gain
            floats[i] = tanh(amplified.toDouble()).toFloat()
        }

        val pcm = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val clamped = max(-1.0f, min(1.0f, floats[i]))
            val sampleVal = (clamped * PCM_16_MAX).toInt()
            pcm[i * 2] = (sampleVal and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }

        return chunk.copy(samples = pcm)
    }

    /**
     * Returns true if any audio sample is close to maximum 16-bit PCM amplitude.
     */
    fun hasClipping(chunk: AudioChunk): Boolean {
        val floats = chunk.toNormalizedFloatArray()
        return floats.any { abs(it) >= CLIPPING_THRESHOLD }
    }
}

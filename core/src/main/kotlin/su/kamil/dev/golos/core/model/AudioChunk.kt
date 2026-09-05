package su.kamil.dev.golos.core.model

/**
 * Represents raw PCM audio data captured or preprocessed.
 */
data class AudioChunk(
    val samples: ByteArray,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        return samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            bitsPerSample == other.bitsPerSample
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        return result
    }

    /**
     * Converts 16-bit little-endian PCM samples into normalized 32-bit floats in [-1.0, 1.0].
     * This is the standard input representation expected by Whisper models.
     */
    fun toNormalizedFloatArray(): FloatArray {
        if (bitsPerSample != 16) {
            error("Only 16-bit PCM conversion supported currently, got $bitsPerSample-bit")
        }
        val sampleCount = samples.size / 2
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val byte1 = samples[i * 2].toInt() and 0xFF
            val byte2 = samples[i * 2 + 1].toInt()
            val shortVal = (byte2 shl 8) or byte1
            floats[i] = shortVal / 32768.0f
        }
        return floats
    }

    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8)
            return if (bytesPerSecond > 0) (samples.size.toLong() * 1000L) / bytesPerSecond else 0L
        }
}

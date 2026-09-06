package su.kamil.dev.golos.voice.audio

import su.kamil.dev.golos.core.audio.AudioSignalAnalyzer
import su.kamil.dev.golos.core.model.AudioChunk
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Preprocesses raw audio chunks for speech recognition engines:
 * - Resampling to target rate (16,000 Hz)
 * - Channel mixing (stereo to mono)
 * - Energy/RMS calculation and volume normalization
 * - WAV header packaging
 */
object AudioPreprocessor {
    const val TARGET_SAMPLE_RATE = 16000
    const val TARGET_CHANNELS = 1
    const val TARGET_BITS_PER_SAMPLE = 16

    /**
     * Resamples and mixes channels down to 16kHz 16-bit mono PCM.
     */
    fun toWhisperStandard(chunk: AudioChunk): AudioChunk {
        if (chunk.sampleRate == TARGET_SAMPLE_RATE &&
            chunk.channels == TARGET_CHANNELS &&
            chunk.bitsPerSample == TARGET_BITS_PER_SAMPLE
        ) {
            return chunk
        }

        // Step 1: Decode to 32-bit floats
        val monoFloats = decodeToMonoFloats(chunk)

        // Step 2: Resample to 16000 Hz using linear interpolation
        val resampledFloats =
            if (chunk.sampleRate != TARGET_SAMPLE_RATE) {
                resampleLinear(monoFloats, chunk.sampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoFloats
            }

        // Step 3: Encode back to 16-bit little-endian PCM
        val pcmBytes = encodeFloatsTo16BitPcm(resampledFloats)

        return AudioChunk(
            samples = pcmBytes,
            sampleRate = TARGET_SAMPLE_RATE,
            channels = TARGET_CHANNELS,
            bitsPerSample = TARGET_BITS_PER_SAMPLE,
            timestampMs = chunk.timestampMs,
        )
    }

    /**
     * Calculates the Root Mean Square (RMS) energy of the audio samples.
     * Value between 0.0 (silent) and 1.0 (maximum amplitude).
     */
    fun calculateRms(chunk: AudioChunk): Float = AudioSignalAnalyzer.analyzeSignal(chunk).rms

    fun rmsToDb(rms: Float): Float = AudioSignalAnalyzer.rmsToDb(rms)

    fun analyzeSignal(chunk: AudioChunk): su.kamil.dev.golos.core.model.AudioSignalStats = AudioSignalAnalyzer.analyzeSignal(chunk)

    fun applyGainAndSoftClip(
        chunk: AudioChunk,
        gain: Float,
    ): AudioChunk = AudioSignalAnalyzer.applyGainAndSoftClip(chunk, gain)

    fun hasClipping(chunk: AudioChunk): Boolean = AudioSignalAnalyzer.hasClipping(chunk)

    /**
     * Checks if the chunk contains audible speech based on RMS threshold.
     */
    fun isAudible(
        chunk: AudioChunk,
        threshold: Float = 0.01f,
    ): Boolean {
        return calculateRms(chunk) >= threshold
    }

    /**
     * Converts raw PCM bytes with format into a valid standalone WAV file byte array.
     */
    fun createWavBytes(chunk: AudioChunk): ByteArray {
        val standard = toWhisperStandard(chunk)
        val pcmData = standard.samples
        val totalDataLen = pcmData.size + 36
        val sampleRate = standard.sampleRate
        val channels = standard.channels
        val byteRate = sampleRate * channels * 2

        val out = ByteArrayOutputStream(pcmData.size + 44)
        // RIFF header
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        out.write(intToByteArray(totalDataLen))
        out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))

        // fmt chunk
        out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        out.write(intToByteArray(16)) // Subchunk1Size (16 for PCM)
        out.write(shortToByteArray(1)) // AudioFormat (1 = PCM)
        out.write(shortToByteArray(channels.toShort()))
        out.write(intToByteArray(sampleRate))
        out.write(intToByteArray(byteRate))
        out.write(shortToByteArray((channels * 2).toShort())) // BlockAlign
        out.write(shortToByteArray(16)) // BitsPerSample

        // data chunk
        out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        out.write(intToByteArray(pcmData.size))
        out.write(pcmData)

        return out.toByteArray()
    }

    private fun decodeToMonoFloats(chunk: AudioChunk): FloatArray {
        val bytes = chunk.samples
        val bytesPerSample = chunk.bitsPerSample / 8
        val totalSamples = bytes.size / (bytesPerSample * chunk.channels)
        val mono = FloatArray(totalSamples)

        for (i in 0 until totalSamples) {
            var channelSum = 0f
            for (ch in 0 until chunk.channels) {
                val index = (i * chunk.channels + ch) * bytesPerSample
                val sampleValue =
                    if (chunk.bitsPerSample == 16) {
                        val low = bytes[index].toInt() and 0xFF
                        val high = bytes[index + 1].toInt()
                        ((high shl 8) or low) / 32768.0f
                    } else {
                        0f
                    }
                channelSum += sampleValue
            }
            mono[i] = channelSum / chunk.channels
        }
        return mono
    }

    private fun resampleLinear(
        input: FloatArray,
        srcRate: Int,
        dstRate: Int,
    ): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstLength = (input.size / ratio).toInt()
        val output = FloatArray(dstLength)

        for (i in 0 until dstLength) {
            val srcIdx = i * ratio
            val index0 = srcIdx.toInt()
            val index1 = min(index0 + 1, input.size - 1)
            val frac = (srcIdx - index0).toFloat()
            output[i] = input[index0] * (1.0f - frac) + input[index1] * frac
        }
        return output
    }

    private fun encodeFloatsTo16BitPcm(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val clamped = max(-1.0f, min(1.0f, floats[i]))
            val sampleVal = (clamped * 32767).toInt()
            bytes[i * 2] = (sampleVal and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun intToByteArray(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )

    private fun shortToByteArray(v: Short): ByteArray =
        byteArrayOf(
            (v.toInt() and 0xFF).toByte(),
            ((v.toInt() ushr 8) and 0xFF).toByte(),
        )

    /**
     * Reads a WAV file and returns its PCM data as an AudioChunk (Criteria N-06, N-07).
     */
    fun readWavFile(file: java.io.File): AudioChunk {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV file '${file.name}' is too short (${bytes.size} bytes)" }
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "File '${file.name}' lacks valid RIFF/WAVE header" }

        var offset = 12
        var channels = 1
        var sampleRate = TARGET_SAMPLE_RATE
        var bitsPerSample = TARGET_BITS_PER_SAMPLE
        var pcmData: ByteArray? = null

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize =
                (bytes[offset + 4].toInt() and 0xFF) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            offset += 8

            if (chunkId == "fmt " && chunkSize >= 16 && offset + 16 <= bytes.size) {
                channels = (bytes[offset + 2].toInt() and 0xFF) or ((bytes[offset + 3].toInt() and 0xFF) shl 8)
                sampleRate = (bytes[offset + 4].toInt() and 0xFF) or
                    ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 7].toInt() and 0xFF) shl 24)
                bitsPerSample = (bytes[offset + 14].toInt() and 0xFF) or ((bytes[offset + 15].toInt() and 0xFF) shl 8)
                offset += chunkSize
            } else if (chunkId == "data") {
                val dataLen = minOf(chunkSize, bytes.size - offset)
                pcmData = bytes.copyOfRange(offset, offset + dataLen)
                break
            } else {
                offset += chunkSize
            }
        }

        requireNotNull(pcmData) { "No 'data' chunk found in WAV file '${file.name}'" }
        return AudioChunk(
            samples = pcmData,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
    }

    /**
     * Resamples and downmixes any WAV file to 16kHz mono 16-bit PCM WAV (Criteria N-06, N-07).
     */
    fun convertWavToStandard(
        inputFile: java.io.File,
        outputFile: java.io.File,
    ): java.io.File {
        val chunk = readWavFile(inputFile)
        val standardChunk = toWhisperStandard(chunk)
        val wavBytes = createWavBytes(standardChunk)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(wavBytes)
        return outputFile
    }
}

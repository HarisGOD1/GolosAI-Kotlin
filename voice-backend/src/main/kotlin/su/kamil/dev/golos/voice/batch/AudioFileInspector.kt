package su.kamil.dev.golos.voice.batch

import su.kamil.dev.golos.voice.audio.AudioPreprocessor
import java.io.File

/**
 * Categorization of audio containers and formats.
 */
enum class AudioFormatCategory {
    WAV,
    MP3,
    OGG,
    FLAC,
    M4A,
    VIDEO,
    UNSUPPORTED,
}

/**
 * Inspection result of an audio file for batch transcription (Criteria N-14, N-15, N-16).
 */
sealed class AudioFileInspection {
    data class Valid(
        val category: AudioFormatCategory,
        val durationMs: Long,
        val sampleRate: Int = 16000,
        val channels: Int = 1,
        val preparedFile: File? = null,
    ) : AudioFileInspection()

    data class Corrupted(val reason: String) : AudioFileInspection()

    data class Empty(val reason: String) : AudioFileInspection()

    data class NoAudioTrack(val reason: String) : AudioFileInspection()

    data class Unsupported(val reason: String) : AudioFileInspection()
}

/**
 * Validates audio file headers, detects formats, and normalizes non-standard files (Criteria N-01..N-08, N-14..N-16).
 */
@Suppress("MagicNumber")
object AudioFileInspector {
    private const val MIN_WAV_HEADER_SIZE = 44
    private const val MS_PER_SEC = 1000L
    private const val ESTIMATED_MP3_KBPS = 128
    private const val BYTES_PER_KB = 1024
    private const val BITS_PER_BYTE = 8

    private val NON_AUDIO_EXTENSIONS =
        setOf(
            "txt", "text", "md", "csv", "json", "xml", "html", "htm",
            "pdf", "doc", "docx", "xls", "xlsx", "zip", "tar", "gz",
            "bin", "exe", "so", "dll", "log", "png", "jpg", "jpeg",
        )

    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mkv", "avi", "mov", "webm", "wmv", "flv")

    fun inspect(file: File): AudioFileInspection {
        val ext = file.extension.lowercase()
        return when {
            !file.exists() -> AudioFileInspection.Corrupted("Audio file '${file.name}' does not exist")
            file.length() == 0L -> AudioFileInspection.Empty("Audio file '${file.name}' is empty (0 bytes)")
            ext in NON_AUDIO_EXTENSIONS ->
                AudioFileInspection.NoAudioTrack("File '${file.name}' contains no detectable audio track")
            ext == "wav" || ext == "wave" -> inspectWav(file)
            ext == "mp3" -> inspectMp3(file)
            ext == "flac" -> inspectFlac(file)
            ext == "ogg" -> inspectOgg(file)
            ext == "m4a" || ext == "aac" -> inspectM4a(file)
            ext in VIDEO_EXTENSIONS -> inspectVideo(file)
            else -> AudioFileInspection.Unsupported("Unsupported audio extension '.${file.extension}'")
        }
    }

    private fun inspectWav(file: File): AudioFileInspection {
        if (file.length() < MIN_WAV_HEADER_SIZE) {
            return AudioFileInspection.Corrupted(
                "Audio file '${file.name}' is corrupted: smaller than minimum WAV header",
            )
        }

        return try {
            val chunk = AudioPreprocessor.readWavFile(file)
            val durationMs =
                (chunk.samples.size.toLong() * MS_PER_SEC) /
                    (chunk.sampleRate * chunk.channels * (chunk.bitsPerSample / BITS_PER_BYTE))

            if (chunk.sampleRate != AudioPreprocessor.TARGET_SAMPLE_RATE ||
                chunk.channels != AudioPreprocessor.TARGET_CHANNELS
            ) {
                val tempPrepared = File.createTempFile("golos_batch_resampled_", ".wav")
                tempPrepared.deleteOnExit()
                AudioPreprocessor.convertWavToStandard(file, tempPrepared)
                AudioFileInspection.Valid(
                    category = AudioFormatCategory.WAV,
                    durationMs = durationMs,
                    sampleRate = chunk.sampleRate,
                    channels = chunk.channels,
                    preparedFile = tempPrepared,
                )
            } else {
                AudioFileInspection.Valid(
                    category = AudioFormatCategory.WAV,
                    durationMs = durationMs,
                    sampleRate = chunk.sampleRate,
                    channels = chunk.channels,
                    preparedFile = file,
                )
            }
        } catch (e: Exception) {
            AudioFileInspection.Corrupted("Audio file '${file.name}' has invalid or corrupted WAV data: ${e.message}")
        }
    }

    private fun inspectMp3(file: File): AudioFileInspection {
        val bytes = ByteArray(minOf(file.length().toInt(), 128))
        file.inputStream().use { it.read(bytes) }

        val hasId3 =
            bytes.size >= 3 && bytes[0] == 'I'.code.toByte() &&
                bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        val hasMpegSync =
            bytes.indices.any { i ->
                i + 1 < bytes.size && (bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0
            }

        if (!hasId3 && !hasMpegSync) {
            return AudioFileInspection.Corrupted(
                "Audio file '${file.name}' is corrupted or contains invalid MP3 sync header",
            )
        }

        // Estimate duration based on file size at 128 kbps (16 KB/sec)
        val estimatedSec = file.length() / (ESTIMATED_MP3_KBPS * BYTES_PER_KB / BITS_PER_BYTE)
        return AudioFileInspection.Valid(
            category = AudioFormatCategory.MP3,
            durationMs = maxOf(MS_PER_SEC, estimatedSec * MS_PER_SEC),
            preparedFile = file,
        )
    }

    private fun inspectFlac(file: File): AudioFileInspection {
        val bytes = ByteArray(minOf(file.length().toInt(), 16))
        file.inputStream().use { it.read(bytes) }
        val hasFlacMagic =
            bytes.size >= 4 && bytes[0] == 'f'.code.toByte() &&
                bytes[1] == 'L'.code.toByte() && bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()

        if (!hasFlacMagic) {
            return AudioFileInspection.Corrupted("Audio file '${file.name}' is corrupted: missing 'fLaC' marker")
        }

        val estimatedSec = file.length() / (256 * BYTES_PER_KB / BITS_PER_BYTE)
        return AudioFileInspection.Valid(
            category = AudioFormatCategory.FLAC,
            durationMs = maxOf(MS_PER_SEC, estimatedSec * MS_PER_SEC),
            preparedFile = file,
        )
    }

    private fun inspectOgg(file: File): AudioFileInspection {
        val bytes = ByteArray(minOf(file.length().toInt(), 16))
        file.inputStream().use { it.read(bytes) }
        val hasOggMagic =
            bytes.size >= 4 && bytes[0] == 'O'.code.toByte() &&
                bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()

        if (!hasOggMagic) {
            return AudioFileInspection.Corrupted(
                "Audio file '${file.name}' is corrupted: missing 'OggS' container marker",
            )
        }

        val estimatedSec = file.length() / (128 * BYTES_PER_KB / BITS_PER_BYTE)
        return AudioFileInspection.Valid(
            category = AudioFormatCategory.OGG,
            durationMs = maxOf(MS_PER_SEC, estimatedSec * MS_PER_SEC),
            preparedFile = file,
        )
    }

    private fun inspectM4a(file: File): AudioFileInspection {
        val bytes = ByteArray(minOf(file.length().toInt(), 64))
        file.inputStream().use { it.read(bytes) }
        val hasFtyp =
            (0 until bytes.size - 4).any { i ->
                bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                    bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
            }

        if (!hasFtyp) {
            return AudioFileInspection.Corrupted("Audio file '${file.name}' is corrupted: missing MP4/M4A ftyp header")
        }

        val estimatedSec = file.length() / (128 * BYTES_PER_KB / BITS_PER_BYTE)
        return AudioFileInspection.Valid(
            category = AudioFormatCategory.M4A,
            durationMs = maxOf(MS_PER_SEC, estimatedSec * MS_PER_SEC),
            preparedFile = file,
        )
    }

    private fun inspectVideo(file: File): AudioFileInspection {
        val estimatedSec = file.length() / (500 * BYTES_PER_KB)
        return AudioFileInspection.Valid(
            category = AudioFormatCategory.VIDEO,
            durationMs = maxOf(MS_PER_SEC, estimatedSec * MS_PER_SEC),
            preparedFile = file,
        )
    }
}

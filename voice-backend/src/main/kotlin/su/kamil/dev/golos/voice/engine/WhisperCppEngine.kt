package su.kamil.dev.golos.voice.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.voice.audio.AudioPreprocessor
import java.io.File
import java.util.concurrent.TimeUnit

enum class InferenceDevice(val displayName: String) {
    CPU("CPU (Multi-threaded AVX)"),
    GPU("GPU (Auto-Accelerated)")
}

/**
 * Whisper.cpp speech engine runner for local multilingual GGML models.
 */
class WhisperCppEngine(
    var modelPath: String,
    var binaryPath: String = "whisper-cli",
    var language: String = "auto",
    var device: InferenceDevice = InferenceDevice.CPU,
    var threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
    override val id: String = "whisper-cpp",
    override val displayName: String = "Whisper.cpp (Local GGML)"
) : SpeechToTextEngine {

    private val logger = LoggerFactory.getLogger(WhisperCppEngine::class.java)

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            logger.warn("Whisper model file does not exist at: {}", modelPath)
            return@withContext Result.failure(IllegalArgumentException("Model file not found: $modelPath"))
        }
        Result.success(Unit)
    }

    override suspend fun transcribe(audio: AudioChunk): TranscriptionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val standardChunk = AudioPreprocessor.toWhisperStandard(audio)
        val wavBytes = AudioPreprocessor.createWavBytes(standardChunk)

        val tempWav = File.createTempFile("golos_audio_", ".wav")
        try {
            tempWav.writeBytes(wavBytes)

            val resolvedBin = if (File(binaryPath).canExecute()) {
                binaryPath
            } else {
                su.kamil.dev.golos.voice.download.WhisperBinaryManager().findWhisperBinary(binaryPath)
            }

            val cmd = mutableListOf(
                resolvedBin,
                "-m", modelPath,
                "-f", tempWav.absolutePath,
                "-t", threads.toString(),
                "-l", language,
                "--no-timestamps"
            )

            if (device == InferenceDevice.CPU) {
                cmd.add("--no-gpu")
            }

            logger.info("Executing whisper-cli (device: {}, lang: {}, model: {}): {}",
                device, language, File(modelPath).name, cmd.joinToString(" ")
            )

            val process = try {
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                logger.error("Failed to start whisper-cli process at path '{}'", resolvedBin, e)
                return@withContext TranscriptionResult(
                    text = "[Error: whisper-cli not found at '$resolvedBin'. Open Preferences -> 'Whisper Models & Hardware' and click 'Download whisper-cli' or select 'Mock Engine'.]",
                    durationMs = System.currentTimeMillis() - startTime,
                    isFinal = true,
                    confidence = 0.0f
                )
            }

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Whisper process timed out after 30 seconds")
            }

            val cleanedText = cleanWhisperOutput(output)
            val duration = System.currentTimeMillis() - startTime

            TranscriptionResult(
                text = cleanedText,
                durationMs = duration,
                isFinal = true,
                confidence = 0.95f
            )
        } finally {
            tempWav.delete()
        }
    }

    private fun cleanWhisperOutput(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                        !line.startsWith("whisper_") &&
                        !line.startsWith("system_info:") &&
                        !line.startsWith("main:") &&
                        !line.startsWith("output_") &&
                        !line.contains("ggml_")
            }
            .joinToString(" ")
            .trim()
    }
}

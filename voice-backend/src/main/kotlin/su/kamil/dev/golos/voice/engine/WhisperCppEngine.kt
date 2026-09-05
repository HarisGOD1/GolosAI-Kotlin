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

/**
 * Whisper.cpp speech engine runner for local GGML/GGUF models.
 */
class WhisperCppEngine(
    val modelPath: String,
    val binaryPath: String = "whisper-cli",
    val language: String = "auto",
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
    override val id: String = "whisper-cpp",
    override val displayName: String = "Whisper.cpp (Local GGML)"
) : SpeechToTextEngine {

    private val logger = LoggerFactory.getLogger(WhisperCppEngine::class.java)

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            logger.warn("Whisper model file does not exist at: $modelPath")
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

            val cmd = mutableListOf(
                binaryPath,
                "-m", modelPath,
                "-f", tempWav.absolutePath,
                "-t", threads.toString(),
                "-l", language,
                "--no-timestamps"
            )

            logger.debug("Executing whisper command: {}", cmd.joinToString(" "))
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Whisper process timed out")
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
                        !line.startsWith("main:")
            }
            .joinToString(" ")
            .trim()
    }
}

package su.kamil.dev.golos.voice.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    GPU("GPU (Auto-Accelerated)"),
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
    var bilingualMode: Boolean = false,
    var initialPrompt: String = "",
    override val id: String = "whisper-cpp",
    override val displayName: String = "Whisper.cpp (Local GGML)",
) : SpeechToTextEngine {
    private val logger = LoggerFactory.getLogger(WhisperCppEngine::class.java)

    override suspend fun initialize(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                logger.warn("Whisper model file does not exist at: {}", modelPath)
                return@withContext Result.failure(IllegalArgumentException("Model file not found: $modelPath"))
            }
            Result.success(Unit)
        }

    val postProcessor = su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor()
    var activeProfile: su.kamil.dev.golos.core.model.ApplicationProfile =
        su.kamil.dev.golos.core.model.ApplicationProfile.GENERAL
    var postProcessingSettings: su.kamil.dev.golos.core.model.PostProcessingSettings =
        su.kamil.dev.golos.core.model.PostProcessingSettings()

    override suspend fun transcribe(audio: AudioChunk): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val rms = AudioPreprocessor.calculateRms(audio)
            if (rms < 0.003f && audio.durationMs > 0) {
                logger.info("Audio RMS ({}) below audibility threshold; skipping recognition (silence).", rms)
                return@withContext TranscriptionResult(
                    text = "",
                    durationMs = 0L,
                    isFinal = true,
                    confidence = 1.0f,
                )
            }

            val standardChunk = AudioPreprocessor.toWhisperStandard(audio)
            val wavBytes = AudioPreprocessor.createWavBytes(standardChunk)

            val tempWav = File.createTempFile("golos_audio_", ".wav")
            try {
                tempWav.writeBytes(wavBytes)
                transcribeAudioFileInternal(tempWav)
            } finally {
                tempWav.delete()
            }
        }

    override suspend fun transcribeFile(file: File): TranscriptionResult =
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                return@withContext TranscriptionResult(
                    text = "[Error: Audio file not found at '${file.absolutePath}']",
                    durationMs = 0L,
                    confidence = 0.0f,
                )
            }
            transcribeAudioFileInternal(file)
        }

    private suspend fun transcribeAudioFileInternal(audioFile: File): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val resolvedBin =
                if (File(binaryPath).canExecute()) {
                    binaryPath
                } else {
                    val bin = su.kamil.dev.golos.voice.download.WhisperBinaryManager().ensureBinaryPresent(binaryPath)
                    this@WhisperCppEngine.binaryPath = bin
                    bin
                }

            val cmd =
                mutableListOf(
                    resolvedBin,
                    "-m", modelPath,
                    "-f", audioFile.absolutePath,
                    "-t", threads.toString(),
                    "-l", language,
                    "-bo", "1",
                    "-bs", "1",
                    "--no-timestamps",
                    "--no-prints",
                )

            if (device == InferenceDevice.CPU) {
                cmd.add("--no-gpu")
            }

            if (bilingualMode && language != "auto" && language != "en") {
                val langName =
                    when (language) {
                        "ru" -> "Russian"
                        "fr" -> "French"
                        "de" -> "German"
                        "jp", "ja" -> "Japanese"
                        "cn", "zh" -> "Chinese"
                        "tr" -> "Turkish"
                        "ar" -> "Arabic"
                        "es" -> "Spanish"
                        "it" -> "Italian"
                        "pt" -> "Portuguese"
                        "ko" -> "Korean"
                        "uk" -> "Ukrainian"
                        "pl" -> "Polish"
                        "nl" -> "Dutch"
                        else -> language
                    }
                cmd.add("--prompt")
                cmd.add("Bilingual English and $langName conversation. Technical words, code, mixed vocabulary.")
            } else if (initialPrompt.isNotBlank()) {
                cmd.add("--prompt")
                cmd.add(initialPrompt)
            } else {
                val promptTerms = postProcessor.dictionaryManager.generatePromptTerms()
                if (promptTerms.isNotBlank()) {
                    cmd.add("--prompt")
                    cmd.add(promptTerms)
                }
            }

            logger.info(
                "Executing whisper-cli (device: {}, lang: {}, model: {}): {}",
                device,
                language,
                File(modelPath).name,
                cmd.joinToString(" "),
            )

            val process =
                try {
                    ProcessBuilder(cmd)
                        .redirectErrorStream(false)
                        .start()
                } catch (e: Exception) {
                    logger.error("Failed to start whisper-cli process at path '{}'", resolvedBin, e)
                    return@withContext TranscriptionResult(
                        text =
                            "[Error: whisper-cli not found at '$resolvedBin'. " +
                                "Open Preferences -> 'Whisper Models & Hardware' and click " +
                                "'Download whisper-cli' or select 'Mock Engine'.]",
                        durationMs = System.currentTimeMillis() - startTime,
                        isFinal = true,
                        confidence = 0.0f,
                    )
                }

            val stdoutDeferred =
                async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().readText()
                }
            val stderrDeferred =
                async(Dispatchers.IO) {
                    process.errorStream.bufferedReader().readText()
                }

            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Whisper process timed out after 120 seconds")
            }

            val rawOutput = stdoutDeferred.await()
            val rawStderr = stderrDeferred.await()
            if (rawStderr.isNotBlank()) {
                logger.debug("whisper-cli stderr: {}", rawStderr)
            }

            val cleanedText = cleanWhisperOutput(rawOutput)
            val postProcessed =
                postProcessor.postProcess(
                    cleanedText,
                    profile = activeProfile,
                    settings = postProcessingSettings,
                )
            val duration = System.currentTimeMillis() - startTime

            TranscriptionResult(
                text = postProcessed,
                durationMs = duration,
                isFinal = true,
                confidence = 0.95f,
            )
        }

    internal fun cleanWhisperOutput(raw: String): String {
        val timestampRegex = Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]")

        return raw.lines()
            .map { line ->
                var l = line.replace(timestampRegex, "").trim()
                if (l.contains("miniaudio")) {
                    l = l.substringAfter("miniaudio").trim()
                }
                l
            }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("whisper_") &&
                    !line.startsWith("system_info:") &&
                    !line.startsWith("main:") &&
                    !line.startsWith("output_") &&
                    !line.startsWith("load_backend:") &&
                    !line.startsWith("loadload_backend:") &&
                    !line.startsWith("read_audio_data:") &&
                    !line.contains("ggml_") &&
                    !line.contains("ggml-") &&
                    !line.equals("[BLANK_AUDIO]", ignoreCase = true) &&
                    !line.equals("[START]", ignoreCase = true)
            }
            .joinToString(" ")
            .trim()
    }
}

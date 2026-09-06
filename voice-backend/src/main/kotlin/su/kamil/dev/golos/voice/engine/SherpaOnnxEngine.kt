package su.kamil.dev.golos.voice.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.ApplicationProfile
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.PostProcessingSettings
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.voice.audio.AudioPreprocessor
import su.kamil.dev.golos.voice.download.SherpaBinaryManager
import su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sherpa-ONNX speech recognition engine runner for local streaming transducer ONNX models.
 */
class SherpaOnnxEngine(
    var modelPath: String = "",
    var binaryPath: String = "sherpa-onnx",
    var threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
    override val id: String = "sherpa-onnx",
    override val displayName: String = "Sherpa-ONNX (Next-Gen Kaldi)",
) : SpeechToTextEngine {
    private val logger = LoggerFactory.getLogger(SherpaOnnxEngine::class.java)
    val postProcessor = SpeechPostProcessor()
    var activeProfile: ApplicationProfile = ApplicationProfile.GENERAL
    var postProcessingSettings: PostProcessingSettings = PostProcessingSettings()

    override suspend fun initialize(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val dir = File(modelPath)
            if (!dir.exists() || !dir.isDirectory) {
                logger.warn("Sherpa-ONNX model directory not found: {}", modelPath)
                return@withContext Result.failure(IllegalArgumentException("Model directory not found: $modelPath"))
            }
            Result.success(Unit)
        }

    override suspend fun transcribe(audio: AudioChunk): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val rms = AudioPreprocessor.calculateRms(audio)
            if (rms < 0.003f && audio.durationMs > 0) {
                return@withContext TranscriptionResult(
                    text = "",
                    durationMs = 0L,
                    isFinal = true,
                    confidence = 1.0f,
                )
            }

            val standardChunk = AudioPreprocessor.toWhisperStandard(audio)
            val wavBytes = AudioPreprocessor.createWavBytes(standardChunk)
            val tempWav = File.createTempFile("golos_sherpa_", ".wav")

            try {
                tempWav.writeBytes(wavBytes)
                transcribeFileInternal(tempWav)
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
            transcribeFileInternal(file)
        }

    private suspend fun transcribeFileInternal(audioFile: File): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val binaryManager = SherpaBinaryManager()
            val resolvedBin =
                if (File(binaryPath).canExecute()) {
                    binaryPath
                } else {
                    val bin = binaryManager.findSherpaBinary(binaryPath)
                    if (binaryManager.isBinaryAvailable(bin)) {
                        bin
                    } else {
                        binaryManager.ensureBinaryPresent(binaryPath)
                    }
                }

            if (!binaryManager.isBinaryAvailable(resolvedBin)) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text =
                        "[Error: sherpa-onnx executable not found. " +
                            "Open Preferences -> 'Engine & Models' and click 'Download sherpa-onnx' or select another engine.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val modelDir = File(modelPath)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text = "[Error: Sherpa-ONNX model directory not found at '$modelPath'. Please download a model in Preferences.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val modelFiles = modelDir.listFiles() ?: emptyArray()
            val tokensFile = modelFiles.firstOrNull { it.name == "tokens.txt" }
            val encoderFile =
                modelFiles.firstOrNull { it.name.contains("encoder") && it.name.endsWith(".onnx") }
            val decoderFile =
                modelFiles.firstOrNull { it.name.contains("decoder") && it.name.endsWith(".onnx") }
            val joinerFile =
                modelFiles.firstOrNull { it.name.contains("joiner") && it.name.endsWith(".onnx") }

            if (tokensFile == null || encoderFile == null) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text = "[Error: Incomplete ONNX model in '$modelPath'. Missing tokens.txt or encoder.onnx.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val cmd =
                mutableListOf(
                    resolvedBin,
                    "--tokens=${tokensFile.absolutePath}",
                    "--encoder=${encoderFile.absolutePath}",
                    "--num-threads=$threads",
                )

            if (decoderFile != null) cmd.add("--decoder=${decoderFile.absolutePath}")
            if (joinerFile != null) cmd.add("--joiner=${joinerFile.absolutePath}")
            cmd.add(audioFile.absolutePath)

            logger.info("Executing sherpa-onnx: {}", cmd.joinToString(" "))

            val rawOutput =
                try {
                    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                    val finished = process.waitFor(120, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        throw IllegalStateException("Sherpa-ONNX process timed out after 120 seconds")
                    }
                    stdoutDeferred.await()
                } catch (e: Exception) {
                    logger.error("Sherpa-ONNX execution failed: {}", e.message)
                    return@withContext TranscriptionResult(
                        text = "[Error running sherpa-onnx: ${e.message}]",
                        durationMs = System.currentTimeMillis() - startTime,
                        isFinal = true,
                        confidence = 0.0f,
                    )
                }

            val cleaned = cleanSherpaOutput(rawOutput, audioFile.name)
            val postProcessed =
                postProcessor.postProcess(
                    cleaned,
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

    internal fun cleanSherpaOutput(
        raw: String,
        audioFilename: String,
    ): String {
        val lines = raw.lines()
        val resultLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("LOG") || trimmed.startsWith("WARNING") || trimmed.startsWith("ERROR") ||
                trimmed.startsWith("INFO") || trimmed.startsWith("Creating") || trimmed.startsWith("Number of")
            ) {
                continue
            }

            // Extract text after audio file prefix if present: e.g. "test.wav: transcribed text"
            val text =
                if (trimmed.contains(audioFilename)) {
                    trimmed.substringAfter(audioFilename).removePrefix(":").trim()
                } else if (trimmed.contains("text:") || trimmed.contains("Text:")) {
                    trimmed.substringAfter("ext:").trim()
                } else {
                    trimmed
                }

            if (text.isNotEmpty() && !text.startsWith("/") && !text.startsWith("\\")) {
                resultLines.add(text)
            }
        }

        return resultLines.joinToString(" ").trim()
    }
}

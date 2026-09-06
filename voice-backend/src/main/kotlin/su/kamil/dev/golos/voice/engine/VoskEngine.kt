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
import su.kamil.dev.golos.voice.download.VoskBinaryManager
import su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Vosk speech recognition engine runner using local offline acoustic models.
 */
class VoskEngine(
    var modelPath: String = "",
    var binaryPath: String = "vosk-transcriber",
    override val id: String = "vosk",
    override val displayName: String = "Vosk (Lightweight Offline)",
) : SpeechToTextEngine {
    private val logger = LoggerFactory.getLogger(VoskEngine::class.java)
    val postProcessor = SpeechPostProcessor()
    var activeProfile: ApplicationProfile = ApplicationProfile.GENERAL
    var postProcessingSettings: PostProcessingSettings = PostProcessingSettings()

    override suspend fun initialize(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val modelDir = File(modelPath)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                logger.warn("Vosk model directory not found at: {}", modelPath)
                return@withContext Result.failure(IllegalArgumentException("Vosk model directory not found: $modelPath"))
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
            val tempWav = File.createTempFile("golos_vosk_", ".wav")

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
            val resolvedBin = VoskBinaryManager().findVoskBinary(binaryPath)
            val isBinAvailable = VoskBinaryManager().isBinaryAvailable(resolvedBin)

            if (!isBinAvailable) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text =
                        "[Error: vosk-transcriber not found on system PATH. " +
                            "Install Vosk via 'pip install vosk' or specify binary path in Settings.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text = "[Error: Vosk model not found at '$modelPath'. Please download a model in Settings.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val tempOut = File.createTempFile("vosk_out_", ".txt")
            val cmd =
                listOf(
                    resolvedBin,
                    "-m", modelDir.absolutePath,
                    "-i", audioFile.absolutePath,
                    "-o", tempOut.absolutePath,
                    "-t", "txt",
                    "--log-level", "WARNING",
                )

            val rawOutput =
                try {
                    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                    val finished = process.waitFor(120, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        throw IllegalStateException("Vosk process timed out")
                    }
                    stdoutDeferred.await()
                } catch (e: Exception) {
                    logger.error("Vosk execution failed: {}", e.message)
                    ""
                }

            val textFromFile = if (tempOut.exists()) tempOut.readText().trim() else ""
            tempOut.delete()

            val rawText = textFromFile.ifEmpty { rawOutput.trim() }
            val postProcessed =
                postProcessor.postProcess(
                    rawText,
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
}

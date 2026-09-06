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

    private var cachedModelPath: String? = null
    private var cachedModel: Any? = null
    private var cachedClassLoader: java.net.URLClassLoader? = null
    private val modelLock = Any()

    override suspend fun initialize(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val modelDir = File(modelPath)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                logger.warn("Vosk model directory not found at: {}", modelPath)
                return@withContext Result.failure(IllegalArgumentException("Vosk model directory not found: $modelPath"))
            }
            val jarFile = File(VoskBinaryManager().binDir, "vosk.jar")
            if (jarFile.exists()) {
                try {
                    getModel(jarFile, modelDir)
                } catch (e: Exception) {
                    logger.warn("Eager Vosk model preloading skipped: {}", e.message)
                }
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

            val startTime = System.currentTimeMillis()
            val standardChunk = AudioPreprocessor.toWhisperStandard(audio)
            val manager = VoskBinaryManager()
            val jarFile = File(manager.binDir, "vosk.jar")
            val modelDir = File(modelPath)

            // High-speed in-memory path for live on-the-fly streaming & recording
            if (jarFile.exists() && modelDir.exists()) {
                val rawText = transcribeWithJar(jarFile, modelDir, standardChunk.samples)
                val postProcessed =
                    postProcessor.postProcess(
                        rawText,
                        profile = activeProfile,
                        settings = postProcessingSettings,
                    )
                return@withContext TranscriptionResult(
                    text = postProcessed,
                    durationMs = System.currentTimeMillis() - startTime,
                    isFinal = true,
                    confidence = 0.95f,
                )
            }

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
            val manager = VoskBinaryManager()
            val resolvedBin = manager.findVoskBinary(binaryPath)
            val isBinAvailable = manager.isBinaryAvailable(resolvedBin)
            val jarFile = File(manager.binDir, "vosk.jar")

            if (!isBinAvailable && !jarFile.exists()) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text =
                        "[Error: Vosk engine not found. " +
                            "Open Preferences -> 'Engine & Models' and click 'Download Vosk'.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val modelDir = File(modelPath)
            if (!modelDir.exists()) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext TranscriptionResult(
                    text = "[Error: Vosk model not found at '$modelPath'. Please download a model in Preferences.]",
                    durationMs = duration,
                    isFinal = true,
                    confidence = 0.0f,
                )
            }

            val rawText =
                if (jarFile.exists()) {
                    val bytes = audioFile.readBytes()
                    val pcm = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
                    transcribeWithJar(jarFile, modelDir, pcm)
                } else {
                    transcribeWithCli(resolvedBin, modelDir, audioFile)
                }

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

    private fun getModel(
        jarFile: File,
        modelDir: File,
    ): Pair<java.net.URLClassLoader, Any> =
        synchronized(modelLock) {
            val cl =
                cachedClassLoader ?: java.net.URLClassLoader(
                    arrayOf(jarFile.toURI().toURL()),
                    Thread.currentThread().contextClassLoader,
                ).also { cachedClassLoader = it }

            if (cachedModel != null && cachedModelPath == modelDir.absolutePath) {
                return Pair(cl, cachedModel!!)
            }

            closeModel()

            val modelClass = cl.loadClass("org.vosk.Model")
            val modelCtor = modelClass.getConstructor(String::class.java)
            val model = modelCtor.newInstance(modelDir.absolutePath)
            cachedModel = model
            cachedModelPath = modelDir.absolutePath
            Pair(cl, model)
        }

    fun closeModel() {
        synchronized(modelLock) {
            try {
                if (cachedModel != null) {
                    val closeMethod = cachedModel!!.javaClass.getMethod("close")
                    closeMethod.invoke(cachedModel)
                }
            } catch (_: Exception) {
            }
            cachedModel = null
            cachedModelPath = null
        }
    }

    private fun transcribeWithJar(
        jarFile: File,
        modelDir: File,
        pcm: ByteArray,
    ): String =
        try {
            val (cl, model) = getModel(jarFile, modelDir)
            val modelClass = cl.loadClass("org.vosk.Model")
            val recClass = cl.loadClass("org.vosk.Recognizer")

            val recCtor = recClass.getConstructor(modelClass, Float::class.javaPrimitiveType)
            val rec = recCtor.newInstance(model, 16000.0f)

            val acceptWf = recClass.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType)
            val getResult = recClass.getMethod("getResult")
            val getFinalResult = recClass.getMethod("getFinalResult")
            val getPartialResult = recClass.getMethod("getPartialResult")
            val closeRec = recClass.getMethod("close")

            try {
                val fullText = StringBuilder()
                var lastPartial = ""
                val chunkSize = 4096
                var offset = 0
                while (offset < pcm.size) {
                    val len = minOf(chunkSize, pcm.size - offset)
                    val chunk = pcm.copyOfRange(offset, offset + len)
                    val accepted = acceptWf.invoke(rec, chunk, len) as Boolean
                    if (accepted) {
                        val resJson = getResult.invoke(rec) as String
                        val text = extractJsonField(resJson, "text")
                        if (text.isNotBlank()) {
                            if (fullText.isNotEmpty()) fullText.append(" ")
                            fullText.append(text)
                        }
                    } else {
                        val partJson = getPartialResult.invoke(rec) as String
                        val partial = extractJsonField(partJson, "partial")
                        if (partial.isNotBlank()) {
                            lastPartial = partial
                        }
                    }
                    offset += len
                }

                val finalJson = getFinalResult.invoke(rec) as String
                val finalText = extractJsonField(finalJson, "text")
                if (finalText.isNotBlank()) {
                    if (fullText.isNotEmpty()) fullText.append(" ")
                    fullText.append(finalText)
                } else if (lastPartial.isNotBlank()) {
                    if (fullText.isNotEmpty()) fullText.append(" ")
                    fullText.append(lastPartial)
                }

                val resultText = fullText.toString().trim()
                resultText
            } finally {
                try {
                    closeRec.invoke(rec)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            logger.error("In-process Vosk transcription failed: {}", e.message)
            ""
        }

    private fun extractJsonField(
        json: String,
        fieldName: String,
    ): String {
        val regex = """"$fieldName"\s*:\s*"([^"]*)"""".toRegex()
        val match = regex.find(json)
        return match?.groups?.get(1)?.value ?: ""
    }

    private suspend fun transcribeWithCli(
        resolvedBin: String,
        modelDir: File,
        audioFile: File,
    ): String =
        withContext(Dispatchers.IO) {
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
            textFromFile.ifEmpty { rawOutput.trim() }
        }
}

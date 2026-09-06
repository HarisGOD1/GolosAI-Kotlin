package su.kamil.dev.golos.voice.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Metadata for supported official Whisper.cpp GGML models.
 */
data class WhisperModelInfo(
    override val id: String,
    override val name: String,
    override val filename: String,
    override val downloadUrl: String,
    override val approximateSizeMb: Int,
    override val engineId: String = "whisper-cpp",
    val isMultilingual: Boolean = true,
) : EngineModel {
    override val isArchive: Boolean get() = false
    override val extractedDirName: String get() = ""

    companion object {
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        val AVAILABLE_MODELS =
            listOf(
                WhisperModelInfo(
                    id = "tiny",
                    name = "Tiny (Multilingual, ~75 MB)",
                    filename = "ggml-tiny.bin",
                    downloadUrl = "$BASE_URL/ggml-tiny.bin",
                    approximateSizeMb = 75,
                ),
                WhisperModelInfo(
                    id = "base",
                    name = "Base (Multilingual, ~142 MB) [Recommended]",
                    filename = "ggml-base.bin",
                    downloadUrl = "$BASE_URL/ggml-base.bin",
                    approximateSizeMb = 142,
                ),
                WhisperModelInfo(
                    id = "small",
                    name = "Small (Multilingual, ~466 MB)",
                    filename = "ggml-small.bin",
                    downloadUrl = "$BASE_URL/ggml-small.bin",
                    approximateSizeMb = 466,
                ),
                WhisperModelInfo(
                    id = "turbo",
                    name = "Large-v3-Turbo (Multilingual, ~1.5 GB)",
                    filename = "ggml-large-v3-turbo.bin",
                    downloadUrl = "$BASE_URL/ggml-large-v3-turbo.bin",
                    approximateSizeMb = 1500,
                ),
            )
    }
}

/**
 * Non-blocking model downloader with progress tracking, cancellation, and archive extraction.
 */
class ModelDownloader(
    val modelsDir: File = File(System.getProperty("user.home"), ".cache/golos-ai/models"),
) {
    private val logger = LoggerFactory.getLogger(ModelDownloader::class.java)
    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()

    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    fun findModelFile(model: EngineModel): File? {
        if (model.isArchive) {
            val localDir = File(modelsDir, model.extractedDirName)
            if (localDir.exists() && localDir.isDirectory) {
                val contents = localDir.listFiles()
                if (contents != null && contents.isNotEmpty()) return localDir
            }
            val bundledDir = File("models", model.extractedDirName)
            if (bundledDir.exists() && bundledDir.isDirectory) return bundledDir
            return null
        }

        val local = File(modelsDir, model.filename)
        if (local.exists() && local.length() > 1024 * 1024) return local

        val bundled = File("models", model.filename)
        if (bundled.exists() && bundled.length() > 1024 * 1024) return bundled

        val projectRootBundled = File(System.getProperty("user.dir"), "models/${model.filename}")
        if (projectRootBundled.exists() && projectRootBundled.length() > 1024 * 1024) return projectRootBundled

        val resourceStream = javaClass.getResourceAsStream("/models/${model.filename}")
        if (resourceStream != null) {
            local.parentFile.mkdirs()
            resourceStream.use { input ->
                local.outputStream().use { output -> input.copyTo(output) }
            }
            if (local.exists() && local.length() > 1024) return local
        }

        return null
    }

    fun isModelDownloaded(model: EngineModel): Boolean = findModelFile(model) != null

    fun getLocalModelFile(model: EngineModel): File {
        val found = findModelFile(model)
        if (found != null) return found
        return if (model.isArchive) File(modelsDir, model.extractedDirName) else File(modelsDir, model.filename)
    }

    suspend fun downloadModel(
        model: EngineModel,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            val destinationFile = File(modelsDir, if (model.isArchive) model.extractedDirName else model.filename)
            val tempFile = File(modelsDir, "${model.filename}.tmp")

            try {
                logger.info("Starting download for model '{}' from: {}", model.name, model.downloadUrl)
                val request =
                    HttpRequest.newBuilder()
                        .uri(URI.create(model.downloadUrl))
                        .timeout(Duration.ofMinutes(15))
                        .GET()
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    return@withContext Result.failure(IllegalStateException("HTTP download error: ${response.statusCode()}"))
                }

                val totalBytes =
                    response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(model.approximateSizeMb * 1024L * 1024L)

                response.body().use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (cancelFlag.get()) {
                                tempFile.delete()
                                return@withContext Result.failure(InterruptedException("Download cancelled by user"))
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val downloadPercent =
                                if (totalBytes > 0) {
                                    ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                            val effectivePercent = if (model.isArchive) (downloadPercent * 0.85).toInt() else downloadPercent
                            onProgress(totalRead, totalBytes, effectivePercent)
                        }
                    }
                }

                if (model.isArchive) {
                    onProgress(totalBytes, totalBytes, 90)
                    extractArchive(tempFile, modelsDir)
                    tempFile.delete()
                    onProgress(totalBytes, totalBytes, 100)
                    logger.info("Successfully downloaded and extracted model archive to: {}", destinationFile.absolutePath)
                    Result.success(destinationFile)
                } else {
                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    tempFile.renameTo(destinationFile)
                    onProgress(totalBytes, totalBytes, 100)
                    logger.info("Successfully downloaded model to: {}", destinationFile.absolutePath)
                    Result.success(destinationFile)
                }
            } catch (e: Exception) {
                tempFile.delete()
                logger.error("Failed to download model '{}'", model.name, e)
                Result.failure(e)
            }
        }

    private fun extractArchive(
        archiveFile: File,
        targetDir: File,
    ) {
        targetDir.mkdirs()
        if (archiveFile.name.endsWith(".zip") || archiveFile.name.endsWith(".tmp") && archiveFile.name.contains(".zip")) {
            ZipInputStream(archiveFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        throw SecurityException("Zip entry attempted path traversal: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> zip.copyTo(output) }
                    }
                    entry = zip.nextEntry
                }
            }
        } else {
            val isBzip2 = archiveFile.name.contains(".bz2")
            val flag = if (isBzip2) "-xjf" else "-xzf"
            val pb = ProcessBuilder("tar", flag, archiveFile.absolutePath, "-C", targetDir.absolutePath)
            val proc = pb.start()
            val finished = proc.waitFor(120, TimeUnit.SECONDS)
            if (!finished || proc.exitValue() != 0) {
                val pbFallback = ProcessBuilder("tar", "-xf", archiveFile.absolutePath, "-C", targetDir.absolutePath)
                val procFallback = pbFallback.start()
                val ok = procFallback.waitFor(120, TimeUnit.SECONDS) && procFallback.exitValue() == 0
                if (!ok) {
                    throw IllegalStateException("Failed to extract tar archive: ${archiveFile.name}")
                }
            }
        }
    }
}

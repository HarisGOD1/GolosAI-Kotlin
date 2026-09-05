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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Metadata for supported official Whisper.cpp GGML models.
 */
data class WhisperModelInfo(
    val id: String,
    val name: String,
    val filename: String,
    val downloadUrl: String,
    val approximateSizeMb: Int,
    val isMultilingual: Boolean = true
) {
    companion object {
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        val AVAILABLE_MODELS = listOf(
            WhisperModelInfo(
                id = "tiny",
                name = "Tiny (Multilingual, ~75 MB)",
                filename = "ggml-tiny.bin",
                downloadUrl = "$BASE_URL/ggml-tiny.bin",
                approximateSizeMb = 75
            ),
            WhisperModelInfo(
                id = "base",
                name = "Base (Multilingual, ~142 MB) [Recommended]",
                filename = "ggml-base.bin",
                downloadUrl = "$BASE_URL/ggml-base.bin",
                approximateSizeMb = 142
            ),
            WhisperModelInfo(
                id = "small",
                name = "Small (Multilingual, ~466 MB)",
                filename = "ggml-small.bin",
                downloadUrl = "$BASE_URL/ggml-small.bin",
                approximateSizeMb = 466
            ),
            WhisperModelInfo(
                id = "turbo",
                name = "Large-v3-Turbo (Multilingual, ~1.5 GB)",
                filename = "ggml-large-v3-turbo.bin",
                downloadUrl = "$BASE_URL/ggml-large-v3-turbo.bin",
                approximateSizeMb = 1500
            )
        )
    }
}

/**
 * Non-blocking model downloader with progress tracking and cancellation.
 */
class ModelDownloader(
    val modelsDir: File = File(System.getProperty("user.home"), ".cache/golos-ai/models")
) {
    private val logger = LoggerFactory.getLogger(ModelDownloader::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    init {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
    }

    fun findModelFile(model: WhisperModelInfo): File? {
        // 1. Check local cache dir (~/.cache/golos-ai/models/)
        val local = File(modelsDir, model.filename)
        if (local.exists() && local.length() > 1024 * 1024) return local

        // 2. Check bundled project models/ folder for offline archives
        val bundled = File("models", model.filename)
        if (bundled.exists() && bundled.length() > 1024 * 1024) return bundled

        val projectRootBundled = File(System.getProperty("user.dir"), "models/${model.filename}")
        if (projectRootBundled.exists() && projectRootBundled.length() > 1024 * 1024) return projectRootBundled

        // 3. Check bundled classpath resource
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

    fun isModelDownloaded(model: WhisperModelInfo): Boolean {
        return findModelFile(model) != null
    }

    fun getLocalModelFile(model: WhisperModelInfo): File {
        return findModelFile(model) ?: File(modelsDir, model.filename)
    }

    /**
     * Downloads model with live progress updates: (bytesDownloaded, totalBytes, percent) -> Unit
     */
    suspend fun downloadModel(
        model: WhisperModelInfo,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val destinationFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        try {
            logger.info("Starting download for model '{}' from: {}", model.name, model.downloadUrl)
            val request = HttpRequest.newBuilder()
                .uri(URI.create(model.downloadUrl))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP download error: ${response.statusCode()}"))
            }

            val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(model.approximateSizeMb * 1024L * 1024L)

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
                        val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                        onProgress(totalRead, totalBytes, percent)
                    }
                }
            }

            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            tempFile.renameTo(destinationFile)
            logger.info("Successfully downloaded model to: {}", destinationFile.absolutePath)
            Result.success(destinationFile)
        } catch (e: Exception) {
            tempFile.delete()
            logger.error("Failed to download model '{}'", model.name, e)
            Result.failure(e)
        }
    }
}

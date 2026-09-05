package su.kamil.dev.golos.voice.download

import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Discovers, manages, and automatically downloads the local whisper.cpp executable binary (`whisper-cli` or `main`).
 */
class WhisperBinaryManager(
    val binDir: File = File(System.getProperty("user.home"), ".cache/golos-ai/bin")
) {
    private val logger = LoggerFactory.getLogger(WhisperBinaryManager::class.java)

    init {
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
    }

    fun findWhisperBinary(customPath: String? = null): String {
        // 1. Check custom configured path if provided
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.canExecute()) {
                logger.info("Using custom configured whisper binary: {}", customFile.absolutePath)
                return customFile.absolutePath
            }
        }

        // 2. Check environment variable
        val envBin = System.getenv("WHISPER_BIN")
        if (!envBin.isNullOrBlank() && File(envBin).canExecute()) {
            logger.info("Using whisper binary from WHISPER_BIN: {}", envBin)
            return envBin
        }

        // 3. Check local user cache dir (whisper-cli or main)
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val candidateNames = if (isWin) {
            listOf("whisper-cli.exe", "main.exe", "whisper.exe")
        } else {
            listOf("whisper-cli", "main", "whisper")
        }

        for (name in candidateNames) {
            val localBin = File(binDir, name)
            if (localBin.exists()) {
                localBin.setExecutable(true)
                if (localBin.canExecute()) {
                    logger.info("Using local cached whisper binary: {}", localBin.absolutePath)
                    return localBin.absolutePath
                }
            }
        }

        // 4. Search standard system paths
        val systemPaths = listOf(
            "/usr/bin/whisper-cli",
            "/usr/local/bin/whisper-cli",
            "/opt/homebrew/bin/whisper-cli",
            "/usr/bin/whisper",
            "/usr/local/bin/whisper",
            "/usr/bin/main"
        )

        for (path in systemPaths) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                logger.info("Found system whisper binary: {}", path)
                return path
            }
        }

        // 5. Check if whisper-cli is resolvable in PATH via 'which'
        try {
            val p = ProcessBuilder(if (isWin) "where" else "which", "whisper-cli").start()
            val output = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && output.isNotEmpty() && File(output).canExecute()) {
                return output
            }
        } catch (_: Exception) {}

        // Fallback default
        return "whisper-cli"
    }

    fun isBinaryAvailable(customPath: String? = null): Boolean {
        val bin = findWhisperBinary(customPath)
        val f = File(bin)
        if (f.isAbsolute && f.exists() && f.canExecute()) return true

        return try {
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val p = ProcessBuilder(if (isWin) "where" else "which", bin).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ensures whisper-cli is present locally. If not found, automatically downloads
     * the precompiled binary archive by default into ~/.cache/golos-ai/bin/.
     */
    fun ensureBinaryPresent(
        customPath: String? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): String {
        val existing = findWhisperBinary(customPath)
        if (isBinaryAvailable(existing)) {
            return existing
        }

        logger.info("whisper-cli executable not found locally. Auto-downloading precompiled binary by default...")
        val downloaded = downloadPrecompiledBinary(onProgress)
        return if (downloaded.isSuccess) {
            val bin = downloaded.getOrThrow().absolutePath
            logger.info("whisper-cli auto-downloaded successfully: {}", bin)
            bin
        } else {
            logger.warn("Automatic whisper-cli download failed: {}. Fallback to default.", downloaded.exceptionOrNull()?.message)
            existing
        }
    }

    fun downloadPrecompiledBinary(onProgress: (Float, String) -> Unit = { _, _ -> }): Result<File> = runCatching {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val downloadUrl = when {
            os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) ->
                "https://github.com/ggml-org/whisper.cpp/releases/download/b4938/whisper-bin-ubuntu-arm64.tar.gz"
            os.contains("linux") ->
                "https://github.com/ggml-org/whisper.cpp/releases/download/b4938/whisper-bin-ubuntu-x64.tar.gz"
            os.contains("win") ->
                "https://github.com/ggml-org/whisper.cpp/releases/download/b4938/whisper-bin-x64.zip"
            os.contains("mac") ->
                "https://github.com/ggml-org/whisper.cpp/releases/download/b4938/whisper-b4938-xcframework.zip"
            else ->
                throw UnsupportedOperationException("Automatic download not supported for OS: $os, arch: $arch")
        }

        onProgress(0.1f, "Connecting to GitHub releases...")
        val client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build()
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(URI(downloadUrl))
            .GET()
            .build()
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw java.io.IOException("HTTP error ${response.statusCode()} while downloading $downloadUrl")
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        val isZip = downloadUrl.endsWith(".zip")
        val tempArchive = File.createTempFile("whisper_bin_", if (isZip) ".zip" else ".tar.gz")

        try {
            response.body().use { input ->
                tempArchive.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val pct = 0.1f + 0.7f * (totalRead.toFloat() / totalBytes)
                            onProgress(pct, "Downloading: ${(totalRead / (1024 * 1024))} MB / ${(totalBytes / (1024 * 1024))} MB")
                        }
                    }
                }
            }

            onProgress(0.85f, "Extracting binary files...")
            binDir.mkdirs()

            if (isZip) {
                ZipInputStream(tempArchive.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val fileName = File(entry.name).name
                        if (!entry.isDirectory && fileName.isNotEmpty()) {
                            val target = File(binDir, fileName)
                            target.outputStream().use { zip.copyTo(it) }
                            target.setExecutable(true)
                        }
                        entry = zip.nextEntry
                    }
                }
            } else {
                val pb = ProcessBuilder("tar", "-xzf", tempArchive.absolutePath, "--strip-components=1", "-C", binDir.absolutePath)
                val proc = pb.start()
                val ok = proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
                if (!ok) {
                    throw IllegalStateException("Failed to extract tar archive")
                }
            }

            val isWin = os.contains("win")
            val whisperBin = File(binDir, if (isWin) "whisper-cli.exe" else "whisper-cli")
            val mainBin = File(binDir, if (isWin) "main.exe" else "main")
            whisperBin.setExecutable(true)
            mainBin.setExecutable(true)

            onProgress(1.0f, "Complete")
            logger.info("Successfully downloaded whisper binary to: {}", whisperBin.absolutePath)
            if (whisperBin.exists()) whisperBin else mainBin
        } finally {
            tempArchive.delete()
        }
    }
}

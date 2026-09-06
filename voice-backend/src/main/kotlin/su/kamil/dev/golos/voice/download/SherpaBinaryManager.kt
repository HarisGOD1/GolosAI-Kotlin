package su.kamil.dev.golos.voice.download

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Discovers, manages, and downloads precompiled sherpa-onnx executable binaries.
 */
class SherpaBinaryManager(
    val binDir: File = File(System.getProperty("user.home"), ".cache/golos-ai/bin"),
) {
    private val logger = LoggerFactory.getLogger(SherpaBinaryManager::class.java)

    init {
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
    }

    fun findSherpaBinary(customPath: String? = null): String {
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.canExecute()) {
                logger.info("Using custom configured sherpa-onnx binary: {}", customFile.absolutePath)
                return customFile.absolutePath
            }
        }

        val envBin = System.getenv("SHERPA_BIN")
        if (!envBin.isNullOrBlank() && File(envBin).canExecute()) {
            logger.info("Using sherpa-onnx binary from SHERPA_BIN: {}", envBin)
            return envBin
        }

        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val candidateNames =
            if (isWin) {
                listOf("sherpa-onnx.exe", "sherpa-onnx-offline.exe")
            } else {
                listOf("sherpa-onnx", "sherpa-onnx-offline")
            }

        for (name in candidateNames) {
            val localBin = File(binDir, name)
            if (localBin.exists()) {
                localBin.setExecutable(true)
                if (localBin.canExecute()) {
                    logger.info("Using local cached sherpa-onnx binary: {}", localBin.absolutePath)
                    return localBin.absolutePath
                }
            }
        }

        val systemPaths =
            listOf(
                "/usr/bin/sherpa-onnx",
                "/usr/local/bin/sherpa-onnx",
                "/opt/homebrew/bin/sherpa-onnx",
                "/usr/bin/sherpa-onnx-offline",
                "/usr/local/bin/sherpa-onnx-offline",
            )

        for (path in systemPaths) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                logger.info("Found system sherpa-onnx binary: {}", path)
                return path
            }
        }

        try {
            val p = ProcessBuilder(if (isWin) "where" else "which", "sherpa-onnx").start()
            val output = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && output.isNotEmpty() && File(output).canExecute()) {
                return output
            }
        } catch (_: Exception) {
        }

        return "sherpa-onnx"
    }

    fun isBinaryAvailable(customPath: String? = null): Boolean {
        val bin = findSherpaBinary(customPath)
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

    fun ensureBinaryPresent(
        customPath: String? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): String {
        val existing = findSherpaBinary(customPath)
        if (isBinaryAvailable(existing)) {
            return existing
        }

        logger.info("sherpa-onnx executable not found locally. Auto-downloading precompiled binary...")
        val downloaded = downloadPrecompiledBinary(onProgress)
        return if (downloaded.isSuccess) {
            val bin = downloaded.getOrThrow().absolutePath
            logger.info("sherpa-onnx auto-downloaded successfully: {}", bin)
            bin
        } else {
            logger.warn("Automatic sherpa-onnx download failed: {}. Fallback to default.", downloaded.exceptionOrNull()?.message)
            existing
        }
    }

    fun downloadPrecompiledBinary(onProgress: (Float, String) -> Unit = { _, _ -> }): Result<File> =
        runCatching {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()

            val baseUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.36"
            val downloadUrl =
                when {
                    os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) ->
                        "$baseUrl/sherpa-onnx-v1.10.36-linux-aarch64.tar.bz2"
                    os.contains("linux") ->
                        "$baseUrl/sherpa-onnx-v1.10.36-linux-x64.tar.bz2"
                    os.contains("win") ->
                        "$baseUrl/sherpa-onnx-v1.10.36-win-x64.zip"
                    os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) ->
                        "$baseUrl/sherpa-onnx-v1.10.36-osx-arm64.tar.bz2"
                    os.contains("mac") ->
                        "$baseUrl/sherpa-onnx-v1.10.36-osx-x64.tar.bz2"
                    else ->
                        throw UnsupportedOperationException("Automatic sherpa-onnx download not supported for OS: $os, arch: $arch")
                }

            onProgress(0.1f, "Connecting to GitHub releases...")
            val client =
                java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .build()
            val request =
                java.net.http.HttpRequest.newBuilder()
                    .uri(URI(downloadUrl))
                    .GET()
                    .build()
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw java.io.IOException("HTTP error ${response.statusCode()} while downloading $downloadUrl")
            }

            val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            val isZip = downloadUrl.endsWith(".zip")
            val tempArchive = File.createTempFile("sherpa_bin_", if (isZip) ".zip" else ".tar.bz2")

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

                onProgress(0.85f, "Extracting sherpa-onnx binary...")
                binDir.mkdirs()

                if (isZip) {
                    ZipInputStream(tempArchive.inputStream()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val fileName = File(entry.name).name
                            val isMatch =
                                fileName.startsWith("sherpa-onnx") ||
                                    fileName.endsWith(".dll") ||
                                    fileName.endsWith(".exe")
                            if (!entry.isDirectory && isMatch) {
                                val target = File(binDir, fileName)
                                target.outputStream().use { zip.copyTo(it) }
                                target.setExecutable(true)
                            }
                            entry = zip.nextEntry
                        }
                    }
                } else {
                    val pb =
                        ProcessBuilder(
                            "tar",
                            "-xjf",
                            tempArchive.absolutePath,
                            "--wildcards",
                            "*/bin/sherpa-onnx*",
                            "--strip-components=2",
                            "-C",
                            binDir.absolutePath,
                        )
                    val proc = pb.start()
                    val ok = proc.waitFor(40, TimeUnit.SECONDS) && proc.exitValue() == 0
                    if (!ok) {
                        val pbAll = ProcessBuilder("tar", "-xjf", tempArchive.absolutePath, "-C", binDir.absolutePath)
                        pbAll.start().waitFor(40, TimeUnit.SECONDS)
                    }
                }

                val isWin = os.contains("win")
                val sherpaBin = File(binDir, if (isWin) "sherpa-onnx.exe" else "sherpa-onnx")
                val offlineBin = File(binDir, if (isWin) "sherpa-onnx-offline.exe" else "sherpa-onnx-offline")
                sherpaBin.setExecutable(true)
                offlineBin.setExecutable(true)

                onProgress(1.0f, "Complete")
                logger.info("Successfully downloaded sherpa-onnx binary to: {}", sherpaBin.absolutePath)
                if (sherpaBin.exists()) sherpaBin else offlineBin
            } finally {
                tempArchive.delete()
            }
        }
}

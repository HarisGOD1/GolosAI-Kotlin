package su.kamil.dev.golos.voice.download

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Discovers and manages Vosk CLI executable (e.g. vosk-transcriber).
 */
class VoskBinaryManager(
    val binDir: File = File(System.getProperty("user.home"), ".cache/golos-ai/bin"),
) {
    private val logger = LoggerFactory.getLogger(VoskBinaryManager::class.java)

    init {
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
    }

    fun findVoskBinary(customPath: String? = null): String {
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.canExecute()) {
                logger.info("Using custom configured vosk binary: {}", customFile.absolutePath)
                return customFile.absolutePath
            }
        }

        val envBin = System.getenv("VOSK_BIN")
        if (!envBin.isNullOrBlank() && File(envBin).canExecute()) {
            logger.info("Using vosk binary from VOSK_BIN: {}", envBin)
            return envBin
        }

        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val candidateNames =
            if (isWin) {
                listOf("vosk-transcriber.exe", "vosk.exe")
            } else {
                listOf("vosk-transcriber", "vosk")
            }

        for (name in candidateNames) {
            val localBin = File(binDir, name)
            if (localBin.exists()) {
                localBin.setExecutable(true)
                if (localBin.canExecute()) {
                    logger.info("Using local cached vosk binary: {}", localBin.absolutePath)
                    return localBin.absolutePath
                }
            }
        }

        val userHome = System.getProperty("user.home")
        val systemPaths =
            listOf(
                "$userHome/.local/bin/vosk-transcriber",
                "/usr/bin/vosk-transcriber",
                "/usr/local/bin/vosk-transcriber",
                "/opt/homebrew/bin/vosk-transcriber",
                "/usr/bin/vosk",
                "/usr/local/bin/vosk",
            )

        for (path in systemPaths) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                logger.info("Found system vosk binary: {}", path)
                return path
            }
        }

        try {
            val p = ProcessBuilder(if (isWin) "where" else "which", "vosk-transcriber").start()
            val output = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && output.isNotEmpty() && File(output).canExecute()) {
                return output
            }
        } catch (_: Exception) {
        }

        val localJar = File(binDir, "vosk.jar")
        if (localJar.exists()) {
            return localJar.absolutePath
        }

        return "vosk-transcriber"
    }

    fun isBinaryAvailable(customPath: String? = null): Boolean {
        if (File(binDir, "vosk.jar").exists()) return true
        val bin = findVoskBinary(customPath)
        if (bin.endsWith("vosk.jar") && File(bin).exists()) return true
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

    fun downloadPrecompiledBinary(onProgress: (Float, String) -> Unit = { _, _ -> }): Result<File> =
        runCatching {
            onProgress(0.1f, "Connecting to Maven repository...")
            val downloadUrl = "https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar"
            val client =
                java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .build()
            val request =
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI(downloadUrl))
                    .GET()
                    .build()
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw java.io.IOException("HTTP error ${response.statusCode()} while downloading $downloadUrl")
            }

            binDir.mkdirs()
            val targetJar = File(binDir, "vosk.jar")
            val tempJar = File(binDir, "vosk.jar.tmp")
            val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)

            try {
                response.body().use { input ->
                    tempJar.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val pct = 0.1f + 0.85f * (totalRead.toFloat() / totalBytes)
                                val mbRead = totalRead / (1024 * 1024)
                                val mbTotal = totalBytes / (1024 * 1024)
                                onProgress(pct, "Downloading Vosk: $mbRead MB / $mbTotal MB")
                            }
                        }
                    }
                }
                if (targetJar.exists()) targetJar.delete()
                tempJar.renameTo(targetJar)

                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val launcherFile = File(binDir, if (isWin) "vosk.bat" else "vosk")
                if (isWin) {
                    launcherFile.writeText("@echo off\r\njava -cp \"%~dp0vosk.jar\" org.vosk.LibVosk %*\r\n")
                } else {
                    val script = "#!/bin/sh\nDIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"\n" +
                        "exec java -cp \"\$DIR/vosk.jar\" org.vosk.LibVosk \"\$@\"\n"
                    launcherFile.writeText(script)
                    launcherFile.setExecutable(true)
                }

                onProgress(1.0f, "Complete")
                logger.info("Successfully installed Vosk library to: {}", targetJar.absolutePath)
                targetJar
            } finally {
                if (tempJar.exists()) tempJar.delete()
            }
        }
}

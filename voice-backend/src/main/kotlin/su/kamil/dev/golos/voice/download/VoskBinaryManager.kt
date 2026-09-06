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

        return "vosk-transcriber"
    }

    fun isBinaryAvailable(customPath: String? = null): Boolean {
        val bin = findVoskBinary(customPath)
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
}

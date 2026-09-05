package su.kamil.dev.golos.voice.download

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Discovers and manages the local whisper.cpp executable binary (`whisper-cli` or `main`).
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

    fun findWhisperBinary(): String {
        // 1. Check environment variable
        val envBin = System.getenv("WHISPER_BIN")
        if (!envBin.isNullOrBlank() && File(envBin).canExecute()) {
            logger.info("Using whisper binary from WHISPER_BIN: {}", envBin)
            return envBin
        }

        // 2. Check local user cache dir
        val localBin = File(binDir, "whisper-cli")
        if (localBin.exists()) {
            localBin.setExecutable(true)
            if (localBin.canExecute()) {
                logger.info("Using local cached whisper binary: {}", localBin.absolutePath)
                return localBin.absolutePath
            }
        }

        // 3. Search standard system paths
        val candidates = listOf(
            "/usr/bin/whisper-cli",
            "/usr/local/bin/whisper-cli",
            "/opt/homebrew/bin/whisper-cli",
            "/usr/bin/whisper",
            "whisper-cli"
        )

        for (candidate in candidates) {
            val f = File(candidate)
            if (f.exists() && f.canExecute()) {
                logger.info("Found system whisper binary: {}", candidate)
                return candidate
            }
        }

        // Default fallback to command name (will search PATH when executed)
        return "whisper-cli"
    }

    fun isBinaryAvailable(): Boolean {
        val bin = findWhisperBinary()
        if (bin != "whisper-cli") return true
        return try {
            val p = ProcessBuilder("which", "whisper-cli").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}

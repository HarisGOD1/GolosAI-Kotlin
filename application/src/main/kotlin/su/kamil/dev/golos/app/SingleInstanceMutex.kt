package su.kamil.dev.golos.app

import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Ensures single-instance execution (Criterion B-13).
 * Uses a file lock in the user cache directory (~/.cache/golos-ai/golos.lock).
 */
class SingleInstanceMutex(
    private val lockFile: File = File(System.getProperty("user.home"), ".cache/golos-ai/golos.lock"),
) {
    private val logger = LoggerFactory.getLogger(SingleInstanceMutex::class.java)
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    fun tryAcquire(): Boolean {
        return try {
            lockFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(lockFile, "rw")
            channel = raf.channel
            lock = channel?.tryLock()
            val acquired = lock != null
            if (acquired) {
                logger.info("SingleInstanceMutex lock successfully acquired at: {}", lockFile.absolutePath)
            } else {
                logger.warn("Another instance of GolosAI is already running. Lock file: {}", lockFile.absolutePath)
            }
            acquired
        } catch (e: Exception) {
            logger.error("Failed to acquire application lock", e)
            false
        }
    }

    fun release() {
        try {
            lock?.release()
            channel?.close()
            if (lockFile.exists()) {
                lockFile.delete()
            }
            logger.info("SingleInstanceMutex lock released.")
        } catch (e: Exception) {
            logger.warn("Error releasing application lock: {}", e.message)
        }
    }
}

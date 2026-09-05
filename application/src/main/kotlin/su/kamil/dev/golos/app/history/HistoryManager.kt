package su.kamil.dev.golos.app.history

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import su.kamil.dev.golos.core.model.HistoryEntry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages chronological dictation history with local persistence.
 */
class HistoryManager(
    private val historyFile: File = File(System.getProperty("user.home"), ".cache/golos-ai/history.jsonl"),
) {
    private val logger = LoggerFactory.getLogger(HistoryManager::class.java)
    private val entries = CopyOnWriteArrayList<HistoryEntry>()
    private val yaml = Yaml()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            if (historyFile.exists()) {
                // Newest lines are at the end of the file; reverse so index 0 is newest
                val lines = historyFile.readLines().asReversed()
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        parseLine(line.trim())?.let { entries.add(it) }
                    }
                }
                logger.info("Loaded {} history entries from disk", entries.size)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load history file: {}", e.message)
        }
    }

    @Synchronized
    fun addEntry(
        text: String,
        durationMs: Long,
        engine: String,
        language: String = "",
    ): HistoryEntry {
        val entry =
            HistoryEntry(
                text = text,
                durationMs = durationMs,
                engine = engine,
                language = language,
            )
        // Add to front of list (newest first)
        entries.add(0, entry)
        appendToFile(entry)
        return entry
    }

    fun getAll(): List<HistoryEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        try {
            if (historyFile.exists()) {
                historyFile.writeText("")
            }
        } catch (e: Exception) {
            logger.error("Failed to clear history file", e)
        }
    }

    private fun appendToFile(entry: HistoryEntry) {
        try {
            historyFile.parentFile?.mkdirs()
            val escapedText =
                entry.text
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")

            val json = """{"id":"${entry.id}","ts":${entry.timestamp},"dur":${entry.durationMs},"engine":"${entry.engine}","lang":"${entry.language}","text":"$escapedText"}"""
            historyFile.appendText(json + "\n")
        } catch (e: Exception) {
            logger.error("Failed to append history entry to disk", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLine(line: String): HistoryEntry? {
        return try {
            val map = yaml.load<Map<String, Any>>(line) ?: return null
            val text = map["text"]?.toString() ?: ""
            if (text.isEmpty()) return null

            HistoryEntry(
                id = map["id"]?.toString() ?: java.util.UUID.randomUUID().toString(),
                timestamp = (map["ts"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                text = text,
                durationMs = (map["dur"] as? Number)?.toLong() ?: 0L,
                engine = map["engine"]?.toString() ?: "",
                language = map["lang"]?.toString() ?: "",
            )
        } catch (_: Exception) {
            null
        }
    }
}

package su.kamil.dev.golos.core.model

import java.util.UUID

/**
 * Historical transcript record of a spoken dictation session.
 */
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val durationMs: Long = 0,
    val engine: String = "",
    val language: String = ""
)

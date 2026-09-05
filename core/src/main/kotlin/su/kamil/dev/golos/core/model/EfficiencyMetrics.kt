package su.kamil.dev.golos.core.model

/**
 * Detailed efficiency and speed performance metrics for a single recognized dictation replica.
 */
data class ReplicaMetrics(
    val text: String = "",
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val audioDurationMs: Long = 0,
    val latencyMs: Long = 0,
    val realTimeFactor: Float = 0.0f,
    val wordsPerMinute: Int = 0,
    val timeSavedSeconds: Float = 0.0f,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Summary aggregate efficiency metrics over a session history or lifetime usage.
 */
data class AggregateMetrics(
    val replicaCount: Long = 0,
    val totalAudioDurationMs: Long = 0,
    val totalLatencyMs: Long = 0,
    val totalWords: Long = 0,
    val totalChars: Long = 0,
    val avgAudioDurationMs: Long = 0,
    val avgLatencyMs: Long = 0,
    val avgRealTimeFactor: Float = 0.0f,
    val avgWordsPerMinute: Int = 0,
    val totalTimeSavedSeconds: Float = 0.0f,
)

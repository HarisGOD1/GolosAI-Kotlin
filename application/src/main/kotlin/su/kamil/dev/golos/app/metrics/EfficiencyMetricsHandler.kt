package su.kamil.dev.golos.app.metrics

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import su.kamil.dev.golos.core.model.AggregateMetrics
import su.kamil.dev.golos.core.model.HistoryEntry
import su.kamil.dev.golos.core.model.ReplicaMetrics
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service calculating speech efficiency, latency, RTF, WPM, and time saved across 3 scopes:
 * 1. Current Text (latest recognized replica)
 * 2. History Mean (current session / loaded history)
 * 3. All Time (cumulative lifetime statistics persisted to disk)
 */
class EfficiencyMetricsHandler(
    private val metricsFile: File = File(System.getProperty("user.home"), ".cache/golos-ai/metrics.json"),
) {
    private val logger = LoggerFactory.getLogger(EfficiencyMetricsHandler::class.java)
    private val yaml = Yaml()

    var currentTextMetrics: ReplicaMetrics = ReplicaMetrics()
        private set

    private val sessionReplicas = CopyOnWriteArrayList<ReplicaMetrics>()
    private var lifetimeAggregate: AggregateMetrics = AggregateMetrics()

    init {
        loadLifetimeMetrics()
    }

    companion object {
        private const val HUMAN_TYPING_WPM = 40.0
        private const val MILLIS_IN_SECOND = 1000.0
        private const val SECONDS_IN_MINUTE = 60.0
        private const val MS_PER_MINUTE = 60000.0
        private const val DEFAULT_LATENCY_DIVISOR = 2L
    }

    /**
     * Computes efficiency metrics for a newly transcribed replica and updates aggregates.
     */
    @Synchronized
    fun recordReplica(
        text: String,
        audioDurationMs: Long,
        latencyMs: Long,
    ): ReplicaMetrics {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wordCount = words.size
        val charCount = text.length

        val audioSec = (audioDurationMs.coerceAtLeast(1L)).toDouble() / MILLIS_IN_SECOND
        val latencySec = latencyMs.toDouble() / MILLIS_IN_SECOND

        val rtf = (latencySec / audioSec).toFloat()
        val minutes = audioDurationMs.toDouble() / MS_PER_MINUTE
        val wpm = if (minutes > 0.0) (wordCount.toDouble() / minutes).toInt() else 0

        // Time saved assuming standard typing rate of 40 WPM:
        // typingSec = (words / 40.0) * 60.0
        val standardTypingSec = (wordCount.toDouble() / HUMAN_TYPING_WPM) * SECONDS_IN_MINUTE
        val savedSec = (standardTypingSec - audioSec).coerceAtLeast(0.0).toFloat()

        val replica =
            ReplicaMetrics(
                text = text,
                wordCount = wordCount,
                charCount = charCount,
                audioDurationMs = audioDurationMs,
                latencyMs = latencyMs,
                realTimeFactor = rtf,
                wordsPerMinute = wpm,
                timeSavedSeconds = savedSec,
            )

        currentTextMetrics = replica
        sessionReplicas.add(replica)
        updateLifetime(replica)

        return replica
    }

    /**
     * Computes the mean aggregate metrics for the currently loaded history entries.
     */
    fun computeHistoryMean(entries: List<HistoryEntry>): AggregateMetrics {
        if (entries.isEmpty()) {
            return AggregateMetrics()
        }

        val count = entries.size.toLong()
        var totalAudioMs = 0L
        var totalWords = 0L
        var totalChars = 0L

        for (entry in entries) {
            totalAudioMs += entry.durationMs
            val words = entry.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            totalWords += words.size
            totalChars += entry.text.length
        }

        val avgAudio = totalAudioMs / count
        // For latency, take session replicas if available or estimate from audio
        val sessionAvgLatency =
            if (sessionReplicas.isNotEmpty()) {
                sessionReplicas.map { it.latencyMs }.average().toLong()
            } else {
                avgAudio / DEFAULT_LATENCY_DIVISOR
            }

        val totalMinutes = totalAudioMs.toDouble() / MS_PER_MINUTE
        val avgWpm = if (totalMinutes > 0.0) (totalWords.toDouble() / totalMinutes).toInt() else 0

        val totalAudioSec = totalAudioMs.toDouble() / MILLIS_IN_SECOND
        val totalStandardTypingSec = (totalWords.toDouble() / HUMAN_TYPING_WPM) * SECONDS_IN_MINUTE
        val totalSavedSec = (totalStandardTypingSec - totalAudioSec).coerceAtLeast(0.0).toFloat()

        val avgRtf =
            if (totalAudioMs > 0) {
                (sessionAvgLatency.toDouble() / avgAudio.coerceAtLeast(1L).toDouble()).toFloat()
            } else {
                0.0f
            }

        return AggregateMetrics(
            replicaCount = count,
            totalAudioDurationMs = totalAudioMs,
            totalLatencyMs = sessionAvgLatency * count,
            totalWords = totalWords,
            totalChars = totalChars,
            avgAudioDurationMs = avgAudio,
            avgLatencyMs = sessionAvgLatency,
            avgRealTimeFactor = avgRtf,
            avgWordsPerMinute = avgWpm,
            totalTimeSavedSeconds = totalSavedSec,
        )
    }

    /**
     * Returns cumulative all-time metrics.
     */
    @Synchronized
    fun getAllTimeMetrics(): AggregateMetrics = lifetimeAggregate

    @Synchronized
    private fun updateLifetime(replica: ReplicaMetrics) {
        val newCount = lifetimeAggregate.replicaCount + 1
        val newTotalAudio = lifetimeAggregate.totalAudioDurationMs + replica.audioDurationMs
        val newTotalLatency = lifetimeAggregate.totalLatencyMs + replica.latencyMs
        val newTotalWords = lifetimeAggregate.totalWords + replica.wordCount
        val newTotalChars = lifetimeAggregate.totalChars + replica.charCount
        val newTotalSaved = lifetimeAggregate.totalTimeSavedSeconds + replica.timeSavedSeconds

        val avgAudio = newTotalAudio / newCount
        val avgLatency = newTotalLatency / newCount
        val avgRtf = if (newTotalAudio > 0) (newTotalLatency.toDouble() / newTotalAudio.toDouble()).toFloat() else 0.0f

        val totalMinutes = newTotalAudio.toDouble() / MS_PER_MINUTE
        val avgWpm = if (totalMinutes > 0.0) (newTotalWords.toDouble() / totalMinutes).toInt() else 0

        lifetimeAggregate =
            AggregateMetrics(
                replicaCount = newCount,
                totalAudioDurationMs = newTotalAudio,
                totalLatencyMs = newTotalLatency,
                totalWords = newTotalWords,
                totalChars = newTotalChars,
                avgAudioDurationMs = avgAudio,
                avgLatencyMs = avgLatency,
                avgRealTimeFactor = avgRtf,
                avgWordsPerMinute = avgWpm,
                totalTimeSavedSeconds = newTotalSaved,
            )

        persistLifetimeMetrics()
    }

    private fun persistLifetimeMetrics() {
        try {
            metricsFile.parentFile?.mkdirs()
            val agg = lifetimeAggregate
            val json =
                """{"replicaCount":${agg.replicaCount},"totalAudioMs":${agg.totalAudioDurationMs},""" +
                    """"totalLatencyMs":${agg.totalLatencyMs},"totalWords":${agg.totalWords},""" +
                    """"totalChars":${agg.totalChars},"totalSavedSec":${agg.totalTimeSavedSeconds}}"""
            metricsFile.writeText(json)
        } catch (e: Exception) {
            logger.warn("Failed to persist lifetime metrics: {}", e.message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadLifetimeMetrics() {
        try {
            if (metricsFile.exists()) {
                val content = metricsFile.readText().trim()
                if (content.isNotEmpty()) {
                    val map = yaml.load<Map<String, Any>>(content) ?: return
                    val count = (map["replicaCount"] as? Number)?.toLong() ?: 0L
                    val totalAudio = (map["totalAudioMs"] as? Number)?.toLong() ?: 0L
                    val totalLatency = (map["totalLatencyMs"] as? Number)?.toLong() ?: 0L
                    val totalWords = (map["totalWords"] as? Number)?.toLong() ?: 0L
                    val totalChars = (map["totalChars"] as? Number)?.toLong() ?: 0L
                    val totalSaved = (map["totalSavedSec"] as? Number)?.toFloat() ?: 0.0f

                    if (count > 0) {
                        val avgAudio = totalAudio / count
                        val avgLatency = totalLatency / count
                        val avgRtf = if (totalAudio > 0) (totalLatency.toDouble() / totalAudio.toDouble()).toFloat() else 0.0f
                        val totalMinutes = totalAudio.toDouble() / MS_PER_MINUTE
                        val avgWpm = if (totalMinutes > 0.0) (totalWords.toDouble() / totalMinutes).toInt() else 0

                        lifetimeAggregate =
                            AggregateMetrics(
                                replicaCount = count,
                                totalAudioDurationMs = totalAudio,
                                totalLatencyMs = totalLatency,
                                totalWords = totalWords,
                                totalChars = totalChars,
                                avgAudioDurationMs = avgAudio,
                                avgLatencyMs = avgLatency,
                                avgRealTimeFactor = avgRtf,
                                avgWordsPerMinute = avgWpm,
                                totalTimeSavedSeconds = totalSaved,
                            )
                        logger.info("Loaded lifetime metrics: {} replicas, {} words", count, totalWords)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load metrics file: {}", e.message)
        }
    }
}

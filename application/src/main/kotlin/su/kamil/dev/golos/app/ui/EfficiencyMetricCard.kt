package su.kamil.dev.golos.app.ui

import su.kamil.dev.golos.core.model.AggregateMetrics
import su.kamil.dev.golos.core.model.ReplicaMetrics
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Metric card panel for displaying speech efficiency statistics on the Dashboard tab.
 * Uses proportional font for descriptive labels and monospace Hack font for numeric metrics.
 * Supports 3 scopes:
 * - Current Text (latest replica)
 * - History Mean (average over session / filtered history)
 * - All Time (cumulative lifetime statistics)
 */
class EfficiencyMetricCard(
    val titleText: String,
) : JPanel(BorderLayout(4, 4)) {
    val titleLabel =
        JLabel(titleText).apply {
            font = FontManager.bold(FontManager.DEFAULT_SIZE)
            foreground = Color(55, 65, 80)
        }

    private val line1Label =
        JLabel("-").apply {
            font = FontManager.mono(FontManager.SMALL_SIZE)
            foreground = Color(30, 40, 55)
        }

    private val line2Label =
        JLabel("-").apply {
            font = FontManager.mono(FontManager.SMALL_SIZE)
            foreground = Color(30, 40, 55)
        }

    private val line3Label =
        JLabel("-").apply {
            font = FontManager.mono(FontManager.SMALL_SIZE)
            foreground = Color(30, 40, 55)
        }

    companion object {
        private const val MILLIS_PER_SEC = 1000.0f
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600
        private const val CARD_PREF_WIDTH = 200
        private const val CARD_PREF_HEIGHT = 90
    }

    init {
        background = Color(248, 250, 252)
        border =
            CompoundBorder(
                LineBorder(Color(222, 228, 238), 1, true),
                EmptyBorder(8, 10, 8, 10),
            )
        preferredSize = Dimension(CARD_PREF_WIDTH, CARD_PREF_HEIGHT)

        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel, BorderLayout.WEST)
        add(headerPanel, BorderLayout.NORTH)

        val contentPanel = JPanel()
        contentPanel.isOpaque = false
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.add(line1Label)
        contentPanel.add(Box.createVerticalStrut(2))
        contentPanel.add(line2Label)
        contentPanel.add(Box.createVerticalStrut(2))
        contentPanel.add(line3Label)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun updateCurrentReplica(m: ReplicaMetrics) {
        if (m.audioDurationMs <= 0 && m.latencyMs <= 0) {
            line1Label.text = "Audio: - | Latency: -"
            line2Label.text = "Speed: - | RTF: -"
            line3Label.text = "Time Saved: -"
            return
        }

        val audioSec = m.audioDurationMs.toFloat() / MILLIS_PER_SEC
        val latencyMs = m.latencyMs
        val rtf = m.realTimeFactor
        val wpm = m.wordsPerMinute
        val savedSec = m.timeSavedSeconds

        line1Label.text = String.format(Locale.US, "Audio: %.1fs | Latency: %dms", audioSec, latencyMs)
        line2Label.text = String.format(Locale.US, "Speed: %d WPM | RTF: %.2fx", wpm, rtf)
        line3Label.text = String.format(Locale.US, "Time Saved: +%.1fs (%d words)", savedSec, m.wordCount)
    }

    fun updateHistoryMean(agg: AggregateMetrics) {
        if (agg.replicaCount <= 0) {
            line1Label.text = "Mean Audio: - | Latency: -"
            line2Label.text = "Mean Speed: - | RTF: -"
            line3Label.text = "Session: 0 replicas | 0 words"
            return
        }

        val avgAudioSec = agg.avgAudioDurationMs.toFloat() / MILLIS_PER_SEC
        val avgLatency = agg.avgLatencyMs
        val avgRtf = agg.avgRealTimeFactor
        val avgWpm = agg.avgWordsPerMinute
        val savedMin = agg.totalTimeSavedSeconds / SECONDS_PER_MINUTE

        line1Label.text = String.format(Locale.US, "Mean Audio: %.1fs | Latency: %dms", avgAudioSec, avgLatency)
        line2Label.text = String.format(Locale.US, "Mean Speed: %d WPM | RTF: %.2fx", avgWpm, avgRtf)
        line3Label.text =
            String.format(
                Locale.US,
                "Session: %d reps | +%.1fm saved",
                agg.replicaCount,
                savedMin,
            )
    }

    fun updateAllTime(agg: AggregateMetrics) {
        if (agg.replicaCount <= 0) {
            line1Label.text = "Lifetime: 0 replicas | 0h 0m"
            line2Label.text = "Words: 0 | Speed: - WPM"
            line3Label.text = "Time Saved: 0.0h"
            return
        }

        val totalSec = agg.totalAudioDurationMs / 1000
        val hours = totalSec / SECONDS_PER_HOUR
        val minutes = (totalSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val savedHours = agg.totalTimeSavedSeconds / SECONDS_PER_HOUR

        line1Label.text = String.format(Locale.US, "Lifetime: %d reps | %dh %dm", agg.replicaCount, hours, minutes)
        line2Label.text = String.format(Locale.US, "Words: %d | Speed: %d WPM", agg.totalWords, agg.avgWordsPerMinute)
        line3Label.text = String.format(Locale.US, "Time Saved: +%.1f hrs", savedHours)
    }
}

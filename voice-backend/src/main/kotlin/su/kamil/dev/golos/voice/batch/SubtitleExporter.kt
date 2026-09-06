package su.kamil.dev.golos.voice.batch

import su.kamil.dev.golos.core.model.ExportFormat
import su.kamil.dev.golos.core.model.TimecodedSegment
import su.kamil.dev.golos.core.model.TranscriptionResult
import java.io.File
import java.util.Locale

/**
 * Exporter for transcription timecodes and subtitles in TXT, SRT, and WebVTT formats (Criteria N-12, N-13).
 */
object SubtitleExporter {
    private const val MS_PER_SECOND = 1000L
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L

    fun formatSrtTimestamp(ms: Long): String {
        val clamped = maxOf(0L, ms)
        val hours = clamped / MS_PER_HOUR
        val minutes = (clamped % MS_PER_HOUR) / MS_PER_MINUTE
        val seconds = (clamped % MS_PER_MINUTE) / MS_PER_SECOND
        val millis = clamped % MS_PER_SECOND
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun formatVttTimestamp(ms: Long): String {
        val clamped = maxOf(0L, ms)
        val hours = clamped / MS_PER_HOUR
        val minutes = (clamped % MS_PER_HOUR) / MS_PER_MINUTE
        val seconds = (clamped % MS_PER_MINUTE) / MS_PER_SECOND
        val millis = clamped % MS_PER_SECOND
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    fun buildSrt(result: TranscriptionResult): String {
        val segments =
            if (result.segments.isNotEmpty()) {
                result.segments
            } else {
                listOf(TimecodedSegment(0L, maxOf(MS_PER_SECOND, result.durationMs), result.text))
            }

        return buildString {
            segments.forEachIndexed { index, seg ->
                appendLine(index + 1)
                appendLine("${formatSrtTimestamp(seg.startMs)} --> ${formatSrtTimestamp(seg.endMs)}")
                appendLine(seg.text)
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun buildVtt(result: TranscriptionResult): String {
        val segments =
            if (result.segments.isNotEmpty()) {
                result.segments
            } else {
                listOf(TimecodedSegment(0L, maxOf(MS_PER_SECOND, result.durationMs), result.text))
            }

        return buildString {
            appendLine("WEBVTT")
            appendLine()
            segments.forEach { seg ->
                appendLine("${formatVttTimestamp(seg.startMs)} --> ${formatVttTimestamp(seg.endMs)}")
                appendLine(seg.text)
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun exportToTxt(
        result: TranscriptionResult,
        targetFile: File,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(result.text.trim() + "\n", Charsets.UTF_8)
    }

    fun exportToSrt(
        result: TranscriptionResult,
        targetFile: File,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(buildSrt(result), Charsets.UTF_8)
    }

    fun exportToVtt(
        result: TranscriptionResult,
        targetFile: File,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(buildVtt(result), Charsets.UTF_8)
    }

    fun export(
        result: TranscriptionResult,
        baseFile: File,
        formats: Set<ExportFormat>,
    ) {
        val parent = baseFile.parentFile ?: File(".")
        val nameWithoutExt = baseFile.nameWithoutExtension
        if (formats.contains(ExportFormat.TXT)) {
            exportToTxt(result, File(parent, "$nameWithoutExt.txt"))
        }
        if (formats.contains(ExportFormat.SRT)) {
            exportToSrt(result, File(parent, "$nameWithoutExt.srt"))
        }
        if (formats.contains(ExportFormat.VTT)) {
            exportToVtt(result, File(parent, "$nameWithoutExt.vtt"))
        }
    }
}

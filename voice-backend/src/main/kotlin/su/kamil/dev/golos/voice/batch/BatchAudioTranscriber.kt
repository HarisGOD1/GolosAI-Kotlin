package su.kamil.dev.golos.voice.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.BatchItemState
import su.kamil.dev.golos.core.model.BatchItemStatus
import su.kamil.dev.golos.core.model.BatchProgress
import su.kamil.dev.golos.core.model.BatchSummary
import su.kamil.dev.golos.core.model.ExportFormat
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for batch audio file transcription with multi-format subtitles and RTF reporting (Criteria N-09..N-17).
 */
class BatchAudioTranscriber(
    var speechEngine: SpeechToTextEngine,
) {
    private val logger = LoggerFactory.getLogger(BatchAudioTranscriber::class.java)
    private val isCancelled = AtomicBoolean(false)

    var onProgress: ((BatchProgress) -> Unit)? = null
    var onItemStatusChanged: ((BatchItemStatus) -> Unit)? = null
    var onFileCompleted: ((File, TranscriptionResult?, BatchItemStatus) -> Unit)? = null

    fun cancel() {
        isCancelled.set(true)
    }

    suspend fun processDirectory(
        directory: File,
        recursive: Boolean = false,
        exportFormats: Set<ExportFormat> = setOf(ExportFormat.TXT, ExportFormat.SRT, ExportFormat.VTT),
        outputDirectory: File? = null,
    ): BatchSummary {
        val files = collectAudioFiles(directory, recursive)
        return processFiles(files, exportFormats, outputDirectory)
    }

    suspend fun processFiles(
        files: List<File>,
        exportFormats: Set<ExportFormat> = setOf(ExportFormat.TXT, ExportFormat.SRT, ExportFormat.VTT),
        outputDirectory: File? = null,
    ): BatchSummary =
        withContext(Dispatchers.IO) {
            isCancelled.set(false)
            val statuses = files.map { BatchItemStatus(file = it, state = BatchItemState.QUEUED) }.toMutableList()
            val totalFiles = files.size
            var completedCount = 0

            for (index in files.indices) {
                val file = files[index]
                if (isCancelled.get()) {
                    logger.info("Batch transcription cancelled by user.")
                    break
                }
                ensureActive()

                updateItem(statuses, index) { it.copy(state = BatchItemState.PROCESSING, progress = PROGRESS_INITIAL) }
                notifyProgress(completedCount, totalFiles, file, PROGRESS_INITIAL)

                val inspection = AudioFileInspector.inspect(file)
                when (inspection) {
                    is AudioFileInspection.Empty -> {
                        val status =
                            updateItem(statuses, index) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    progress = 1.0f,
                                    errorMessage = inspection.reason,
                                )
                            }
                        completedCount++
                        notifyProgress(completedCount, totalFiles, file, 1.0f)
                        onFileCompleted?.invoke(file, null, status)
                    }
                    is AudioFileInspection.Corrupted -> {
                        val status =
                            updateItem(statuses, index) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    progress = 1.0f,
                                    errorMessage = inspection.reason,
                                )
                            }
                        completedCount++
                        notifyProgress(completedCount, totalFiles, file, 1.0f)
                        onFileCompleted?.invoke(file, null, status)
                    }
                    is AudioFileInspection.NoAudioTrack -> {
                        val status =
                            updateItem(statuses, index) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    progress = 1.0f,
                                    errorMessage = inspection.reason,
                                )
                            }
                        completedCount++
                        notifyProgress(completedCount, totalFiles, file, 1.0f)
                        onFileCompleted?.invoke(file, null, status)
                    }
                    is AudioFileInspection.Unsupported -> {
                        val status =
                            updateItem(statuses, index) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    progress = 1.0f,
                                    errorMessage = inspection.reason,
                                )
                            }
                        completedCount++
                        notifyProgress(completedCount, totalFiles, file, 1.0f)
                        onFileCompleted?.invoke(file, null, status)
                    }
                    is AudioFileInspection.Valid -> {
                        try {
                            val readyFile = inspection.preparedFile ?: file
                            val startTime = System.currentTimeMillis()

                            updateItem(statuses, index) { it.copy(progress = PROGRESS_TRANSCRIBING) }
                            notifyProgress(completedCount, totalFiles, file, PROGRESS_TRANSCRIBING)

                            val result = speechEngine.transcribeFile(readyFile)
                            val procTimeMs = System.currentTimeMillis() - startTime
                            val audioDurMs =
                                if (inspection.durationMs > 0) inspection.durationMs else result.durationMs

                            val rtf =
                                if (audioDurMs > 0) {
                                    procTimeMs.toFloat() / audioDurMs.toFloat()
                                } else {
                                    0.0f
                                }

                            val targetBase =
                                if (outputDirectory != null) {
                                    File(outputDirectory, file.name)
                                } else {
                                    file
                                }
                            SubtitleExporter.export(result, targetBase, exportFormats)

                            val status =
                                updateItem(statuses, index) {
                                    it.copy(
                                        state = BatchItemState.COMPLETED,
                                        progress = 1.0f,
                                        result = result,
                                        audioDurationMs = audioDurMs,
                                        processingTimeMs = procTimeMs,
                                        rtf = rtf,
                                    )
                                }
                            completedCount++
                            notifyProgress(completedCount, totalFiles, file, 1.0f)
                            onFileCompleted?.invoke(file, result, status)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logger.error("Error transcribing batch file '{}'", file.name, e)
                            val status =
                                updateItem(statuses, index) {
                                    it.copy(
                                        state = BatchItemState.FAILED,
                                        progress = 1.0f,
                                        errorMessage = e.message ?: "Transcription failed",
                                    )
                                }
                            completedCount++
                            notifyProgress(completedCount, totalFiles, file, 1.0f)
                            onFileCompleted?.invoke(file, null, status)
                        }
                    }
                }
            }

            buildSummary(statuses)
        }

    private fun collectAudioFiles(
        dir: File,
        recursive: Boolean,
    ): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val allowedExtensions =
            setOf("wav", "wave", "mp3", "ogg", "flac", "m4a", "aac", "mp4", "mkv", "avi", "mov", "webm")
        val fileTree = if (recursive) dir.walkTopDown() else dir.listFiles()?.asSequence() ?: emptySequence()
        return fileTree
            .filter { it.isFile && it.extension.lowercase() in allowedExtensions }
            .sortedBy { it.name }
            .toList()
    }

    private fun updateItem(
        statuses: MutableList<BatchItemStatus>,
        index: Int,
        transform: (BatchItemStatus) -> BatchItemStatus,
    ): BatchItemStatus {
        val updated = transform(statuses[index])
        statuses[index] = updated
        onItemStatusChanged?.invoke(updated)
        return updated
    }

    private fun notifyProgress(
        completedFiles: Int,
        totalFiles: Int,
        currentFile: File?,
        currentFileProgress: Float,
    ) {
        val overall =
            if (totalFiles > 0) {
                (completedFiles.toFloat() + currentFileProgress) / totalFiles.toFloat()
            } else {
                1.0f
            }
        val progress =
            BatchProgress(
                completedFiles = completedFiles,
                totalFiles = totalFiles,
                currentFile = currentFile,
                currentFileProgress = currentFileProgress,
                overallProgress = minOf(1.0f, maxOf(0.0f, overall)),
            )
        onProgress?.invoke(progress)
    }

    private fun buildSummary(items: List<BatchItemStatus>): BatchSummary {
        val total = items.size
        val successful = items.count { it.state == BatchItemState.COMPLETED }
        val failed = items.count { it.state == BatchItemState.FAILED }
        val totalAudio = items.filter { it.state == BatchItemState.COMPLETED }.sumOf { it.audioDurationMs }
        val totalProcessing = items.filter { it.state == BatchItemState.COMPLETED }.sumOf { it.processingTimeMs }
        val overallRtf = if (totalAudio > 0) totalProcessing.toFloat() / totalAudio.toFloat() else 0.0f

        return BatchSummary(
            totalFiles = total,
            successfulFiles = successful,
            failedFiles = failed,
            totalAudioDurationMs = totalAudio,
            totalProcessingTimeMs = totalProcessing,
            overallRtf = overallRtf,
            items = items,
        )
    }

    companion object {
        private const val PROGRESS_INITIAL = 0.1f
        private const val PROGRESS_TRANSCRIBING = 0.4f
    }
}

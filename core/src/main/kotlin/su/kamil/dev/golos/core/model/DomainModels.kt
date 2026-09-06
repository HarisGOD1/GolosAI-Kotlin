package su.kamil.dev.golos.core.model

data class AudioDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val isLoopbackMonitor: Boolean = false,
)

/**
 * Represents a single timecoded subtitle/transcription segment (Criterion N-13).
 */
data class TimecodedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TranscriptionResult(
    val text: String,
    val durationMs: Long = 0,
    val isFinal: Boolean = true,
    val confidence: Float = 1.0f,
    val segments: List<TimecodedSegment> = emptyList(),
)

enum class TriggerMode {
    /** Press and hold key to dictate; release key to transcribe and inject (Default). */
    HOLD_TO_TALK,

    /** Press key once to start recording; press key a second time to stop and transcribe. */
    TOGGLE_ON_OFF,
}

data class HotkeyConfig(
    val keyName: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val keyCode: Int = 0,
    val triggerMode: TriggerMode = TriggerMode.HOLD_TO_TALK,
) {
    val displayText: String
        get() =
            buildString {
                if (ctrl) append("Ctrl+")
                if (alt) append("Alt+")
                if (shift) append("Shift+")
                if (meta) append("Meta+")
                append(keyName)
            }

    companion object {
        val DEFAULT = HotkeyConfig(keyName = "F8")

        fun parse(
            input: String,
            triggerMode: TriggerMode = TriggerMode.HOLD_TO_TALK,
        ): HotkeyConfig {
            val tokens = input.split("+", "-").map { it.trim() }.filter { it.isNotEmpty() }
            var ctrl = false
            var shift = false
            var alt = false
            var meta = false
            var primaryKey = "F8"

            for (token in tokens) {
                when (token.lowercase()) {
                    "ctrl", "control" -> ctrl = true
                    "shift" -> shift = true
                    "alt", "opt", "option" -> alt = true
                    "meta", "cmd", "command", "super", "win" -> meta = true
                    else -> primaryKey = token
                }
            }
            return HotkeyConfig(
                keyName = primaryKey,
                ctrl = ctrl,
                shift = shift,
                alt = alt,
                meta = meta,
                triggerMode = triggerMode,
            )
        }
    }
}

enum class InsertionMode {
    /** Synthesizes direct character keystrokes into the active field without touching the clipboard. */
    DIRECT_TYPING,

    /** Pastes text via system clipboard (Ctrl+V) */
    CLIPBOARD_PASTE,
}

enum class InjectionTiming {
    /** Injects transcribed text once when push-to-talk hotkey is released (Default, high accuracy). */
    ON_KEY_RELEASE,

    /** Streams and types words incrementally on the fly into the active text field while speaking. */
    ON_THE_FLY,
}

data class InjectionConfig(
    val mode: InsertionMode = InsertionMode.DIRECT_TYPING,
    val timing: InjectionTiming = InjectionTiming.ON_KEY_RELEASE,
    val copyToClipboard: Boolean = false,
    val copyToClipboardIfNoField: Boolean = true,
)

/**
 * Real-time audio warning categories (Criteria C-08, E-07).
 */
enum class AudioWarningType {
    NONE,
    SILENCE_MUTED,
    CLIPPING,
}

/**
 * Signal statistics computed for audio level indicators and clipping detection (Criteria C-07, C-08, E-07).
 */
@Suppress("MagicNumber")
data class AudioSignalStats(
    val rms: Float = 0.0f,
    val rmsDb: Float = -96.0f,
    val peak: Float = 0.0f,
    val peakDb: Float = -96.0f,
    val isClipping: Boolean = false,
    val isSilence: Boolean = false,
)

/**
 * Application profile modes for tailored speech post-processing (Criteria J-01..J-05).
 */
enum class ApplicationProfile {
    GENERAL,
    MESSENGER,
    MAIL,
    CODE,
}

/**
 * Information regarding the active foreground window context (Criteria J-04, M-02).
 */
data class ActiveWindowInfo(
    val appName: String = "",
    val windowTitle: String = "",
    val profile: ApplicationProfile = ApplicationProfile.GENERAL,
)

/**
 * Export format options for batch transcription outputs (Criteria N-12, N-13).
 */
enum class ExportFormat(val extension: String) {
    TXT(".txt"),
    SRT(".srt"),
    VTT(".vtt"),
}

/**
 * Processing state of an individual file in a batch queue (Criteria N-09, N-10).
 */
enum class BatchItemState {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
}

/**
 * Detailed status of an individual audio file in the batch queue (Criteria N-09, N-17).
 */
data class BatchItemStatus(
    val file: java.io.File,
    val state: BatchItemState = BatchItemState.QUEUED,
    val progress: Float = 0.0f,
    val result: TranscriptionResult? = null,
    val errorMessage: String? = null,
    val audioDurationMs: Long = 0L,
    val processingTimeMs: Long = 0L,
    val rtf: Float = 0.0f,
)

/**
 * Real-time overall progress for batch processing (Criterion N-10).
 */
data class BatchProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val currentFile: java.io.File?,
    val currentFileProgress: Float = 0.0f,
    val overallProgress: Float = 0.0f,
)

/**
 * Aggregate summary report of completed batch audio transcription (Criteria N-10, N-17).
 */
data class BatchSummary(
    val totalFiles: Int,
    val successfulFiles: Int,
    val failedFiles: Int,
    val totalAudioDurationMs: Long,
    val totalProcessingTimeMs: Long,
    val overallRtf: Float,
    val items: List<BatchItemStatus>,
)

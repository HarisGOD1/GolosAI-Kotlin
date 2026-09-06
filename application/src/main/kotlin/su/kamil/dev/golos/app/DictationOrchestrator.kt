package su.kamil.dev.golos.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.app.metrics.EfficiencyMetricsHandler
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.model.TriggerMode
import su.kamil.dev.golos.core.ports.AudioCapturePort
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.core.state.DictationStateMachine
import java.util.concurrent.atomic.AtomicLong

/**
 * Main orchestrator coordinating the dictation workflow:
 * Hotkey events -> Audio capture -> Speech recognition -> Text injection -> Efficiency Metrics.
 */
class DictationOrchestrator(
    val stateMachine: DictationStateMachine,
    var audioCapture: AudioCapturePort,
    var speechEngine: SpeechToTextEngine,
    val hotkeyHook: GlobalHotkeyHook,
    val textInjector: TextInjectorPort,
    val activeWindowDetector: su.kamil.dev.golos.core.ports.ActiveWindowDetectorPort =
        su.kamil.dev.golos.system.window.ActiveWindowDetector(),
    val metricsHandler: EfficiencyMetricsHandler = EfficiencyMetricsHandler(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LoggerFactory.getLogger(DictationOrchestrator::class.java)
    var selectedDevice: AudioDevice? = null
    var currentHotkey: HotkeyConfig = HotkeyConfig.DEFAULT
        private set
    var injectionConfig: su.kamil.dev.golos.core.model.InjectionConfig = su.kamil.dev.golos.core.model.InjectionConfig()

    var manualProfile: su.kamil.dev.golos.core.model.ApplicationProfile? = null
    var postProcessingSettings: su.kamil.dev.golos.core.model.PostProcessingSettings =
        su.kamil.dev.golos.core.model.PostProcessingSettings()
    var currentActiveWindow: su.kamil.dev.golos.core.model.ActiveWindowInfo =
        su.kamil.dev.golos.core.model.ActiveWindowInfo()
        private set

    val state: StateFlow<DictationState> = stateMachine.state
    var onTranscriptionCompleted: ((TranscriptionResult, SpeechToTextEngine) -> Unit)? = null
    var onTranscriptionWithContextCompleted: (
        (
            result: TranscriptionResult,
            engine: SpeechToTextEngine,
            window: su.kamil.dev.golos.core.model.ActiveWindowInfo,
            profile: su.kamil.dev.golos.core.model.ApplicationProfile,
        ) -> Unit
    )? = null
    var onAudioLevel: ((rmsDb: Float, peakDb: Float, isClipping: Boolean) -> Unit)? = null
    var onAudioWarning: ((su.kamil.dev.golos.core.model.AudioWarningType) -> Unit)? = null

    val batchTranscriber: su.kamil.dev.golos.voice.batch.BatchAudioTranscriber
        get() = su.kamil.dev.golos.voice.batch.BatchAudioTranscriber(speechEngine)

    fun getEffectiveProfile(): su.kamil.dev.golos.core.model.ApplicationProfile = manualProfile ?: currentActiveWindow.profile

    fun cycleManualProfile(): su.kamil.dev.golos.core.model.ApplicationProfile? {
        manualProfile =
            when (manualProfile) {
                null -> su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER
                su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER ->
                    su.kamil.dev.golos.core.model.ApplicationProfile.MAIL
                su.kamil.dev.golos.core.model.ApplicationProfile.MAIL ->
                    su.kamil.dev.golos.core.model.ApplicationProfile.CODE
                su.kamil.dev.golos.core.model.ApplicationProfile.CODE ->
                    su.kamil.dev.golos.core.model.ApplicationProfile.GENERAL
                su.kamil.dev.golos.core.model.ApplicationProfile.GENERAL -> null
            }
        return manualProfile
    }

    private var streamingJob: kotlinx.coroutines.Job? = null
    private val liveAudioStream = java.io.ByteArrayOutputStream()
    private val committedWords = mutableListOf<String>()
    private val recordingStartTime = AtomicLong(0L)
    private var silenceStartTime = 0L
    private var isTestingMic = false

    companion object {
        private const val MIN_REPLICA_DURATION_MS = 200L
        private const val STREAMING_DELAY_MS = 400L
        private const val MIN_STREAMING_BYTES = 16000
        private const val SILENCE_THRESHOLD_DB = -50.0f
        private const val SILENCE_DURATION_THRESHOLD_MS = 1500L
        private const val DB_SILENT = -96.0f
    }

    /**
     * Initializes hotkey binding and starts listening for push-to-talk events.
     */
    fun start(hotkeyConfig: HotkeyConfig = HotkeyConfig.DEFAULT): Result<Unit> {
        this.currentHotkey = hotkeyConfig
        logger.info(
            "Starting DictationOrchestrator with engine '{}' and key '{}' (mode: {})",
            speechEngine.displayName,
            hotkeyConfig.displayText,
            hotkeyConfig.triggerMode,
        )

        return hotkeyHook.register(
            config = hotkeyConfig,
            onKeyDown = { onHotkeyEvent(isKeyDown = true) },
            onKeyUp = { onHotkeyEvent(isKeyDown = false) },
        )
    }

    /**
     * Transcribes an audio file directly and records it in history.
     */
    suspend fun transcribeFile(file: java.io.File): TranscriptionResult {
        logger.info("Transcribing file '{}' with engine '{}'...", file.name, speechEngine.displayName)
        val startTime = System.currentTimeMillis()
        val effectiveProfile = getEffectiveProfile()
        if (speechEngine is su.kamil.dev.golos.voice.engine.WhisperCppEngine) {
            (speechEngine as su.kamil.dev.golos.voice.engine.WhisperCppEngine).activeProfile = effectiveProfile
            (speechEngine as su.kamil.dev.golos.voice.engine.WhisperCppEngine).postProcessingSettings =
                postProcessingSettings
        }
        val result = speechEngine.transcribeFile(file)
        val latencyMs = System.currentTimeMillis() - startTime
        if (result.text.isNotBlank()) {
            metricsHandler.recordReplica(
                text = result.text,
                audioDurationMs = result.durationMs,
                latencyMs = latencyMs,
            )
            onTranscriptionCompleted?.invoke(result, speechEngine)
            onTranscriptionWithContextCompleted?.invoke(
                result,
                speechEngine,
                currentActiveWindow,
                effectiveProfile,
            )
        }
        return result
    }

    /**
     * Updates global hotkey binding at runtime.
     */
    fun updateHotkey(newConfig: HotkeyConfig): Result<Unit> {
        logger.info("Rebinding hotkey to: {} (mode: {})", newConfig.displayText, newConfig.triggerMode)
        this.currentHotkey = newConfig
        hotkeyHook.unregister()
        return hotkeyHook.register(
            config = newConfig,
            onKeyDown = { onHotkeyEvent(isKeyDown = true) },
            onKeyUp = { onHotkeyEvent(isKeyDown = false) },
        )
    }

    private fun onHotkeyEvent(isKeyDown: Boolean) {
        if (currentHotkey.triggerMode == TriggerMode.TOGGLE_ON_OFF) {
            if (isKeyDown) {
                if (stateMachine.state.value == DictationState.RECORDING) {
                    onPushToTalkReleased()
                } else if (stateMachine.state.value == DictationState.IDLE) {
                    onPushToTalkPressed()
                }
            }
        } else {
            if (isKeyDown) {
                onPushToTalkPressed()
            } else {
                onPushToTalkReleased()
            }
        }
    }

    /**
     * Triggered when push-to-talk key is pressed.
     */
    fun onPushToTalkPressed() {
        if (stateMachine.startRecording()) {
            recordingStartTime.set(System.currentTimeMillis())
            silenceStartTime = 0L
            currentActiveWindow = activeWindowDetector.detectActiveWindow()
            logger.info(
                "Active window context: '{}' ('{}'), effective profile: {}",
                currentActiveWindow.appName,
                currentActiveWindow.windowTitle,
                getEffectiveProfile(),
            )
            onAudioWarning?.invoke(su.kamil.dev.golos.core.model.AudioWarningType.NONE)
            logger.info("State changed -> RECORDING. Starting audio capture.")
            synchronized(liveAudioStream) {
                liveAudioStream.reset()
            }
            synchronized(committedWords) {
                committedWords.clear()
            }

            try {
                audioCapture.onAudioLevel = { rmsDb, peakDb, isClipping ->
                    handleAudioLevel(rmsDb, peakDb, isClipping)
                }
                audioCapture.startCapture(selectedDevice) { chunk ->
                    logger.trace("Audio chunk captured: {} bytes", chunk.samples.size)
                    synchronized(liveAudioStream) {
                        liveAudioStream.write(chunk.samples)
                    }
                }

                if (injectionConfig.timing == su.kamil.dev.golos.core.model.InjectionTiming.ON_THE_FLY) {
                    streamingJob =
                        scope.launch {
                            startLiveStreamingLoop()
                        }
                }
            } catch (e: Exception) {
                logger.error("Failed to start audio capture", e)
                stateMachine.reset()
            }
        }
    }

    private suspend fun startLiveStreamingLoop() {
        while (stateMachine.state.value == DictationState.RECORDING) {
            kotlinx.coroutines.delay(STREAMING_DELAY_MS)
            if (stateMachine.state.value != DictationState.RECORDING) break

            val currentBytes =
                synchronized(liveAudioStream) {
                    if (liveAudioStream.size() >= MIN_STREAMING_BYTES) {
                        liveAudioStream.toByteArray()
                    } else {
                        null
                    }
                } ?: continue

            try {
                val partialChunk =
                    su.kamil.dev.golos.core.model.AudioChunk(
                        samples = currentBytes,
                        sampleRate = 16000,
                        channels = 1,
                        bitsPerSample = 16,
                    )
                val partial = speechEngine.transcribe(partialChunk)
                val newWords = partial.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

                synchronized(committedWords) {
                    if (newWords.size > committedWords.size) {
                        val deltaList = newWords.subList(committedWords.size, newWords.size)
                        val deltaText = (if (committedWords.isNotEmpty()) " " else "") + deltaList.joinToString(" ")
                        textInjector.injectText(deltaText, injectionConfig)
                        committedWords.addAll(deltaList)
                        logger.debug("On-the-fly injected delta words: '{}'", deltaText)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Partial streaming transcription skipped: {}", e.message)
            }
        }
    }

    /**
     * Triggered when push-to-talk key is released or toggled off.
     */
    fun onPushToTalkReleased() {
        streamingJob?.cancel()
        streamingJob = null
        silenceStartTime = 0L
        onAudioLevel?.invoke(DB_SILENT, DB_SILENT, false)
        onAudioWarning?.invoke(su.kamil.dev.golos.core.model.AudioWarningType.NONE)

        if (stateMachine.startProcessing()) {
            logger.info("State changed -> PROCESSING. Stopping capture and running speech recognition.")
            val recordedAudio =
                try {
                    audioCapture.stopCapture()
                } catch (e: Exception) {
                    logger.error("Failed to stop audio capture", e)
                    null
                }

            scope.launch {
                try {
                    if (recordedAudio != null && recordedAudio.samples.isNotEmpty()) {
                        // Criterion D-10: A delay of less than 200 ms does not create an empty replica
                        if (recordedAudio.durationMs < MIN_REPLICA_DURATION_MS) {
                            logger.info(
                                "Push-to-talk press too short ({} ms < {} ms); ignoring empty replica.",
                                recordedAudio.durationMs,
                                MIN_REPLICA_DURATION_MS,
                            )
                            return@launch
                        }

                        logger.info(
                            "Captured {} ms audio. Transcribing with '{}'...",
                            recordedAudio.durationMs,
                            speechEngine.displayName,
                        )

                        val effectiveProfile = getEffectiveProfile()
                        if (speechEngine is su.kamil.dev.golos.voice.engine.WhisperCppEngine) {
                            (speechEngine as su.kamil.dev.golos.voice.engine.WhisperCppEngine).activeProfile =
                                effectiveProfile
                            (speechEngine as su.kamil.dev.golos.voice.engine.WhisperCppEngine).postProcessingSettings =
                                postProcessingSettings
                        }

                        val inferenceStart = System.currentTimeMillis()
                        val result = speechEngine.transcribe(recordedAudio)
                        val inferenceLatency = System.currentTimeMillis() - inferenceStart

                        logger.info(
                            "Transcription completed in {} ms (latency: {} ms): \"{}\"",
                            result.durationMs,
                            inferenceLatency,
                            result.text,
                        )

                        if (result.text.isNotBlank()) {
                            metricsHandler.recordReplica(
                                text = result.text,
                                audioDurationMs = recordedAudio.durationMs,
                                latencyMs = inferenceLatency,
                            )

                            onTranscriptionCompleted?.invoke(result, speechEngine)
                            onTranscriptionWithContextCompleted?.invoke(
                                result,
                                speechEngine,
                                currentActiveWindow,
                                effectiveProfile,
                            )
                            if (injectionConfig.timing == su.kamil.dev.golos.core.model.InjectionTiming.ON_THE_FLY) {
                                val finalWords = result.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                val deltaText =
                                    synchronized(committedWords) {
                                        if (finalWords.size > committedWords.size) {
                                            val deltaList = finalWords.subList(committedWords.size, finalWords.size)
                                            (if (committedWords.isNotEmpty()) " " else "") + deltaList.joinToString(" ")
                                        } else {
                                            ""
                                        }
                                    }
                                if (deltaText.isNotEmpty()) {
                                    textInjector.injectText(deltaText, injectionConfig)
                                }
                            } else {
                                textInjector.injectText(result.text, injectionConfig)
                            }
                        } else {
                            logger.info("Transcription result is blank; skipping injection.")
                        }
                    } else {
                        logger.warn("No audio captured during push-to-talk press.")
                    }
                } catch (e: Exception) {
                    logger.error("Error processing speech or injecting text", e)
                } finally {
                    synchronized(committedWords) {
                        committedWords.clear()
                    }
                    stateMachine.finishProcessing()
                    logger.info("State changed -> IDLE.")
                }
            }
        }
    }

    private fun handleAudioLevel(
        rmsDb: Float,
        peakDb: Float,
        isClipping: Boolean,
    ) {
        onAudioLevel?.invoke(rmsDb, peakDb, isClipping)
        if (stateMachine.state.value == DictationState.RECORDING) {
            val now = System.currentTimeMillis()
            if (rmsDb < SILENCE_THRESHOLD_DB) {
                if (silenceStartTime == 0L) {
                    silenceStartTime = now
                } else if (now - silenceStartTime >= SILENCE_DURATION_THRESHOLD_MS) {
                    onAudioWarning?.invoke(su.kamil.dev.golos.core.model.AudioWarningType.SILENCE_MUTED)
                }
            } else {
                silenceStartTime = 0L
                if (isClipping) {
                    onAudioWarning?.invoke(su.kamil.dev.golos.core.model.AudioWarningType.CLIPPING)
                } else {
                    onAudioWarning?.invoke(su.kamil.dev.golos.core.model.AudioWarningType.NONE)
                }
            }
        }
    }

    /**
     * Starts preview audio level capture for microphone testing in settings dialog (Criterion C-07).
     */
    fun startAudioTest(onLevel: (rmsDb: Float, peakDb: Float, isClipping: Boolean) -> Unit) {
        if (stateMachine.state.value != DictationState.IDLE || isTestingMic) return
        isTestingMic = true
        audioCapture.onAudioLevel = { rmsDb, peakDb, isClipping ->
            onLevel(rmsDb, peakDb, isClipping)
        }
        audioCapture.startCapture(selectedDevice) { /* Discard test chunks */ }
    }

    /**
     * Stops preview audio level capture.
     */
    fun stopAudioTest() {
        if (!isTestingMic) return
        isTestingMic = false
        audioCapture.stopCapture()
        audioCapture.onAudioLevel = null
        onAudioLevel?.invoke(DB_SILENT, DB_SILENT, false)
        onAudioWarning?.invoke(su.kamil.dev.golos.core.model.AudioWarningType.NONE)
    }

    fun isTestingAudio(): Boolean = isTestingMic

    fun stop() {
        streamingJob?.cancel()
        streamingJob = null
        hotkeyHook.unregister()
        if (audioCapture.isCapturing()) {
            audioCapture.stopCapture()
        }
        stateMachine.reset()
        logger.info("DictationOrchestrator stopped.")
    }
}

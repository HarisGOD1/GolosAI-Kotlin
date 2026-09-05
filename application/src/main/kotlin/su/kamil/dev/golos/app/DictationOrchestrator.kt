package su.kamil.dev.golos.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.AudioCapturePort
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.core.state.DictationStateMachine

/**
 * Main orchestrator coordinating the dictation workflow:
 * Hotkey events -> Audio capture -> Speech recognition -> Text injection.
 */
class DictationOrchestrator(
    val stateMachine: DictationStateMachine,
    val audioCapture: AudioCapturePort,
    var speechEngine: SpeechToTextEngine,
    val hotkeyHook: GlobalHotkeyHook,
    val textInjector: TextInjectorPort,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val logger = LoggerFactory.getLogger(DictationOrchestrator::class.java)
    var selectedDevice: AudioDevice? = null
    var currentHotkey: HotkeyConfig = HotkeyConfig.DEFAULT
        private set
    var injectionConfig: su.kamil.dev.golos.core.model.InjectionConfig = su.kamil.dev.golos.core.model.InjectionConfig()

    val state: StateFlow<DictationState> = stateMachine.state

    /**
     * Initializes hotkey binding and starts listening for push-to-talk events.
     */
    fun start(hotkeyConfig: HotkeyConfig = HotkeyConfig.DEFAULT): Result<Unit> {
        this.currentHotkey = hotkeyConfig
        logger.info("Starting DictationOrchestrator with engine '{}' and key '{}'",
            speechEngine.displayName, hotkeyConfig.displayText
        )

        return hotkeyHook.register(
            config = hotkeyConfig,
            onKeyDown = { onPushToTalkPressed() },
            onKeyUp = { onPushToTalkReleased() }
        )
    }

    /**
     * Updates global hotkey binding at runtime.
     */
    fun updateHotkey(newConfig: HotkeyConfig): Result<Unit> {
        logger.info("Rebinding hotkey to: {}", newConfig.displayText)
        this.currentHotkey = newConfig
        hotkeyHook.unregister()
        return hotkeyHook.register(
            config = newConfig,
            onKeyDown = { onPushToTalkPressed() },
            onKeyUp = { onPushToTalkReleased() }
        )
    }

    /**
     * Triggered when push-to-talk key is held down.
     */
    fun onPushToTalkPressed() {
        if (stateMachine.startRecording()) {
            logger.info("State changed -> RECORDING. Starting audio capture.")
            try {
                audioCapture.startCapture(selectedDevice) { chunk ->
                    logger.trace("Audio chunk captured: {} bytes", chunk.samples.size)
                }
            } catch (e: Exception) {
                logger.error("Failed to start audio capture", e)
                stateMachine.reset()
            }
        }
    }

    /**
     * Triggered when push-to-talk key is released.
     */
    fun onPushToTalkReleased() {
        if (stateMachine.startProcessing()) {
            logger.info("State changed -> PROCESSING. Stopping capture and running speech recognition.")
            val recordedAudio = try {
                audioCapture.stopCapture()
            } catch (e: Exception) {
                logger.error("Failed to stop audio capture", e)
                null
            }

            scope.launch {
                try {
                    if (recordedAudio != null && recordedAudio.samples.isNotEmpty()) {
                        logger.info("Captured {} ms audio. Transcribing with '{}'...",
                            recordedAudio.durationMs, speechEngine.displayName
                        )

                        val result = speechEngine.transcribe(recordedAudio)
                        logger.info("Transcription completed in {} ms: \"{}\"",
                            result.durationMs, result.text
                        )

                        if (result.text.isNotBlank()) {
                            textInjector.injectText(result.text, injectionConfig)
                        } else {
                            logger.info("Transcription result is blank; skipping injection.")
                        }
                    } else {
                        logger.warn("No audio captured during push-to-talk press.")
                    }
                } catch (e: Exception) {
                    logger.error("Error processing speech or injecting text", e)
                } finally {
                    stateMachine.finishProcessing()
                    logger.info("State changed -> IDLE.")
                }
            }
        }
    }

    fun stop() {
        hotkeyHook.unregister()
        if (audioCapture.isCapturing()) {
            audioCapture.stopCapture()
        }
        stateMachine.reset()
        logger.info("DictationOrchestrator stopped.")
    }
}

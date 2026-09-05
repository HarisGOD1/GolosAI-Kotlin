package su.kamil.dev.golos.app

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.app.ui.PreferencesDialog
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.core.state.DictationStateMachine
import su.kamil.dev.golos.system.audio.JavaSoundAudioCapture
import su.kamil.dev.golos.system.input.ActiveWindowTextInjector
import su.kamil.dev.golos.system.keyboard.GlobalHotkeyManager
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine
import su.kamil.dev.golos.voice.engine.WhisperCppEngine
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import javax.swing.UIManager

private val logger = LoggerFactory.getLogger("GolosAI-Main")

fun main() {
    println("Hi Kostya!")
    logger.info("Initializing GolosAI Speech-to-Text Assistant...")

    // 1. Core State Machine
    val stateMachine = DictationStateMachine()

    // 2. System Utilities
    val audioCapture = JavaSoundAudioCapture()
    val hotkeyHook = GlobalHotkeyManager()
    val textInjector = ActiveWindowTextInjector()
    textInjector.initialize()

    // 3. Voice Backend Engines
    val whisperModelPath = System.getenv("WHISPER_MODEL") ?: "models/ggml-base.bin"
    val whisperBinary = System.getenv("WHISPER_BIN") ?: "whisper-cli"

    val engines = mutableListOf<SpeechToTextEngine>(
        MockSpeechToTextEngine(),
        WhisperCppEngine(
            modelPath = whisperModelPath,
            binaryPath = whisperBinary,
            displayName = "Whisper.cpp ($whisperBinary)"
        )
    )

    // 4. Application Orchestrator
    val orchestrator = DictationOrchestrator(
        stateMachine = stateMachine,
        audioCapture = audioCapture,
        speechEngine = engines.first(),
        hotkeyHook = hotkeyHook,
        textInjector = textInjector
    )

    // Start listening for default push-to-talk hotkey (F8)
    orchestrator.start(HotkeyConfig.DEFAULT)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down GolosAI...")
        orchestrator.stop()
    })

    // 5. User Interface (if not headless)
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}

        SwingUtilities.invokeLater {
            val dialog = PreferencesDialog(orchestrator, engines)
            dialog.isVisible = true
        }
    } else {
        logger.info("Running in headless mode. Global hotkeys active.")
        // Keep main thread alive
        Thread.currentThread().join()
    }
}

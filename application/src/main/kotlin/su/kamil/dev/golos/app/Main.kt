package su.kamil.dev.golos.app

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.app.config.SettingsManager
import su.kamil.dev.golos.app.history.HistoryManager
import su.kamil.dev.golos.app.ui.PreferencesDialog
import su.kamil.dev.golos.core.model.*
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.core.state.DictationStateMachine
import su.kamil.dev.golos.system.audio.JavaSoundAudioCapture
import su.kamil.dev.golos.system.autostart.AutoStartManager
import su.kamil.dev.golos.system.input.ActiveWindowTextInjector
import su.kamil.dev.golos.system.keyboard.GlobalHotkeyManager
import su.kamil.dev.golos.voice.download.ModelDownloader
import su.kamil.dev.golos.voice.download.WhisperBinaryManager
import su.kamil.dev.golos.voice.download.WhisperModelInfo
import su.kamil.dev.golos.voice.engine.InferenceDevice
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine
import su.kamil.dev.golos.voice.engine.WhisperCppEngine
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

private val logger = LoggerFactory.getLogger("GolosAI-Main")

fun main() {
    println("Hi Kostya!")
    logger.info("Initializing GolosAI Speech-to-Text Assistant...")

    // 1. Settings & Persistence Contract
    val settingsManager = SettingsManager()
    val config = settingsManager.load()
    val historyManager = HistoryManager()
    val autoStartManager = AutoStartManager()

    if (config.autostart.enabled) {
        autoStartManager.setAutoStart(true)
    }

    // 2. Core State Machine & System Utilities
    val stateMachine = DictationStateMachine()
    val audioCapture = JavaSoundAudioCapture()
    val hotkeyHook = GlobalHotkeyManager()
    val textInjector = ActiveWindowTextInjector()
    textInjector.initialize()

    // 3. Voice Backend Engines
    val binaryManager = WhisperBinaryManager()
    val modelDownloader = ModelDownloader()
    val defaultModelInfo = WhisperModelInfo.AVAILABLE_MODELS[1] // Base

    val configuredModel = config.engine.whisper.modelPath.ifEmpty {
        System.getenv("WHISPER_MODEL") ?: modelDownloader.getLocalModelFile(defaultModelInfo).absolutePath
    }
    val configuredBinary = binaryManager.findWhisperBinary(config.engine.whisper.binaryPath.ifEmpty { null })
    val configuredDevice = if (config.engine.whisper.device == "GPU") InferenceDevice.GPU else InferenceDevice.CPU

    val whisperEngine = WhisperCppEngine(
        modelPath = configuredModel,
        binaryPath = configuredBinary,
        language = config.engine.whisper.language,
        device = configuredDevice,
        displayName = "Whisper.cpp (${File(configuredBinary).name})"
    )

    val engines = mutableListOf<SpeechToTextEngine>(
        MockSpeechToTextEngine(),
        whisperEngine
    )

    val activeEngine = if (config.engine.selectedId == "whisper-cpp") whisperEngine else engines.first()

    // 4. Application Orchestrator
    val orchestrator = DictationOrchestrator(
        stateMachine = stateMachine,
        audioCapture = audioCapture,
        speechEngine = activeEngine,
        hotkeyHook = hotkeyHook,
        textInjector = textInjector
    )

    orchestrator.injectionConfig = config.insertion.toInjectionConfig()
    orchestrator.onTranscriptionCompleted = { result, engine ->
        historyManager.addEntry(
            text = result.text,
            durationMs = result.durationMs,
            engine = engine.displayName
        )
    }

    // Start listening for configured hotkey
    val hotkeyConfig = config.hotkey.toHotkeyConfig()
    orchestrator.start(hotkeyConfig)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down GolosAI...")
        orchestrator.stop()
    })

    // 5. User Interface
    if (!GraphicsEnvironment.isHeadless()) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}

        SwingUtilities.invokeLater {
            val dialog = PreferencesDialog(
                orchestrator = orchestrator,
                availableEngines = engines,
                settingsManager = settingsManager,
                historyManager = historyManager,
                autoStartManager = autoStartManager
            )
            dialog.isVisible = true
        }
    } else {
        logger.info("Running in headless mode. Global hotkeys active.")
        Thread.currentThread().join()
    }
}

package su.kamil.dev.golos.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import su.kamil.dev.golos.app.DictationOrchestrator
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.voice.download.ModelDownloader
import su.kamil.dev.golos.voice.download.WhisperBinaryManager
import su.kamil.dev.golos.voice.download.WhisperModelInfo
import su.kamil.dev.golos.voice.engine.InferenceDevice
import su.kamil.dev.golos.voice.engine.WhisperCppEngine
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder

/**
 * Swing Preferences and Status Window for GolosAI.
 * Organized into tabs for General Dictation settings and Whisper.cpp Model/Hardware management.
 */
class PreferencesDialog(
    private val orchestrator: DictationOrchestrator,
    private val availableEngines: List<SpeechToTextEngine>
) : JFrame("GolosAI - Speech to Text Assistant") {

    private val statusLabel = JLabel("Status: IDLE", SwingConstants.CENTER)
    private val micCombo = JComboBox<String>()
    private val engineCombo = JComboBox<String>()
    private val pttButton = JButton("🎙️ Hold to Speak (Push to Talk)")

    // Hotkey Controls
    private val ctrlCheck = JCheckBox("Ctrl")
    private val shiftCheck = JCheckBox("Shift")
    private val altCheck = JCheckBox("Alt")
    private val metaCheck = JCheckBox("Super/Win")
    private val keyField = JTextField("F8", 6)
    private val activeHotkeyLabel = JLabel(orchestrator.currentHotkey.displayText)
    private val applyHotkeyBtn = JButton("Apply")

    // Privacy & Insertion Controls
    private val insertionModeCombo = JComboBox(arrayOf("Direct Typing (Privacy-preserving)", "Clipboard Paste (Ctrl+V)"))
    private val copyClipboardCheck = JCheckBox("Save transcription to clipboard", false)
    private val fallbackClipboardCheck = JCheckBox("Save to clipboard if no active field focused", true)

    // Whisper & Model Management
    private val whisperEngine = availableEngines.filterIsInstance<WhisperCppEngine>().firstOrNull()
    private val modelDownloader = ModelDownloader()
    private val binaryManager = WhisperBinaryManager()

    private val modelCombo = JComboBox<String>()
    private val modelStatusLabel = JLabel("Status: Checking...")
    private val downloadModelBtn = JButton("Download Model")
    private val downloadProgressBar = JProgressBar(0, 100)
    private val downloadCancelFlag = AtomicBoolean(false)

    private val languageCombo = JComboBox(arrayOf(
        "Auto-Detect (auto)", "English (en)", "Russian (ru)", "Spanish (es)",
        "German (de)", "French (fr)", "Italian (it)", "Chinese (zh)", "Japanese (ja)"
    ))
    private val languageCodes = listOf("auto", "en", "ru", "es", "de", "fr", "it", "zh", "ja")

    private val deviceCombo = JComboBox(arrayOf(
        InferenceDevice.CPU.displayName,
        InferenceDevice.GPU.displayName
    ))

    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private var availableDevices: List<AudioDevice> = emptyList()

    init {
        initUi()
        observeState()
    }

    private fun initUi() {
        defaultCloseOperation = HIDE_ON_CLOSE
        setSize(620, 580)
        setLocationRelativeTo(null)
        layout = BorderLayout(10, 10)

        // Header Panel
        val headerPanel = JPanel(BorderLayout(8, 8))
        headerPanel.border = EmptyBorder(14, 16, 6, 16)
        val titleLabel = JLabel("GolosAI Dictation Assistant")
        titleLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        headerPanel.add(titleLabel, BorderLayout.NORTH)

        statusLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        statusLabel.isOpaque = true
        statusLabel.background = Color(230, 245, 230)
        statusLabel.foreground = Color(30, 120, 30)
        statusLabel.border = EmptyBorder(8, 8, 8, 8)
        headerPanel.add(statusLabel, BorderLayout.SOUTH)
        add(headerPanel, BorderLayout.NORTH)

        // Main Tabbed Content
        val tabbedPane = JTabbedPane()
        tabbedPane.addTab("Dictation & Hotkeys", createGeneralTab())
        tabbedPane.addTab("Whisper Models & Hardware", createWhisperTab())
        add(tabbedPane, BorderLayout.CENTER)

        // Bottom Action Panel
        val actionPanel = JPanel(BorderLayout(10, 10))
        actionPanel.border = EmptyBorder(8, 16, 14, 16)

        pttButton.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        pttButton.preferredSize = Dimension(200, 46)
        pttButton.isFocusPainted = false

        pttButton.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                orchestrator.onPushToTalkPressed()
            }

            override fun mouseReleased(e: MouseEvent?) {
                orchestrator.onPushToTalkReleased()
            }
        })

        actionPanel.add(pttButton, BorderLayout.CENTER)
        add(actionPanel, BorderLayout.SOUTH)

        // Initial setup
        syncInjectionConfig()
        renderStatus(orchestrator.state.value)
    }

    private fun createGeneralTab(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(12, 14, 12, 14)
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(6, 6, 6, 6)
            gridx = 0
            gridy = 0
            weightx = 0.3
        }

        // 1. Microphone Device Selection
        panel.add(JLabel("Microphone Input:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        refreshMicrophoneList()
        micCombo.addActionListener {
            val idx = micCombo.selectedIndex
            if (idx in availableDevices.indices) {
                orchestrator.selectedDevice = availableDevices[idx]
            }
        }
        panel.add(micCombo, gbc)

        // 2. Speech-to-Text Engine Selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.3
        panel.add(JLabel("Processing Engine:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        availableEngines.forEach { engineCombo.addItem(it.displayName) }
        engineCombo.addActionListener {
            val idx = engineCombo.selectedIndex
            if (idx in availableEngines.indices) {
                orchestrator.speechEngine = availableEngines[idx]
            }
        }
        panel.add(engineCombo, gbc)

        // 3. Active Hotkey Info
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.3
        panel.add(JLabel("Active Shortcut:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        activeHotkeyLabel.font = Font(Font.MONOSPACED, Font.BOLD, 13)
        activeHotkeyLabel.foreground = Color(0, 102, 204)
        panel.add(activeHotkeyLabel, gbc)

        // 4. Change Hotkey Configuration
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.3
        panel.add(JLabel("Change Shortcut:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7

        val hotkeyEditPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        hotkeyEditPanel.add(ctrlCheck)
        hotkeyEditPanel.add(shiftCheck)
        hotkeyEditPanel.add(altCheck)
        hotkeyEditPanel.add(metaCheck)

        keyField.toolTipText = "Key (e.g. L, F8, Space, Return)"
        hotkeyEditPanel.add(JLabel("+ Key:"))
        hotkeyEditPanel.add(keyField)

        applyHotkeyBtn.addActionListener {
            val primaryKey = keyField.text.trim().ifEmpty { "F8" }
            val newConfig = HotkeyConfig(
                keyName = primaryKey,
                ctrl = ctrlCheck.isSelected,
                shift = shiftCheck.isSelected,
                alt = altCheck.isSelected,
                meta = metaCheck.isSelected
            )
            val result = orchestrator.updateHotkey(newConfig)
            if (result.isSuccess) {
                activeHotkeyLabel.text = newConfig.displayText
                activeHotkeyLabel.foreground = Color(0, 128, 0)
                renderStatus(orchestrator.state.value)
                JOptionPane.showMessageDialog(
                    this,
                    "Hotkey updated to: ${newConfig.displayText}",
                    "Hotkey Registered",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } else {
                activeHotkeyLabel.foreground = Color(180, 0, 0)
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to register hotkey: ${result.exceptionOrNull()?.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
        hotkeyEditPanel.add(applyHotkeyBtn)
        panel.add(hotkeyEditPanel, gbc)

        // 5. Text Insertion Mode
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.3
        panel.add(JLabel("Insertion Mode:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        insertionModeCombo.addActionListener { syncInjectionConfig() }
        panel.add(insertionModeCombo, gbc)

        // 6. Clipboard Privacy
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.weightx = 0.3
        panel.add(JLabel("Clipboard Privacy:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val privacyPanel = JPanel(GridLayout(2, 1, 2, 2))
        copyClipboardCheck.addActionListener { syncInjectionConfig() }
        fallbackClipboardCheck.addActionListener { syncInjectionConfig() }
        privacyPanel.add(copyClipboardCheck)
        privacyPanel.add(fallbackClipboardCheck)
        panel.add(privacyPanel, gbc)

        return panel
    }

    private fun createWhisperTab(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(12, 14, 12, 14)
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(6, 6, 6, 6)
            gridx = 0
            gridy = 0
            weightx = 0.3
        }

        // 1. Model Selector
        panel.add(JLabel("Multilingual Model:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        WhisperModelInfo.AVAILABLE_MODELS.forEach { modelCombo.addItem(it.name) }
        modelCombo.selectedIndex = 1 // default to Base
        modelCombo.addActionListener { updateModelStatus() }
        panel.add(modelCombo, gbc)

        // 2. Model Status & Download Button
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.3
        panel.add(JLabel("Model File Status:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7

        val downloadActionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        downloadActionPanel.add(modelStatusLabel)
        downloadActionPanel.add(downloadModelBtn)
        downloadModelBtn.addActionListener { startModelDownload() }
        panel.add(downloadActionPanel, gbc)

        // 3. Download Progress
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.3
        panel.add(JLabel("Download Progress:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        downloadProgressBar.isStringPainted = true
        downloadProgressBar.string = "Idle"
        panel.add(downloadProgressBar, gbc)

        // 4. Language Selection
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.3
        panel.add(JLabel("Spoken Language:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        languageCombo.addActionListener {
            val idx = languageCombo.selectedIndex
            if (idx in languageCodes.indices && whisperEngine != null) {
                whisperEngine.language = languageCodes[idx]
            }
        }
        panel.add(languageCombo, gbc)

        // 5. Inference Device Selection (CPU vs GPU)
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.3
        panel.add(JLabel("Inference Device:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        deviceCombo.addActionListener {
            if (whisperEngine != null) {
                whisperEngine.device = if (deviceCombo.selectedIndex == 0) InferenceDevice.CPU else InferenceDevice.GPU
            }
        }
        panel.add(deviceCombo, gbc)

        // 6. Binary Path info
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.weightx = 0.3
        panel.add(JLabel("Whisper Executable:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val binaryPath = binaryManager.findWhisperBinary()
        val binLabel = JLabel(binaryPath)
        binLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        panel.add(binLabel, gbc)

        updateModelStatus()
        return panel
    }

    private fun updateModelStatus() {
        val selectedModel = WhisperModelInfo.AVAILABLE_MODELS[modelCombo.selectedIndex]
        val isDownloaded = modelDownloader.isModelDownloaded(selectedModel)
        if (isDownloaded) {
            modelStatusLabel.text = "✓ Downloaded"
            modelStatusLabel.foreground = Color(0, 140, 0)
            downloadModelBtn.text = "Re-download"
            // Update engine model path
            whisperEngine?.modelPath = modelDownloader.getLocalModelFile(selectedModel).absolutePath
        } else {
            modelStatusLabel.text = "✗ Not found locally"
            modelStatusLabel.foreground = Color(180, 0, 0)
            downloadModelBtn.text = "Download (${selectedModel.approximateSizeMb} MB)"
        }
    }

    private fun startModelDownload() {
        val selectedModel = WhisperModelInfo.AVAILABLE_MODELS[modelCombo.selectedIndex]
        downloadModelBtn.isEnabled = false
        downloadProgressBar.value = 0
        downloadProgressBar.string = "Connecting..."
        downloadCancelFlag.set(false)

        coroutineScope.launch {
            val result = modelDownloader.downloadModel(
                model = selectedModel,
                cancelFlag = downloadCancelFlag,
                onProgress = { bytesDownloaded, totalBytes, percent ->
                    SwingUtilities.invokeLater {
                        downloadProgressBar.value = percent
                        val mbDownloaded = bytesDownloaded / (1024 * 1024)
                        val mbTotal = totalBytes / (1024 * 1024)
                        downloadProgressBar.string = "$percent% ($mbDownloaded MB / $mbTotal MB)"
                    }
                }
            )

            SwingUtilities.invokeLater {
                downloadModelBtn.isEnabled = true
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    whisperEngine?.modelPath = file.absolutePath
                    updateModelStatus()
                    downloadProgressBar.string = "Completed"
                    JOptionPane.showMessageDialog(
                        this@PreferencesDialog,
                        "Whisper model downloaded successfully:\n${file.absolutePath}",
                        "Download Complete",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                } else {
                    downloadProgressBar.string = "Failed"
                    JOptionPane.showMessageDialog(
                        this@PreferencesDialog,
                        "Download failed: ${result.exceptionOrNull()?.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun syncInjectionConfig() {
        val mode = if (insertionModeCombo.selectedIndex == 0) InsertionMode.DIRECT_TYPING else InsertionMode.CLIPBOARD_PASTE
        orchestrator.injectionConfig = InjectionConfig(
            mode = mode,
            copyToClipboard = copyClipboardCheck.isSelected,
            copyToClipboardIfNoField = fallbackClipboardCheck.isSelected
        )
    }

    private fun refreshMicrophoneList() {
        micCombo.removeAllItems()
        availableDevices = orchestrator.audioCapture.getAvailableDevices()
        if (availableDevices.isEmpty()) {
            micCombo.addItem("Default System Microphone")
        } else {
            availableDevices.forEach { device ->
                micCombo.addItem(device.name)
            }
        }
    }

    private fun observeState() {
        coroutineScope.launch {
            orchestrator.state.collect { state ->
                SwingUtilities.invokeLater {
                    renderStatus(state)
                }
            }
        }
    }

    private fun renderStatus(state: DictationState) {
        when (state) {
            DictationState.IDLE -> {
                statusLabel.text = "Status: IDLE (Ready - Hold ${orchestrator.currentHotkey.displayText})"
                statusLabel.background = Color(230, 245, 230)
                statusLabel.foreground = Color(30, 120, 30)
                pttButton.text = "🎙️ Hold to Speak (${orchestrator.currentHotkey.displayText})"
            }
            DictationState.RECORDING -> {
                statusLabel.text = "Status: 🔴 RECORDING (Listening...)"
                statusLabel.background = Color(255, 230, 230)
                statusLabel.foreground = Color(180, 20, 20)
                pttButton.text = "🔴 Recording... Release to transcribe"
            }
            DictationState.PROCESSING -> {
                statusLabel.text = "Status: ⏳ PROCESSING (Transcribing & Pasting...)"
                statusLabel.background = Color(255, 250, 220)
                statusLabel.foreground = Color(160, 100, 0)
                pttButton.text = "⏳ Processing speech..."
            }
        }
    }
}

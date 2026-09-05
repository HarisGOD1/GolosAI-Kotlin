package su.kamil.dev.golos.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import su.kamil.dev.golos.app.DictationOrchestrator
import su.kamil.dev.golos.app.config.SettingsManager
import su.kamil.dev.golos.app.history.HistoryManager
import su.kamil.dev.golos.core.model.*
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.system.autostart.AutoStartManager
import su.kamil.dev.golos.voice.download.ModelDownloader
import su.kamil.dev.golos.voice.download.WhisperBinaryManager
import su.kamil.dev.golos.voice.download.WhisperModelInfo
import su.kamil.dev.golos.voice.engine.InferenceDevice
import su.kamil.dev.golos.voice.engine.WhisperCppEngine
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing Preferences, Status, and History Window for GolosAI.
 * Configured with YAML settings persistence, hotkey recorder, model management,
 * and dictation history with one-click copy.
 */
class PreferencesDialog(
    private val orchestrator: DictationOrchestrator,
    private val availableEngines: List<SpeechToTextEngine>,
    private val settingsManager: SettingsManager = SettingsManager(),
    private val historyManager: HistoryManager = HistoryManager(),
    private val autoStartManager: AutoStartManager = AutoStartManager(),
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
    private val recordBtn = JButton("🎙️ Record Shortcut (Click & Press Keys)")

    // Privacy & Insertion Controls
    private val insertionModeCombo = JComboBox(arrayOf("Direct Typing (Privacy-preserving)", "Clipboard Paste (Ctrl+V)"))
    private val timingCombo = JComboBox(arrayOf("On Key Release (Default - Whole phrase)", "On the Fly (Incremental live typing)"))
    private val copyClipboardCheck = JCheckBox("Save transcription to clipboard", false)
    private val fallbackClipboardCheck = JCheckBox("Save to clipboard if no active field focused", true)

    // Autostart Control
    private val autostartCheck = JCheckBox("Start GolosAI automatically on system login", autoStartManager.isAutoStartEnabled())

    // Whisper & Model Management
    private val whisperEngine = availableEngines.filterIsInstance<WhisperCppEngine>().firstOrNull()
    private val modelDownloader = ModelDownloader()
    private val binaryManager = WhisperBinaryManager()

    // Binary Controls
    private val binaryStatusLabel = JLabel("Checking binary...")
    private val binaryPathField = JTextField(whisperEngine?.binaryPath ?: "", 18)
    private val downloadBinaryBtn = JButton("Download whisper-cli")
    private val browseBinaryBtn = JButton("Browse...")

    // Model Controls
    private val modelCombo = JComboBox<String>()
    private val modelStatusLabel = JLabel("Status: Checking...")
    private val downloadModelBtn = JButton("Download Model")
    private val downloadProgressBar = JProgressBar(0, 100)
    private val downloadCancelFlag = AtomicBoolean(false)

    private val languageCombo =
        JComboBox(
            arrayOf(
                "Auto-Detect (auto)", "English (en)", "Russian (ru)", "Spanish (es)",
                "German (de)", "French (fr)", "Italian (it)", "Chinese (zh)", "Japanese (ja)",
            ),
        )
    private val languageCodes = listOf("auto", "en", "ru", "es", "de", "fr", "it", "zh", "ja")

    private val deviceCombo =
        JComboBox(
            arrayOf(
                InferenceDevice.CPU.displayName,
                InferenceDevice.GPU.displayName,
            ),
        )

    // History UI components
    private val historyListPanel = JPanel()
    private val historySearchField = JTextField(15)
    private val historyCountLabel = JLabel("Total: 0 entries")

    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private var availableDevices: List<AudioDevice> = emptyList()

    init {
        initUi()
        loadInitialConfig()
        observeState()
        wireHistoryListener()
    }

    private fun initUi() {
        defaultCloseOperation = HIDE_ON_CLOSE
        setSize(680, 680)
        setMinimumSize(Dimension(580, 520))
        setLocationRelativeTo(null)
        layout = BorderLayout(10, 10)

        // Header Panel
        val headerPanel = JPanel(BorderLayout(8, 8))
        headerPanel.border = EmptyBorder(12, 16, 4, 16)
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
        tabbedPane.addTab("📜 History", createHistoryTab())
        add(tabbedPane, BorderLayout.CENTER)

        // Bottom Action Panel
        val actionPanel = JPanel(BorderLayout(8, 8))
        actionPanel.border = EmptyBorder(4, 16, 12, 16)

        pttButton.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        pttButton.preferredSize = Dimension(200, 46)
        pttButton.isFocusPainted = false

        pttButton.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    orchestrator.onPushToTalkPressed()
                }

                override fun mouseReleased(e: MouseEvent?) {
                    orchestrator.onPushToTalkReleased()
                }
            },
        )
        actionPanel.add(pttButton, BorderLayout.CENTER)

        // Configuration Toolbar (Reset, Export, Import)
        val configBar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        val resetBtn = JButton("↺ Reset to Defaults")
        val exportBtn = JButton("📤 Export Settings...")
        val importBtn = JButton("📥 Import Settings...")

        resetBtn.toolTipText = "Reset all settings to initial defaults"
        exportBtn.toolTipText = "Export settings to YAML file"
        importBtn.toolTipText = "Import settings from YAML file"

        resetBtn.addActionListener {
            val confirm =
                JOptionPane.showConfirmDialog(
                    this,
                    "Reset all settings to default values?",
                    "Confirm Reset",
                    JOptionPane.YES_NO_OPTION,
                )
            if (confirm == JOptionPane.YES_OPTION) {
                val defaultConfig = settingsManager.resetToDefaults()
                applyConfigToUi(defaultConfig)
                JOptionPane.showMessageDialog(this, "Settings have been reset to defaults.", "Reset", JOptionPane.INFORMATION_MESSAGE)
            }
        }

        exportBtn.addActionListener {
            val chooser = JFileChooser()
            chooser.dialogTitle = "Export Settings to YAML"
            chooser.selectedFile = File("golos_settings.yaml")
            chooser.fileFilter = FileNameExtensionFilter("YAML Configuration (*.yaml, *.yml)", "yaml", "yml")
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                var f = chooser.selectedFile
                if (!f.name.endsWith(".yaml") && !f.name.endsWith(".yml")) {
                    f = File(f.parentFile, f.name + ".yaml")
                }
                saveCurrentConfig()
                settingsManager.exportConfig(f)
                JOptionPane.showMessageDialog(
                    this,
                    "Settings exported to:\n${f.absolutePath}",
                    "Export Successful",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }

        importBtn.addActionListener {
            val chooser = JFileChooser()
            chooser.dialogTitle = "Import Settings from YAML"
            chooser.fileFilter = FileNameExtensionFilter("YAML Configuration (*.yaml, *.yml)", "yaml", "yml")
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    val imported = settingsManager.importConfig(chooser.selectedFile)
                    applyConfigToUi(imported)
                    JOptionPane.showMessageDialog(
                        this,
                        "Settings successfully imported!",
                        "Import Successful",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } catch (ex: Exception) {
                    JOptionPane.showMessageDialog(this, "Failed to import settings: ${ex.message}", "Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        }

        configBar.add(resetBtn)
        configBar.add(exportBtn)
        configBar.add(importBtn)
        actionPanel.add(configBar, BorderLayout.SOUTH)

        add(actionPanel, BorderLayout.SOUTH)

        syncInjectionConfig()
        renderStatus(orchestrator.state.value)
    }

    private fun createGeneralTab(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(12, 14, 12, 14)
        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(5, 6, 5, 6)
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
                saveCurrentConfig()
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
                saveCurrentConfig()
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

        val hotkeyOuterPanel = JPanel(BorderLayout(4, 4))
        recordBtn.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        recordBtn.isFocusPainted = false

        var pendingKeyConfig: HotkeyConfig? = null

        recordBtn.addActionListener {
            recordBtn.text = "Press keys now... (e.g. Ctrl+Shift+L)"
            recordBtn.background = Color(210, 235, 255)
            recordBtn.requestFocusInWindow()
        }

        recordBtn.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (recordBtn.text.startsWith("Press") || recordBtn.text.startsWith("Holding")) {
                        val isCtrl = (e.modifiersEx and java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0
                        val isShift = (e.modifiersEx and java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0
                        val isAlt = (e.modifiersEx and java.awt.event.InputEvent.ALT_DOWN_MASK) != 0
                        val isMeta = (e.modifiersEx and java.awt.event.InputEvent.META_DOWN_MASK) != 0

                        val isModifierKey =
                            e.keyCode == KeyEvent.VK_CONTROL ||
                                e.keyCode == KeyEvent.VK_SHIFT ||
                                e.keyCode == KeyEvent.VK_ALT ||
                                e.keyCode == KeyEvent.VK_META

                        if (!isModifierKey && e.keyCode != KeyEvent.VK_UNDEFINED) {
                            val keyName = KeyEvent.getKeyText(e.keyCode)
                            val config =
                                HotkeyConfig(
                                    keyName = keyName,
                                    ctrl = isCtrl,
                                    shift = isShift,
                                    alt = isAlt,
                                    meta = isMeta,
                                    keyCode = e.keyCode,
                                )
                            pendingKeyConfig = config
                            recordBtn.text = "Holding: ${config.displayText}"
                            ctrlCheck.isSelected = isCtrl
                            shiftCheck.isSelected = isShift
                            altCheck.isSelected = isAlt
                            metaCheck.isSelected = isMeta
                            keyField.text = keyName
                        }
                    }
                }

                override fun keyReleased(e: KeyEvent) {
                    val config = pendingKeyConfig
                    if (config != null) {
                        pendingKeyConfig = null
                        val result = orchestrator.updateHotkey(config)
                        if (result.isSuccess) {
                            activeHotkeyLabel.text = config.displayText
                            activeHotkeyLabel.foreground = Color(0, 128, 0)
                            renderStatus(orchestrator.state.value)
                            recordBtn.text = "Recorded: ${config.displayText} (Click to change)"
                            recordBtn.background = null
                            saveCurrentConfig()
                        } else {
                            recordBtn.text = "🎙️ Record Shortcut (Click & Press Keys)"
                            recordBtn.background = null
                            JOptionPane.showMessageDialog(
                                this@PreferencesDialog,
                                "Failed to register hotkey: ${result.exceptionOrNull()?.message}",
                                "Error",
                                JOptionPane.ERROR_MESSAGE,
                            )
                        }
                    }
                }
            },
        )
        hotkeyOuterPanel.add(recordBtn, BorderLayout.NORTH)

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
            val newConfig =
                HotkeyConfig(
                    keyName = primaryKey,
                    ctrl = ctrlCheck.isSelected,
                    shift = shiftCheck.isSelected,
                    alt = altCheck.isSelected,
                    meta = metaCheck.isSelected,
                )
            val result = orchestrator.updateHotkey(newConfig)
            if (result.isSuccess) {
                activeHotkeyLabel.text = newConfig.displayText
                activeHotkeyLabel.foreground = Color(0, 128, 0)
                renderStatus(orchestrator.state.value)
                saveCurrentConfig()
                JOptionPane.showMessageDialog(
                    this,
                    "Hotkey updated to: ${newConfig.displayText}",
                    "Hotkey Registered",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            } else {
                activeHotkeyLabel.foreground = Color(180, 0, 0)
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to register hotkey: ${result.exceptionOrNull()?.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
        hotkeyEditPanel.add(applyHotkeyBtn)
        hotkeyOuterPanel.add(hotkeyEditPanel, BorderLayout.SOUTH)
        panel.add(hotkeyOuterPanel, gbc)

        // 5. Text Insertion Mode
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.3
        panel.add(JLabel("Insertion Mode:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        insertionModeCombo.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
        }
        panel.add(insertionModeCombo, gbc)

        // 6. Insertion Timing (On Key Release vs On the Fly)
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.weightx = 0.3
        panel.add(JLabel("Insertion Timing:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        timingCombo.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
        }
        panel.add(timingCombo, gbc)

        // 7. Clipboard Privacy
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.weightx = 0.3
        panel.add(JLabel("Clipboard Privacy:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val privacyPanel = JPanel(GridLayout(2, 1, 2, 2))
        copyClipboardCheck.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
        }
        fallbackClipboardCheck.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
        }
        privacyPanel.add(copyClipboardCheck)
        privacyPanel.add(fallbackClipboardCheck)
        panel.add(privacyPanel, gbc)

        // 8. System Autostart
        gbc.gridx = 0
        gbc.gridy = 7
        gbc.weightx = 0.3
        panel.add(JLabel("System Startup:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        autostartCheck.addActionListener {
            val enabled = autostartCheck.isSelected
            autoStartManager.setAutoStart(enabled)
            saveCurrentConfig()
        }
        panel.add(autostartCheck, gbc)

        // 9. Audio File Speech-to-Text
        gbc.gridx = 0
        gbc.gridy = 8
        gbc.weightx = 0.3
        panel.add(JLabel("Audio File Dictation:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val transcribeFileBtn = JButton("📁 Transcribe Audio File (WAV, MP3, FLAC)...")
        transcribeFileBtn.addActionListener {
            promptAndTranscribeAudioFile()
        }
        panel.add(transcribeFileBtn, gbc)

        return panel
    }

    private fun createWhisperTab(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(12, 14, 12, 14)
        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(5, 6, 5, 6)
                gridx = 0
                gridy = 0
                weightx = 0.3
            }

        // 1. Whisper Executable Management
        panel.add(JLabel("Whisper Executable:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7

        val binBox = JPanel(BorderLayout(4, 4))
        val binInputRow = JPanel(BorderLayout(4, 0))
        binInputRow.add(binaryPathField, BorderLayout.CENTER)

        val binBtnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        binBtnRow.add(browseBinaryBtn)
        binBtnRow.add(downloadBinaryBtn)
        binInputRow.add(binBtnRow, BorderLayout.EAST)
        binBox.add(binInputRow, BorderLayout.NORTH)
        binBox.add(binaryStatusLabel, BorderLayout.SOUTH)

        browseBinaryBtn.addActionListener {
            val chooser = JFileChooser()
            chooser.dialogTitle = "Select whisper-cli or main executable"
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                val f = chooser.selectedFile
                binaryPathField.text = f.absolutePath
                whisperEngine?.binaryPath = f.absolutePath
                updateBinaryStatus()
                saveCurrentConfig()
            }
        }

        downloadBinaryBtn.addActionListener {
            startBinaryDownload()
        }

        binaryPathField.addActionListener {
            val path = binaryPathField.text.trim()
            whisperEngine?.binaryPath = path
            updateBinaryStatus()
            saveCurrentConfig()
        }

        panel.add(binBox, gbc)

        // 2. Multilingual Model Selector
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.3
        panel.add(JLabel("Multilingual Model:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        WhisperModelInfo.AVAILABLE_MODELS.forEach { modelCombo.addItem(it.name) }
        modelCombo.selectedIndex = 1 // default to Base
        modelCombo.addActionListener {
            updateModelStatus()
            saveCurrentConfig()
        }
        panel.add(modelCombo, gbc)

        // 3. Model Status & Download Button
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.3
        panel.add(JLabel("Model File Status:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7

        val downloadActionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        downloadActionPanel.add(modelStatusLabel)
        downloadActionPanel.add(downloadModelBtn)
        downloadModelBtn.addActionListener { startModelDownload() }
        panel.add(downloadActionPanel, gbc)

        // 4. Download Progress
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.3
        panel.add(JLabel("Download Progress:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        downloadProgressBar.isStringPainted = true
        downloadProgressBar.string = "Idle"
        panel.add(downloadProgressBar, gbc)

        // 5. Language Selection
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.3
        panel.add(JLabel("Spoken Language:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        languageCombo.addActionListener {
            val idx = languageCombo.selectedIndex
            if (idx in languageCodes.indices && whisperEngine != null) {
                whisperEngine.language = languageCodes[idx]
                saveCurrentConfig()
            }
        }
        panel.add(languageCombo, gbc)

        // 6. Inference Device Selection (CPU vs GPU)
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.weightx = 0.3
        panel.add(JLabel("Inference Device:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        deviceCombo.addActionListener {
            if (whisperEngine != null) {
                whisperEngine.device = if (deviceCombo.selectedIndex == 0) InferenceDevice.CPU else InferenceDevice.GPU
                saveCurrentConfig()
            }
        }
        panel.add(deviceCombo, gbc)

        updateBinaryStatus()
        updateModelStatus()
        return panel
    }

    private fun createHistoryTab(): JPanel {
        val panel = JPanel(BorderLayout(8, 8))
        panel.border = EmptyBorder(10, 12, 10, 12)

        // Top Filter & Action Bar
        val topBar = JPanel(BorderLayout(6, 6))
        val searchBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        searchBox.add(JLabel("Search History:"))
        searchBox.add(historySearchField)
        val historyTranscribeBtn = JButton("📁 Transcribe Audio File...")
        historyTranscribeBtn.addActionListener { promptAndTranscribeAudioFile() }
        searchBox.add(historyTranscribeBtn)
        topBar.add(searchBox, BorderLayout.WEST)

        val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        historyCountLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 11)
        rightBox.add(historyCountLabel)

        val clearBtn = JButton("Clear History")
        clearBtn.addActionListener {
            val confirm =
                JOptionPane.showConfirmDialog(
                    this,
                    "Clear all transcription history?",
                    "Confirm Clear",
                    JOptionPane.YES_NO_OPTION,
                )
            if (confirm == JOptionPane.YES_OPTION) {
                historyManager.clear()
                refreshHistoryList()
            }
        }
        rightBox.add(clearBtn)
        topBar.add(rightBox, BorderLayout.EAST)
        panel.add(topBar, BorderLayout.NORTH)

        // Center Scrollable List
        historyListPanel.layout = BoxLayout(historyListPanel, BoxLayout.Y_AXIS)
        historyListPanel.border = EmptyBorder(4, 4, 4, 4)

        val scrollPane = JScrollPane(historyListPanel)
        scrollPane.verticalScrollBar.unitIncrement = 16
        scrollPane.border = LineBorder(Color.LIGHT_GRAY, 1)
        panel.add(scrollPane, BorderLayout.CENTER)

        historySearchField.addCaretListener {
            refreshHistoryList(historySearchField.text.trim())
        }

        refreshHistoryList()
        return panel
    }

    private fun refreshHistoryList(query: String = historySearchField.text.trim()) {
        historyListPanel.removeAll()
        val allEntries = historyManager.getAll()
        val filtered =
            if (query.isEmpty()) {
                allEntries
            } else {
                allEntries.filter { it.text.contains(query, ignoreCase = true) }
            }

        historyCountLabel.text = "Showing ${filtered.size} of ${allEntries.size} entries"

        if (filtered.isEmpty()) {
            val emptyLabel =
                JLabel(
                    if (allEntries.isEmpty()) {
                        "No dictations yet. Hold your hotkey (${orchestrator.currentHotkey.displayText}) and speak!"
                    } else {
                        "No transcriptions matching \"$query\""
                    },
                )
            emptyLabel.border = EmptyBorder(20, 20, 20, 20)
            emptyLabel.foreground = Color.GRAY
            historyListPanel.add(emptyLabel)
        } else {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            for (entry in filtered) {
                val card = createHistoryCard(entry, dateFormat)
                historyListPanel.add(card)
                historyListPanel.add(Box.createVerticalStrut(6))
            }
        }

        historyListPanel.revalidate()
        historyListPanel.repaint()
    }

    private fun createHistoryCard(
        entry: HistoryEntry,
        dateFormat: SimpleDateFormat,
    ): JPanel {
        val card = JPanel(BorderLayout(6, 6))
        card.border =
            CompoundBorder(
                LineBorder(Color(218, 224, 233), 1, true),
                EmptyBorder(8, 10, 8, 10),
            )
        card.background = Color(250, 252, 255)
        card.maximumSize = Dimension(Short.MAX_VALUE.toInt(), 120)

        // Top info row
        val topRow = JPanel(BorderLayout())
        topRow.isOpaque = false

        val dateStr = dateFormat.format(Date(entry.timestamp))
        val durSec = String.format("%.1fs", entry.durationMs / 1000.0)
        val infoLabel = JLabel("$dateStr  |  $durSec  |  ${entry.engine.ifEmpty { "GolosAI" }}")
        infoLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        infoLabel.foreground = Color(100, 110, 120)
        topRow.add(infoLabel, BorderLayout.WEST)

        // Copy Button
        val copyBtn = JButton("📋 Copy")
        copyBtn.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        copyBtn.isFocusPainted = false
        copyBtn.addActionListener {
            val selection = StringSelection(entry.text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            copyBtn.text = "✓ Copied!"
            Timer(1500) { copyBtn.text = "📋 Copy" }.apply {
                isRepeats = false
                start()
            }
        }
        topRow.add(copyBtn, BorderLayout.EAST)
        card.add(topRow, BorderLayout.NORTH)

        // Text Area
        val textArea = JTextArea(entry.text)
        textArea.isEditable = false
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = Color(250, 252, 255)
        textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        card.add(textArea, BorderLayout.CENTER)

        return card
    }

    private fun updateBinaryStatus() {
        val configuredPath = binaryPathField.text.trim()
        val foundPath = binaryManager.findWhisperBinary(configuredPath.ifEmpty { null })
        val exists = File(foundPath).canExecute()

        if (exists) {
            binaryStatusLabel.text = "✓ Ready: $foundPath"
            binaryStatusLabel.foreground = Color(0, 130, 0)
            binaryPathField.text = foundPath
            whisperEngine?.binaryPath = foundPath
        } else {
            binaryStatusLabel.text = "✗ Not Found! Click 'Download whisper-cli' or 'Browse' to set executable."
            binaryStatusLabel.foreground = Color(180, 0, 0)
        }
    }

    private fun startBinaryDownload() {
        downloadBinaryBtn.isEnabled = false
        downloadProgressBar.value = 0
        downloadProgressBar.string = "Downloading whisper-cli..."

        coroutineScope.launch {
            val result =
                binaryManager.downloadPrecompiledBinary { pct, status ->
                    SwingUtilities.invokeLater {
                        downloadProgressBar.value = (pct * 100).toInt()
                        downloadProgressBar.string = status
                    }
                }

            SwingUtilities.invokeLater {
                downloadBinaryBtn.isEnabled = true
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    binaryPathField.text = file.absolutePath
                    whisperEngine?.binaryPath = file.absolutePath
                    updateBinaryStatus()
                    saveCurrentConfig()
                    downloadProgressBar.string = "whisper-cli Installed"
                    JOptionPane.showMessageDialog(
                        this@PreferencesDialog,
                        "whisper-cli binary installed successfully:\n${file.absolutePath}",
                        "Installation Complete",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } else {
                    downloadProgressBar.string = "Download Failed"
                    JOptionPane.showMessageDialog(
                        this@PreferencesDialog,
                        "Failed to download whisper-cli:\n${result.exceptionOrNull()?.message}",
                        "Download Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun updateModelStatus() {
        val selectedModel = WhisperModelInfo.AVAILABLE_MODELS[modelCombo.selectedIndex]
        val isDownloaded = modelDownloader.isModelDownloaded(selectedModel)
        if (isDownloaded) {
            modelStatusLabel.text = "✓ Downloaded"
            modelStatusLabel.foreground = Color(0, 140, 0)
            downloadModelBtn.text = "Re-download"
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
            val result =
                modelDownloader.downloadModel(
                    model = selectedModel,
                    cancelFlag = downloadCancelFlag,
                    onProgress = { bytesDownloaded, totalBytes, percent ->
                        SwingUtilities.invokeLater {
                            downloadProgressBar.value = percent
                            val mbDownloaded = bytesDownloaded / (1024 * 1024)
                            val mbTotal = totalBytes / (1024 * 1024)
                            downloadProgressBar.string = "$percent% ($mbDownloaded MB / $mbTotal MB)"
                        }
                    },
                )

            SwingUtilities.invokeLater {
                downloadModelBtn.isEnabled = true
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    whisperEngine?.modelPath = file.absolutePath
                    updateModelStatus()
                    saveCurrentConfig()
                    downloadProgressBar.string = "Completed"
                    JOptionPane.showMessageDialog(
                        this@PreferencesDialog,
                        "Whisper model downloaded successfully:\n${file.absolutePath}",
                        "Download Complete",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                } else {
                    downloadProgressBar.string = "Failed"
                    JOptionPane.showMessageDialog(
                        this@PreferencesDialog,
                        "Download failed: ${result.exceptionOrNull()?.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    private fun syncInjectionConfig() {
        val mode = if (insertionModeCombo.selectedIndex == 0) InsertionMode.DIRECT_TYPING else InsertionMode.CLIPBOARD_PASTE
        val timing = if (timingCombo.selectedIndex == 0) InjectionTiming.ON_KEY_RELEASE else InjectionTiming.ON_THE_FLY
        orchestrator.injectionConfig =
            InjectionConfig(
                mode = mode,
                timing = timing,
                copyToClipboard = copyClipboardCheck.isSelected,
                copyToClipboardIfNoField = fallbackClipboardCheck.isSelected,
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

    private fun wireHistoryListener() {
        val oldCallback = orchestrator.onTranscriptionCompleted
        orchestrator.onTranscriptionCompleted = { result, engine ->
            oldCallback?.invoke(result, engine)
            historyManager.addEntry(
                text = result.text,
                durationMs = result.durationMs,
                engine = engine.displayName,
            )
            SwingUtilities.invokeLater {
                refreshHistoryList()
            }
        }
    }

    private fun loadInitialConfig() {
        val config = settingsManager.load()
        applyConfigToUi(config)
    }

    private fun applyConfigToUi(c: GolosConfig) {
        // Hotkey
        val hk = c.hotkey.toHotkeyConfig()
        orchestrator.updateHotkey(hk)
        activeHotkeyLabel.text = hk.displayText
        ctrlCheck.isSelected = c.hotkey.ctrl
        shiftCheck.isSelected = c.hotkey.shift
        altCheck.isSelected = c.hotkey.alt
        metaCheck.isSelected = c.hotkey.meta
        keyField.text = c.hotkey.keyName
        recordBtn.text = "Recorded: ${hk.displayText} (Click to change)"

        // Insertion
        val ins = c.insertion.toInjectionConfig()
        orchestrator.injectionConfig = ins
        insertionModeCombo.selectedIndex = if (ins.mode == InsertionMode.DIRECT_TYPING) 0 else 1
        timingCombo.selectedIndex = if (ins.timing == InjectionTiming.ON_KEY_RELEASE) 0 else 1
        copyClipboardCheck.isSelected = ins.copyToClipboard
        fallbackClipboardCheck.isSelected = ins.copyToClipboardIfNoField

        // Autostart
        autostartCheck.isSelected = c.autostart.enabled
        autoStartManager.setAutoStart(c.autostart.enabled)

        // Engine
        if (c.engine.selectedId == "whisper-cpp") {
            val idx = availableEngines.indexOfFirst { it is WhisperCppEngine }
            if (idx != -1) engineCombo.selectedIndex = idx
        } else {
            engineCombo.selectedIndex = 0
        }

        // Whisper details
        if (c.engine.whisper.binaryPath.isNotEmpty()) {
            binaryPathField.text = c.engine.whisper.binaryPath
            whisperEngine?.binaryPath = c.engine.whisper.binaryPath
        }
        val langIdx = languageCodes.indexOf(c.engine.whisper.language)
        if (langIdx != -1) languageCombo.selectedIndex = langIdx
        deviceCombo.selectedIndex = if (c.engine.whisper.device == "GPU") 1 else 0

        updateBinaryStatus()
        updateModelStatus()
        renderStatus(orchestrator.state.value)
    }

    private fun saveCurrentConfig() {
        val hk = orchestrator.currentHotkey
        val ins = orchestrator.injectionConfig
        val selectedEngineId = if (orchestrator.speechEngine is WhisperCppEngine) "whisper-cpp" else "mock"

        val config =
            GolosConfig(
                version = "1.0",
                hotkey = HotkeySettings.from(hk),
                insertion = InsertionSettings.from(ins),
                audio =
                    AudioSettings(
                        deviceName = orchestrator.selectedDevice?.id ?: "",
                        provider = "JavaSound",
                    ),
                engine =
                    EngineSettings(
                        selectedId = selectedEngineId,
                        whisper =
                            WhisperSettings(
                                binaryPath = whisperEngine?.binaryPath ?: "",
                                modelPath = whisperEngine?.modelPath ?: "",
                                modelName = WhisperModelInfo.AVAILABLE_MODELS.getOrNull(modelCombo.selectedIndex)?.name ?: "base",
                                language = whisperEngine?.language ?: "auto",
                                device = whisperEngine?.device?.name ?: "CPU",
                            ),
                    ),
                autostart =
                    AutostartSettings(
                        enabled = autostartCheck.isSelected,
                    ),
            )
        settingsManager.save(config)
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

    private fun promptAndTranscribeAudioFile() {
        val chooser = JFileChooser()
        chooser.dialogTitle = "Select Audio File for Speech Recognition"
        chooser.fileFilter = FileNameExtensionFilter(
            "Audio Files (*.wav, *.mp3, *.flac, *.ogg, *.m4a)",
            "wav", "mp3", "flac", "ogg", "m4a",
        )
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val audioFile = chooser.selectedFile
            statusLabel.text = "Status: ⏳ Transcribing file '${audioFile.name}'..."
            statusLabel.background = Color(255, 250, 220)
            statusLabel.foreground = Color(160, 100, 0)

            coroutineScope.launch {
                val result = orchestrator.transcribeFile(audioFile)
                SwingUtilities.invokeLater {
                    renderStatus(orchestrator.state.value)
                    refreshHistoryList()

                    if (copyClipboardCheck.isSelected && result.text.isNotBlank()) {
                        copyToClipboard(result.text)
                    }

                    showFileTranscriptionResult(audioFile, result)
                }
            }
        }
    }

    private fun showFileTranscriptionResult(file: File, result: TranscriptionResult) {
        val dialog = JDialog(this, "Transcription Result - ${file.name}", true)
        dialog.setSize(540, 400)
        dialog.setLocationRelativeTo(this)
        dialog.layout = BorderLayout(10, 10)

        val header = JPanel(BorderLayout(4, 4))
        header.border = EmptyBorder(12, 14, 4, 14)
        val infoLabel = JLabel(
            "<html><b>File:</b> ${file.name}<br/><b>Duration:</b> ${result.durationMs} ms | <b>Engine:</b> ${orchestrator.speechEngine.displayName}</html>"
        )
        header.add(infoLabel, BorderLayout.CENTER)
        dialog.add(header, BorderLayout.NORTH)

        val textArea = JTextArea(result.text)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.isEditable = false
        textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        textArea.margin = Insets(8, 8, 8, 8)
        val scrollPane = JScrollPane(textArea)
        scrollPane.border = CompoundBorder(EmptyBorder(0, 14, 0, 14), LineBorder(Color.LIGHT_GRAY))
        dialog.add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8))
        val copyBtn = JButton("📋 Copy to Clipboard")
        copyBtn.addActionListener {
            copyToClipboard(result.text)
            copyBtn.text = "✓ Copied!"
        }
        val closeBtn = JButton("Close")
        closeBtn.addActionListener { dialog.dispose() }

        buttonPanel.add(copyBtn)
        buttonPanel.add(closeBtn)
        dialog.add(buttonPanel, BorderLayout.SOUTH)

        dialog.isVisible = true
    }

    private fun copyToClipboard(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
}

package su.kamil.dev.golos.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
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
 * Swing UI for GolosAI Speech-to-Text Assistant.
 * Features a minimalist main dashboard with 3 primary status indicators,
 * secondary scrollable settings and model tabs, system tray integration,
 * and clean application termination.
 */
class PreferencesDialog(
    private val orchestrator: DictationOrchestrator,
    private val availableEngines: List<SpeechToTextEngine>,
    private val settingsManager: SettingsManager = SettingsManager(),
    private val historyManager: HistoryManager = HistoryManager(),
    private val autoStartManager: AutoStartManager = AutoStartManager(),
) : JFrame("GolosAI - Speech to Text Assistant") {
    private val logger = LoggerFactory.getLogger(PreferencesDialog::class.java)

    private val tabbedPane = JTabbedPane()

    // 3 Status Indicators for Main Page
    private val appIndicator = createIndicatorBadge("● Application: Active", Color(40, 160, 70), Color(236, 249, 240))
    private val speechIndicator = createIndicatorBadge("● Status: IDLE", Color(40, 160, 70), Color(236, 249, 240))
    private val modeIndicator =
        createIndicatorBadge("● Mode: [On Key Release] | [Direct Typing] | [F8]", Color(60, 65, 75), Color(242, 244, 247))

    // Main Page Push-to-Talk and Recent Dictation
    private val pttButton = JButton("🎙️ Hold to Speak (Push to Talk)")
    private val recentDictationArea =
        JTextArea("No dictations yet. Hold your hotkey or press Hold to Speak.", 4, 30).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            background = Color(248, 249, 250)
            border = EmptyBorder(6, 8, 6, 8)
        }

    // System Tray
    private var trayIcon: TrayIcon? = null

    // Audio Provider & Microphone
    private val audioProviderCombo = JComboBox(arrayOf("JavaSound (Standard Cross-Platform)", "PortAudio (Alternative Native)"))
    private val micCombo = JComboBox<String>()
    private val engineCombo = JComboBox<String>()

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
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    exitApplication()
                }
            },
        )
        setSize(650, 680)
        setMinimumSize(Dimension(540, 480))
        setLocationRelativeTo(null)
        layout = BorderLayout()

        // Tab 1: Primary Minimal Dashboard
        tabbedPane.addTab("Dashboard", createMainTab())

        // Tab 2: Secondary Settings with ScrollPane
        val settingsScroll =
            JScrollPane(createGeneralTab(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
                border = null
                viewport.background = Color(250, 250, 250)
                verticalScrollBar.unitIncrement = 16
            }
        tabbedPane.addTab("⚙️ Settings", settingsScroll)

        // Tab 3: Secondary Whisper & Models with ScrollPane
        val whisperScroll =
            JScrollPane(createWhisperTab(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
                border = null
                viewport.background = Color(250, 250, 250)
                verticalScrollBar.unitIncrement = 16
            }
        tabbedPane.addTab("🧠 Whisper & Models", whisperScroll)

        // Tab 4: Secondary History Tab
        tabbedPane.addTab("📜 History", createHistoryTab())

        add(tabbedPane, BorderLayout.CENTER)

        setupSystemTray()
        syncInjectionConfig()
        renderStatus(orchestrator.state.value)
    }

    private fun createMainTab(): JPanel {
        val mainPanel = JPanel(BorderLayout(12, 14))
        mainPanel.border = EmptyBorder(18, 20, 18, 20)
        mainPanel.background = Color(252, 252, 253)

        // 3 Minimal Status Indicators
        val indicatorsPanel = JPanel(GridLayout(3, 1, 0, 8))
        indicatorsPanel.isOpaque = false
        indicatorsPanel.add(appIndicator)
        indicatorsPanel.add(speechIndicator)
        indicatorsPanel.add(modeIndicator)
        mainPanel.add(indicatorsPanel, BorderLayout.NORTH)

        // Center Content: Push to Talk + Recent Dictation
        val centerPanel = JPanel(BorderLayout(10, 12))
        centerPanel.isOpaque = false

        pttButton.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        pttButton.preferredSize = Dimension(200, 48)
        pttButton.isFocusPainted = false
        pttButton.border = CompoundBorder(LineBorder(Color(180, 190, 205), 1, true), EmptyBorder(8, 16, 8, 16))
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
        centerPanel.add(pttButton, BorderLayout.NORTH)

        val recentBox = JPanel(BorderLayout(6, 6))
        recentBox.isOpaque = false
        val recentHeader = JPanel(BorderLayout())
        recentHeader.isOpaque = false
        val recentTitle = JLabel("Recent Speech Transcription:")
        recentTitle.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        recentTitle.foreground = Color(70, 75, 85)
        recentHeader.add(recentTitle, BorderLayout.WEST)

        val copyRecentBtn = JButton("📋 Copy")
        styleMinimalistButton(copyRecentBtn)
        copyRecentBtn.addActionListener {
            val text = recentDictationArea.text.trim()
            if (text.isNotEmpty() && !text.startsWith("No dictations yet")) {
                copyToClipboard(text)
                JOptionPane.showMessageDialog(this, "Copied transcription to clipboard!", "Copied", JOptionPane.INFORMATION_MESSAGE)
            }
        }
        recentHeader.add(copyRecentBtn, BorderLayout.EAST)
        recentBox.add(recentHeader, BorderLayout.NORTH)

        val recentScroll =
            JScrollPane(recentDictationArea).apply {
                border = LineBorder(Color(220, 224, 230), 1, true)
                preferredSize = Dimension(400, 120)
            }
        recentBox.add(recentScroll, BorderLayout.CENTER)
        centerPanel.add(recentBox, BorderLayout.CENTER)

        mainPanel.add(centerPanel, BorderLayout.CENTER)

        // Bottom Controls: Hide to Tray, Open Settings, Exit
        val bottomBar = JPanel(BorderLayout())
        bottomBar.isOpaque = false

        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        leftActions.isOpaque = false
        val hideTrayBtn = JButton("📥 Hide to Tray")
        styleMinimalistButton(hideTrayBtn)
        hideTrayBtn.addActionListener {
            isVisible = false
        }
        if (SystemTray.isSupported()) {
            leftActions.add(hideTrayBtn)
        }
        bottomBar.add(leftActions, BorderLayout.WEST)

        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        rightActions.isOpaque = false
        val openSettingsBtn = JButton("⚙️ Open Settings...")
        styleMinimalistButton(openSettingsBtn)
        openSettingsBtn.addActionListener {
            tabbedPane.selectedIndex = 1
        }
        val exitBtn = JButton("✖ Exit Application")
        styleMinimalistButton(exitBtn)
        exitBtn.foreground = Color(180, 40, 40)
        exitBtn.addActionListener {
            exitApplication()
        }
        rightActions.add(openSettingsBtn)
        rightActions.add(exitBtn)
        bottomBar.add(rightActions, BorderLayout.EAST)

        mainPanel.add(bottomBar, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun styleMinimalistButton(
        btn: JButton,
        isAccent: Boolean = false,
    ) {
        btn.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        btn.margin = Insets(4, 8, 4, 8)
        btn.isFocusPainted = false
        if (!isAccent) {
            btn.background = Color(248, 249, 250)
            btn.border = CompoundBorder(LineBorder(Color(215, 220, 225), 1, true), EmptyBorder(3, 8, 3, 8))
        }
    }

    private fun createIndicatorBadge(
        text: String,
        fg: Color,
        bg: Color,
    ): JLabel {
        return JLabel(text, SwingConstants.CENTER).apply {
            font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            isOpaque = true
            foreground = fg
            background = bg
            border = CompoundBorder(LineBorder(fg.darker(), 1, true), EmptyBorder(7, 12, 7, 12))
        }
    }

    private fun setupSystemTray() {
        if (!SystemTray.isSupported()) return
        try {
            val tray = SystemTray.getSystemTray()
            val popup = PopupMenu()

            val openItem = MenuItem("Open GolosAI")
            openItem.addActionListener {
                isVisible = true
                toFront()
                state = Frame.NORMAL
            }
            popup.add(openItem)

            val settingsItem = MenuItem("Settings")
            settingsItem.addActionListener {
                isVisible = true
                toFront()
                state = Frame.NORMAL
                tabbedPane.selectedIndex = 1
            }
            popup.add(settingsItem)

            popup.addSeparator()

            val exitItem = MenuItem("Exit GolosAI")
            exitItem.addActionListener {
                exitApplication()
            }
            popup.add(exitItem)

            val icon = TrayIcon(createTrayImage(orchestrator.state.value), "GolosAI Speech Assistant", popup)
            icon.isImageAutoSize = true
            icon.addActionListener {
                if (isVisible && isActive) {
                    isVisible = false
                } else {
                    isVisible = true
                    toFront()
                    state = Frame.NORMAL
                }
            }
            tray.add(icon)
            trayIcon = icon
        } catch (e: Exception) {
            logger.info("Could not initialize system tray: {}", e.message)
        }
    }

    private fun updateTrayIcon(state: DictationState) {
        if (!SystemTray.isSupported() || trayIcon == null) return
        val img = createTrayImage(state)
        trayIcon?.image = img
        val timingStr = if (timingCombo.selectedIndex == 0) "Release" else "Live"
        val insertStr = if (insertionModeCombo.selectedIndex == 0) "Direct" else "Clip"
        val hotkeyStr = orchestrator.currentHotkey.displayText
        trayIcon?.toolTip = "GolosAI - ${state.name} [$timingStr | $insertStr | $hotkeyStr]"
    }

    private fun createTrayImage(state: DictationState): Image {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val fillColor =
            when (state) {
                DictationState.IDLE -> Color(46, 204, 113) // Green
                DictationState.RECORDING -> Color(241, 196, 15) // Yellow/Amber
                DictationState.PROCESSING -> Color(231, 76, 60) // Red
            }
        val borderColor =
            when (state) {
                DictationState.IDLE -> Color(30, 132, 73)
                DictationState.RECORDING -> Color(183, 149, 11)
                DictationState.PROCESSING -> Color(176, 58, 46)
            }

        g2d.color = fillColor
        g2d.fillOval(1, 1, size - 3, size - 3)
        g2d.color = borderColor
        g2d.drawOval(1, 1, size - 3, size - 3)
        g2d.dispose()
        return image
    }

    fun exitApplication() {
        logger.info("Shutting down GolosAI and terminating JVM process...")
        try {
            if (SystemTray.isSupported() && trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon)
            }
        } catch (_: Throwable) {
        }
        orchestrator.stop()
        dispose()
        kotlin.system.exitProcess(0)
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

        // 1. Audio Provider Selection
        val provLabel = JLabel("Audio Provider:")
        provLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(provLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        audioProviderCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        audioProviderCombo.addActionListener {
            val isPortAudio = audioProviderCombo.selectedIndex == 1
            orchestrator.audioCapture =
                if (isPortAudio) {
                    su.kamil.dev.golos.system.audio.PortAudioAudioCapture()
                } else {
                    su.kamil.dev.golos.system.audio.JavaSoundAudioCapture()
                }
            refreshMicrophoneList()
            saveCurrentConfig()
        }
        panel.add(audioProviderCombo, gbc)

        // 2. Microphone / Output Monitor Device Selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.3
        val micLabel = JLabel("Audio Input / Monitor:")
        micLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(micLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val micPanel = JPanel(BorderLayout(0, 4))
        refreshMicrophoneList()
        micCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        micCombo.addActionListener {
            val idx = micCombo.selectedIndex
            if (idx in availableDevices.indices) {
                orchestrator.selectedDevice = availableDevices[idx]
                saveCurrentConfig()
            }
        }
        micPanel.add(micCombo, BorderLayout.NORTH)
        val clueLabel = JLabel("💡 Tip: Select 🎙️ [Microphone] for voice, or 🎧 [System Output Monitor] for desktop audio/calls.")
        clueLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 11)
        clueLabel.foreground = Color(100, 100, 100)
        micPanel.add(clueLabel, BorderLayout.SOUTH)
        panel.add(micPanel, gbc)

        // 3. Speech-to-Text Engine Selection
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.3
        val engLabel = JLabel("Recognition Engine:")
        engLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(engLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        availableEngines.forEach { engineCombo.addItem(it.displayName) }
        engineCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        engineCombo.addActionListener {
            val idx = engineCombo.selectedIndex
            if (idx in availableEngines.indices) {
                orchestrator.speechEngine = availableEngines[idx]
                saveCurrentConfig()
            }
        }
        panel.add(engineCombo, gbc)

        // 4. Global Push-to-Talk Hotkey
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.3
        val hkLabel = JLabel("Push-to-Talk Hotkey:")
        hkLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(hkLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val hotkeyOuterPanel = JPanel(BorderLayout(0, 4))

        styleMinimalistButton(recordBtn)
        var pendingKeyConfig: HotkeyConfig? = null

        recordBtn.addActionListener {
            recordBtn.text = "Press any key combo... (e.g. Ctrl+Space, F8)"
            recordBtn.background = Color(255, 245, 200)
            recordBtn.requestFocusInWindow()
        }

        recordBtn.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (recordBtn.text.startsWith("Press any key")) {
                        val isCtrl = e.isControlDown
                        val isShift = e.isShiftDown
                        val isAlt = e.isAltDown
                        val isMeta = e.isMetaDown

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
        ctrlCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        shiftCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        altCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        metaCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        keyField.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)

        hotkeyEditPanel.add(ctrlCheck)
        hotkeyEditPanel.add(shiftCheck)
        hotkeyEditPanel.add(altCheck)
        hotkeyEditPanel.add(metaCheck)

        keyField.toolTipText = "Key (e.g. L, F8, Space, Return)"
        val plusKeyLabel = JLabel("+ Key:")
        plusKeyLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        hotkeyEditPanel.add(plusKeyLabel)
        hotkeyEditPanel.add(keyField)

        styleMinimalistButton(applyHotkeyBtn)
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
        val insLabel = JLabel("Text Insertion Mode:")
        insLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(insLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        insertionModeCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        insertionModeCombo.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
        }
        panel.add(insertionModeCombo, gbc)

        // 6. Insertion Timing (On the Fly vs On Release)
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.weightx = 0.3
        val timingLabel = JLabel("Insertion Timing:")
        timingLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(timingLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        timingCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        timingCombo.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
            renderStatus(orchestrator.state.value)
        }
        panel.add(timingCombo, gbc)

        // 7. Clipboard Privacy Options
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.weightx = 0.3
        val privLabel = JLabel("Clipboard Privacy:")
        privLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(privLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val privacyPanel = JPanel(GridLayout(2, 1, 0, 2))
        copyClipboardCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        fallbackClipboardCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
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
        val autoLabel = JLabel("System Startup:")
        autoLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(autoLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        autostartCheck.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        autostartCheck.addActionListener {
            val enabled = autostartCheck.isSelected
            autoStartManager.setAutoStart(enabled)
            saveCurrentConfig()
        }
        panel.add(autostartCheck, gbc)

        // 9. Configuration Toolbar (Reset, Export, Import)
        gbc.gridx = 0
        gbc.gridy = 8
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        val configBar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6))
        val resetBtn = JButton("↺ Reset to Defaults")
        val exportBtn = JButton("📤 Export Settings...")
        val importBtn = JButton("📥 Import Settings...")

        styleMinimalistButton(resetBtn)
        styleMinimalistButton(exportBtn)
        styleMinimalistButton(importBtn)

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
        panel.add(configBar, gbc)

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
        val execLabel = JLabel("Whisper Executable:")
        execLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(execLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7

        val binBox = JPanel(BorderLayout(4, 4))
        val binInputRow = JPanel(BorderLayout(4, 0))
        binaryPathField.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        binInputRow.add(binaryPathField, BorderLayout.CENTER)

        val binBtnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        styleMinimalistButton(browseBinaryBtn)
        styleMinimalistButton(downloadBinaryBtn)
        binBtnRow.add(browseBinaryBtn)
        binBtnRow.add(downloadBinaryBtn)
        binInputRow.add(binBtnRow, BorderLayout.EAST)
        binBox.add(binInputRow, BorderLayout.NORTH)
        binaryStatusLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
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
        val modLabel = JLabel("Multilingual Model:")
        modLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(modLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        WhisperModelInfo.AVAILABLE_MODELS.forEach { modelCombo.addItem(it.name) }
        modelCombo.selectedIndex = 1 // default to Base
        modelCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        modelCombo.addActionListener {
            updateModelStatus()
            saveCurrentConfig()
        }
        panel.add(modelCombo, gbc)

        // 3. Model Status & Download Button
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.3
        val statLabel = JLabel("Model File Status:")
        statLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(statLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7

        val downloadActionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        modelStatusLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        downloadActionPanel.add(modelStatusLabel)
        styleMinimalistButton(downloadModelBtn)
        downloadActionPanel.add(downloadModelBtn)
        downloadModelBtn.addActionListener { startModelDownload() }
        panel.add(downloadActionPanel, gbc)

        // 4. Download Progress
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.3
        val progLabel = JLabel("Download Progress:")
        progLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(progLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        downloadProgressBar.isStringPainted = true
        downloadProgressBar.string = "Idle"
        downloadProgressBar.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        panel.add(downloadProgressBar, gbc)

        // 5. Language Selection
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.3
        val langLabel = JLabel("Spoken Language:")
        langLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(langLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        languageCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
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
        val devLabel = JLabel("Inference Device:")
        devLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(devLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        deviceCombo.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        deviceCombo.addActionListener {
            if (whisperEngine != null) {
                whisperEngine.device = if (deviceCombo.selectedIndex == 0) InferenceDevice.CPU else InferenceDevice.GPU
                saveCurrentConfig()
            }
        }
        panel.add(deviceCombo, gbc)

        // 7. Transcribe Audio File
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.weightx = 0.3
        val fileLabel = JLabel("Audio File Dictation:")
        fileLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        panel.add(fileLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        val transcribeFileBtn = JButton("📁 Transcribe Audio File (WAV, MP3, FLAC)...")
        styleMinimalistButton(transcribeFileBtn)
        transcribeFileBtn.addActionListener {
            promptAndTranscribeAudioFile()
        }
        panel.add(transcribeFileBtn, gbc)

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
        val sLabel = JLabel("Search History:")
        sLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        searchBox.add(sLabel)
        historySearchField.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        searchBox.add(historySearchField)
        val historyTranscribeBtn = JButton("📁 Transcribe File...")
        styleMinimalistButton(historyTranscribeBtn)
        historyTranscribeBtn.addActionListener { promptAndTranscribeAudioFile() }
        searchBox.add(historyTranscribeBtn)
        topBar.add(searchBox, BorderLayout.WEST)

        val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        historyCountLabel.font = Font(Font.SANS_SERIF, Font.ITALIC, 11)
        rightBox.add(historyCountLabel)

        val clearBtn = JButton("Clear History")
        styleMinimalistButton(clearBtn)
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
        styleMinimalistButton(copyBtn)
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
            if (oldCallback == null) {
                historyManager.addEntry(
                    text = result.text,
                    durationMs = result.durationMs,
                    engine = engine.displayName,
                )
            }
            SwingUtilities.invokeLater {
                recentDictationArea.text = result.text
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

        // Audio Provider & Device
        val isPortAudio = c.audio.provider.equals("PortAudio", ignoreCase = true)
        audioProviderCombo.selectedIndex = if (isPortAudio) 1 else 0
        orchestrator.audioCapture =
            if (isPortAudio) {
                su.kamil.dev.golos.system.audio.PortAudioAudioCapture()
            } else {
                su.kamil.dev.golos.system.audio.JavaSoundAudioCapture()
            }
        refreshMicrophoneList()
        if (c.audio.deviceName.isNotEmpty()) {
            val devIdx = availableDevices.indexOfFirst { it.id == c.audio.deviceName || it.name == c.audio.deviceName }
            if (devIdx != -1) {
                micCombo.selectedIndex = devIdx
                orchestrator.selectedDevice = availableDevices[devIdx]
            }
        }

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
        val isPortAudio = audioProviderCombo.selectedIndex == 1

        val config =
            GolosConfig(
                version = "1.0",
                hotkey = HotkeySettings.from(hk),
                insertion = InsertionSettings.from(ins),
                audio =
                    AudioSettings(
                        deviceName = orchestrator.selectedDevice?.id ?: "",
                        provider = if (isPortAudio) "PortAudio" else "JavaSound",
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
        val timingStr = if (timingCombo.selectedIndex == 0) "On Key Release" else "On the Fly"
        val insertStr = if (insertionModeCombo.selectedIndex == 0) "Direct Typing" else "Clipboard"
        val hotkeyStr = orchestrator.currentHotkey.displayText
        modeIndicator.text = "● Mode: [$timingStr] | [$insertStr] | [$hotkeyStr]"

        when (state) {
            DictationState.IDLE -> {
                speechIndicator.text = "● Status: IDLE"
                speechIndicator.foreground = Color(40, 160, 70)
                speechIndicator.background = Color(236, 249, 240)
                speechIndicator.border = CompoundBorder(LineBorder(Color(40, 160, 70), 1, true), EmptyBorder(7, 12, 7, 12))
                pttButton.text = "🎙️ Hold to Speak ($hotkeyStr)"
                pttButton.background = null
            }
            DictationState.RECORDING -> {
                speechIndicator.text = "● Status: LISTENING"
                speechIndicator.foreground = Color(190, 120, 0)
                speechIndicator.background = Color(255, 250, 225)
                speechIndicator.border = CompoundBorder(LineBorder(Color(190, 120, 0), 1, true), EmptyBorder(7, 12, 7, 12))
                pttButton.text = "🔴 Recording... Release to transcribe"
                pttButton.background = Color(255, 235, 235)
            }
            DictationState.PROCESSING -> {
                speechIndicator.text = "● Status: PROCESSING"
                speechIndicator.foreground = Color(210, 45, 45)
                speechIndicator.background = Color(255, 238, 238)
                speechIndicator.border = CompoundBorder(LineBorder(Color(210, 45, 45), 1, true), EmptyBorder(7, 12, 7, 12))
                pttButton.text = "⏳ Processing speech..."
                pttButton.background = Color(245, 245, 245)
            }
        }
        updateTrayIcon(state)
    }

    private fun promptAndTranscribeAudioFile() {
        val chooser = JFileChooser()
        chooser.dialogTitle = "Select Audio File for Speech Recognition"
        chooser.fileFilter =
            FileNameExtensionFilter(
                "Audio Files (*.wav, *.mp3, *.flac, *.ogg, *.m4a)",
                "wav", "mp3", "flac", "ogg", "m4a",
            )
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val audioFile = chooser.selectedFile
            speechIndicator.text = "● Status: TRANSCRIBING FILE"
            speechIndicator.foreground = Color(190, 120, 0)
            speechIndicator.background = Color(255, 250, 225)

            coroutineScope.launch {
                val result = orchestrator.transcribeFile(audioFile)
                SwingUtilities.invokeLater {
                    renderStatus(orchestrator.state.value)
                    recentDictationArea.text = result.text
                    refreshHistoryList()

                    if (copyClipboardCheck.isSelected && result.text.isNotBlank()) {
                        copyToClipboard(result.text)
                    }

                    showFileTranscriptionResult(audioFile, result)
                }
            }
        }
    }

    private fun showFileTranscriptionResult(
        file: File,
        result: TranscriptionResult,
    ) {
        val dialog = JDialog(this, "Transcription Result - ${file.name}", true)
        dialog.setSize(540, 400)
        dialog.setLocationRelativeTo(this)
        dialog.layout = BorderLayout(10, 10)

        val header = JPanel(BorderLayout(4, 4))
        header.border = EmptyBorder(12, 14, 4, 14)
        val infoLabel =
            JLabel(
                "<html><b>File:</b> ${file.name}<br/><b>Duration:</b> ${result.durationMs} ms | <b>Engine:</b> ${orchestrator.speechEngine.displayName}</html>",
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
        styleMinimalistButton(copyBtn)
        copyBtn.addActionListener {
            copyToClipboard(result.text)
            copyBtn.text = "✓ Copied!"
        }
        val closeBtn = JButton("Close")
        styleMinimalistButton(closeBtn)
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

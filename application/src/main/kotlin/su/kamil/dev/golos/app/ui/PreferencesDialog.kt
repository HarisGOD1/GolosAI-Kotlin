package su.kamil.dev.golos.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.app.DictationOrchestrator
import su.kamil.dev.golos.app.config.SettingsManager
import su.kamil.dev.golos.app.history.HistoryManager
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.AudioSettings
import su.kamil.dev.golos.core.model.AutostartSettings
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.EngineSettings
import su.kamil.dev.golos.core.model.GolosConfig
import su.kamil.dev.golos.core.model.HistoryEntry
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.model.HotkeySettings
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InjectionTiming
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.core.model.InsertionSettings
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.model.WhisperSettings
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.system.autostart.AutoStartManager
import su.kamil.dev.golos.voice.download.ModelDownloader
import su.kamil.dev.golos.voice.download.WhisperBinaryManager
import su.kamil.dev.golos.voice.download.WhisperModelInfo
import su.kamil.dev.golos.voice.engine.InferenceDevice
import su.kamil.dev.golos.voice.engine.WhisperCppEngine
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Image
import java.awt.Insets
import java.awt.MenuItem
import java.awt.Point
import java.awt.PopupMenu
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
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
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing UI for GolosAI Speech-to-Text Assistant.
 * Uses MIT Hack typography, 3 glowing indicator bulbs, collapsible mini floating bar,
 * multi-language UI localization (FR, DE, RU, JP, CN, TR, AR, ES, IT, EN),
 * and bilingual speech recognition (EN + any language).
 */
class PreferencesDialog(
    private val orchestrator: DictationOrchestrator,
    private val availableEngines: List<SpeechToTextEngine>,
    private val settingsManager: SettingsManager = SettingsManager(),
    private val historyManager: HistoryManager = HistoryManager(),
    private val autoStartManager: AutoStartManager = AutoStartManager(),
) : JPanel(BorderLayout()) {
    private val logger = LoggerFactory.getLogger(PreferencesDialog::class.java)

    var frame: JFrame? = null

    // Color definitions for Bulbs and Accents
    private val greenActive = Color(46, 204, 113)
    private val greenGlow = Color(46, 204, 113, 140)
    private val amberListening = Color(243, 156, 18)
    private val amberGlow = Color(243, 156, 18, 160)
    private val redProcessing = Color(231, 76, 60)
    private val redGlow = Color(231, 76, 60, 160)
    private val blueMode = Color(52, 152, 219)
    private val blueGlow = Color(52, 152, 219, 140)

    // Window Layout & Collapse State
    var isCollapsed = false
        private set
    private var expandedBounds: Rectangle = Rectangle(100, 100, 700, 720)
    val mainContainer = JPanel(BorderLayout())
    val collapsedBarPanel = JPanel()

    private val tabbedPane = JTabbedPane()

    // 3 Glowing Bulbs for Main Dashboard Tab
    private val dashboardAppBulb = BulbWidget(greenActive, greenGlow, "APP", "ACTIVE", compact = false)
    private val dashboardVoiceBulb = BulbWidget(greenActive, greenGlow, "VOICE", "IDLE", compact = false)
    private val dashboardModeBulb = BulbWidget(blueMode, blueGlow, "MODE", "[Rel | Direct | F8]", compact = false)

    // 3 Glowing Bulbs for Collapsed Mini-Bar
    private val miniAppBulb = BulbWidget(greenActive, greenGlow, "APP", "ACTIVE", compact = true)
    private val miniVoiceBulb = BulbWidget(greenActive, greenGlow, "VOICE", "IDLE", compact = true)
    private val miniModeBulb = BulbWidget(blueMode, blueGlow, "MODE", "F8 • Direct", compact = true)

    // Push-to-Talk Controls
    private val pttButton = JButton("Hold to Speak (Push to Talk)")
    private val miniPttButton = JButton("Speak")
    private val recentDictationArea =
        JTextArea(AppLocalization.tr("placeholder.no_dictations"), 4, 30).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = FontManager.regular(FontManager.INDICATOR_SIZE)
            background = Color(248, 249, 250)
            border = EmptyBorder(6, 8, 6, 8)
        }

    // System Tray
    private var trayIcon: TrayIcon? = null

    // UI Language Selector
    private val uiLanguageCombo = JComboBox(AppLanguage.entries.map { it.displayName }.toTypedArray())

    // Audio Provider & Microphone
    private val audioProviderCombo = JComboBox(arrayOf("JavaSound (Standard Cross-Platform)", "PortAudio (Alternative Native)"))
    private val micCombo = JComboBox<String>()
    private val engineCombo = JComboBox<String>()

    // Hotkey Controls
    private val ctrlCheck = JCheckBox("Ctrl")
    private val shiftCheck = JCheckBox("Shift")
    private val altCheck = JCheckBox("Alt")
    private val metaCheck = JCheckBox("Super/Win")
    private val keyField =
        JTextField("F8", 6).apply {
            font = FontManager.mono(FontManager.DEFAULT_SIZE)
        }
    private val activeHotkeyLabel =
        JLabel(orchestrator.currentHotkey.displayText).apply {
            font = FontManager.monoBold(FontManager.DEFAULT_SIZE)
        }
    private val applyHotkeyBtn = JButton("Apply")
    private val recordBtn = JButton("Record Shortcut (Click & Press Keys)")

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
    private val binaryPathField =
        JTextField(whisperEngine?.binaryPath ?: "", 18).apply {
            font = FontManager.mono(FontManager.SMALL_SIZE)
        }
    private val downloadBinaryBtn =
        JButton("Download CLI").apply {
            toolTipText = "Download precompiled whisper-cli binary"
        }
    private val browseBinaryBtn = JButton("Browse...")

    // Model Controls
    private val modelCombo = JComboBox<String>()
    private val modelStatusLabel = JLabel("Status: Checking...")
    private val downloadModelBtn = JButton("Download Model")
    private val downloadProgressBar = JProgressBar(0, 100)
    private val downloadCancelFlag = AtomicBoolean(false)

    // Spoken Languages (FR, DE, RU, JP, CN, TR, AR, ES, IT, PT, KO, UK, PL, NL, EN, Auto)
    private val languageCombo =
        JComboBox(
            arrayOf(
                "Auto-Detect (auto)",
                "English (en)",
                "French (fr)",
                "German (de)",
                "Russian (ru)",
                "Japanese (ja)",
                "Chinese (zh)",
                "Turkish (tr)",
                "Arabic (ar)",
                "Spanish (es)",
                "Italian (it)",
                "Portuguese (pt)",
                "Korean (ko)",
                "Ukrainian (uk)",
                "Polish (pl)",
                "Dutch (nl)",
            ),
        )
    private val languageCodes =
        listOf("auto", "en", "fr", "de", "ru", "ja", "zh", "tr", "ar", "es", "it", "pt", "ko", "uk", "pl", "nl")

    // Bilingual Mode (EN + Selected Language)
    private val bilingualModeCheck =
        JCheckBox("Bilingual Mode (EN + Selected Language)", false).apply {
            toolTipText = "Allows mixed code-switching between English and selected language"
        }

    private val deviceCombo =
        JComboBox(
            arrayOf(
                InferenceDevice.CPU.displayName,
                InferenceDevice.GPU.displayName,
            ),
        )

    // History UI components
    private val historyListPanel = JPanel()
    private val historySearchField = JTextField(12)
    private val historyCountLabel = JLabel("Total: 0 entries")

    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private var availableDevices: List<AudioDevice> = emptyList()

    // Localized UI Elements (dynamic language switching)
    private val collapseBtn = JButton("[-] " + AppLocalization.tr("btn.collapse"))
    private val expandBtn = JButton("[+] " + AppLocalization.tr("btn.expand"))
    private val closeBtn = JButton("X")
    private val copyRecentBtn = JButton(AppLocalization.tr("btn.copy"))
    private val hideTrayBtn = JButton(AppLocalization.tr("btn.hide_tray"))
    private val openSettingsBtn = JButton(AppLocalization.tr("btn.open_settings"))
    private val exitBtn = JButton(AppLocalization.tr("btn.exit"))
    private val recentTitle = JLabel(AppLocalization.tr("label.recent_speech"))

    private val langLabel = JLabel(AppLocalization.tr("label.ui_language"))
    private val provLabel = JLabel(AppLocalization.tr("label.audio_provider"))
    private val micLabel = JLabel(AppLocalization.tr("label.microphone"))
    private val engLabel = JLabel(AppLocalization.tr("label.engine"))
    private val hkLabel = JLabel(AppLocalization.tr("label.hotkey"))
    private val insLabel = JLabel(AppLocalization.tr("label.insertion_mode"))
    private val timingLabel = JLabel(AppLocalization.tr("label.timing"))
    private val privLabel = JLabel(AppLocalization.tr("label.clipboard_privacy"))
    private val autoLabel = JLabel(AppLocalization.tr("label.system_startup"))
    private val resetBtn = JButton(AppLocalization.tr("btn.reset_defaults"))
    private val exportBtn = JButton(AppLocalization.tr("btn.export"))
    private val importBtn = JButton(AppLocalization.tr("btn.import"))

    private val execLabel = JLabel(AppLocalization.tr("label.whisper_exec"))
    private val modLabel = JLabel(AppLocalization.tr("label.multilingual_model"))
    private val spokenLangLabel = JLabel(AppLocalization.tr("label.spoken_lang"))
    private val devLabel = JLabel(AppLocalization.tr("label.inference_device"))
    private val transcribeFileBtn = JButton(AppLocalization.tr("btn.transcribe_file"))

    private val historySearchLabel = JLabel(AppLocalization.tr("label.search_history"))
    private val historyTranscribeBtn = JButton(AppLocalization.tr("btn.transcribe_file"))
    private val clearBtn = JButton(AppLocalization.tr("btn.clear_history"))

    init {
        FontManager.installGlobalSwingDefaults(13f)
        initUi()
        loadInitialConfig()
        observeState()
        wireHistoryListener()
        setupLocalizationListener()
    }

    fun showInFrame(): JFrame {
        val f = JFrame("GolosAI - Speech to Text Assistant")
        this.frame = f
        f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        f.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    exitApplication()
                }
            },
        )
        f.setSize(700, 720)
        f.minimumSize = Dimension(580, 480)
        f.setLocationRelativeTo(null)
        f.contentPane = this
        expandedBounds = f.bounds

        setupSystemTray()
        return f
    }

    private fun initUi() {
        setSize(700, 720)
        preferredSize = Dimension(700, 720)
        minimumSize = Dimension(580, 480)

        // Tab 1: Dashboard
        tabbedPane.addTab(AppLocalization.tr("tab.dashboard"), createMainTab())

        // Tab 2: Settings with ScrollPane
        val settingsScroll =
            JScrollPane(createGeneralTab(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
                border = null
                viewport.background = Color(250, 250, 250)
                verticalScrollBar.unitIncrement = 16
            }
        tabbedPane.addTab(AppLocalization.tr("tab.settings"), settingsScroll)

        // Tab 3: Whisper & Models with ScrollPane
        val whisperScroll =
            JScrollPane(createWhisperTab(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
                border = null
                viewport.background = Color(250, 250, 250)
                verticalScrollBar.unitIncrement = 16
            }
        tabbedPane.addTab(AppLocalization.tr("tab.whisper"), whisperScroll)

        // Tab 4: History
        tabbedPane.addTab(AppLocalization.tr("tab.history"), createHistoryTab())

        mainContainer.add(tabbedPane, BorderLayout.CENTER)
        add(mainContainer, BorderLayout.CENTER)

        setupCollapsedBar()
        syncInjectionConfig()
        renderStatus(orchestrator.state.value)
    }

    private fun setupCollapsedBar() {
        collapsedBarPanel.border = EmptyBorder(6, 12, 6, 12)
        collapsedBarPanel.background = Color(245, 247, 250)
        collapsedBarPanel.layout = BoxLayout(collapsedBarPanel, BoxLayout.X_AXIS)

        val bulbsBox = JPanel(GridLayout(1, 3, 6, 0))
        bulbsBox.isOpaque = false
        bulbsBox.add(miniAppBulb)
        bulbsBox.add(miniVoiceBulb)
        bulbsBox.add(miniModeBulb)

        val actionsBox = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        actionsBox.isOpaque = false

        styleMinimalistButton(miniPttButton)
        miniPttButton.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    orchestrator.onPushToTalkPressed()
                }

                override fun mouseReleased(e: MouseEvent?) {
                    orchestrator.onPushToTalkReleased()
                }
            },
        )
        actionsBox.add(miniPttButton)

        styleMinimalistButton(expandBtn, isAccent = true)
        expandBtn.addActionListener { toggleCollapse() }
        actionsBox.add(expandBtn)

        styleMinimalistButton(closeBtn)
        closeBtn.foreground = Color(180, 40, 40)
        closeBtn.toolTipText = AppLocalization.tr("btn.exit")
        closeBtn.addActionListener { exitApplication() }
        actionsBox.add(closeBtn)

        collapsedBarPanel.add(bulbsBox)
        collapsedBarPanel.add(Box.createHorizontalGlue())
        collapsedBarPanel.add(actionsBox)

        // Allow window dragging anywhere on collapsed bar
        var mouseOffset: Point? = null
        val dragListener =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    mouseOffset = e.point
                }

                override fun mouseDragged(e: MouseEvent) {
                    mouseOffset?.let { offset ->
                        frame?.let { f ->
                            val cur = f.location
                            f.setLocation(cur.x + e.x - offset.x, cur.y + e.y - offset.y)
                        }
                    }
                }
            }
        collapsedBarPanel.addMouseListener(dragListener)
        collapsedBarPanel.addMouseMotionListener(dragListener)
    }

    fun toggleCollapse() {
        isCollapsed = !isCollapsed
        removeAll()
        if (isCollapsed) {
            expandedBounds = frame?.bounds ?: Rectangle(100, 100, 700, 720)
            add(collapsedBarPanel, BorderLayout.CENTER)
            frame?.let { f ->
                f.isAlwaysOnTop = true
                f.isResizable = false
                f.setSize(640, 68)
                f.minimumSize = Dimension(540, 60)
            }
        } else {
            add(mainContainer, BorderLayout.CENTER)
            frame?.let { f ->
                f.isAlwaysOnTop = false
                f.isResizable = true
                f.minimumSize = Dimension(580, 480)
                f.bounds = expandedBounds
            }
        }
        revalidate()
        repaint()
    }

    private fun createMainTab(): JPanel {
        val mainPanel = JPanel(BorderLayout(12, 14))
        mainPanel.border = EmptyBorder(16, 18, 16, 18)
        mainPanel.background = Color(252, 252, 253)

        // Top Header: 3 Glowing Bulb Indicators + Collapse Button
        val topHeader = JPanel(BorderLayout(8, 8))
        topHeader.isOpaque = false

        val bulbsPanel = JPanel(GridLayout(1, 3, 10, 0))
        bulbsPanel.isOpaque = false

        val appCard = createBulbCard(dashboardAppBulb)
        val voiceCard = createBulbCard(dashboardVoiceBulb)
        val modeCard = createBulbCard(dashboardModeBulb)

        bulbsPanel.add(appCard)
        bulbsPanel.add(voiceCard)
        bulbsPanel.add(modeCard)
        topHeader.add(bulbsPanel, BorderLayout.CENTER)

        val collapseToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 4))
        collapseToolbar.isOpaque = false
        styleMinimalistButton(collapseBtn, isAccent = true)
        collapseBtn.toolTipText = "Collapse GolosAI window to floating 3-bulb indicator bar"
        collapseBtn.addActionListener { toggleCollapse() }
        collapseToolbar.add(collapseBtn)
        topHeader.add(collapseToolbar, BorderLayout.SOUTH)

        mainPanel.add(topHeader, BorderLayout.NORTH)

        // Center Content: Push to Talk + Recent Dictation
        val centerPanel = JPanel(BorderLayout(10, 12))
        centerPanel.isOpaque = false

        pttButton.font = FontManager.bold(14f)
        pttButton.preferredSize = Dimension(220, 52)
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
        recentTitle.font = FontManager.bold(13f)
        recentTitle.foreground = Color(70, 75, 85)
        recentHeader.add(recentTitle, BorderLayout.WEST)

        styleMinimalistButton(copyRecentBtn)
        copyRecentBtn.addActionListener {
            val text = recentDictationArea.text.trim()
            if (text.isNotEmpty() && !isPlaceholderText(text)) {
                copyToClipboard(text)
                JOptionPane.showMessageDialog(this, "Copied transcription to clipboard!", "Copied", JOptionPane.INFORMATION_MESSAGE)
            }
        }
        recentHeader.add(copyRecentBtn, BorderLayout.EAST)
        recentBox.add(recentHeader, BorderLayout.NORTH)

        val recentScroll =
            JScrollPane(recentDictationArea).apply {
                border = LineBorder(Color(220, 224, 230), 1, true)
                preferredSize = Dimension(400, 140)
            }
        recentBox.add(recentScroll, BorderLayout.CENTER)
        centerPanel.add(recentBox, BorderLayout.CENTER)

        mainPanel.add(centerPanel, BorderLayout.CENTER)

        // Bottom Controls: Hide to Tray, Open Settings, Exit
        val bottomBar = JPanel(BorderLayout())
        bottomBar.isOpaque = false

        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        leftActions.isOpaque = false
        styleMinimalistButton(hideTrayBtn)
        hideTrayBtn.addActionListener {
            frame?.isVisible = false
        }
        if (SystemTray.isSupported()) {
            leftActions.add(hideTrayBtn)
        }
        bottomBar.add(leftActions, BorderLayout.WEST)

        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        rightActions.isOpaque = false
        styleMinimalistButton(openSettingsBtn)
        openSettingsBtn.addActionListener {
            tabbedPane.selectedIndex = 1
        }
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

    private fun createBulbCard(bulb: BulbWidget): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Color(248, 250, 252)
            border = CompoundBorder(LineBorder(Color(220, 226, 235), 1, true), EmptyBorder(6, 6, 6, 6))
            add(bulb, BorderLayout.CENTER)
        }
    }

    private fun styleMinimalistButton(
        btn: JButton,
        isAccent: Boolean = false,
    ) {
        btn.font = FontManager.regular(FontManager.INDICATOR_SIZE)
        btn.margin = Insets(4, 8, 4, 8)
        btn.isFocusPainted = false
        if (!isAccent) {
            btn.background = Color(248, 249, 250)
            btn.border = CompoundBorder(LineBorder(Color(215, 220, 225), 1, true), EmptyBorder(4, 8, 4, 8))
        } else {
            btn.background = Color(238, 244, 255)
            btn.foreground = Color(30, 90, 200)
            btn.border = CompoundBorder(LineBorder(Color(180, 205, 245), 1, true), EmptyBorder(4, 8, 4, 8))
        }
    }

    fun updateLocalizedTexts() {
        FontManager.selectFontForLanguage(AppLocalization.currentLanguage)
        FontManager.installGlobalSwingDefaults(13f)
        updateFontsRecursively(this)

        tabbedPane.setTitleAt(0, AppLocalization.tr("tab.dashboard"))
        tabbedPane.setTitleAt(1, AppLocalization.tr("tab.settings"))
        tabbedPane.setTitleAt(2, AppLocalization.tr("tab.whisper"))
        tabbedPane.setTitleAt(3, AppLocalization.tr("tab.history"))

        collapseBtn.text = "[-] " + AppLocalization.tr("btn.collapse")
        recentTitle.text = AppLocalization.tr("label.recent_speech")
        copyRecentBtn.text = AppLocalization.tr("btn.copy")
        hideTrayBtn.text = AppLocalization.tr("btn.hide_tray")
        openSettingsBtn.text = AppLocalization.tr("btn.open_settings")
        exitBtn.text = AppLocalization.tr("btn.exit")

        expandBtn.text = "[+] " + AppLocalization.tr("btn.expand")
        closeBtn.toolTipText = AppLocalization.tr("btn.exit")

        langLabel.text = AppLocalization.tr("label.ui_language")
        provLabel.text = AppLocalization.tr("label.audio_provider")
        micLabel.text = AppLocalization.tr("label.microphone")
        engLabel.text = AppLocalization.tr("label.engine")
        hkLabel.text = AppLocalization.tr("label.hotkey")
        insLabel.text = AppLocalization.tr("label.insertion_mode")
        timingLabel.text = AppLocalization.tr("label.timing")
        privLabel.text = AppLocalization.tr("label.clipboard_privacy")
        autoLabel.text = AppLocalization.tr("label.system_startup")
        resetBtn.text = AppLocalization.tr("btn.reset_defaults")
        exportBtn.text = AppLocalization.tr("btn.export")
        importBtn.text = AppLocalization.tr("btn.import")

        execLabel.text = AppLocalization.tr("label.whisper_exec")
        modLabel.text = AppLocalization.tr("label.multilingual_model")
        spokenLangLabel.text = AppLocalization.tr("label.spoken_lang")
        devLabel.text = AppLocalization.tr("label.inference_device")
        transcribeFileBtn.text = AppLocalization.tr("btn.transcribe_file")

        historySearchLabel.text = AppLocalization.tr("label.search_history")
        historyTranscribeBtn.text = AppLocalization.tr("btn.transcribe_file")
        clearBtn.text = AppLocalization.tr("btn.clear_history")

        val currentAreaText = recentDictationArea.text
        if (currentAreaText.isBlank() || isPlaceholderText(currentAreaText)) {
            recentDictationArea.text = AppLocalization.tr("placeholder.no_dictations")
        }

        renderStatus(orchestrator.state.value)
        dashboardAppBulb.repaint()
        dashboardVoiceBulb.repaint()
        dashboardModeBulb.repaint()
        miniAppBulb.repaint()
        miniVoiceBulb.repaint()
        miniModeBulb.repaint()
        revalidate()
        repaint()
    }

    private fun isPlaceholderText(text: String): Boolean {
        return text.startsWith("No dictations yet") ||
            text.startsWith("Aucune dictée") ||
            text.startsWith("Noch keine Diktate") ||
            text.startsWith("Диктовки отсутствуют") ||
            text.startsWith("文字起こしはまだありません") ||
            text.startsWith("暂无听写记录") ||
            text.startsWith("Henüz dikte yok") ||
            text.startsWith("لا توجد تسجيلات") ||
            text.startsWith("No hay dictados") ||
            text.startsWith("Nessuna dettatura")
    }

    private fun updateFontsRecursively(comp: Component) {
        val f = comp.font
        if (f != null && f.family != FontManager.hackRegularFont.family && f.family != FontManager.hackBoldFont.family) {
            val size = f.size2D
            comp.font = if (f.isBold) FontManager.bold(size) else FontManager.regular(size)
        }
        if (comp is Container) {
            for (child in comp.components) {
                updateFontsRecursively(child)
            }
        }
    }

    private fun setupLocalizationListener() {
        AppLocalization.addLanguageChangeListener {
            if (SwingUtilities.isEventDispatchThread()) {
                updateLocalizedTexts()
            } else {
                SwingUtilities.invokeLater {
                    updateLocalizedTexts()
                }
            }
        }
    }

    private fun setupSystemTray() {
        if (!SystemTray.isSupported()) return
        try {
            val tray = SystemTray.getSystemTray()
            val popup = PopupMenu()

            val openItem = MenuItem("Open GolosAI")
            openItem.addActionListener {
                frame?.let { f ->
                    f.isVisible = true
                    f.toFront()
                    f.state = Frame.NORMAL
                }
            }
            popup.add(openItem)

            val settingsItem = MenuItem("Settings")
            settingsItem.addActionListener {
                frame?.let { f ->
                    f.isVisible = true
                    f.toFront()
                    f.state = Frame.NORMAL
                }
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
                frame?.let { f ->
                    if (f.isVisible && f.isActive) {
                        f.isVisible = false
                    } else {
                        f.isVisible = true
                        f.toFront()
                        f.state = Frame.NORMAL
                    }
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
                DictationState.IDLE -> greenActive
                DictationState.RECORDING -> amberListening
                DictationState.PROCESSING -> redProcessing
            }
        val borderColor = fillColor.darker()

        g2d.color = fillColor
        g2d.fillOval(1, 1, size - 3, size - 3)
        g2d.color = borderColor
        g2d.drawOval(1, 1, size - 3, size - 3)
        g2d.dispose()
        return image
    }

    fun exitApplication() {
        logger.info("Shutting down GolosAI and terminating JVM process cleanly...")
        try {
            if (SystemTray.isSupported() && trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon)
            }
        } catch (_: Throwable) {
        }
        orchestrator.stop()
        frame?.dispose()
        kotlin.system.exitProcess(0)
    }

    private fun createGeneralTab(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(14, 16, 14, 16)
        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(6, 6, 6, 6)
                gridx = 0
                gridy = 0
                weightx = 0.32
            }

        // 0. Interface Language Selector (FR, DE, RU, JP, CN, TR, AR, ES, IT, EN)
        panel.add(langLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        uiLanguageCombo.addActionListener {
            val selected = AppLanguage.entries.getOrNull(uiLanguageCombo.selectedIndex) ?: AppLanguage.EN
            AppLocalization.setLanguage(selected)
            saveCurrentConfig()
        }
        panel.add(uiLanguageCombo, gbc)

        // 1. Audio Provider Selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.32
        panel.add(provLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
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
        gbc.gridy = 2
        gbc.weightx = 0.32
        panel.add(micLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        val micPanel = JPanel(BorderLayout(0, 4))
        refreshMicrophoneList()
        micCombo.addActionListener {
            val idx = micCombo.selectedIndex
            if (idx in availableDevices.indices) {
                orchestrator.selectedDevice = availableDevices[idx]
                saveCurrentConfig()
            }
        }
        micPanel.add(micCombo, BorderLayout.NORTH)
        val clueLabel = JLabel("Tip: Select [Microphone] for voice, or [System Output Monitor] for desktop audio/calls.")
        clueLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
        clueLabel.foreground = Color(100, 100, 100)
        micPanel.add(clueLabel, BorderLayout.SOUTH)
        panel.add(micPanel, gbc)

        // 3. Speech-to-Text Engine Selection
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.32
        panel.add(engLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        availableEngines.forEach { engineCombo.addItem(it.displayName) }
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
        gbc.gridy = 4
        gbc.weightx = 0.32
        panel.add(hkLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
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
                            recordBtn.text = "Record Shortcut (Click & Press Keys)"
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
        val plusKeyLabel = JLabel("+ Key:")
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
        gbc.gridy = 5
        gbc.weightx = 0.32
        panel.add(insLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        insertionModeCombo.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
        }
        panel.add(insertionModeCombo, gbc)

        // 6. Insertion Timing (On the Fly vs On Release)
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.weightx = 0.32
        panel.add(timingLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        timingCombo.addActionListener {
            syncInjectionConfig()
            saveCurrentConfig()
            renderStatus(orchestrator.state.value)
        }
        panel.add(timingCombo, gbc)

        // 7. Clipboard Privacy Options
        gbc.gridx = 0
        gbc.gridy = 7
        gbc.weightx = 0.32
        panel.add(privLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        val privacyPanel = JPanel(GridLayout(2, 1, 0, 2))
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
        gbc.gridy = 8
        gbc.weightx = 0.32
        panel.add(autoLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        autostartCheck.addActionListener {
            val enabled = autostartCheck.isSelected
            autoStartManager.setAutoStart(enabled)
            saveCurrentConfig()
        }
        panel.add(autostartCheck, gbc)

        // 9. Configuration Toolbar (Reset, Export, Import)
        gbc.gridx = 0
        gbc.gridy = 9
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        val configBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4))

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
        panel.border = EmptyBorder(14, 16, 14, 16)
        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(6, 6, 6, 6)
                gridx = 0
                gridy = 0
                weightx = 0.32
            }

        // 1. Whisper Executable Management
        panel.add(execLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68

        val binBox = JPanel(BorderLayout(4, 4))
        val binInputRow = JPanel(BorderLayout(4, 0))
        binInputRow.add(binaryPathField, BorderLayout.CENTER)

        val binBtnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        styleMinimalistButton(browseBinaryBtn)
        styleMinimalistButton(downloadBinaryBtn)
        binBtnRow.add(browseBinaryBtn)
        binBtnRow.add(downloadBinaryBtn)
        binInputRow.add(binBtnRow, BorderLayout.EAST)
        binBox.add(binInputRow, BorderLayout.NORTH)
        binaryStatusLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
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
        gbc.weightx = 0.32
        panel.add(modLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
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
        gbc.weightx = 0.32
        val statLabel = JLabel("Model Status:")
        panel.add(statLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68

        val downloadActionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        downloadActionPanel.add(modelStatusLabel)
        styleMinimalistButton(downloadModelBtn)
        downloadActionPanel.add(downloadModelBtn)
        downloadModelBtn.addActionListener { startModelDownload() }
        panel.add(downloadActionPanel, gbc)

        // 4. Download Progress
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.32
        val progLabel = JLabel("Download Progress:")
        panel.add(progLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        downloadProgressBar.isStringPainted = true
        downloadProgressBar.string = "Idle"
        downloadProgressBar.font = FontManager.regular(FontManager.SMALL_SIZE)
        panel.add(downloadProgressBar, gbc)

        // 5. Spoken Language Selection (Extended: FR, DE, RU, JP, CN, TR, AR, ES, IT, PT, KO, UK, PL, NL, EN)
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.32
        panel.add(spokenLangLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        languageCombo.addActionListener {
            val idx = languageCombo.selectedIndex
            if (idx in languageCodes.indices && whisperEngine != null) {
                whisperEngine.language = languageCodes[idx]
                saveCurrentConfig()
                renderStatus(orchestrator.state.value)
            }
        }
        panel.add(languageCombo, gbc)

        // 6. Bilingual Mode Checkbox (EN + Selected Language)
        gbc.gridx = 0
        gbc.gridy = 5
        gbc.weightx = 0.32
        val bilingualLabel = JLabel("Bilingual Mode:")
        panel.add(bilingualLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        bilingualModeCheck.font = FontManager.regular(FontManager.INDICATOR_SIZE)
        bilingualModeCheck.addActionListener {
            whisperEngine?.bilingualMode = bilingualModeCheck.isSelected
            saveCurrentConfig()
            renderStatus(orchestrator.state.value)
        }
        panel.add(bilingualModeCheck, gbc)

        // 7. Inference Device Selection (CPU vs GPU)
        gbc.gridx = 0
        gbc.gridy = 6
        gbc.weightx = 0.32
        panel.add(devLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        deviceCombo.addActionListener {
            if (whisperEngine != null) {
                whisperEngine.device = if (deviceCombo.selectedIndex == 0) InferenceDevice.CPU else InferenceDevice.GPU
                saveCurrentConfig()
            }
        }
        panel.add(deviceCombo, gbc)

        // 8. Transcribe Audio File
        gbc.gridx = 0
        gbc.gridy = 7
        gbc.weightx = 0.32
        val fileLabel = JLabel("Audio File Dictation:")
        panel.add(fileLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
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
        val topBar = JPanel(BorderLayout(8, 6))
        val searchBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        searchBox.isOpaque = false
        searchBox.add(historySearchLabel)
        historySearchField.columns = 12
        searchBox.add(historySearchField)
        topBar.add(searchBox, BorderLayout.WEST)

        val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        rightBox.isOpaque = false
        styleMinimalistButton(historyTranscribeBtn)
        historyTranscribeBtn.addActionListener { promptAndTranscribeAudioFile() }
        rightBox.add(historyTranscribeBtn)

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

        val headerPanel = JPanel(BorderLayout(0, 4))
        headerPanel.isOpaque = false
        headerPanel.add(topBar, BorderLayout.NORTH)
        val countPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        countPanel.isOpaque = false
        historyCountLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
        countPanel.add(historyCountLabel)
        headerPanel.add(countPanel, BorderLayout.SOUTH)

        panel.add(headerPanel, BorderLayout.NORTH)

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
        infoLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
        infoLabel.foreground = Color(100, 110, 120)
        topRow.add(infoLabel, BorderLayout.WEST)

        // Copy Button
        val copyBtn = JButton("Copy")
        styleMinimalistButton(copyBtn)
        copyBtn.addActionListener {
            val selection = StringSelection(entry.text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            copyBtn.text = "Copied!"
            Timer(1500) { copyBtn.text = "Copy" }.apply {
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
        textArea.font = FontManager.regular(13f)
        card.add(textArea, BorderLayout.CENTER)

        return card
    }

    private fun updateBinaryStatus() {
        val configuredPath = binaryPathField.text.trim()
        val foundPath = binaryManager.findWhisperBinary(configuredPath.ifEmpty { null })
        val exists = File(foundPath).canExecute()

        if (exists) {
            binaryStatusLabel.text = "[OK] Ready: $foundPath"
            binaryStatusLabel.foreground = Color(0, 130, 0)
            binaryPathField.text = foundPath
            whisperEngine?.binaryPath = foundPath
        } else {
            binaryStatusLabel.text = "[!] Not Found! Click 'Download CLI' or 'Browse' to set executable."
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
            modelStatusLabel.text = "[OK] Downloaded"
            modelStatusLabel.foreground = Color(0, 140, 0)
            downloadModelBtn.text = "Re-download"
            whisperEngine?.modelPath = modelDownloader.getLocalModelFile(selectedModel).absolutePath
        } else {
            modelStatusLabel.text = "[!] Not found locally"
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
        // UI Language
        val appLang = AppLanguage.fromCode(c.uiLanguage)
        uiLanguageCombo.selectedIndex = AppLanguage.entries.indexOf(appLang).coerceAtLeast(0)
        AppLocalization.setLanguage(appLang)

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

        bilingualModeCheck.isSelected = c.engine.whisper.bilingualMode
        whisperEngine?.bilingualMode = c.engine.whisper.bilingualMode

        updateBinaryStatus()
        updateModelStatus()
        renderStatus(orchestrator.state.value)
    }

    private fun saveCurrentConfig() {
        val hk = orchestrator.currentHotkey
        val ins = orchestrator.injectionConfig
        val selectedEngineId = if (orchestrator.speechEngine is WhisperCppEngine) "whisper-cpp" else "mock"
        val isPortAudio = audioProviderCombo.selectedIndex == 1
        val selectedLang = AppLanguage.entries.getOrNull(uiLanguageCombo.selectedIndex) ?: AppLanguage.EN

        val config =
            GolosConfig(
                version = "1.0",
                uiLanguage = selectedLang.code,
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
                                bilingualMode = bilingualModeCheck.isSelected,
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

    fun selectTab(index: Int) {
        if (index in 0 until tabbedPane.tabCount) {
            tabbedPane.selectedIndex = index
        }
    }

    fun renderStatus(state: DictationState) {
        val timingShort = if (timingCombo.selectedIndex == 0) "Rel" else "Live"
        val insertShort = if (insertionModeCombo.selectedIndex == 0) "Direct" else "Clip"
        val hotkeyStr = orchestrator.currentHotkey.displayText
        val bilingualSuffix =
            if (bilingualModeCheck.isSelected && whisperEngine?.language != "auto" && whisperEngine?.language != "en") {
                " [EN+${whisperEngine?.language?.uppercase()}]"
            } else {
                ""
            }

        val modeDisplay = "[$timingShort | $insertShort | $hotkeyStr]$bilingualSuffix"
        val miniModeDisplay = "$hotkeyStr • $insertShort"

        // 1. Update App Bulb
        dashboardAppBulb.updateState(greenActive, greenGlow, AppLocalization.tr("bulb.app.title"), AppLocalization.tr("bulb.app.active"))
        miniAppBulb.updateState(greenActive, greenGlow, AppLocalization.tr("bulb.app.title"), AppLocalization.tr("bulb.app.active"))

        // 2. Update Voice Bulb & PTT Buttons
        when (state) {
            DictationState.IDLE -> {
                val idleText = AppLocalization.tr("bulb.voice.idle")
                dashboardVoiceBulb.updateState(greenActive, greenGlow, AppLocalization.tr("bulb.voice.title"), idleText)
                miniVoiceBulb.updateState(greenActive, greenGlow, AppLocalization.tr("bulb.voice.title"), idleText)
                pttButton.text = AppLocalization.tr("btn.hold_to_speak") + " ($hotkeyStr)"
                pttButton.background = null
                miniPttButton.text = "Speak"
                miniPttButton.background = null
            }
            DictationState.RECORDING -> {
                val recText = AppLocalization.tr("bulb.voice.listening")
                dashboardVoiceBulb.updateState(amberListening, amberGlow, AppLocalization.tr("bulb.voice.title"), recText)
                miniVoiceBulb.updateState(amberListening, amberGlow, AppLocalization.tr("bulb.voice.title"), recText)
                pttButton.text = "[REC] " + AppLocalization.tr("btn.recording")
                pttButton.background = Color(255, 235, 235)
                miniPttButton.text = "[REC]"
                miniPttButton.background = Color(255, 235, 235)
            }
            DictationState.PROCESSING -> {
                val procText = AppLocalization.tr("bulb.voice.processing")
                dashboardVoiceBulb.updateState(redProcessing, redGlow, AppLocalization.tr("bulb.voice.title"), procText)
                miniVoiceBulb.updateState(redProcessing, redGlow, AppLocalization.tr("bulb.voice.title"), procText)
                pttButton.text = "[...] " + AppLocalization.tr("btn.processing")
                pttButton.background = Color(245, 245, 245)
                miniPttButton.text = "[...]"
                miniPttButton.background = Color(245, 245, 245)
            }
        }

        // 3. Update Mode Bulb
        dashboardModeBulb.updateState(blueMode, blueGlow, AppLocalization.tr("bulb.mode.title"), modeDisplay)
        miniModeBulb.updateState(blueMode, blueGlow, AppLocalization.tr("bulb.mode.title"), miniModeDisplay)

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
            dashboardVoiceBulb.updateState(amberListening, amberGlow, "VOICE", "TRANSCRIBING")
            miniVoiceBulb.updateState(amberListening, amberGlow, "VOICE", "TRANSCRIBING")

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
        val parentWindow = frame ?: SwingUtilities.getWindowAncestor(this)
        val dialog = JDialog(parentWindow, "Transcription Result - ${file.name}", Dialog.ModalityType.APPLICATION_MODAL)
        dialog.setSize(540, 400)
        dialog.setLocationRelativeTo(this)
        dialog.layout = BorderLayout(10, 10)

        val header = JPanel(BorderLayout(4, 4))
        header.border = EmptyBorder(12, 14, 4, 14)
        val infoLabel =
            JLabel(
                "<html><b>File:</b> ${file.name}<br/>" +
                    "<b>Duration:</b> ${result.durationMs} ms | " +
                    "<b>Engine:</b> ${orchestrator.speechEngine.displayName}</html>",
            )
        header.add(infoLabel, BorderLayout.CENTER)
        dialog.add(header, BorderLayout.NORTH)

        val textArea = JTextArea(result.text)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.isEditable = false
        textArea.font = FontManager.regular(13f)
        textArea.margin = Insets(8, 8, 8, 8)
        val scrollPane = JScrollPane(textArea)
        scrollPane.border = CompoundBorder(EmptyBorder(0, 14, 0, 14), LineBorder(Color.LIGHT_GRAY))
        dialog.add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8))
        val copyBtn = JButton("Copy to Clipboard")
        styleMinimalistButton(copyBtn)
        copyBtn.addActionListener {
            copyToClipboard(result.text)
            copyBtn.text = "Copied!"
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

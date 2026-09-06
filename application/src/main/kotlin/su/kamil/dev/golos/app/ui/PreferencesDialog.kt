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
import su.kamil.dev.golos.core.model.BatchItemState
import su.kamil.dev.golos.core.model.BatchItemStatus
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.EngineSettings
import su.kamil.dev.golos.core.model.ExportFormat
import su.kamil.dev.golos.core.model.GolosConfig
import su.kamil.dev.golos.core.model.HistoryEntry
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.model.HotkeySettings
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InjectionTiming
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.core.model.InsertionSettings
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.model.TriggerMode
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToolTip
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

    private var isUpdatingLocalization = false

    private fun createCleanToolTip(parent: JComponent): JToolTip {
        return object : JToolTip() {
            init {
                component = parent
                font = FontManager.regular(FontManager.SMALL_SIZE)
            }
        }
    }

    private class LocalizedComboRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            c.font = FontManager.regular(FontManager.DEFAULT_SIZE)
            return c
        }
    }

    // UI Language Selector
    private val uiLanguageCombo = JComboBox(AppLanguage.entries.map { it.displayName }.toTypedArray())

    // Audio Provider & Microphone
    private val audioProviderCombo = JComboBox<String>()
    private val micCombo = JComboBox<String>()
    private val engineCombo = JComboBox<String>()

    // Hotkey Controls
    private val ctrlCheck = JCheckBox("Ctrl")
    private val shiftCheck = JCheckBox("Shift")
    private val altCheck = JCheckBox("Alt")
    private val metaCheck = JCheckBox("Super/Win")
    private val keyField =
        object : JTextField("F8", 6) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }.apply {
            font = FontManager.mono(FontManager.DEFAULT_SIZE)
            toolTipText = AppLocalization.tr("tip.key_field")
        }
    private val activeHotkeyLabel =
        JLabel(orchestrator.currentHotkey.displayText).apply {
            font = FontManager.monoBold(FontManager.DEFAULT_SIZE)
        }
    private val applyHotkeyBtn = JButton(AppLocalization.tr("btn.apply"))
    private val recordBtn = JButton(AppLocalization.tr("btn.record_shortcut"))
    private val plusKeyLabel = JLabel(AppLocalization.tr("label.hotkey_plus_key"))

    // Hotkey Trigger Mode (Hold to Talk vs Toggle On/Off - Criterion D-03)
    private val triggerModeCombo = JComboBox<String>()
    private val triggerModeLabel = JLabel(AppLocalization.tr("label.trigger_mode"))

    // Efficiency Metrics Cards (Criterion F-05, N-17, M-02)
    val currentMetricsCard = EfficiencyMetricCard(AppLocalization.tr("metric.current_title"))
    val historyMetricsCard = EfficiencyMetricCard(AppLocalization.tr("metric.history_mean_title"))
    val allTimeMetricsCard = EfficiencyMetricCard(AppLocalization.tr("metric.all_time_title"))

    // Undecorated Floating Mini-Bar Window (Criterion B-11, D-08)
    var floatingBarWindow: IndicatorFloatingBar? = null

    // Privacy & Insertion Controls
    private val insertionModeCombo = JComboBox<String>()
    private val timingCombo = JComboBox<String>()
    private val copyClipboardCheck = JCheckBox(AppLocalization.tr("check.copy_clipboard"), false)
    private val fallbackClipboardCheck = JCheckBox(AppLocalization.tr("check.fallback_clipboard"), true)

    // Autostart Control
    private val autostartCheck = JCheckBox(AppLocalization.tr("check.autostart"), autoStartManager.isAutoStartEnabled())

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
        object : JButton(AppLocalization.tr("btn.download_binary")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }.apply {
            toolTipText = AppLocalization.tr("tip.download_cli")
        }
    private val browseBinaryBtn = JButton(AppLocalization.tr("btn.browse"))

    // Model Controls
    private val modelCombo = JComboBox<String>()
    private val modelStatusLabel = JLabel("Status: Checking...")
    private val downloadModelBtn = JButton(AppLocalization.tr("btn.download_model"))
    private val downloadProgressBar = JProgressBar(0, 100)
    private val downloadCancelFlag = AtomicBoolean(false)

    // Spoken Languages (FR, DE, RU, JP, CN, TR, AR, ES, IT, PT, KO, UK, PL, NL, EN, Auto)
    private val languageCodes =
        listOf("auto", "en", "fr", "de", "ru", "ja", "zh", "tr", "ar", "es", "it", "pt", "ko", "uk", "pl", "nl")
    private val languageCombo = JComboBox<String>()

    // Bilingual Mode (EN + Selected Language)
    private val bilingualLabel = JLabel(AppLocalization.tr("label.bilingual_mode"))
    private val bilingualModeCheck =
        object : JCheckBox(AppLocalization.tr("check.bilingual"), false) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }.apply {
            toolTipText = AppLocalization.tr("tip.bilingual")
        }

    private val deviceCombo = JComboBox<String>()

    // History UI components
    private val historyListPanel = JPanel()
    private val historySearchField = JTextField(12)
    private val historyAppFilterBox = JComboBox<String>()
    private val historyCountLabel = JLabel(String.format(AppLocalization.tr("label.history_total"), 0))

    // Profile selector (Criteria J-04, J-05, D-15)
    private val styleProfileCombo =
        JComboBox(
            arrayOf(
                "Auto (Active Window Detection)",
                "Messenger (Short replicas, chat)",
                "Mail (Expanded paragraph, punctuation)",
                "Code (Preserve identifiers, acronyms)",
                "General (Standard dictation)",
            ),
        )

    // Batch Audio UI components (Criteria N-09..N-18)
    private var selectedBatchFiles: List<File> = emptyList()
    private var selectedBatchDir: File? = null
    private val batchPathLabel = JLabel("No folder or files selected")
    private val batchRecursiveCheck = JCheckBox("Include subfolders", false)
    private val batchExportTxtCheck = JCheckBox("Text (.txt)", true)
    private val batchExportSrtCheck = JCheckBox("SubRip (.srt)", true)
    private val batchExportVttCheck = JCheckBox("WebVTT (.vtt)", true)
    private val batchStartBtn = JButton("Start Batch")
    private val batchCancelBtn = JButton("Cancel").apply { isEnabled = false }
    private val batchOverallProgressBar =
        JProgressBar(0, 100).apply {
            isStringPainted = true
            string = "0%"
        }
    private val batchStatusLabel = JLabel("Ready")
    private val batchResultsPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(250, 250, 250)
        }
    private val batchSummaryLabel = JLabel("")
    private var batchTranscriberJob: Job? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private var availableDevices: List<AudioDevice> = emptyList()

    // Localized UI Elements (dynamic language switching)
    private val collapseBtn =
        object : JButton("[-] " + AppLocalization.tr("btn.collapse")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }
    private val expandBtn =
        object : JButton("[+] " + AppLocalization.tr("btn.expand")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }
    private val closeBtn =
        object : JButton("X") {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }
    private val copyRecentBtn = JButton(AppLocalization.tr("btn.copy"))
    private val hideTrayBtn = JButton(AppLocalization.tr("btn.hide_tray"))
    private val openSettingsBtn = JButton(AppLocalization.tr("btn.open_settings"))
    private val exitBtn = JButton(AppLocalization.tr("btn.exit"))
    private val recentTitle = JLabel(AppLocalization.tr("label.recent_speech"))

    private val langLabel = JLabel(AppLocalization.tr("label.ui_language"))
    private val provLabel = JLabel(AppLocalization.tr("label.audio_provider"))
    private val micLabel = JLabel(AppLocalization.tr("label.microphone"))
    private val gainLabel = JLabel(AppLocalization.tr("label.input_gain"))
    private val inputLevelLabel = JLabel(AppLocalization.tr("label.input_level"))
    private val gainSlider = javax.swing.JSlider(0, 200, 100)
    private val gainValueLabel =
        JLabel("100% (1.0x)").apply {
            font = FontManager.mono(FontManager.SMALL_SIZE)
            foreground = Color(60, 70, 85)
        }
    private val testMicButton = JButton(AppLocalization.tr("btn.test_mic"))

    val dashboardVuMeter = AudioVuMeterWidget(showTitle = false)
    val settingsVuMeter = AudioVuMeterWidget(showTitle = false)
    private val engLabel = JLabel(AppLocalization.tr("label.engine"))
    private val hkLabel = JLabel(AppLocalization.tr("label.hotkey"))
    private val insLabel = JLabel(AppLocalization.tr("label.insertion_mode"))
    private val timingLabel = JLabel(AppLocalization.tr("label.timing"))
    private val privLabel = JLabel(AppLocalization.tr("label.clipboard_privacy"))
    private val autoLabel = JLabel(AppLocalization.tr("label.system_startup"))
    private val resetBtn =
        object : JButton(AppLocalization.tr("btn.reset_defaults")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }
    private val exportBtn =
        object : JButton(AppLocalization.tr("btn.export")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }
    private val importBtn =
        object : JButton(AppLocalization.tr("btn.import")) {
            override fun createToolTip(): JToolTip = createCleanToolTip(this)
        }

    private val execLabel = JLabel(AppLocalization.tr("label.whisper_exec"))
    private val modLabel = JLabel(AppLocalization.tr("label.multilingual_model"))
    private val spokenLangLabel = JLabel(AppLocalization.tr("label.spoken_lang"))
    private val devLabel = JLabel(AppLocalization.tr("label.inference_device"))
    private val statLabel = JLabel(AppLocalization.tr("label.model_status"))
    private val progLabel = JLabel(AppLocalization.tr("label.download_progress"))
    private val fileLabel = JLabel(AppLocalization.tr("label.audio_file_dictation"))
    private val transcribeFileBtn = JButton(AppLocalization.tr("btn.transcribe_file"))

    private val historySearchLabel = JLabel(AppLocalization.tr("label.search_history"))
    private val historyTranscribeBtn = JButton(AppLocalization.tr("btn.transcribe_file"))
    private val clearBtn = JButton(AppLocalization.tr("btn.clear_history"))

    private fun configureCheckBox(cb: JCheckBox) {
        val regularFont = FontManager.regular(FontManager.DEFAULT_SIZE)
        cb.font = regularFont
        cb.isFocusPainted = false
        cb.addChangeListener {
            val f = FontManager.regular(FontManager.DEFAULT_SIZE)
            if (cb.font != f) {
                cb.font = f
            }
        }
        cb.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    cb.font = FontManager.regular(FontManager.DEFAULT_SIZE)
                }

                override fun mouseExited(e: MouseEvent) {
                    cb.font = FontManager.regular(FontManager.DEFAULT_SIZE)
                }
            },
        )
    }

    private fun configureAllCheckBoxes() {
        configureCheckBox(ctrlCheck)
        configureCheckBox(shiftCheck)
        configureCheckBox(altCheck)
        configureCheckBox(metaCheck)
        configureCheckBox(copyClipboardCheck)
        configureCheckBox(fallbackClipboardCheck)
        configureCheckBox(autostartCheck)
        configureCheckBox(bilingualModeCheck)
    }

    private fun configureAllComboBoxes() {
        val renderer = LocalizedComboRenderer()
        val combos: List<JComboBox<*>> =
            listOf(
                uiLanguageCombo,
                audioProviderCombo,
                micCombo,
                engineCombo,
                insertionModeCombo,
                timingCombo,
                modelCombo,
                languageCombo,
                deviceCombo,
            )
        for (c in combos) {
            c.font = FontManager.regular(FontManager.DEFAULT_SIZE)
            c.renderer = renderer
        }
        refreshComboModel(
            audioProviderCombo,
            listOf(
                AppLocalization.tr("opt.audio.javasound"),
                AppLocalization.tr("opt.audio.portaudio"),
            ),
        )
        refreshComboModel(
            triggerModeCombo,
            listOf(
                AppLocalization.tr("opt.trigger.hold"),
                AppLocalization.tr("opt.trigger.toggle"),
            ),
        )
        refreshComboModel(
            insertionModeCombo,
            listOf(
                AppLocalization.tr("opt.ins.direct"),
                AppLocalization.tr("opt.ins.clipboard"),
            ),
        )
        refreshComboModel(
            timingCombo,
            listOf(
                AppLocalization.tr("opt.timing.release"),
                AppLocalization.tr("opt.timing.onthefly"),
            ),
        )
        refreshComboModel(
            deviceCombo,
            listOf(
                AppLocalization.tr("opt.device.cpu"),
                AppLocalization.tr("opt.device.gpu"),
            ),
        )
        refreshComboModel(
            languageCombo,
            languageCodes.map { AppLocalization.tr("lang.$it") },
        )
    }

    private fun refreshComboModel(
        combo: JComboBox<String>,
        items: List<String>,
    ) {
        val prevIdx = combo.selectedIndex
        val model = DefaultComboBoxModel(items.toTypedArray())
        combo.model = model
        if (prevIdx in items.indices) {
            combo.selectedIndex = prevIdx
        } else if (items.isNotEmpty()) {
            combo.selectedIndex = 0
        }
    }

    init {
        configureAllCheckBoxes()
        configureAllComboBoxes()
        FontManager.installGlobalSwingDefaults(FontManager.DEFAULT_SIZE)
        initUi()
        setupLocalizationListener()
        updateLocalizedTexts()
        loadInitialConfig()
        observeState()
        wireHistoryListener()
        refreshMetricsCards()
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

        // Tab 5: Batch Audio (Criteria N-09..N-18)
        val batchScroll =
            JScrollPane(
                createBatchTab(),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply {
                border = null
                viewport.background = Color(250, 250, 250)
                verticalScrollBar.unitIncrement = 16
            }
        tabbedPane.addTab(AppLocalization.tr("tab.batch"), batchScroll)

        mainContainer.add(tabbedPane, BorderLayout.CENTER)
        add(mainContainer, BorderLayout.CENTER)

        setupCollapsedBar()
        syncInjectionConfig()
        renderStatus(orchestrator.state.value)

        orchestrator.onAudioLevel = { rmsDb, peakDb, isClipping ->
            SwingUtilities.invokeLater {
                dashboardVuMeter.updateLevel(rmsDb, peakDb, isClipping)
                if (orchestrator.isTestingAudio()) {
                    settingsVuMeter.updateLevel(rmsDb, peakDb, isClipping)
                }
            }
        }
        orchestrator.onAudioWarning = { warning ->
            SwingUtilities.invokeLater {
                dashboardVuMeter.updateWarning(warning)
                if (orchestrator.isTestingAudio()) {
                    settingsVuMeter.updateWarning(warning)
                }
            }
        }
        tabbedPane.addChangeListener {
            if (tabbedPane.selectedIndex != 1 && orchestrator.isTestingAudio()) {
                orchestrator.stopAudioTest()
                testMicButton.text = AppLocalization.tr("btn.test_mic")
                settingsVuMeter.reset()
            }
        }
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
                if (floatingBarWindow == null) {
                    floatingBarWindow =
                        IndicatorFloatingBar(
                            owner = f,
                            orchestrator = orchestrator,
                            miniAppBulb = miniAppBulb,
                            miniVoiceBulb = miniVoiceBulb,
                            miniModeBulb = miniModeBulb,
                            expandAction = { toggleCollapse() },
                            exitAction = { exitApplication() },
                        )
                }
                floatingBarWindow?.let { win ->
                    val barWidth = 480
                    val barHeight = 54
                    val x = f.x + (f.width - barWidth) / 2
                    val y = f.y + 20
                    win.setLocation(x.coerceAtLeast(0), y.coerceAtLeast(0))
                    win.updateStatus(orchestrator.state.value)
                    win.isVisible = true
                }
                f.isVisible = false
            }
        } else {
            floatingBarWindow?.isVisible = false
            add(mainContainer, BorderLayout.CENTER)
            frame?.let { f ->
                f.isAlwaysOnTop = false
                f.isResizable = true
                f.minimumSize = Dimension(580, 480)
                f.bounds = expandedBounds
                f.isVisible = true
                f.toFront()
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

        // Center Content: Push to Talk + Efficiency Metrics + Recent Dictation
        val centerPanel = JPanel(BorderLayout(10, 12))
        centerPanel.isOpaque = false

        val topActionBox = JPanel(BorderLayout(0, 10))
        topActionBox.isOpaque = false

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
        topActionBox.add(pttButton, BorderLayout.NORTH)

        val middleBox = JPanel(BorderLayout(0, 6))
        middleBox.isOpaque = false
        middleBox.add(dashboardVuMeter, BorderLayout.NORTH)

        // 3 Efficiency Metrics Panels: Current Text, History Mean, All Time
        val metricsGrid = JPanel(GridLayout(1, 3, 8, 0))
        metricsGrid.isOpaque = false
        metricsGrid.add(currentMetricsCard)
        metricsGrid.add(historyMetricsCard)
        metricsGrid.add(allTimeMetricsCard)
        middleBox.add(metricsGrid, BorderLayout.CENTER)

        topActionBox.add(middleBox, BorderLayout.CENTER)

        centerPanel.add(topActionBox, BorderLayout.NORTH)

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
        isUpdatingLocalization = true
        try {
            FontManager.selectFontForLanguage(AppLocalization.currentLanguage)
            FontManager.installGlobalSwingDefaults(13f)
            updateFontsRecursively(this)

            tabbedPane.setTitleAt(0, AppLocalization.tr("tab.dashboard"))
            tabbedPane.setTitleAt(1, AppLocalization.tr("tab.settings"))
            tabbedPane.setTitleAt(2, AppLocalization.tr("tab.whisper"))
            tabbedPane.setTitleAt(3, AppLocalization.tr("tab.history"))
            tabbedPane.setTitleAt(4, AppLocalization.tr("tab.batch"))

            collapseBtn.text = "[-] " + AppLocalization.tr("btn.collapse")
            collapseBtn.toolTipText = AppLocalization.tr("btn.collapse")
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
            gainLabel.text = AppLocalization.tr("label.input_gain")
            inputLevelLabel.text = AppLocalization.tr("label.input_level")
            testMicButton.text =
                if (orchestrator.isTestingAudio()) {
                    AppLocalization.tr("btn.stop_test")
                } else {
                    AppLocalization.tr("btn.test_mic")
                }
            engLabel.text = AppLocalization.tr("label.engine")
            hkLabel.text = AppLocalization.tr("label.hotkey")
            triggerModeLabel.text = AppLocalization.tr("label.trigger_mode")
            currentMetricsCard.titleLabel.text = AppLocalization.tr("metric.current_title")
            historyMetricsCard.titleLabel.text = AppLocalization.tr("metric.history_mean_title")
            allTimeMetricsCard.titleLabel.text = AppLocalization.tr("metric.all_time_title")
            insLabel.text = AppLocalization.tr("label.insertion_mode")
            timingLabel.text = AppLocalization.tr("label.timing")
            privLabel.text = AppLocalization.tr("label.clipboard_privacy")
            autoLabel.text = AppLocalization.tr("label.system_startup")
            resetBtn.text = AppLocalization.tr("btn.reset_defaults")
            resetBtn.toolTipText = AppLocalization.tr("tip.reset_defaults")
            exportBtn.text = AppLocalization.tr("btn.export")
            exportBtn.toolTipText = AppLocalization.tr("tip.export")
            importBtn.text = AppLocalization.tr("btn.import")
            importBtn.toolTipText = AppLocalization.tr("tip.import")

            recordBtn.text = AppLocalization.tr("btn.record_shortcut")
            applyHotkeyBtn.text = AppLocalization.tr("btn.apply")
            plusKeyLabel.text = AppLocalization.tr("label.hotkey_plus_key")
            keyField.toolTipText = AppLocalization.tr("tip.key_field")

            execLabel.text = AppLocalization.tr("label.whisper_exec")
            modLabel.text = AppLocalization.tr("label.multilingual_model")
            spokenLangLabel.text = AppLocalization.tr("label.spoken_lang")
            devLabel.text = AppLocalization.tr("label.inference_device")
            transcribeFileBtn.text = AppLocalization.tr("btn.transcribe_file")
            bilingualLabel.text = AppLocalization.tr("label.bilingual_mode")
            statLabel.text = AppLocalization.tr("label.model_status")
            progLabel.text = AppLocalization.tr("label.download_progress")
            fileLabel.text = AppLocalization.tr("label.audio_file_dictation")

            downloadBinaryBtn.text = AppLocalization.tr("btn.download_binary")
            downloadBinaryBtn.toolTipText = AppLocalization.tr("tip.download_cli")
            browseBinaryBtn.text = AppLocalization.tr("btn.browse")
            downloadModelBtn.text = AppLocalization.tr("btn.download_model")

            copyClipboardCheck.text = AppLocalization.tr("check.copy_clipboard")
            fallbackClipboardCheck.text = AppLocalization.tr("check.fallback_clipboard")
            autostartCheck.text = AppLocalization.tr("check.autostart")
            bilingualModeCheck.text = AppLocalization.tr("check.bilingual")
            bilingualModeCheck.toolTipText = AppLocalization.tr("tip.bilingual")

            historySearchLabel.text = AppLocalization.tr("label.search_history")
            historyTranscribeBtn.text = AppLocalization.tr("btn.transcribe_file")
            clearBtn.text = AppLocalization.tr("btn.clear_history")

            // Refresh drop-box options with localized strings
            val audioProviders =
                listOf(
                    AppLocalization.tr("opt.audio.javasound"),
                    AppLocalization.tr("opt.audio.portaudio"),
                )
            refreshComboModel(audioProviderCombo, audioProviders)

            val insItems =
                listOf(
                    AppLocalization.tr("opt.ins.direct"),
                    AppLocalization.tr("opt.ins.clipboard"),
                )
            refreshComboModel(insertionModeCombo, insItems)

            val timingItems =
                listOf(
                    AppLocalization.tr("opt.timing.release"),
                    AppLocalization.tr("opt.timing.onthefly"),
                )
            refreshComboModel(timingCombo, timingItems)

            val devItems =
                listOf(
                    AppLocalization.tr("opt.device.cpu"),
                    AppLocalization.tr("opt.device.gpu"),
                )
            refreshComboModel(deviceCombo, devItems)

            val langItems =
                languageCodes.map { code ->
                    AppLocalization.tr("lang.$code")
                }
            refreshComboModel(languageCombo, langItems)

            val currentAreaText = recentDictationArea.text
            if (currentAreaText.isBlank() || isPlaceholderText(currentAreaText)) {
                recentDictationArea.text = AppLocalization.tr("placeholder.no_dictations")
            }

            updateBinaryStatus()
            updateModelStatus()

            renderStatus(orchestrator.state.value)
            dashboardAppBulb.repaint()
            dashboardVoiceBulb.repaint()
            dashboardModeBulb.repaint()
            miniAppBulb.repaint()
            miniVoiceBulb.repaint()
            miniModeBulb.repaint()
            revalidate()
            repaint()
        } finally {
            isUpdatingLocalization = false
        }
    }

    private fun isPlaceholderText(text: String): Boolean {
        return text.startsWith("No dictations yet") ||
            text.startsWith("Aucune dictée") ||
            text.startsWith("Noch keine Diktate") ||
            text.startsWith("Диктовки отсутствуют") ||
            text.startsWith("変換履歴がありません") ||
            text.startsWith("文字起こしはまだありません") ||
            text.startsWith("暂无听写记录") ||
            text.startsWith("Henüz dikte yok") ||
            text.startsWith("لا توجد تسجيلات") ||
            text.startsWith("No hay dictados") ||
            text.startsWith("Nessuna dettatura")
    }

    private fun updateFontsRecursively(comp: Component) {
        if (comp === keyField || comp === activeHotkeyLabel || comp === binaryPathField) {
            return
        }
        val regularFont = FontManager.regular(FontManager.DEFAULT_SIZE)
        val boldFont = FontManager.bold(FontManager.DEFAULT_SIZE)
        val f = comp.font
        if (f != null && f.family != FontManager.hackRegularFont.family && f.family != FontManager.hackBoldFont.family) {
            val size = f.size2D
            comp.font = if (f.isBold) FontManager.bold(size) else FontManager.regular(size)
        } else if (f == null || f.family == FontManager.hackRegularFont.family) {
            comp.font = regularFont
        }
        if (comp is JTabbedPane) {
            comp.font = boldFont
        }
        if (comp is JComboBox<*>) {
            comp.font = regularFont
        }
        if (comp is JCheckBox) {
            comp.font = regularFont
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
            if (isUpdatingLocalization) return@addActionListener
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
            if (isUpdatingLocalization) return@addActionListener
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

        // 3. Microphone Input Gain (Criterion C-09)
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.32
        panel.add(gainLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        val gainPanel = JPanel(BorderLayout(8, 0))
        gainPanel.isOpaque = false
        gainSlider.isOpaque = false
        gainSlider.addChangeListener {
            val v = gainSlider.value
            val multiplier = v / 100.0f
            gainValueLabel.text = String.format(Locale.US, "%d%% (%.1fx)", v, multiplier)
            orchestrator.audioCapture.gain = multiplier
            saveCurrentConfig()
        }
        gainPanel.add(gainSlider, BorderLayout.CENTER)
        gainPanel.add(gainValueLabel, BorderLayout.EAST)
        panel.add(gainPanel, gbc)

        // 4. Live Audio Input Level & Test Mic (Criteria C-07, C-08, E-07)
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.weightx = 0.32
        panel.add(inputLevelLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        val testPanel = JPanel(BorderLayout(8, 0))
        testPanel.isOpaque = false
        testPanel.add(settingsVuMeter, BorderLayout.CENTER)
        styleMinimalistButton(testMicButton)
        testMicButton.addActionListener {
            if (orchestrator.isTestingAudio()) {
                orchestrator.stopAudioTest()
                testMicButton.text = AppLocalization.tr("btn.test_mic")
                settingsVuMeter.reset()
            } else {
                testMicButton.text = AppLocalization.tr("btn.stop_test")
                orchestrator.startAudioTest { rms, peak, clipping ->
                    SwingUtilities.invokeLater {
                        settingsVuMeter.updateLevel(rms, peak, clipping)
                    }
                }
            }
        }
        testPanel.add(testMicButton, BorderLayout.EAST)
        panel.add(testPanel, gbc)

        // 5. Speech-to-Text Engine Selection
        gbc.gridx = 0
        gbc.gridy = 5
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

        // 6. Global Push-to-Talk Hotkey
        gbc.gridx = 0
        gbc.gridy = 6
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
        if (su.kamil.dev.golos.system.linux.LinuxPermissionManager.isLinux()) {
            val permPanel =
                JPanel(BorderLayout(6, 4)).apply {
                    border =
                        BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(Color(220, 220, 220), 1),
                            EmptyBorder(6, 8, 6, 8),
                        )
                }
            val permStatusLabel = JLabel()
            val permBtn =
                JButton("Grant Permission").apply {
                    font = FontManager.regular(FontManager.SMALL_SIZE)
                    styleMinimalistButton(this)
                }
            val copyCmdBtn =
                JButton("Terminal Command").apply {
                    font = FontManager.regular(FontManager.SMALL_SIZE)
                    styleMinimalistButton(this)
                }

            fun updatePermStatus() {
                val hasPerms = su.kamil.dev.golos.system.linux.LinuxPermissionManager.hasInputPermissions()
                if (hasPerms) {
                    permStatusLabel.text = "Wayland Hotkeys: Input permissions active (Browser/Telegram supported)"
                    permStatusLabel.foreground = Color(0, 128, 0)
                    permBtn.isVisible = false
                    copyCmdBtn.isVisible = false
                } else {
                    permStatusLabel.text = "Wayland Hotkeys: Missing permissions for Browser/Telegram"
                    permStatusLabel.foreground = Color(180, 50, 0)
                    permBtn.isVisible = true
                    copyCmdBtn.isVisible = true
                }
            }
            updatePermStatus()

            permBtn.addActionListener {
                permBtn.isEnabled = false
                permStatusLabel.text = "Requesting permissions via pkexec..."
                su.kamil.dev.golos.system.linux.LinuxPermissionManager.requestPermissionsAsync { granted ->
                    SwingUtilities.invokeLater {
                        permBtn.isEnabled = true
                        updatePermStatus()
                        if (granted) {
                            orchestrator.updateHotkey(orchestrator.currentHotkey)
                            JOptionPane.showMessageDialog(
                                this@PreferencesDialog,
                                "Wayland permissions granted! Global hotkey is active for Browser and Telegram.",
                                "Permissions Granted",
                                JOptionPane.INFORMATION_MESSAGE,
                            )
                        } else {
                            JOptionPane.showMessageDialog(
                                this@PreferencesDialog,
                                "Permission request cancelled or failed.\nRun in terminal:\n" +
                                    su.kamil.dev.golos.system.linux.LinuxPermissionManager.getManualCommand(),
                                "Permission Required",
                                JOptionPane.WARNING_MESSAGE,
                            )
                        }
                    }
                }
            }

            copyCmdBtn.addActionListener {
                val cmd = su.kamil.dev.golos.system.linux.LinuxPermissionManager.getManualCommand()
                try {
                    val sel = StringSelection(cmd)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                } catch (_: Exception) {
                }
                JOptionPane.showMessageDialog(
                    this@PreferencesDialog,
                    "Command copied to clipboard:\n\n$cmd\n\nRun this in a terminal to grant permanent access.",
                    "Wayland Input Setup",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }

            val permBtnBox = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            permBtnBox.add(copyCmdBtn)
            permBtnBox.add(permBtn)

            permPanel.add(permStatusLabel, BorderLayout.CENTER)
            permPanel.add(permBtnBox, BorderLayout.EAST)
            hotkeyOuterPanel.add(permPanel, BorderLayout.CENTER)
        }

        hotkeyEditPanel.add(applyHotkeyBtn)
        hotkeyOuterPanel.add(hotkeyEditPanel, BorderLayout.SOUTH)
        panel.add(hotkeyOuterPanel, gbc)

        // 7. Hotkey Trigger Mode (Hold to Talk vs Toggle On/Off - Criterion D-03)
        gbc.gridx = 0
        gbc.gridy = 7
        gbc.weightx = 0.32
        panel.add(triggerModeLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        triggerModeCombo.addActionListener {
            if (isUpdatingLocalization) return@addActionListener
            val newMode = if (triggerModeCombo.selectedIndex == 1) TriggerMode.TOGGLE_ON_OFF else TriggerMode.HOLD_TO_TALK
            val cur = orchestrator.currentHotkey
            if (cur.triggerMode != newMode) {
                val updated = cur.copy(triggerMode = newMode)
                orchestrator.updateHotkey(updated)
                saveCurrentConfig()
                renderStatus(orchestrator.state.value)
            }
        }
        panel.add(triggerModeCombo, gbc)

        // 8. Text Insertion Mode
        gbc.gridx = 0
        gbc.gridy = 8
        gbc.weightx = 0.32
        panel.add(insLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        insertionModeCombo.addActionListener {
            if (isUpdatingLocalization) return@addActionListener
            syncInjectionConfig()
            saveCurrentConfig()
        }
        panel.add(insertionModeCombo, gbc)

        // 6. Insertion Timing (On the Fly vs On Release)
        gbc.gridx = 0
        gbc.gridy = 7
        gbc.weightx = 0.32
        panel.add(timingLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        timingCombo.addActionListener {
            if (isUpdatingLocalization) return@addActionListener
            syncInjectionConfig()
            saveCurrentConfig()
            renderStatus(orchestrator.state.value)
        }
        panel.add(timingCombo, gbc)

        // 10. Clipboard Privacy Options
        gbc.gridx = 0
        gbc.gridy = 10
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

        // 11. System Autostart
        gbc.gridx = 0
        gbc.gridy = 11
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

        // 12. Speech Style Profile (Criteria J-04, J-05, D-15)
        gbc.gridx = 0
        gbc.gridy = 12
        gbc.weightx = 0.32
        val profileLabel = JLabel("Style Profile:")
        profileLabel.font = FontManager.regular(FontManager.DEFAULT_SIZE)
        panel.add(profileLabel, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.68
        styleProfileCombo.addActionListener {
            if (isUpdatingLocalization) return@addActionListener
            orchestrator.manualProfile =
                when (styleProfileCombo.selectedIndex) {
                    1 -> su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER
                    2 -> su.kamil.dev.golos.core.model.ApplicationProfile.MAIL
                    3 -> su.kamil.dev.golos.core.model.ApplicationProfile.CODE
                    4 -> su.kamil.dev.golos.core.model.ApplicationProfile.GENERAL
                    else -> null
                }
            renderStatus(orchestrator.state.value)
            saveCurrentConfig()
        }
        panel.add(styleProfileCombo, gbc)

        // 13. Configuration Toolbar (Reset, Export, Import)
        gbc.gridx = 0
        gbc.gridy = 13
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
            if (isUpdatingLocalization) return@addActionListener
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
            if (isUpdatingLocalization) return@addActionListener
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
        historySearchField.columns = 10
        searchBox.add(historySearchField)
        val appFilterLabel = JLabel("App:")
        appFilterLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
        searchBox.add(appFilterLabel)
        historyAppFilterBox.prototypeDisplayValue = "All Applications"
        historyAppFilterBox.addActionListener {
            refreshHistoryList(historySearchField.text.trim())
        }
        searchBox.add(historyAppFilterBox)
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

        // Update app filter dropdown items dynamically
        val currentSelected = historyAppFilterBox.selectedItem as? String ?: "All Applications"
        val uniqueApps = listOf("All Applications") + historyManager.getUniqueAppNames()
        if (historyAppFilterBox.itemCount != uniqueApps.size) {
            val model = DefaultComboBoxModel(uniqueApps.toTypedArray())
            if (uniqueApps.contains(currentSelected)) {
                model.selectedItem = currentSelected
            } else {
                model.selectedItem = "All Applications"
            }
            historyAppFilterBox.model = model
        }

        val selectedApp = historyAppFilterBox.selectedItem as? String ?: "All Applications"
        val filtered =
            allEntries.filter { entry ->
                val matchesApp =
                    selectedApp == "All Applications" ||
                        entry.appName.equals(selectedApp, ignoreCase = true)
                val matchesQuery = query.isEmpty() || entry.text.contains(query, ignoreCase = true)
                matchesApp && matchesQuery
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
        val appTag = if (entry.appName.isNotBlank()) "  |  ${entry.appName}" else ""
        val profileTag = if (entry.profile.isNotBlank()) " [${entry.profile}]" else ""
        val infoLabel = JLabel("$dateStr  |  $durSec$appTag$profileTag  |  ${entry.engine.ifEmpty { "GolosAI" }}")
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

    private fun createBatchTab(): JPanel {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = EmptyBorder(12, 14, 12, 14)
        panel.background = Color(250, 250, 250)

        val topCard = JPanel(GridBagLayout())
        topCard.background = Color.WHITE
        topCard.border =
            CompoundBorder(
                LineBorder(Color(230, 230, 230), 1, true),
                EmptyBorder(12, 14, 12, 14),
            )
        val gbc =
            GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 4, 4, 4)
            }

        // Selection row
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        val chooseDirBtn =
            JButton("Choose Folder...").apply {
                font = FontManager.regular(FontManager.SMALL_SIZE)
                styleMinimalistButton(this)
                addActionListener { promptSelectBatchDirectory() }
            }
        topCard.add(chooseDirBtn, gbc)

        gbc.gridx = 1
        gbc.weightx = 0.0
        val chooseFilesBtn =
            JButton("Choose Files...").apply {
                font = FontManager.regular(FontManager.SMALL_SIZE)
                styleMinimalistButton(this)
                addActionListener { promptSelectBatchFiles() }
            }
        topCard.add(chooseFilesBtn, gbc)

        gbc.gridx = 2
        gbc.weightx = 1.0
        batchPathLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
        batchPathLabel.foreground = Color(90, 90, 90)
        topCard.add(batchPathLabel, gbc)

        // Options row: Recursive & Export Formats
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 3
        val optionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false }
        batchRecursiveCheck.font = FontManager.regular(FontManager.SMALL_SIZE)
        batchRecursiveCheck.isOpaque = false
        optionsPanel.add(batchRecursiveCheck)

        val formatsLabel =
            JLabel("Export:").apply {
                font = FontManager.bold(FontManager.SMALL_SIZE)
            }
        optionsPanel.add(formatsLabel)

        batchExportTxtCheck.font = FontManager.regular(FontManager.SMALL_SIZE)
        batchExportTxtCheck.isOpaque = false
        batchExportSrtCheck.font = FontManager.regular(FontManager.SMALL_SIZE)
        batchExportSrtCheck.isOpaque = false
        batchExportVttCheck.font = FontManager.regular(FontManager.SMALL_SIZE)
        batchExportVttCheck.isOpaque = false
        optionsPanel.add(batchExportTxtCheck)
        optionsPanel.add(batchExportSrtCheck)
        optionsPanel.add(batchExportVttCheck)
        topCard.add(optionsPanel, gbc)

        // Action row: Start & Cancel buttons
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 3
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false }
        batchStartBtn.font = FontManager.bold(FontManager.DEFAULT_SIZE)
        styleMinimalistButton(batchStartBtn, isAccent = true)
        batchStartBtn.addActionListener { startBatchTranscription() }
        actionPanel.add(batchStartBtn)

        batchCancelBtn.font = FontManager.regular(FontManager.DEFAULT_SIZE)
        styleMinimalistButton(batchCancelBtn)
        batchCancelBtn.addActionListener { cancelBatchTranscription() }
        actionPanel.add(batchCancelBtn)

        topCard.add(actionPanel, gbc)

        // Progress row
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 3
        val progressPanel = JPanel(BorderLayout(6, 4)).apply { isOpaque = false }
        batchOverallProgressBar.font = FontManager.mono(FontManager.SMALL_SIZE)
        progressPanel.add(batchOverallProgressBar, BorderLayout.CENTER)
        batchStatusLabel.font = FontManager.regular(FontManager.SMALL_SIZE)
        batchStatusLabel.foreground = Color(80, 80, 80)
        progressPanel.add(batchStatusLabel, BorderLayout.SOUTH)
        topCard.add(progressPanel, gbc)

        panel.add(topCard, BorderLayout.NORTH)

        // Center: Results scroll pane
        val resultsScroll =
            JScrollPane(
                batchResultsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply {
                border = LineBorder(Color(230, 230, 230), 1)
                viewport.background = Color(250, 250, 250)
                verticalScrollBar.unitIncrement = 16
            }
        panel.add(resultsScroll, BorderLayout.CENTER)

        // Bottom: Summary report
        batchSummaryLabel.font = FontManager.mono(FontManager.SMALL_SIZE)
        batchSummaryLabel.foreground = Color(60, 60, 60)
        batchSummaryLabel.border = EmptyBorder(4, 4, 4, 4)
        panel.add(batchSummaryLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun promptSelectBatchDirectory() {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Select Audio Directory for Batch Transcription"
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedBatchDir = chooser.selectedFile
            selectedBatchFiles = emptyList()
            batchPathLabel.text = "Folder: ${chooser.selectedFile.absolutePath}"
        }
    }

    private fun promptSelectBatchFiles() {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = true
        chooser.dialogTitle = "Select Audio Files for Batch Transcription"
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedBatchFiles = chooser.selectedFiles.toList()
            selectedBatchDir = null
            batchPathLabel.text = "Selected ${selectedBatchFiles.size} audio file(s)"
        }
    }

    private fun startBatchTranscription() {
        val transcriber = orchestrator.batchTranscriber
        val formats = mutableSetOf<ExportFormat>()
        if (batchExportTxtCheck.isSelected) formats.add(ExportFormat.TXT)
        if (batchExportSrtCheck.isSelected) formats.add(ExportFormat.SRT)
        if (batchExportVttCheck.isSelected) formats.add(ExportFormat.VTT)

        val filesToProcess =
            if (selectedBatchFiles.isNotEmpty()) {
                selectedBatchFiles
            } else if (selectedBatchDir != null) {
                val exts =
                    setOf(
                        "wav", "wave", "mp3", "ogg", "flac", "m4a", "aac", "mp4", "mkv", "avi", "mov", "webm",
                    )
                val seq =
                    if (batchRecursiveCheck.isSelected) {
                        selectedBatchDir!!.walkTopDown()
                    } else {
                        selectedBatchDir!!.listFiles()?.asSequence() ?: emptySequence()
                    }
                seq.filter { it.isFile && it.extension.lowercase() in exts }.sortedBy { it.name }.toList()
            } else {
                JOptionPane.showMessageDialog(
                    this,
                    "Please select an audio folder or files first.",
                    "No Files Selected",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return
            }

        if (filesToProcess.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "No supported audio files found in selected location.",
                "No Audio Files",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }

        batchResultsPanel.removeAll()
        batchResultsPanel.revalidate()
        batchResultsPanel.repaint()
        batchStartBtn.isEnabled = false
        batchCancelBtn.isEnabled = true
        batchOverallProgressBar.value = 0
        batchOverallProgressBar.string = "0% (0/${filesToProcess.size})"
        batchStatusLabel.text = "Starting batch processing of ${filesToProcess.size} files..."

        transcriber.onProgress = { p ->
            SwingUtilities.invokeLater {
                batchOverallProgressBar.value = (p.overallProgress * 100).toInt()
                batchOverallProgressBar.string =
                    "${(p.overallProgress * 100).toInt()}% (${p.completedFiles}/${p.totalFiles})"
                val currentName = p.currentFile?.name ?: ""
                batchStatusLabel.text = "Processing: $currentName (${(p.currentFileProgress * 100).toInt()}%)"
            }
        }

        transcriber.onFileCompleted = { file, result, status ->
            SwingUtilities.invokeLater {
                if (result != null) {
                    historyManager.addEntry(
                        text = result.text,
                        durationMs = status.audioDurationMs,
                        engine = orchestrator.speechEngine.displayName,
                        appName = "Batch: ${file.name}",
                        profile = "GENERAL",
                    )
                    refreshHistoryList()
                }
                addBatchResultCard(status)
            }
        }

        batchTranscriberJob =
            coroutineScope.launch {
                val summary = transcriber.processFiles(filesToProcess, formats)
                SwingUtilities.invokeLater {
                    batchStartBtn.isEnabled = true
                    batchCancelBtn.isEnabled = false
                    val rtfStr = String.format(Locale.US, "%.2fx", summary.overallRtf)
                    val totalAudioSec = summary.totalAudioDurationMs / 1000L
                    val totalProcSec = summary.totalProcessingTimeMs / 1000L
                    batchStatusLabel.text =
                        "Completed: ${summary.successfulFiles}/${summary.totalFiles} files | Overall RTF: $rtfStr"
                    batchSummaryLabel.text =
                        "Audio: ${totalAudioSec}s | Proc: ${totalProcSec}s | RTF: $rtfStr | Failed: ${summary.failedFiles}"
                }
            }
    }

    private fun cancelBatchTranscription() {
        orchestrator.batchTranscriber.cancel()
        batchTranscriberJob?.cancel()
        batchStartBtn.isEnabled = true
        batchCancelBtn.isEnabled = false
        batchStatusLabel.text = "Batch processing cancelled."
    }

    private fun addBatchResultCard(status: BatchItemStatus) {
        val card = JPanel(BorderLayout(8, 4))
        card.background = Color.WHITE
        card.border =
            CompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color(230, 230, 230)),
                EmptyBorder(8, 10, 8, 10),
            )
        card.maximumSize = Dimension(Short.MAX_VALUE.toInt(), 54)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val nameLabel =
            JLabel(status.file.name).apply {
                font = FontManager.bold(FontManager.DEFAULT_SIZE)
            }
        left.add(nameLabel)

        val isOk = status.state == BatchItemState.COMPLETED
        val statusBadge =
            JLabel(status.state.name).apply {
                font = FontManager.bold(FontManager.INDICATOR_SIZE)
                foreground = if (isOk) Color(46, 125, 50) else Color(198, 40, 40)
                if (status.errorMessage != null) {
                    toolTipText = status.errorMessage
                }
            }
        left.add(statusBadge)
        card.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply { isOpaque = false }
        val rtfText = if (status.rtf > 0f) String.format(Locale.US, "RTF: %.2fx", status.rtf) else ""
        val durText = if (status.audioDurationMs > 0L) "${status.audioDurationMs / 1000L}s" else ""
        val procText = if (status.processingTimeMs > 0L) "${status.processingTimeMs}ms" else ""

        val metricsLabel =
            JLabel("$durText  |  $procText  |  $rtfText").apply {
                font = FontManager.mono(FontManager.SMALL_SIZE)
                foreground = Color(100, 100, 100)
            }
        right.add(metricsLabel)
        card.add(right, BorderLayout.EAST)

        batchResultsPanel.add(card)
        batchResultsPanel.revalidate()
        batchResultsPanel.repaint()
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
            modelStatusLabel.text = "[OK] " + AppLocalization.tr("status.model_downloaded")
            modelStatusLabel.foreground = Color(0, 140, 0)
            downloadModelBtn.text = AppLocalization.tr("btn.redownload")
            whisperEngine?.modelPath = modelDownloader.getLocalModelFile(selectedModel).absolutePath
        } else {
            modelStatusLabel.text = "[!] " + AppLocalization.tr("status.model_not_found")
            modelStatusLabel.foreground = Color(180, 0, 0)
            downloadModelBtn.text = "${AppLocalization.tr("btn.download")} (${selectedModel.approximateSizeMb} MB)"
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

    private fun refreshMetricsCards() {
        val cur = orchestrator.metricsHandler.currentTextMetrics
        currentMetricsCard.updateCurrentReplica(cur)

        val histMean = orchestrator.metricsHandler.computeHistoryMean(historyManager.getAll())
        historyMetricsCard.updateHistoryMean(histMean)

        val allTime = orchestrator.metricsHandler.getAllTimeMetrics()
        allTimeMetricsCard.updateAllTime(allTime)
    }

    private fun wireHistoryListener() {
        val oldCallback = orchestrator.onTranscriptionWithContextCompleted
        orchestrator.onTranscriptionWithContextCompleted = { result, engine, window, profile ->
            oldCallback?.invoke(result, engine, window, profile)
            if (oldCallback == null) {
                historyManager.addEntry(
                    text = result.text,
                    durationMs = result.durationMs,
                    engine = engine.displayName,
                    appName = window.appName,
                    profile = profile.name,
                )
            }
            SwingUtilities.invokeLater {
                recentDictationArea.text = result.text
                refreshHistoryList()
                refreshMetricsCards()
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
        triggerModeCombo.selectedIndex = if (hk.triggerMode == TriggerMode.TOGGLE_ON_OFF) 1 else 0
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

        // Style Profile (Criteria J-04, J-05, D-15)
        styleProfileCombo.selectedIndex =
            when (c.postProcessing.activeAppProfile.uppercase()) {
                "MESSENGER" -> 1
                "MAIL" -> 2
                "CODE" -> 3
                "GENERAL" -> 4
                else -> 0
            }
        orchestrator.manualProfile =
            when (styleProfileCombo.selectedIndex) {
                1 -> su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER
                2 -> su.kamil.dev.golos.core.model.ApplicationProfile.MAIL
                3 -> su.kamil.dev.golos.core.model.ApplicationProfile.CODE
                4 -> su.kamil.dev.golos.core.model.ApplicationProfile.GENERAL
                else -> null
            }
        orchestrator.postProcessingSettings = c.postProcessing

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

        // Gain (Criterion C-09)
        val gain = c.audio.gain.coerceIn(0.0f, 2.0f)
        orchestrator.audioCapture.gain = gain
        gainSlider.value = (gain * 100).toInt()
        gainValueLabel.text = String.format(Locale.US, "%d%% (%.1fx)", gainSlider.value, gain)

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
        val currentProfileStr =
            when (styleProfileCombo.selectedIndex) {
                1 -> "MESSENGER"
                2 -> "MAIL"
                3 -> "CODE"
                4 -> "GENERAL"
                else -> "AUTO"
            }

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
                        gain = orchestrator.audioCapture.gain,
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
                postProcessing =
                    orchestrator.postProcessingSettings.copy(
                        activeAppProfile = currentProfileStr,
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
        if (state == DictationState.IDLE || state == DictationState.PROCESSING) {
            dashboardVuMeter.reset()
        }

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

        floatingBarWindow?.updateStatus(state)
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

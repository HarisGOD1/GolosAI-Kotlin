package su.kamil.dev.golos.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import su.kamil.dev.golos.app.DictationOrchestrator
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Swing Preferences and Status Window for GolosAI.
 * Allows configuring microphones, speech engines, and customizable multi-key hotkeys.
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

    private val coroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private var availableDevices: List<AudioDevice> = emptyList()

    init {
        initUi()
        observeState()
    }

    private fun initUi() {
        defaultCloseOperation = HIDE_ON_CLOSE
        setSize(540, 460)
        setLocationRelativeTo(null)
        layout = BorderLayout(10, 10)

        // Header Panel
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(16, 16, 8, 16)
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

        // Form Panel
        val formPanel = JPanel(GridBagLayout())
        formPanel.border = EmptyBorder(10, 16, 10, 16)
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(6, 6, 6, 6)
            gridx = 0
            gridy = 0
            weightx = 0.3
        }

        // 1. Microphone Device Selection
        formPanel.add(JLabel("Microphone Input:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        refreshMicrophoneList()
        micCombo.addActionListener {
            val idx = micCombo.selectedIndex
            if (idx in availableDevices.indices) {
                orchestrator.selectedDevice = availableDevices[idx]
            }
        }
        formPanel.add(micCombo, gbc)

        // 2. Speech-to-Text Engine Selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.3
        formPanel.add(JLabel("Processing Engine:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        availableEngines.forEach { engineCombo.addItem(it.displayName) }
        engineCombo.addActionListener {
            val idx = engineCombo.selectedIndex
            if (idx in availableEngines.indices) {
                orchestrator.speechEngine = availableEngines[idx]
            }
        }
        formPanel.add(engineCombo, gbc)

        // 3. Active Hotkey Info
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.weightx = 0.3
        formPanel.add(JLabel("Active Shortcut:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 0.7
        activeHotkeyLabel.font = Font(Font.MONOSPACED, Font.BOLD, 13)
        activeHotkeyLabel.foreground = Color(0, 102, 204)
        formPanel.add(activeHotkeyLabel, gbc)

        // 4. Change Hotkey Configuration
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.weightx = 0.3
        formPanel.add(JLabel("Change Shortcut:"), gbc)
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
        formPanel.add(hotkeyEditPanel, gbc)

        add(formPanel, BorderLayout.CENTER)

        // Bottom Action Panel
        val actionPanel = JPanel(BorderLayout(10, 10))
        actionPanel.border = EmptyBorder(10, 16, 16, 16)

        pttButton.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        pttButton.preferredSize = Dimension(200, 48)
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
                    when (state) {
                        DictationState.IDLE -> {
                            statusLabel.text = "Status: IDLE (Ready - Hold ${activeHotkeyLabel.text})"
                            statusLabel.background = Color(230, 245, 230)
                            statusLabel.foreground = Color(30, 120, 30)
                            pttButton.text = "🎙️ Hold to Speak (Push to Talk)"
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
        }
    }
}

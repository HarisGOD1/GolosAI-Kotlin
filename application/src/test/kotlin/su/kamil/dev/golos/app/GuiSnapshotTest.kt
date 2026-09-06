package su.kamil.dev.golos.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.app.config.SettingsManager
import su.kamil.dev.golos.app.history.HistoryManager
import su.kamil.dev.golos.app.ui.AppLanguage
import su.kamil.dev.golos.app.ui.AppLocalization
import su.kamil.dev.golos.app.ui.BulbWidget
import su.kamil.dev.golos.app.ui.FontManager
import su.kamil.dev.golos.app.ui.PreferencesDialog
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.ports.AudioCapturePort
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.core.state.DictationStateMachine
import su.kamil.dev.golos.system.autostart.AutoStartManager
import su.kamil.dev.golos.system.keyboard.SimulatedHotkeyHook
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

class GuiSnapshotTest {
    private class DummyAudioCapture : AudioCapturePort {
        override fun getAvailableDevices(): List<AudioDevice> =
            listOf(
                AudioDevice("dev1", "Built-in Microphone", isDefault = true),
                AudioDevice("dev2", "System Output Monitor (Speakers)", isDefault = false, isLoopbackMonitor = true),
            )

        override fun startCapture(
            device: AudioDevice?,
            onChunkCaptured: (AudioChunk) -> Unit,
        ) {}

        override fun stopCapture(): AudioChunk? = null

        override fun isCapturing(): Boolean = false
    }

    private class DummyTextInjector : TextInjectorPort {
        override fun injectText(
            text: String,
            config: InjectionConfig,
        ): Result<Unit> = Result.success(Unit)
    }

    @Test
    fun `render UI snapshots of all screens to PNG`() {
        val headless = System.getProperty("java.awt.headless") == "true" ||
            java.awt.GraphicsEnvironment.isHeadless()
        org.junit.jupiter.api.Assumptions.assumeFalse(
            headless,
            "Skipping GUI snapshot test in headless environment",
        )
        val outDir = File("build/screenshots").apply { mkdirs() }
        val mockEngine = MockSpeechToTextEngine(predeterminedText = "Testing GolosAI Speech recognition snapshot")
        val orchestrator =
            DictationOrchestrator(
                stateMachine = DictationStateMachine(),
                audioCapture = DummyAudioCapture(),
                speechEngine = mockEngine,
                hotkeyHook = SimulatedHotkeyHook(),
                textInjector = DummyTextInjector(),
            )

        SwingUtilities.invokeAndWait {
            val dialog =
                PreferencesDialog(
                    orchestrator = orchestrator,
                    availableEngines = listOf(mockEngine),
                    settingsManager = SettingsManager(File.createTempFile("cfg", ".yaml")),
                    historyManager = HistoryManager(File.createTempFile("hist", ".jsonl")),
                    autoStartManager = AutoStartManager(),
                )

            val width = 700
            val height = 720

            // 1. Dashboard (Idle)
            dialog.renderStatus(DictationState.IDLE)
            renderContainer(dialog, width, height, File(outDir, "1_dashboard_idle.png"))

            // 2. Dashboard (Listening / Recording)
            dialog.renderStatus(DictationState.RECORDING)
            renderContainer(dialog, width, height, File(outDir, "2_dashboard_recording.png"))

            // 3. Dashboard (Processing)
            dialog.renderStatus(DictationState.PROCESSING)
            renderContainer(dialog, width, height, File(outDir, "3_dashboard_processing.png"))

            // 4. Collapsed Bar (Idle)
            dialog.toggleCollapse()
            dialog.renderStatus(DictationState.IDLE)
            renderContainer(dialog.collapsedBarPanel, 640, 68, File(outDir, "4_collapsed_bar_idle.png"))

            // 4b. Floating Bar (Idle)
            val testFloatingPanel = createFloatingBarSnapshotPanel()
            val barW = 580.coerceAtLeast(testFloatingPanel.preferredSize.width)
            val barH = 54.coerceAtLeast(testFloatingPanel.preferredSize.height)
            renderContainer(testFloatingPanel, barW, barH, File(outDir, "4b_floating_bar_idle.png"))

            // 5. Collapsed Bar (Recording)
            dialog.renderStatus(DictationState.RECORDING)
            renderContainer(dialog.collapsedBarPanel, 640, 68, File(outDir, "5_collapsed_bar_recording.png"))

            dialog.toggleCollapse()
            dialog.renderStatus(DictationState.IDLE)

            // 6. Settings Tab
            dialog.selectTab(1)
            renderContainer(dialog, width, height, File(outDir, "6_settings_tab.png"))

            // 7. Whisper Tab
            dialog.selectTab(2)
            renderContainer(dialog, width, height, File(outDir, "7_whisper_tab.png"))

            // 8. History Tab
            dialog.selectTab(3)
            renderContainer(dialog, width, height, File(outDir, "8_history_tab.png"))

            // 8b. Batch Audio Tab (Criteria N-09..N-18)
            dialog.selectTab(4)
            renderContainer(dialog, width, height, File(outDir, "8b_batch_tab.png"))

            // 9. Multilingual: Russian UI
            AppLocalization.setLanguage(AppLanguage.RU)
            dialog.selectTab(0)
            renderContainer(dialog, width, height, File(outDir, "9_dashboard_russian.png"))

            // 10. Multilingual: Japanese UI
            AppLocalization.setLanguage(AppLanguage.JP)
            dialog.selectTab(0)
            renderContainer(dialog, width, height, File(outDir, "10_dashboard_japanese.png"))

            // 11. Multilingual: Chinese UI
            AppLocalization.setLanguage(AppLanguage.CN)
            dialog.selectTab(0)
            renderContainer(dialog, width, height, File(outDir, "11_dashboard_chinese.png"))

            // 12. Multilingual: Arabic UI
            AppLocalization.setLanguage(AppLanguage.AR)
            dialog.selectTab(0)
            renderContainer(dialog, width, height, File(outDir, "12_dashboard_arabic.png"))

            // 13. Settings Tab in Chinese (verify dropdown items and font)
            AppLocalization.setLanguage(AppLanguage.CN)
            dialog.selectTab(1)
            renderContainer(dialog, width, height, File(outDir, "13_settings_tab_chinese.png"))

            // 14. Whisper Tab in Chinese (verify full language coverage)
            dialog.selectTab(2)
            renderContainer(dialog, width, height, File(outDir, "14_whisper_tab_chinese.png"))

            // 15. Checkbox hover test (verify font remains proportional sans-serif on rollover)
            dialog.selectTab(1)
            val autostartCheckField =
                PreferencesDialog::class.java.getDeclaredField("autostartCheck").apply {
                    isAccessible = true
                }
            val autostartCheck = autostartCheckField.get(dialog) as JCheckBox
            autostartCheck.model.isRollover = true
            for (listener in autostartCheck.mouseListeners) {
                listener.mouseEntered(
                    java.awt.event.MouseEvent(
                        autostartCheck,
                        java.awt.event.MouseEvent.MOUSE_ENTERED,
                        System.currentTimeMillis(),
                        0,
                        10,
                        10,
                        0,
                        false,
                    ),
                )
            }
            assertFalse(
                autostartCheck.font.family.contains("Hack", ignoreCase = true),
                "Checkbox font must not be Hack on hover",
            )
            renderContainer(dialog, width, height, File(outDir, "15_checkbox_hover.png"))

            // Reset language
            AppLocalization.setLanguage(AppLanguage.EN)
        }
    }

    private fun layoutDeep(
        comp: Component,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        comp.setBounds(x, y, w, h)
        if (comp is Container) {
            comp.doLayout()
            if (comp is JTabbedPane) {
                val selected = comp.selectedComponent
                if (selected != null) {
                    val insets = comp.insets
                    val tabHeight = 34
                    selected.isVisible = true
                    layoutDeep(
                        selected,
                        insets.left + 4,
                        insets.top + tabHeight,
                        w - insets.left - insets.right - 8,
                        h - insets.top - insets.bottom - tabHeight - 6,
                    )
                }
            } else {
                for (child in comp.components) {
                    if (child.width > 0 && child.height > 0) {
                        layoutDeep(child, child.x, child.y, child.width, child.height)
                    }
                }
            }
            comp.validate()
        }
    }

    private fun renderContainer(
        comp: Container,
        width: Int,
        height: Int,
        outFile: File,
    ) {
        layoutDeep(comp, 0, 0, width, height)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Fill background
        g2.color = comp.background ?: java.awt.Color.WHITE
        g2.fillRect(0, 0, width, height)

        comp.paint(g2)

        // If it's a tabbed pane or contains one, ensure selected component is painted
        if (comp is PreferencesDialog) {
            val tabbedPaneField =
                PreferencesDialog::class.java.getDeclaredField("tabbedPane").apply {
                    isAccessible = true
                }
            val tabPane = tabbedPaneField.get(comp) as JTabbedPane
            val selected = tabPane.selectedComponent
            if (selected != null && selected.width > 0 && selected.height > 0) {
                val subG = g2.create(selected.x, selected.y, selected.width, selected.height)
                selected.paint(subG)
                subG.dispose()
            }
        }

        g2.dispose()
        ImageIO.write(image, "PNG", outFile)
        println("Saved snapshot: ${outFile.name} (${width}x$height)")
    }

    private fun createFloatingBarSnapshotPanel(): JPanel {
        val panel = JPanel()
        panel.border =
            CompoundBorder(
                LineBorder(Color(195, 205, 220), 1, true),
                EmptyBorder(5, 12, 5, 12),
            )
        panel.background = Color(245, 247, 250)
        panel.layout = BorderLayout(12, 0)

        val bulbsBox = JPanel(GridLayout(1, 3, 8, 0))
        bulbsBox.isOpaque = false
        bulbsBox.add(
            BulbWidget(
                bulbColor = Color(46, 204, 113),
                glowColor = Color(46, 204, 113, 140),
                title = "APP",
                statusText = "ACTIVE",
                compact = true,
            ),
        )
        bulbsBox.add(
            BulbWidget(
                bulbColor = Color(46, 204, 113),
                glowColor = Color(46, 204, 113, 140),
                title = "VOICE",
                statusText = "READY",
                compact = true,
            ),
        )
        bulbsBox.add(
            BulbWidget(
                bulbColor = Color(52, 152, 219),
                glowColor = Color(52, 152, 219, 140),
                title = "MODE",
                statusText = "F8 • Direct",
                compact = true,
            ),
        )

        val actionsBox = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        actionsBox.isOpaque = false

        val speakBtn = JButton("Speak")
        speakBtn.font = FontManager.regular(12f)
        actionsBox.add(speakBtn)

        val expBtn = JButton("[+] " + AppLocalization.tr("btn.expand"))
        expBtn.font = FontManager.regular(12f)
        actionsBox.add(expBtn)

        val clsBtn = JButton("X")
        clsBtn.font = FontManager.regular(12f)
        clsBtn.foreground = Color(180, 40, 40)
        actionsBox.add(clsBtn)

        panel.add(bulbsBox, BorderLayout.WEST)
        panel.add(actionsBox, BorderLayout.EAST)
        return panel
    }
}

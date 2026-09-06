package su.kamil.dev.golos.app.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.app.DictationOrchestrator
import su.kamil.dev.golos.app.config.SettingsManager
import su.kamil.dev.golos.app.history.HistoryManager
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.ports.AudioCapturePort
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.core.state.DictationStateMachine
import su.kamil.dev.golos.system.autostart.AutoStartManager
import su.kamil.dev.golos.system.keyboard.SimulatedHotkeyHook
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

class UiTextOverlapTest {
    private class DummyAudioCapture : AudioCapturePort {
        override fun getAvailableDevices(): List<AudioDevice> {
            return listOf(AudioDevice("dev1", "Built-in Microphone", isDefault = true))
        }

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

    private fun createDialog(): PreferencesDialog {
        val mockEngine = MockSpeechToTextEngine()
        val orchestrator =
            DictationOrchestrator(
                stateMachine = DictationStateMachine(),
                audioCapture = DummyAudioCapture(),
                speechEngine = mockEngine,
                hotkeyHook = SimulatedHotkeyHook(),
                textInjector = DummyTextInjector(),
            )
        return PreferencesDialog(
            orchestrator = orchestrator,
            availableEngines = listOf(mockEngine),
            settingsManager = SettingsManager(File.createTempFile("cfg", ".yaml")),
            historyManager = HistoryManager(File.createTempFile("hist", ".jsonl")),
            autoStartManager = AutoStartManager(),
        )
    }

    private fun getTabPanel(
        dialog: PreferencesDialog,
        tabIndex: Int,
    ): JPanel {
        val tabbedPaneField =
            PreferencesDialog::class.java.getDeclaredField("tabbedPane").apply {
                isAccessible = true
            }
        val tabPane = tabbedPaneField.get(dialog) as JTabbedPane
        val comp = tabPane.getComponentAt(tabIndex)
        return if (comp is JScrollPane) {
            comp.viewport.view as JPanel
        } else {
            comp as JPanel
        }
    }

    @Test
    fun `test Settings tab components have no overlapping layout bounds across all localizations`() {
        SwingUtilities.invokeAndWait {
            val dialog = createDialog()
            dialog.setSize(700, 720)
            dialog.doLayout()

            val settingsPanel = getTabPanel(dialog, 1)

            for (lang in AppLanguage.entries) {
                AppLocalization.setLanguage(lang)
                dialog.updateLocalizedTexts()

                val prefW = 680
                val prefH = settingsPanel.preferredSize.height.coerceAtLeast(600)
                settingsPanel.size = Dimension(prefW, prefH)
                settingsPanel.doLayout()

                val visibleComponents = settingsPanel.components.filter { it.isVisible }

                for (i in visibleComponents.indices) {
                    for (j in i + 1 until visibleComponents.size) {
                        val c1 = visibleComponents[i]
                        val c2 = visibleComponents[j]
                        val b1 = c1.bounds
                        val b2 = c2.bounds

                        val intersection = b1.intersection(b2)
                        val overlaps = !intersection.isEmpty && intersection.width > 0 && intersection.height > 0
                        assertFalse(
                            overlaps,
                            "Overlapping components in Settings tab for language ${lang.code}: " +
                                "[${c1.javaClass.simpleName} bounds=$b1] and [${c2.javaClass.simpleName} bounds=$b2] " +
                                "overlap at $intersection",
                        )
                    }
                }
            }
            AppLocalization.setLanguage(AppLanguage.EN)
        }
    }

    @Test
    fun `test trigger mode and insertion timing rows are distinct and not overlapping`() {
        SwingUtilities.invokeAndWait {
            val dialog = createDialog()
            val triggerLabelField =
                PreferencesDialog::class.java.getDeclaredField("triggerModeLabel").apply {
                    isAccessible = true
                }
            val timingLabelField =
                PreferencesDialog::class.java.getDeclaredField("timingLabel").apply {
                    isAccessible = true
                }
            val triggerComboField =
                PreferencesDialog::class.java.getDeclaredField("triggerModeCombo").apply {
                    isAccessible = true
                }
            val timingComboField =
                PreferencesDialog::class.java.getDeclaredField("timingCombo").apply {
                    isAccessible = true
                }

            val triggerLabel = triggerLabelField.get(dialog) as JLabel
            val timingLabel = timingLabelField.get(dialog) as JLabel
            val triggerCombo = triggerComboField.get(dialog) as JComboBox<*>
            val timingCombo = timingComboField.get(dialog) as JComboBox<*>

            val settingsPanel = getTabPanel(dialog, 1)
            settingsPanel.size = Dimension(680, 800)
            settingsPanel.doLayout()

            assertTrue(triggerLabel.y < timingLabel.y, "timingLabel must be below triggerModeLabel")
            assertTrue(
                triggerLabel.y + triggerLabel.height <= timingLabel.y,
                "timingLabel (y=${timingLabel.y}) must not overlap triggerModeLabel " +
                    "(y=${triggerLabel.y}, h=${triggerLabel.height})",
            )
            assertTrue(triggerCombo.y < timingCombo.y, "timingCombo must be below triggerModeCombo")
            assertTrue(
                triggerCombo.y + triggerCombo.height <= timingCombo.y,
                "timingCombo (y=${timingCombo.y}) must not overlap triggerModeCombo " +
                    "(y=${triggerCombo.y}, h=${triggerCombo.height})",
            )
        }
    }

    @Test
    fun `test Settings tab renders to image without errors across all localizations`() {
        SwingUtilities.invokeAndWait {
            val dialog = createDialog()
            val settingsPanel = getTabPanel(dialog, 1)

            for (lang in AppLanguage.entries) {
                AppLocalization.setLanguage(lang)
                dialog.updateLocalizedTexts()

                settingsPanel.size = Dimension(680, settingsPanel.preferredSize.height.coerceAtLeast(600))
                settingsPanel.doLayout()

                val image = BufferedImage(680, settingsPanel.height, BufferedImage.TYPE_INT_ARGB)
                val g2 = image.createGraphics()
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                settingsPanel.paint(g2)
                g2.dispose()

                assertNotNull(image)
            }
            AppLocalization.setLanguage(AppLanguage.EN)
        }
    }

    @Test
    fun `test all tabs have non-overlapping components in GridBag layouts`() {
        SwingUtilities.invokeAndWait {
            val dialog = createDialog()
            for (tabIdx in listOf(1, 2)) {
                val panel = getTabPanel(dialog, tabIdx)
                panel.size = Dimension(680, panel.preferredSize.height.coerceAtLeast(600))
                panel.doLayout()

                val visible = panel.components.filter { it.isVisible }
                for (i in visible.indices) {
                    for (j in i + 1 until visible.size) {
                        val c1 = visible[i]
                        val c2 = visible[j]
                        val isect = c1.bounds.intersection(c2.bounds)
                        assertFalse(
                            !isect.isEmpty && isect.width > 0 && isect.height > 0,
                            "Overlap in tab $tabIdx: ${c1.javaClass.simpleName} (${c1.bounds}) and " +
                                "${c2.javaClass.simpleName} (${c2.bounds})",
                        )
                    }
                }
            }
        }
    }
}

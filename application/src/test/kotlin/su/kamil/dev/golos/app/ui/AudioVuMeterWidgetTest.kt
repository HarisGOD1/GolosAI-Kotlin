package su.kamil.dev.golos.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.AudioWarningType
import javax.swing.SwingUtilities

class AudioVuMeterWidgetTest {
    @Test
    fun `test AudioVuMeterWidget updates level and warning states`() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
            java.awt.GraphicsEnvironment.isHeadless(),
            "Skipping Swing widget test in headless environment",
        )
        SwingUtilities.invokeAndWait {
            val widget = AudioVuMeterWidget(showTitle = true)
            assertEquals("Audio Input Level:", widget.titleLabel.text)

            // Update level
            widget.updateLevel(rmsDb = -18.5f, peakDb = -12.0f, isClipping = false)

            // Update warning for silence (Criterion C-08)
            widget.updateWarning(AudioWarningType.SILENCE_MUTED)
            val warningField =
                AudioVuMeterWidget::class.java.getDeclaredField("warningLabel").apply {
                    isAccessible = true
                }
            val warningLabel = warningField.get(widget) as javax.swing.JLabel
            assertTrue(warningLabel.isVisible)
            assertTrue(warningLabel.text.contains("Microphone muted", ignoreCase = true))

            // Update warning for clipping (Criterion E-07)
            widget.updateWarning(AudioWarningType.CLIPPING)
            assertTrue(warningLabel.isVisible)
            assertTrue(warningLabel.text.contains("clipping", ignoreCase = true))

            // Reset
            widget.reset()
            assertFalse(warningLabel.isVisible)
            val readoutField =
                AudioVuMeterWidget::class.java.getDeclaredField("dbReadoutLabel").apply {
                    isAccessible = true
                }
            val readoutLabel = readoutField.get(widget) as javax.swing.JLabel
            assertEquals("-inf dB", readoutLabel.text)
        }
    }
}

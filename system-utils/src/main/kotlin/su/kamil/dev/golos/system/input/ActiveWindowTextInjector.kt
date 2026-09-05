package su.kamil.dev.golos.system.input

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.ports.TextInjectorPort
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent

/**
 * Injects transcribed text into the currently active window / input field.
 * Works by copying text to the system clipboard and generating native paste keystrokes.
 */
class ActiveWindowTextInjector(
    private val restoreClipboard: Boolean = false,
    private val pasteDelayMs: Long = 60
) : TextInjectorPort {

    private val logger = LoggerFactory.getLogger(ActiveWindowTextInjector::class.java)

    override fun injectText(text: String): Result<Unit> = runCatching {
        if (text.isBlank()) {
            logger.debug("Skipping text injection for empty text")
            return@runCatching
        }

        val clipboard = Toolkit.getDefaultToolkit().systemClipboard

        // Save existing clipboard content if restoring
        val previousContent: Transferable? = if (restoreClipboard) {
            try {
                clipboard.getContents(null)
            } catch (e: Exception) {
                null
            }
        } else null

        // 1. Put transcribed text into clipboard
        clipboard.setContents(StringSelection(text), null)
        logger.info("Copied transcription to clipboard: \"{}\"", text)

        // 2. Synthesize paste event
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val isHeadless = java.awt.GraphicsEnvironment.isHeadless()

        if (!isHeadless) {
            try {
                val robot = Robot()
                robot.delay(pasteDelayMs.toInt())

                val modifierKey = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL
                robot.keyPress(modifierKey)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifierKey)

                logger.info("Dispatched paste keystroke sequence")
            } catch (e: Exception) {
                logger.warn("AWT Robot paste synthesis failed, falling back to xdotool / clipboard only", e)
                tryXdotoolPaste()
            }
        } else {
            logger.info("Headless environment detected; text placed in clipboard successfully")
            tryXdotoolPaste()
        }

        // 3. Restore previous clipboard content if configured
        if (restoreClipboard && previousContent != null) {
            Thread {
                try {
                    Thread.sleep(pasteDelayMs + 300)
                    clipboard.setContents(previousContent, null)
                } catch (e: Exception) {
                    logger.debug("Could not restore original clipboard contents", e)
                }
            }.start()
        }
    }

    private fun tryXdotoolPaste() {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("linux")) {
            try {
                ProcessBuilder("xdotool", "key", "--clearmodifiers", "ctrl+v")
                    .start()
                    .waitFor()
            } catch (e: Exception) {
                logger.debug("xdotool not available on system", e)
            }
        }
    }
}

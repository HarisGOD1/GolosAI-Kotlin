package su.kamil.dev.golos.system.input

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.system.x11.X11Lib
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent

/**
 * JNA binding for the X11 XTEST extension (libXtst.so.6).
 * Used to generate true hardware-level keystrokes on X11/Xwayland without triggering desktop portal prompts.
 */
interface XtstLib : Library {
    companion object {
        val INSTANCE: XtstLib? = try {
            Native.load("Xtst", XtstLib::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    fun XTestFakeKeyEvent(display: Pointer?, keycode: Int, is_press: Boolean, delay: Long): Int
}

/**
 * Injects transcribed text into the currently active window / input field.
 *
 * Sequence:
 * 1. Sets both System Clipboard (Ctrl+V) and Primary Selection (Shift+Insert / middle-click).
 * 2. Attempts hardware keystroke synthesis via X11 XTEST extension (if libXtst is present).
 * 3. Attempts xdotool (if installed).
 * 4. Synthesizes Ctrl+V using java.awt.Robot (cross-platform; on modern Wayland/Linux,
 *    this uses the desktop Input/RemoteDesktop portal once permission is granted).
 */
class ActiveWindowTextInjector(
    private val restoreClipboard: Boolean = false,
    private val pasteDelayMs: Long = 100
) : TextInjectorPort {

    private val logger = LoggerFactory.getLogger(ActiveWindowTextInjector::class.java)
    private var cachedRobot: Robot? = null

    override fun initialize(): Result<Unit> = runCatching {
        logger.info("Initializing text injector at startup...")
        if (XtstLib.INSTANCE != null) {
            logger.info("Native X11 XTEST extension detected and ready (zero-portal hardware input).")
            return@runCatching
        }

        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                logger.info("Prompting for OS input permissions upfront at startup. If your desktop displays a 'Share remote desktop / input' dialog, click Allow.")
                cachedRobot = Robot().apply { autoDelay = 20 }
                logger.info("AWT Robot input synthesizer successfully initialized at startup.")
            } catch (e: Exception) {
                logger.warn("AWT Robot startup pre-initialization could not obtain permissions: {}", e.message)
            }
        }
    }

    override fun injectText(text: String): Result<Unit> = runCatching {
        if (text.isBlank()) {
            logger.debug("Skipping text injection for empty text")
            return@runCatching
        }

        val toolkit = Toolkit.getDefaultToolkit()
        val clipboard = toolkit.systemClipboard

        val previousContent: Transferable? = if (restoreClipboard) {
            try {
                clipboard.getContents(null)
            } catch (_: Exception) {
                null
            }
        } else null

        // 1. Put text into both System Clipboard and Primary Selection
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
        try {
            toolkit.systemSelection?.setContents(selection, null)
        } catch (e: Exception) {
            logger.debug("Could not set system primary selection", e)
        }
        logger.info("Copied transcription to clipboard: \"{}\"", text)

        val os = System.getProperty("os.name").lowercase()
        val isMac = os.contains("mac")

        Thread.sleep(pasteDelayMs)

        var pasteSuccess = false

        // 2. Try X11 XTest extension (libXtst) for seamless zero-portal hardware event synthesis
        if (XtstLib.INSTANCE != null) {
            pasteSuccess = tryXtstDirectPaste()
        }

        // 3. Try xdotool if installed
        if (!pasteSuccess) {
            pasteSuccess = tryXdotoolPaste()
        }

        // 4. Primary universal keystroke synthesis via java.awt.Robot
        if (!pasteSuccess && !java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                logger.info("Dispatching Ctrl+V paste keystroke via AWT Robot")
                val robot = cachedRobot ?: Robot().apply { autoDelay = 20 }
                cachedRobot = robot

                val modifierKey = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL
                robot.keyPress(modifierKey)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifierKey)
                pasteSuccess = true
                logger.info("Successfully dispatched paste keystroke")
            } catch (e: Exception) {
                logger.warn("Robot paste simulation encountered error: {}", e.message)
            }
        }

        // 5. Optionally restore original clipboard after delay
        if (restoreClipboard && previousContent != null) {
            Thread {
                try {
                    Thread.sleep(pasteDelayMs + 400)
                    clipboard.setContents(previousContent, null)
                } catch (e: Exception) {
                    logger.debug("Could not restore original clipboard contents", e)
                }
            }.start()
        }
    }

    private fun tryXtstDirectPaste(): Boolean {
        val xtst = XtstLib.INSTANCE ?: return false
        val x11 = X11Lib.INSTANCE ?: return false
        val display = x11.XOpenDisplay(null) ?: return false

        return try {
            val ctrlKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("Control_L")).toInt() and 0xFF
            var vKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("v")).toInt() and 0xFF
            if (vKeycode == 0) {
                vKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("V")).toInt() and 0xFF
            }

            if (ctrlKeycode == 0 || vKeycode == 0) {
                logger.warn("Could not resolve keycodes for XTest Ctrl+V")
                return false
            }

            // KeyPress Control_L
            xtst.XTestFakeKeyEvent(display, ctrlKeycode, true, 0)
            // KeyPress V
            xtst.XTestFakeKeyEvent(display, vKeycode, true, 0)
            // KeyRelease V
            xtst.XTestFakeKeyEvent(display, vKeycode, false, 0)
            // KeyRelease Control_L
            xtst.XTestFakeKeyEvent(display, ctrlKeycode, false, 0)

            x11.XFlush(display)
            logger.info("Synthesized hardware-level Ctrl+V via X11 XTEST extension")
            true
        } catch (e: Exception) {
            logger.warn("Direct XTEST paste failed", e)
            false
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    private fun tryXdotoolPaste(): Boolean {
        return try {
            val process = ProcessBuilder("xdotool", "key", "--clearmodifiers", "ctrl+v")
                .start()
            val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}

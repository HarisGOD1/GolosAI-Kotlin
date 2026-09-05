package su.kamil.dev.golos.system.input

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.system.x11.X11Lib
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit

/**
 * JNA binding for the X11 XTEST extension (libXtst.so.6).
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
 * Supports:
 * - Direct character typing without modifying system clipboard (privacy protection)
 * - Automatic detection of active focused input field
 * - Fallback to clipboard if no input field is present
 * - Safe instant clipboard restoration if temporary paste is used
 */
class ActiveWindowTextInjector(
    private val pasteDelayMs: Long = 80
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
                logger.info("Pre-initializing AWT Robot. If your desktop prompts for RemoteDesktop/input sharing, click Allow.")
                cachedRobot = Robot().apply { autoDelay = 15 }
                logger.info("AWT Robot input synthesizer successfully initialized at startup.")
            } catch (e: Exception) {
                logger.warn("AWT Robot startup pre-initialization could not obtain permissions: {}", e.message)
            }
        }
    }

    override fun injectText(text: String, config: InjectionConfig): Result<Unit> = runCatching {
        if (text.isBlank()) {
            logger.debug("Skipping text injection for empty text")
            return@runCatching
        }

        if (java.awt.GraphicsEnvironment.isHeadless()) {
            logger.debug("Headless environment detected; skipping hardware keystroke injection.")
            return@runCatching
        }

        val hasInputField = isInputFieldFocused()
        logger.info("Injecting text (length: {}, mode: {}, fieldFocused: {}, copyToClipboard: {})",
            text.length, config.mode, hasInputField, config.copyToClipboard
        )

        // Case 1: No active input field detected
        if (!hasInputField) {
            logger.warn("No active text input field detected.")
            if (config.copyToClipboardIfNoField || config.copyToClipboard) {
                copyToClipboard(text)
                logger.info("Saved transcription to clipboard because no active input field was focused.")
            }
            return@runCatching
        }

        var typingSucceeded = false

        // Case 2: Direct Typing Mode (Privacy-preserving, does not overwrite clipboard)
        if (config.mode == InsertionMode.DIRECT_TYPING) {
            typingSucceeded = tryDirectTyping(text)
            if (typingSucceeded) {
                logger.info("Direct typing successfully injected text without touching clipboard.")
            } else {
                logger.warn("Direct typing was unviable; falling back to temporary paste with instant clipboard restoration.")
                typingSucceeded = pasteWithInstantRestore(text)
            }
        }

        // Case 3: Clipboard Paste Mode
        if (!typingSucceeded && config.mode == InsertionMode.CLIPBOARD_PASTE) {
            if (config.copyToClipboard) {
                // User wants text left in clipboard
                copyToClipboard(text)
                dispatchPasteKeystroke()
            } else {
                // User does NOT want text left in clipboard -> paste with instant restore
                pasteWithInstantRestore(text)
            }
        }

        // Always copy to clipboard if user explicitly enabled it
        if (config.copyToClipboard) {
            copyToClipboard(text)
        }
    }

    private fun isInputFieldFocused(): Boolean {
        val x11 = X11Lib.INSTANCE ?: return true
        val display = x11.XOpenDisplay(null) ?: return true

        return try {
            val root = x11.XDefaultRootWindow(display)
            val focusWinMem = Memory(8)
            val revertToMem = Memory(4)
            x11.XGetInputFocus(display, focusWinMem, revertToMem)
            val targetWin = focusWinMem.getLong(0)
            // 0 = None, 1 = PointerRoot
            targetWin > 1L && targetWin != root
        } catch (_: Exception) {
            true
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    private fun tryDirectTyping(text: String): Boolean {
        // 1. Try xdotool type if installed (robust for unicode / multi-lingual)
        if (tryXdotoolType(text)) {
            return true
        }

        // 2. Try XTest character typing if libXtst is loaded and text is simple ASCII
        if (XtstLib.INSTANCE != null && isAscii(text)) {
            if (tryXtstType(text)) {
                return true
            }
        }

        return false
    }

    private fun isAscii(text: String): Boolean {
        return text.all { it.code in 32..126 || it == '\n' || it == '\t' }
    }

    private fun tryXdotoolType(text: String): Boolean {
        return try {
            val process = ProcessBuilder("xdotool", "type", "--clearmodifiers", "--delay", "5", "--", text)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun tryXtstType(text: String): Boolean {
        val xtst = XtstLib.INSTANCE ?: return false
        val x11 = X11Lib.INSTANCE ?: return false
        val display = x11.XOpenDisplay(null) ?: return false

        return try {
            val shiftKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("Shift_L")).toInt() and 0xFF

            // PHASE 1: PRE-VALIDATION (All-or-Nothing)
            // Verify that every single character can be resolved to a valid hardware keycode BEFORE sending any key events.
            // This prevents typing a partial word and then aborting to paste, which would cause word duplication.
            val keycodes = ArrayList<Int>(text.length)
            for (ch in text) {
                val code = resolveAsciiKeycode(x11, display, ch)
                if (code == 0) {
                    logger.debug("Cannot resolve X11 keycode for character '{}' (0x{}); aborting direct typing before sending any keystrokes.", ch, Integer.toHexString(ch.code))
                    return false
                }
                keycodes.add(code)
            }

            // PHASE 2: ATOMIC TYPING
            for (i in text.indices) {
                val ch = text[i]
                val code = keycodes[i]
                val needsShift = ch.isUpperCase() || "!@#$%^&*()_+{}|:\"<>?~".contains(ch)
                if (needsShift && shiftKeycode != 0) {
                    xtst.XTestFakeKeyEvent(display, shiftKeycode, true, 0)
                }
                xtst.XTestFakeKeyEvent(display, code, true, 0)
                xtst.XTestFakeKeyEvent(display, code, false, 0)
                if (needsShift && shiftKeycode != 0) {
                    xtst.XTestFakeKeyEvent(display, shiftKeycode, false, 0)
                }
            }
            x11.XFlush(display)
            true
        } catch (e: Exception) {
            logger.warn("XTest direct typing encountered error", e)
            false
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    private fun resolveAsciiKeycode(x11: X11Lib, display: Pointer, ch: Char): Int {
        val keysym = when (ch) {
            ' ' -> 0x0020L
            '\n' -> x11.XStringToKeysym("Return").takeIf { it != 0L } ?: 0xFF0DL
            '\t' -> x11.XStringToKeysym("Tab").takeIf { it != 0L } ?: 0xFF09L
            else -> {
                if (ch.code in 32..126) ch.code.toLong()
                else 0L
            }
        }
        if (keysym == 0L) return 0
        return x11.XKeysymToKeycode(display, keysym).toInt() and 0xFF
    }

    private fun sendXTestKey(xtst: XtstLib, display: Pointer, keycode: Int) {
        if (keycode != 0) {
            xtst.XTestFakeKeyEvent(display, keycode, true, 0)
            xtst.XTestFakeKeyEvent(display, keycode, false, 0)
        }
    }

    /**
     * Pastes text via clipboard while immediately restoring the user's previous clipboard contents
     * so that sensitive passwords/tokens are not lost or left exposed.
     */
    private fun pasteWithInstantRestore(text: String): Boolean {
        val toolkit = Toolkit.getDefaultToolkit()
        val clipboard = toolkit.systemClipboard

        // 1. Snapshot previous clipboard
        val previousContent: Transferable? = try {
            clipboard.getContents(null)
        } catch (_: Exception) {
            null
        }

        // 2. Set new text and dispatch paste
        clipboard.setContents(StringSelection(text), null)
        try {
            toolkit.systemSelection?.setContents(StringSelection(text), null)
        } catch (_: Exception) {}

        Thread.sleep(pasteDelayMs)
        val pasteOk = dispatchPasteKeystroke()

        // 3. Immediately restore previous clipboard contents after brief pause
        Thread {
            try {
                Thread.sleep(pasteDelayMs + 80)
                if (previousContent != null) {
                    clipboard.setContents(previousContent, null)
                    logger.debug("Restored original user clipboard content successfully.")
                }
            } catch (e: Exception) {
                logger.debug("Could not restore original clipboard contents", e)
            }
        }.start()

        return pasteOk
    }

    private fun copyToClipboard(text: String) {
        val toolkit = Toolkit.getDefaultToolkit()
        val selection = StringSelection(text)
        toolkit.systemClipboard.setContents(selection, null)
        try {
            toolkit.systemSelection?.setContents(selection, null)
        } catch (_: Exception) {}
        logger.info("Copied transcription to system clipboard.")
    }

    private fun dispatchPasteKeystroke(): Boolean {
        // Try XTest first
        if (XtstLib.INSTANCE != null && tryXtstPaste()) {
            return true
        }

        // Try xdotool
        if (tryXdotoolPaste()) {
            return true
        }

        // Try Robot
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            return try {
                val robot = cachedRobot ?: Robot().apply { autoDelay = 15 }
                cachedRobot = robot
                val isMac = System.getProperty("os.name").lowercase().contains("mac")
                val modifier = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL
                robot.keyPress(modifier)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifier)
                true
            } catch (e: Exception) {
                logger.warn("AWT Robot paste failed: {}", e.message)
                false
            }
        }
        return false
    }

    private fun tryXtstPaste(): Boolean {
        val xtst = XtstLib.INSTANCE ?: return false
        val x11 = X11Lib.INSTANCE ?: return false
        val display = x11.XOpenDisplay(null) ?: return false

        return try {
            val ctrlKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("Control_L")).toInt() and 0xFF
            var vKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("v")).toInt() and 0xFF
            if (vKeycode == 0) {
                vKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("V")).toInt() and 0xFF
            }

            if (ctrlKeycode == 0 || vKeycode == 0) return false

            xtst.XTestFakeKeyEvent(display, ctrlKeycode, true, 0)
            xtst.XTestFakeKeyEvent(display, vKeycode, true, 0)
            xtst.XTestFakeKeyEvent(display, vKeycode, false, 0)
            xtst.XTestFakeKeyEvent(display, ctrlKeycode, false, 0)
            x11.XFlush(display)
            true
        } catch (_: Exception) {
            false
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    private fun tryXdotoolPaste(): Boolean {
        return try {
            val process = ProcessBuilder("xdotool", "key", "--clearmodifiers", "ctrl+v")
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}

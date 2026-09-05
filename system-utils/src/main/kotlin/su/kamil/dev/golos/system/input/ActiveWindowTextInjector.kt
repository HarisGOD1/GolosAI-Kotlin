package su.kamil.dev.golos.system.input

import com.sun.jna.Memory
import com.sun.jna.NativeLong
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
 * Injects transcribed text into the currently active window / input field.
 *
 * Under Linux X11, synthesizes keystrokes directly using libX11 (XSendEvent)
 * to avoid triggering the AWT Robot remote-desktop / screen-sharing portal prompt.
 * Under Windows / macOS, uses java.awt.Robot keystroke synthesis.
 */
class ActiveWindowTextInjector(
    private val restoreClipboard: Boolean = false,
    private val pasteDelayMs: Long = 80
) : TextInjectorPort {

    private val logger = LoggerFactory.getLogger(ActiveWindowTextInjector::class.java)

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

        // 1. Put text into both System Clipboard (Ctrl+V) and Primary Selection (Shift+Insert / middle-click)
        val selection = StringSelection(text)
        clipboard.setContents(selection, null)
        try {
            toolkit.systemSelection?.setContents(selection, null)
        } catch (e: Exception) {
            logger.debug("Could not set system selection (primary)", e)
        }
        logger.info("Copied transcription to system clipboard: \"{}\"", text)

        val os = System.getProperty("os.name").lowercase()
        val isLinux = os.contains("linux")
        val isMac = os.contains("mac")

        Thread.sleep(pasteDelayMs)

        var pasteSuccess = false

        // 2. On Linux X11: use native X11 XSendEvent to avoid Robot ScreenCast/RemoteDesktop portal popup
        if (isLinux && X11Lib.INSTANCE != null) {
            pasteSuccess = tryX11DirectPaste()
        }

        // 3. Try xdotool if available on Linux
        if (isLinux && !pasteSuccess) {
            pasteSuccess = tryXdotoolPaste()
        }

        // 4. Fallback: java.awt.Robot (standard on macOS & Windows; fallback on Linux if portal allowed)
        if (!pasteSuccess && !java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                logger.info("Falling back to AWT Robot paste simulation")
                val robot = Robot()
                val modifierKey = if (isMac) KeyEvent.VK_META else KeyEvent.VK_CONTROL
                robot.keyPress(modifierKey)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifierKey)
                pasteSuccess = true
            } catch (e: Exception) {
                logger.warn("Robot paste simulation could not complete", e)
            }
        }

        // 5. Optionally restore original clipboard after paste
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

    /**
     * Synthesizes Ctrl+V directly to the focused X11 window using XSendEvent.
     * This bypasses Java's AWT Robot and does NOT trigger the Linux ScreenCast desktop portal.
     */
    private fun tryX11DirectPaste(): Boolean {
        val x11 = X11Lib.INSTANCE ?: return false
        val display = x11.XOpenDisplay(null) ?: return false

        return try {
            val root = x11.XDefaultRootWindow(display)
            val focusWinMem = Memory(8)
            val revertToMem = Memory(4)
            x11.XGetInputFocus(display, focusWinMem, revertToMem)
            var targetWin = focusWinMem.getLong(0)
            if (targetWin <= 1L) {
                targetWin = root
            }

            val ctrlKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("Control_L")).toInt() and 0xFF
            var vKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("v")).toInt() and 0xFF
            if (vKeycode == 0) {
                vKeycode = x11.XKeysymToKeycode(display, x11.XStringToKeysym("V")).toInt() and 0xFF
            }

            if (ctrlKeycode == 0 || vKeycode == 0) {
                logger.warn("Could not resolve X11 keycodes for Ctrl+V paste")
                return false
            }

            // 1. Press Control_L (type = 2, state = 0)
            sendX11KeyEvent(x11, display, targetWin, root, 2, ctrlKeycode, 0)

            // 2. Press V (type = 2, state = 4 -> ControlMask)
            sendX11KeyEvent(x11, display, targetWin, root, 2, vKeycode, 4)

            // 3. Release V (type = 3, state = 4 -> ControlMask)
            sendX11KeyEvent(x11, display, targetWin, root, 3, vKeycode, 4)

            // 4. Release Control_L (type = 3, state = 0)
            sendX11KeyEvent(x11, display, targetWin, root, 3, ctrlKeycode, 0)

            x11.XFlush(display)
            logger.info("Sent native X11 Ctrl+V paste event to window 0x{}", java.lang.Long.toHexString(targetWin))
            true
        } catch (e: Exception) {
            logger.warn("Direct X11 paste failed", e)
            false
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    private fun sendX11KeyEvent(
        x11: X11Lib,
        display: Pointer,
        window: Long,
        root: Long,
        type: Int,
        keycode: Int,
        state: Int
    ) {
        val event = Memory(192)
        event.clear()
        event.setInt(0, type)
        event.setNativeLong(8, NativeLong(0))
        event.setInt(16, 1) // send_event = True
        event.setPointer(24, display)
        event.setLong(32, window)
        event.setLong(40, root)
        event.setLong(48, 0L) // subwindow
        event.setNativeLong(56, NativeLong(0)) // time = CurrentTime
        event.setInt(64, 1) // x
        event.setInt(68, 1) // y
        event.setInt(72, 1) // x_root
        event.setInt(76, 1) // y_root
        event.setInt(80, state)
        event.setInt(84, keycode)
        event.setInt(88, 1) // same_screen

        val mask = if (type == 2) 1L /* KeyPressMask */ else 2L /* KeyReleaseMask */
        x11.XSendEvent(display, window, true, mask, event)
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

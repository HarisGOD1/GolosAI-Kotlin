package su.kamil.dev.golos.system.keyboard

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNA binding for X11 C library functions used for global hotkeys on Linux.
 */
interface X11Lib : Library {
    companion object {
        val INSTANCE: X11Lib? = try {
            if (System.getProperty("os.name").lowercase().contains("linux") &&
                System.getenv("DISPLAY") != null
            ) {
                Native.load("X11", X11Lib::class.java)
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    fun XOpenDisplay(displayName: String?): com.sun.jna.Pointer?
    fun XCloseDisplay(display: com.sun.jna.Pointer?): Int
    fun XDefaultRootWindow(display: com.sun.jna.Pointer?): Long
    fun XStringToKeysym(string: String): Long
    fun XKeysymToKeycode(display: com.sun.jna.Pointer?, keysym: Long): Byte
    fun XGrabKey(
        display: com.sun.jna.Pointer?,
        keycode: Int,
        modifiers: Int,
        grab_window: Long,
        owner_events: Boolean,
        pointer_mode: Int,
        keyboard_mode: Int
    ): Int
    fun XUngrabKey(
        display: com.sun.jna.Pointer?,
        keycode: Int,
        modifiers: Int,
        grab_window: Long
    ): Int
    fun XNextEvent(display: com.sun.jna.Pointer?, event_return: com.sun.jna.Pointer?): Int
}

/**
 * Global push-to-talk hotkey manager supporting Linux X11 via JNA, with graceful fallback.
 */
class GlobalHotkeyManager : GlobalHotkeyHook {

    private val logger = LoggerFactory.getLogger(GlobalHotkeyManager::class.java)
    private val isHookActive = AtomicBoolean(false)
    private var listenerThread: Thread? = null

    override val isRegistered: Boolean
        get() = isHookActive.get()

    override fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit
    ): Result<Unit> = runCatching {
        if (isHookActive.getAndSet(true)) {
            unregister()
            isHookActive.set(true)
        }

        val x11 = X11Lib.INSTANCE
        if (x11 != null) {
            startLinuxX11Listener(x11, config, onKeyDown, onKeyUp)
        } else {
            logger.warn("Native X11 not detected or DISPLAY unset. Hotkey hook initialized in simulated mode.")
        }
    }

    private fun startLinuxX11Listener(
        x11: X11Lib,
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit
    ) {
        listenerThread = Thread({
            val display = x11.XOpenDisplay(null)
            if (display == null) {
                logger.error("Failed to open X11 display for global hotkey")
                isHookActive.set(false)
                return@Thread
            }

            try {
                val root = x11.XDefaultRootWindow(display)
                val keysym = x11.XStringToKeysym(config.keyName)
                val keycode = x11.XKeysymToKeycode(display, keysym).toInt() and 0xFF

                if (keycode == 0) {
                    logger.error("Could not resolve X11 keycode for key '{}'", config.keyName)
                    return@Thread
                }

                // Grab key for AnyModifier (0x8000 in X11) or specified modifiers
                val grabAnyModifier = 0x8000
                x11.XGrabKey(display, keycode, grabAnyModifier, root, false, 1, 1)
                logger.info("Registered global hotkey '{}' (keycode {}) on X11 root window", config.keyName, keycode)

                // Event loop buffer (192 bytes large enough for XEvent union in 64-bit)
                val eventMemory = com.sun.jna.Memory(256)

                while (isHookActive.get()) {
                    x11.XNextEvent(display, eventMemory)
                    val eventType = eventMemory.getInt(0)

                    // In X11: 2 = KeyPress, 3 = KeyRelease
                    when (eventType) {
                        2 -> {
                            logger.debug("Global hotkey KeyPress detected")
                            onKeyDown()
                        }
                        3 -> {
                            logger.debug("Global hotkey KeyRelease detected")
                            onKeyUp()
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in X11 global hotkey event loop", e)
            } finally {
                x11.XCloseDisplay(display)
            }
        }, "Golos-GlobalHotkeyThread").apply {
            isDaemon = true
            start()
        }
    }

    override fun unregister() {
        if (isHookActive.getAndSet(false)) {
            listenerThread?.interrupt()
            listenerThread = null
            logger.info("Unregistered global hotkey hook")
        }
    }
}

package su.kamil.dev.golos.system.keyboard

import com.sun.jna.Memory
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook
import su.kamil.dev.golos.system.x11.X11Lib
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global push-to-talk hotkey manager supporting multi-key combinations and Linux X11 via JNA.
 * Uses XQueryKeymap hardware state verification and a complete 16-permutation modifier grab matrix
 * (covering Mod5/ISO_Level3_Shift, NumLock, CapsLock, ScrollLock) to reliably capture hotkeys in unfocused windows
 * without auto-repeat self-release artifacts.
 */
class GlobalHotkeyManager : GlobalHotkeyHook {
    companion object {
        private const val MASK_SHIFT = 1
        private const val MASK_LOCK = 2
        private const val MASK_CTRL = 4
        private const val MASK_MOD1 = 8
        private const val MASK_MOD2 = 16
        private const val MASK_MOD3 = 32
        private const val MASK_MOD4 = 64
        private const val MASK_MOD5 = 128
        private const val ANY_MODIFIER = 0x8000
        private const val EVENT_KEY_PRESS = 2
        private const val EVENT_KEY_RELEASE = 3
        private const val BITS_PER_BYTE = 8
        private const val EVENT_BUFFER_BYTES = 256
        private const val KEYMAP_BYTES = 32
        private const val DEBOUNCE_DELAY_MS = 35L
        private const val LOOP_SLEEP_MS = 20L
        private const val THREAD_JOIN_MS = 300L
        private const val SUPERVISOR_INTERVAL_TICKS = 2
        private const val F_KEY_VK_OFFSET = 111
        private const val BYTE_MASK = 0xFF
        private const val COMBINATIONS_COUNT = 16
        private const val BIT_LOCK = 1
        private const val BIT_MOD2 = 2
        private const val BIT_MOD3 = 4
        private const val BIT_MOD5 = 8
    }

    private val logger = LoggerFactory.getLogger(GlobalHotkeyManager::class.java)
    private val isHookActive = AtomicBoolean(false)
    private var listenerThread: Thread? = null

    private val debounceScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Golos-PTT-Debounce").apply { isDaemon = true }
        }
    private val isKeyCurrentlyDown = AtomicBoolean(false)
    private var pendingReleaseJob: ScheduledFuture<*>? = null

    override val isRegistered: Boolean
        get() = isHookActive.get()

    override fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ): Result<Unit> =
        runCatching {
            if (isHookActive.get()) {
                unregister()
            }
            isHookActive.set(true)
            isKeyCurrentlyDown.set(false)

            val x11 = X11Lib.INSTANCE
            if (x11 != null) {
                startLinuxX11Listener(x11, config, onKeyDown, onKeyUp)
            } else {
                logger.warn("Native X11 not detected or DISPLAY unset. Hotkey hook in simulated/inactive mode.")
            }
        }

    private fun resolveKeysym(
        x11: X11Lib,
        rawName: String,
    ): Long {
        val clean = rawName.trim()
        val candidates =
            mutableListOf(
                clean,
                clean.lowercase(),
                clean.uppercase(),
            )

        when (clean.lowercase()) {
            "enter" -> candidates.add("Return")
            "esc" -> candidates.add("Escape")
            "space", " " -> candidates.add("space")
            "backspace", "back space" -> candidates.add("BackSpace")
            "caps lock", "capslock" -> candidates.add("Caps_Lock")
            "page up", "pageup" -> candidates.add("Page_Up")
            "page down", "pagedown" -> candidates.add("Page_Down")
            "print screen", "prntscrn" -> candidates.add("Print")
            "scroll lock" -> candidates.add("Scroll_Lock")
            "num lock" -> candidates.add("Num_Lock")
        }

        var result = 0L
        for (candidate in candidates) {
            val sym = x11.XStringToKeysym(candidate)
            if (sym != 0L) {
                result = sym
                break
            }
        }

        if (result == 0L && clean.length == 1) {
            result = clean[0].code.toLong()
        }
        return result
    }

    private fun startLinuxX11Listener(
        x11: X11Lib,
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ) {
        listenerThread =
            Thread({
                val display = x11.XOpenDisplay(null)
                if (display == null) {
                    logger.error("Failed to open X11 display for global hotkey")
                    isHookActive.set(false)
                    return@Thread
                }

                var grabbedKeycode = 0
                var rootWindow = 0L

                try {
                    x11.XSetErrorHandler { _, _ -> 0 }
                    rootWindow = x11.XDefaultRootWindow(display)

                    val keysym = resolveKeysym(x11, config.keyName)
                    grabbedKeycode = x11.XKeysymToKeycode(display, keysym).toInt() and BYTE_MASK
                    if (grabbedKeycode == 0 && config.keyCode != 0) {
                        val fallbackSym = x11.XStringToKeysym("F" + (config.keyCode - F_KEY_VK_OFFSET))
                        if (fallbackSym != 0L) {
                            grabbedKeycode = x11.XKeysymToKeycode(display, fallbackSym).toInt() and BYTE_MASK
                        }
                    }

                    if (grabbedKeycode == 0) {
                        logger.error("Could not resolve X11 keycode for key '{}'", config.keyName)
                        return@Thread
                    }

                    var baseModifier = 0
                    if (config.shift) baseModifier = baseModifier or MASK_SHIFT
                    if (config.ctrl) baseModifier = baseModifier or MASK_CTRL
                    if (config.alt) baseModifier = baseModifier or MASK_MOD1
                    if (config.meta) baseModifier = baseModifier or MASK_MOD4

                    val lockMasks =
                        (0 until COMBINATIONS_COUNT).map { i ->
                            var mask = 0
                            if ((i and BIT_LOCK) != 0) mask = mask or MASK_LOCK
                            if ((i and BIT_MOD2) != 0) mask = mask or MASK_MOD2
                            if ((i and BIT_MOD3) != 0) mask = mask or MASK_MOD3
                            if ((i and BIT_MOD5) != 0) mask = mask or MASK_MOD5
                            mask
                        }
                    val masksToGrab = lockMasks.map { baseModifier or it }.distinct()

                    x11.XUngrabKey(display, grabbedKeycode, ANY_MODIFIER, rootWindow)
                    for (mask in masksToGrab) {
                        x11.XGrabKey(display, grabbedKeycode, mask, rootWindow, 0, 1, 1)
                    }
                    x11.XFlush(display)
                    x11.XSync(display, false)

                    logger.info(
                        "Registered unfocused global hotkey '{}' (keycode {}, base mod 0x{}, grabbed {} masks)",
                        config.displayText,
                        grabbedKeycode,
                        Integer.toHexString(baseModifier),
                        masksToGrab.size,
                    )

                    val eventMemory = Memory(EVENT_BUFFER_BYTES.toLong())
                    val keysReturn = ByteArray(KEYMAP_BYTES)
                    var pollCounter = 0

                    while (isHookActive.get()) {
                        if (x11.XPending(display) > 0) {
                            x11.XNextEvent(display, eventMemory)
                            val eventType = eventMemory.getInt(0)

                            when (eventType) {
                                EVENT_KEY_PRESS -> {
                                    synchronized(this) {
                                        pendingReleaseJob?.cancel(false)
                                        pendingReleaseJob = null
                                    }
                                    if (isKeyCurrentlyDown.compareAndSet(false, true)) {
                                        logger.debug("Global hotkey KeyPress confirmed: {}", config.displayText)
                                        onKeyDown()
                                    }
                                }
                                EVENT_KEY_RELEASE -> {
                                    x11.XQueryKeymap(display, keysReturn)
                                    val byteIdx = grabbedKeycode / BITS_PER_BYTE
                                    val bitMask = 1 shl (grabbedKeycode % BITS_PER_BYTE)
                                    val isPhysicallyDown = (keysReturn[byteIdx].toInt() and bitMask) != 0

                                    if (isPhysicallyDown) {
                                        logger.trace("X11 auto-repeat release ignored via XQueryKeymap")
                                    } else {
                                        synchronized(this) {
                                            pendingReleaseJob?.cancel(false)
                                            pendingReleaseJob =
                                                debounceScheduler.schedule({
                                                    val verifyKeys = ByteArray(KEYMAP_BYTES)
                                                    x11.XQueryKeymap(display, verifyKeys)
                                                    val verifyByte = grabbedKeycode / BITS_PER_BYTE
                                                    val verifyBit = 1 shl (grabbedKeycode % BITS_PER_BYTE)
                                                    val stillDown = (verifyKeys[verifyByte].toInt() and verifyBit) != 0
                                                    if (!stillDown) {
                                                        if (isKeyCurrentlyDown.compareAndSet(true, false)) {
                                                            logger.debug(
                                                                "Global hotkey KeyRelease confirmed: {}",
                                                                config.displayText,
                                                            )
                                                            onKeyUp()
                                                        }
                                                    }
                                                }, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS)
                                        }
                                    }
                                }
                            }
                        } else {
                            if (isKeyCurrentlyDown.get()) {
                                pollCounter++
                                if (pollCounter >= SUPERVISOR_INTERVAL_TICKS) {
                                    pollCounter = 0
                                    x11.XQueryKeymap(display, keysReturn)
                                    val sByte = grabbedKeycode / BITS_PER_BYTE
                                    val sBit = 1 shl (grabbedKeycode % BITS_PER_BYTE)
                                    val isPhysicallyDown = (keysReturn[sByte].toInt() and sBit) != 0
                                    if (!isPhysicallyDown) {
                                        if (isKeyCurrentlyDown.compareAndSet(true, false)) {
                                            logger.debug(
                                                "Supervisor: key physically released: {}",
                                                config.displayText,
                                            )
                                            onKeyUp()
                                        }
                                    }
                                }
                            } else {
                                pollCounter = 0
                            }

                            try {
                                Thread.sleep(LOOP_SLEEP_MS)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isHookActive.get()) {
                        logger.error("Error in X11 global hotkey event loop", e)
                    }
                } finally {
                    try {
                        if (grabbedKeycode != 0 && rootWindow != 0L) {
                            x11.XUngrabKey(display, grabbedKeycode, ANY_MODIFIER, rootWindow)
                            x11.XFlush(display)
                        }
                        x11.XCloseDisplay(display)
                    } catch (_: Throwable) {
                    }
                }
            }, "Golos-GlobalHotkeyThread").apply {
                isDaemon = true
                start()
            }
    }

    override fun unregister() {
        if (isHookActive.getAndSet(false)) {
            synchronized(this) {
                pendingReleaseJob?.cancel(false)
                pendingReleaseJob = null
            }
            isKeyCurrentlyDown.set(false)
            listenerThread?.interrupt()
            try {
                listenerThread?.join(THREAD_JOIN_MS)
            } catch (_: InterruptedException) {
            }
            listenerThread = null
            logger.info("Unregistered global hotkey hook")
        }
    }
}

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
import kotlin.math.abs

/**
 * Global push-to-talk hotkey manager supporting multi-key combinations and Linux X11 via JNA.
 * Automatically filters out X11 auto-repeat release events to prevent state flickering while holding keys.
 */
class GlobalHotkeyManager : GlobalHotkeyHook {

    private val logger = LoggerFactory.getLogger(GlobalHotkeyManager::class.java)
    private val isHookActive = AtomicBoolean(false)
    private var listenerThread: Thread? = null

    // Debounce scheduler to eliminate X11 key auto-repeat flickering
    private val debounceScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Golos-PTT-Debounce").apply { isDaemon = true }
    }
    private val isKeyCurrentlyDown = AtomicBoolean(false)
    private var pendingReleaseJob: ScheduledFuture<*>? = null

    override val isRegistered: Boolean
        get() = isHookActive.get()

    override fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit
    ): Result<Unit> = runCatching {
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
                // Install a non-fatal error handler to ignore BadAccess if a modifier or key is grabbed by another client
                x11.XSetErrorHandler { _, _ -> 0 }

                val root = x11.XDefaultRootWindow(display)

                // Resolve keycode: try exact, lowercase, uppercase
                var keysym = x11.XStringToKeysym(config.keyName)
                if (keysym == 0L) keysym = x11.XStringToKeysym(config.keyName.lowercase())
                if (keysym == 0L) keysym = x11.XStringToKeysym(config.keyName.uppercase())

                val keycode = x11.XKeysymToKeycode(display, keysym).toInt() and 0xFF
                if (keycode == 0) {
                    logger.error("Could not resolve X11 keycode for key '{}'", config.keyName)
                    return@Thread
                }

                // X11 Modifier masks:
                // Shift: 1 (ShiftMask)
                // Lock: 2 (LockMask / CapsLock)
                // Control: 4 (ControlMask)
                // Mod1: 8 (Mod1Mask / Alt)
                // Mod2: 16 (Mod2Mask / NumLock)
                // Mod4: 64 (Mod4Mask / Super/Windows)
                var baseModifier = 0
                if (config.shift) baseModifier = baseModifier or 1
                if (config.ctrl) baseModifier = baseModifier or 4
                if (config.alt) baseModifier = baseModifier or 8
                if (config.meta) baseModifier = baseModifier or 64

                val capsLockMask = 2
                val numLockMask = 16

                val masksToGrab = if (baseModifier == 0) {
                    listOf(0, capsLockMask, numLockMask, capsLockMask or numLockMask)
                } else {
                    listOf(
                        baseModifier,
                        baseModifier or capsLockMask,
                        baseModifier or numLockMask,
                        baseModifier or capsLockMask or numLockMask
                    )
                }

                for (mask in masksToGrab) {
                    x11.XGrabKey(display, keycode, mask, root, 0, 1, 1)
                }
                x11.XFlush(display)
                x11.XSync(display, false)

                logger.info("Registered global hotkey '{}' (keycode {}, modifiers 0x{}) on X11 root window",
                    config.displayText, keycode, Integer.toHexString(baseModifier)
                )

                val eventMemory = Memory(256)

                while (isHookActive.get()) {
                    if (x11.XPending(display) > 0) {
                        x11.XNextEvent(display, eventMemory)
                        val eventType = eventMemory.getInt(0)

                        when (eventType) {
                            2 -> { // KeyPress
                                // Cancel any pending debounced release
                                synchronized(this) {
                                    pendingReleaseJob?.cancel(false)
                                    pendingReleaseJob = null
                                }

                            if (isKeyCurrentlyDown.compareAndSet(false, true)) {
                                logger.debug("Global hotkey KeyPress triggered: {}", config.displayText)
                                onKeyDown()
                            }
                        }
                        3 -> { // KeyRelease
                            // Check if next event in X11 queue is an auto-repeat KeyPress
                            var isAutoRepeat = false
                            if (x11.XPending(display) > 0) {
                                val peekMemory = Memory(256)
                                x11.XPeekEvent(display, peekMemory)
                                val peekType = peekMemory.getInt(0)
                                if (peekType == 2) {
                                    val curKeycode = eventMemory.getInt(84)
                                    val nextKeycode = peekMemory.getInt(84)
                                    val curTime = eventMemory.getLong(56)
                                    val nextTime = peekMemory.getLong(56)
                                    if (curKeycode == nextKeycode && abs(nextTime - curTime) <= 50) {
                                        isAutoRepeat = true
                                        // Consume the auto-repeat KeyPress so it doesn't re-trigger
                                        x11.XNextEvent(display, peekMemory)
                                    }
                                }
                            }

                            if (!isAutoRepeat) {
                                // Debounce release by 60ms to eliminate any residual hardware/X11 repeat bounce
                                synchronized(this) {
                                    pendingReleaseJob?.cancel(false)
                                    pendingReleaseJob = debounceScheduler.schedule({
                                        if (isKeyCurrentlyDown.compareAndSet(true, false)) {
                                            logger.debug("Global hotkey KeyRelease confirmed: {}", config.displayText)
                                            onKeyUp()
                                        }
                                    }, 60, TimeUnit.MILLISECONDS)
                                }
                            }
                        }
                    }
                } else {
                    try {
                        Thread.sleep(15)
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
                x11.XCloseDisplay(display)
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
            listenerThread = null
            logger.info("Unregistered global hotkey hook")
        }
    }
}

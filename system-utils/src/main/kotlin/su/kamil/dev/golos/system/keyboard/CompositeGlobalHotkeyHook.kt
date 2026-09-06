package su.kamil.dev.golos.system.keyboard

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook
import su.kamil.dev.golos.system.linux.LinuxPermissionManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Composite global hotkey hook combining Linux Evdev (/dev/input/event*)
 * and X11 (XGrabKey) hooks.
 *
 * Ensures hotkeys work seamlessly in native Wayland applications (Browser, Telegram)
 * as well as X11 / Xwayland applications (e.g. IntelliJ IDEA), with automatic
 * permission elevation requests and atomic deduplication of keystroke events.
 */
class CompositeGlobalHotkeyHook(
    val evdevHook: EvdevHotkeyManager = EvdevHotkeyManager(),
    val x11Hook: GlobalHotkeyManager = GlobalHotkeyManager(),
    val permissionManager: LinuxPermissionManager = LinuxPermissionManager,
) : GlobalHotkeyHook {
    private val logger = LoggerFactory.getLogger(CompositeGlobalHotkeyHook::class.java)

    private val isHookActive = AtomicBoolean(false)
    private val isKeyCurrentlyDown = AtomicBoolean(false)
    private var currentConfig: HotkeyConfig? = null

    override val isRegistered: Boolean
        get() = isHookActive.get() && (evdevHook.isRegistered || x11Hook.isRegistered)

    @Suppress("LongMethod")
    override fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ): Result<Unit> {
        if (isHookActive.get()) {
            unregister()
        }

        isHookActive.set(true)
        isKeyCurrentlyDown.set(false)
        currentConfig = config

        val safeKeyDown = {
            if (isKeyCurrentlyDown.compareAndSet(false, true)) {
                logger.debug("Composite hotkey pressed: {}", config.displayText)
                onKeyDown()
            }
        }

        val safeKeyUp = {
            if (isKeyCurrentlyDown.compareAndSet(true, false)) {
                logger.debug("Composite hotkey released: {}", config.displayText)
                onKeyUp()
            }
        }

        var evdevRegistered = false
        if (permissionManager.isLinux()) {
            if (evdevHook.findKeyboardEventDevices().isNotEmpty()) {
                val evdevResult = evdevHook.register(config, safeKeyDown, safeKeyUp)
                evdevRegistered = evdevResult.isSuccess
                if (evdevRegistered) {
                    logger.info("Evdev hotkey hook registered successfully for Wayland apps (Browser, Telegram).")
                } else {
                    logger.warn("Evdev registration failed: {}", evdevResult.exceptionOrNull()?.message)
                }
            } else {
                logger.warn(
                    "Linux /dev/input permissions missing for Wayland hotkeys. " +
                        "Initiating permission elevation request...",
                )
                permissionManager.requestPermissionsAsync { granted ->
                    if (granted && isHookActive.get()) {
                        logger.info("Permissions granted! Binding Evdev hotkey hook for Wayland...")
                        evdevHook.register(config, safeKeyDown, safeKeyUp)
                    } else if (!granted) {
                        logger.warn(
                            "Input permission request was dismissed or denied. " +
                                "Hotkeys will only function in X11/Xwayland windows. " +
                                "To enable in Browser/Telegram, run: {}",
                            permissionManager.getManualCommand(),
                        )
                    }
                }
            }
        }

        val x11Result = x11Hook.register(config, safeKeyDown, safeKeyUp)
        val x11Registered = x11Result.isSuccess

        return if (evdevRegistered || x11Registered) {
            logger.info(
                "Composite hotkey registered (evdev={}, x11={}) for: {}",
                evdevRegistered,
                x11Registered,
                config.displayText,
            )
            Result.success(Unit)
        } else {
            val err = x11Result.exceptionOrNull() ?: Exception("No hotkey backend available")
            Result.failure(err)
        }
    }

    override fun unregister() {
        if (isHookActive.getAndSet(false)) {
            evdevHook.unregister()
            x11Hook.unregister()
            isKeyCurrentlyDown.set(false)
            currentConfig = null
            logger.info("Unregistered composite global hotkey hook")
        }
    }
}

package su.kamil.dev.golos.system.keyboard

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.GlobalHotkeyHook
import su.kamil.dev.golos.system.linux.LinuxLibC
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global push-to-talk hotkey hook reading raw Linux evdev input events (/dev/input/event*).
 * Operates independently of the window server / compositor, providing reliable hotkeys
 * in native Wayland applications (Browser, Telegram, etc.) as well as X11 applications.
 */
@Suppress(
    "TooGenericExceptionCaught",
    "MagicNumber",
    "NestedBlockDepth",
    "CyclomaticComplexMethod",
    "ReturnCount",
)
class EvdevHotkeyManager(
    private val devicesDir: File = File("/dev/input"),
    private val procDevicesFile: File = File("/proc/bus/input/devices"),
    private val libc: LinuxLibC? = LinuxLibC.INSTANCE,
) : GlobalHotkeyHook {
    private val logger = LoggerFactory.getLogger(EvdevHotkeyManager::class.java)

    private val isHookActive = AtomicBoolean(false)
    private val isKeyCurrentlyDown = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    private val openFds = mutableListOf<Int>()

    private var isShiftDown = false
    private var isCtrlDown = false
    private var isAltDown = false
    private var isMetaDown = false

    override val isRegistered: Boolean
        get() = isHookActive.get()

    val activeDeviceCount: Int
        get() = synchronized(openFds) { openFds.size }

    override fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ): Result<Unit> =
        runCatching {
            if (isHookActive.get()) {
                unregister()
            }

            val targetScancode = EvdevKeycodes.resolveScancode(config.keyName)
            if (targetScancode == 0) {
                error("Could not resolve evdev scancode for key '${config.keyName}'")
            }

            val clib = libc ?: error("LinuxLibC native library is not available")
            val targetDevices = findKeyboardEventDevices()
            if (targetDevices.isEmpty()) {
                error("No readable /dev/input/event* keyboard devices found (permissions required)")
            }

            synchronized(openFds) {
                openFds.clear()
                for (dev in targetDevices) {
                    val fd = clib.open(dev.absolutePath, LinuxLibC.O_RDONLY or LinuxLibC.O_NONBLOCK)
                    if (fd >= 0) {
                        openFds.add(fd)
                        logger.debug("Opened evdev keyboard device: {} (fd: {})", dev.name, fd)
                    } else {
                        logger.warn("Could not open evdev device {}: fd={}", dev.name, fd)
                    }
                }
            }

            if (openFds.isEmpty()) {
                error("Failed to open any evdev keyboard devices for reading")
            }

            isHookActive.set(true)
            isKeyCurrentlyDown.set(false)
            isShiftDown = false
            isCtrlDown = false
            isAltDown = false
            isMetaDown = false

            listenerThread =
                Thread({
                    runEventLoop(clib, config, targetScancode, onKeyDown, onKeyUp)
                }, "Golos-EvdevHotkeyThread").apply {
                    isDaemon = true
                    start()
                }

            logger.info(
                "Registered Evdev global hotkey '{}' (scancode {}, monitoring {} devices)",
                config.displayText,
                targetScancode,
                openFds.size,
            )
        }

    private fun runEventLoop(
        clib: LinuxLibC,
        config: HotkeyConfig,
        targetScancode: Int,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ) {
        val eventBuffer = ByteArray(24)
        while (isHookActive.get()) {
            var eventRead = false
            val currentFds = synchronized(openFds) { openFds.toList() }

            for (fd in currentFds) {
                while (isHookActive.get()) {
                    val bytesRead = clib.read(fd, eventBuffer, 24)
                    if (bytesRead == 24) {
                        eventRead = true
                        processInputEvent(eventBuffer, config, targetScancode, onKeyDown, onKeyUp)
                    } else {
                        break
                    }
                }
            }

            if (!eventRead) {
                try {
                    Thread.sleep(15)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun processInputEvent(
        buf: ByteArray,
        config: HotkeyConfig,
        targetScancode: Int,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ) {
        val type = (buf[16].toInt() and 0xFF) or ((buf[17].toInt() and 0xFF) shl 8)
        if (type != EvdevKeycodes.EV_KEY) return

        val code = (buf[18].toInt() and 0xFF) or ((buf[19].toInt() and 0xFF) shl 8)
        val value =
            (buf[20].toInt() and 0xFF) or
                ((buf[21].toInt() and 0xFF) shl 8) or
                ((buf[22].toInt() and 0xFF) shl 16) or
                ((buf[23].toInt() and 0xFF) shl 24)

        // Track modifier keys
        when (code) {
            EvdevKeycodes.KEY_LEFTSHIFT, EvdevKeycodes.KEY_RIGHTSHIFT -> isShiftDown = (value != 0)
            EvdevKeycodes.KEY_LEFTCTRL, EvdevKeycodes.KEY_RIGHTCTRL -> isCtrlDown = (value != 0)
            EvdevKeycodes.KEY_LEFTALT, EvdevKeycodes.KEY_RIGHTALT -> isAltDown = (value != 0)
            EvdevKeycodes.KEY_LEFTMETA, EvdevKeycodes.KEY_RIGHTMETA -> isMetaDown = (value != 0)
        }

        if (code == targetScancode) {
            when (value) {
                1 -> { // Key Press
                    val matchesModifiers =
                        (config.shift == isShiftDown) &&
                            (config.ctrl == isCtrlDown) &&
                            (config.alt == isAltDown) &&
                            (config.meta == isMetaDown)
                    if (matchesModifiers && isKeyCurrentlyDown.compareAndSet(false, true)) {
                        logger.debug("Evdev KeyPress detected: {}", config.displayText)
                        onKeyDown()
                    }
                }
                2 -> { // Auto-repeat: do not fire key release or re-press
                    logger.trace("Evdev auto-repeat ignored for scancode {}", code)
                }
                0 -> { // Key Release
                    if (isKeyCurrentlyDown.compareAndSet(true, false)) {
                        logger.debug("Evdev KeyRelease detected: {}", config.displayText)
                        onKeyUp()
                    }
                }
            }
        }
    }

    /**
     * Inspects /proc/bus/input/devices for devices with 'kbd' handler;
     * falls back to all readable event* files if /proc is not available.
     */
    fun findKeyboardEventDevices(): List<File> {
        if (!devicesDir.exists() || !devicesDir.isDirectory) return emptyList()

        val keyboardEventNames = mutableSetOf<String>()
        if (procDevicesFile.exists() && procDevicesFile.canRead()) {
            try {
                var isKbdDevice = false
                val handlerRegex = Regex("event\\d+")
                procDevicesFile.forEachLine { line ->
                    if (line.startsWith("H: Handlers=")) {
                        if (line.contains("kbd")) {
                            isKbdDevice = true
                            handlerRegex.findAll(line).forEach { m ->
                                keyboardEventNames.add(m.value)
                            }
                        }
                    } else if (line.isBlank()) {
                        isKbdDevice = false
                    }
                }
            } catch (e: Exception) {
                logger.debug("Could not parse /proc/bus/input/devices: {}", e.message)
            }
        }

        val allEventFiles = devicesDir.listFiles { f -> f.name.startsWith("event") } ?: return emptyList()
        val readableFiles = allEventFiles.filter { it.canRead() }

        val prioritized =
            if (keyboardEventNames.isNotEmpty()) {
                readableFiles.filter { keyboardEventNames.contains(it.name) }
            } else {
                emptyList()
            }

        return if (prioritized.isNotEmpty()) prioritized else readableFiles
    }

    override fun unregister() {
        if (isHookActive.getAndSet(false)) {
            listenerThread?.interrupt()
            try {
                listenerThread?.join(300L)
            } catch (_: InterruptedException) {
            }
            listenerThread = null

            synchronized(openFds) {
                libc?.let { clib ->
                    for (fd in openFds) {
                        try {
                            clib.close(fd)
                        } catch (_: Throwable) {
                        }
                    }
                }
                openFds.clear()
            }
            isKeyCurrentlyDown.set(false)
            logger.info("Unregistered Evdev global hotkey hook")
        }
    }
}

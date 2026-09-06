package su.kamil.dev.golos.system.input

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.system.keyboard.EvdevKeycodes
import su.kamil.dev.golos.system.linux.LinuxLibC
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linux virtual keyboard device via /dev/uinput for injecting keystrokes (such as Ctrl+V)
 * across Wayland compositors and native Wayland applications (Browser, Telegram, etc.).
 */
@Suppress("TooGenericExceptionCaught", "MagicNumber", "ReturnCount", "LongMethod")
class UinputKeyboardInjector(
    private val devPath: String = "/dev/uinput",
    private val libc: LinuxLibC? = LinuxLibC.INSTANCE,
) {
    companion object {
        private const val UI_SET_EVBIT: Long = 0x40045564L
        private const val UI_SET_KEYBIT: Long = 0x40045565L
        private const val UI_DEV_CREATE: Long = 0x5501L
        private const val UI_DEV_DESTROY: Long = 0x5502L
        private const val UI_DEV_SETUP: Long = 0x405c5503L
        private const val BUS_USB: Short = 0x03
        private const val VENDOR_ID: Short = 0x1234
        private const val PRODUCT_ID: Short = 0x5678
        private const val EVENT_PACKET_SIZE = 24
        private const val UINPUT_SETUP_SIZE = 92
        private const val UINPUT_USER_DEV_SIZE = 1120
    }

    private val logger = LoggerFactory.getLogger(UinputKeyboardInjector::class.java)
    private var uinputFd: Int = -1

    fun isAvailable(): Boolean = libc != null && File(devPath).exists() && File(devPath).canWrite()

    @Synchronized
    fun initialize(): Boolean {
        if (uinputFd >= 0) return true
        val clib = libc ?: return false
        if (!isAvailable()) return false

        try {
            val fd = clib.open(devPath, LinuxLibC.O_WRONLY or LinuxLibC.O_NONBLOCK)
            if (fd < 0) {
                logger.warn("Could not open /dev/uinput for writing: fd={}", fd)
                return false
            }

            clib.ioctl(fd, UI_SET_EVBIT, EvdevKeycodes.EV_KEY.toLong())
            clib.ioctl(fd, UI_SET_EVBIT, EvdevKeycodes.EV_SYN.toLong())

            val keyCodesToEnable =
                listOf(
                    EvdevKeycodes.KEY_LEFTCTRL,
                    EvdevKeycodes.KEY_RIGHTCTRL,
                    EvdevKeycodes.KEY_LEFTSHIFT,
                    EvdevKeycodes.KEY_RIGHTSHIFT,
                    EvdevKeycodes.KEY_LEFTALT,
                    EvdevKeycodes.KEY_V,
                    EvdevKeycodes.KEY_INSERT,
                    EvdevKeycodes.KEY_ENTER,
                    EvdevKeycodes.KEY_SPACE,
                )
            for (code in keyCodesToEnable) {
                clib.ioctl(fd, UI_SET_KEYBIT, code.toLong())
            }

            // Configure device identity
            val setupBuf = ByteBuffer.allocate(UINPUT_SETUP_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            setupBuf.putShort(BUS_USB)
            setupBuf.putShort(VENDOR_ID)
            setupBuf.putShort(PRODUCT_ID)
            setupBuf.putShort(1) // version
            val nameBytes = "GolosAI Virtual Keyboard".toByteArray()
            setupBuf.put(nameBytes)
            setupBuf.position(88)
            setupBuf.putInt(0) // ff_effects_max

            val setupResult = clib.ioctl(fd, UI_DEV_SETUP, setupBuf.array())
            if (setupResult != 0) {
                // Fallback: write legacy struct uinput_user_dev
                val legacyBuf = ByteBuffer.allocate(UINPUT_USER_DEV_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                legacyBuf.put(nameBytes)
                legacyBuf.position(80)
                legacyBuf.putShort(BUS_USB)
                legacyBuf.putShort(VENDOR_ID)
                legacyBuf.putShort(PRODUCT_ID)
                legacyBuf.putShort(1)
                clib.write(fd, legacyBuf.array(), UINPUT_USER_DEV_SIZE)
            }

            val createResult = clib.ioctl(fd, UI_DEV_CREATE)
            if (createResult == 0) {
                uinputFd = fd
                logger.info("Successfully initialized /dev/uinput virtual keyboard for Wayland text injection.")
                return true
            } else {
                logger.warn("UI_DEV_CREATE failed on /dev/uinput: {}", createResult)
                clib.close(fd)
                return false
            }
        } catch (e: Exception) {
            logger.warn("Failed to initialize /dev/uinput virtual keyboard: {}", e.message)
            return false
        }
    }

    @Synchronized
    fun sendPasteKeystroke(): Boolean {
        if (uinputFd < 0 && !initialize()) {
            return false
        }
        val clib = libc ?: return false

        return try {
            writeKeyEvent(clib, EvdevKeycodes.KEY_LEFTCTRL, 1)
            writeSynEvent(clib)
            writeKeyEvent(clib, EvdevKeycodes.KEY_V, 1)
            writeSynEvent(clib)
            writeKeyEvent(clib, EvdevKeycodes.KEY_V, 0)
            writeSynEvent(clib)
            writeKeyEvent(clib, EvdevKeycodes.KEY_LEFTCTRL, 0)
            writeSynEvent(clib)
            logger.debug("Dispatched Ctrl+V paste keystroke via /dev/uinput")
            true
        } catch (e: Exception) {
            logger.warn("Error sending paste keystroke via /dev/uinput: {}", e.message)
            false
        }
    }

    private fun writeKeyEvent(
        clib: LinuxLibC,
        code: Int,
        value: Int,
    ) {
        val buf = ByteArray(EVENT_PACKET_SIZE)
        buf[16] = (EvdevKeycodes.EV_KEY and 0xFF).toByte()
        buf[17] = ((EvdevKeycodes.EV_KEY ushr 8) and 0xFF).toByte()
        buf[18] = (code and 0xFF).toByte()
        buf[19] = ((code ushr 8) and 0xFF).toByte()
        buf[20] = (value and 0xFF).toByte()
        buf[21] = ((value ushr 8) and 0xFF).toByte()
        buf[22] = ((value ushr 16) and 0xFF).toByte()
        buf[23] = ((value ushr 24) and 0xFF).toByte()
        clib.write(uinputFd, buf, EVENT_PACKET_SIZE)
    }

    private fun writeSynEvent(clib: LinuxLibC) {
        val buf = ByteArray(EVENT_PACKET_SIZE)
        buf[16] = (EvdevKeycodes.EV_SYN and 0xFF).toByte()
        buf[17] = ((EvdevKeycodes.EV_SYN ushr 8) and 0xFF).toByte()
        buf[18] = (EvdevKeycodes.SYN_REPORT and 0xFF).toByte()
        buf[19] = ((EvdevKeycodes.SYN_REPORT ushr 8) and 0xFF).toByte()
        clib.write(uinputFd, buf, EVENT_PACKET_SIZE)
    }

    @Synchronized
    fun close() {
        if (uinputFd >= 0) {
            try {
                libc?.ioctl(uinputFd, UI_DEV_DESTROY)
                libc?.close(uinputFd)
            } catch (_: Throwable) {
            }
            uinputFd = -1
            logger.info("Closed /dev/uinput virtual keyboard.")
        }
    }
}

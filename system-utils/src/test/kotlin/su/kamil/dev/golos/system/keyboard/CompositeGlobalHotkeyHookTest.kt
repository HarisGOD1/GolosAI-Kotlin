package su.kamil.dev.golos.system.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.system.linux.LinuxLibC
import java.io.File
import java.nio.file.Files

class CompositeGlobalHotkeyHookTest {
    private class TestLibC : LinuxLibC {
        var openReturnValue = 10
        val queue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

        override fun open(
            pathname: String,
            flags: Int,
        ): Int = openReturnValue

        override fun close(fd: Int): Int = 0

        override fun read(
            fd: Int,
            buf: ByteArray,
            count: Int,
        ): Int {
            val next = queue.poll() ?: return -1
            System.arraycopy(next, 0, buf, 0, minOf(next.size, count))
            return next.size
        }

        override fun write(
            fd: Int,
            buf: ByteArray,
            count: Int,
        ): Int = count

        override fun ioctl(
            fd: Int,
            request: Long,
            arg: Long,
        ): Int = 0

        override fun ioctl(
            fd: Int,
            request: Long,
            arg: ByteArray,
        ): Int = 0

        override fun ioctl(
            fd: Int,
            request: Long,
        ): Int = 0

        fun enqueueEvent(
            type: Int,
            code: Int,
            value: Int,
        ) {
            val buf = ByteArray(24)
            buf[16] = (type and 0xFF).toByte()
            buf[17] = ((type ushr 8) and 0xFF).toByte()
            buf[18] = (code and 0xFF).toByte()
            buf[19] = ((code ushr 8) and 0xFF).toByte()
            buf[20] = (value and 0xFF).toByte()
            buf[21] = ((value ushr 8) and 0xFF).toByte()
            buf[22] = ((value ushr 16) and 0xFF).toByte()
            buf[23] = ((value ushr 24) and 0xFF).toByte()
            queue.add(buf)
        }
    }

    @Test
    fun `test composite hotkey hook triggers evdev events on Wayland`() {
        val tempDir = Files.createTempDirectory("comp_evdev_").toFile()
        try {
            File(tempDir, "event0").apply { writeBytes(ByteArray(1)) }
            val fakeLibc = TestLibC()

            val evdevHook =
                EvdevHotkeyManager(
                    devicesDir = tempDir,
                    procDevicesFile = File(tempDir, "none"),
                    libc = fakeLibc,
                )
            val composite =
                CompositeGlobalHotkeyHook(
                    evdevHook = evdevHook,
                    x11Hook = GlobalHotkeyManager(),
                )

            var downCount = 0
            var upCount = 0

            val result =
                composite.register(
                    config = HotkeyConfig(keyName = "F8"),
                    onKeyDown = { downCount++ },
                    onKeyUp = { upCount++ },
                )

            assertTrue(result.isSuccess)
            assertTrue(composite.isRegistered)

            // Emit evdev press
            fakeLibc.enqueueEvent(EvdevKeycodes.EV_KEY, EvdevKeycodes.KEY_F8, 1)
            Thread.sleep(80)
            assertEquals(1, downCount)
            assertEquals(0, upCount)

            // Emit evdev release
            fakeLibc.enqueueEvent(EvdevKeycodes.EV_KEY, EvdevKeycodes.KEY_F8, 0)
            Thread.sleep(80)
            assertEquals(1, downCount)
            assertEquals(1, upCount)

            composite.unregister()
            assertFalse(composite.isRegistered)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

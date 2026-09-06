package su.kamil.dev.golos.system.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.system.linux.LinuxLibC
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

@EnabledOnOs(OS.LINUX)
class EvdevHotkeyManagerTest {
    private class FakeLinuxLibC : LinuxLibC {
        val eventQueue = ConcurrentLinkedQueue<ByteArray>()
        var openCalls = 0
        var closeCalls = 0

        override fun open(
            pathname: String,
            flags: Int,
        ): Int {
            openCalls++
            return 42
        }

        override fun close(fd: Int): Int {
            closeCalls++
            return 0
        }

        override fun read(
            fd: Int,
            buf: ByteArray,
            count: Int,
        ): Int {
            val next = eventQueue.poll() ?: return -1
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
            eventQueue.add(buf)
        }
    }

    @Test
    fun `test findKeyboardEventDevices prioritizes kbd handlers from proc input devices`() {
        val tempDir = Files.createTempDirectory("evdev_proc_").toFile()
        try {
            File(tempDir, "event0").writeBytes(ByteArray(1))
            File(tempDir, "event1").writeBytes(ByteArray(1))
            File(tempDir, "event2").writeBytes(ByteArray(1))

            val procFile = File(tempDir, "devices")
            procFile.writeText(
                """
                I: Bus=0011 Vendor=0001 Product=0001
                N: Name="AT Translated Set 2 keyboard"
                H: Handlers=sysrq kbd event1

                I: Bus=0011 Vendor=0002 Product=0013
                N: Name="VMware Mouse"
                H: Handlers=mouse0 event2
                """.trimIndent(),
            )

            val manager =
                EvdevHotkeyManager(
                    devicesDir = tempDir,
                    procDevicesFile = procFile,
                    libc = FakeLinuxLibC(),
                )

            val kbdDevices = manager.findKeyboardEventDevices()
            assertEquals(1, kbdDevices.size)
            assertEquals("event1", kbdDevices[0].name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test keypress auto-repeat and keyup handling`() {
        val tempDir = Files.createTempDirectory("evdev_hook_").toFile()
        try {
            File(tempDir, "event1").apply { writeBytes(ByteArray(1)) }
            val fakeLibc = FakeLinuxLibC()

            val manager =
                EvdevHotkeyManager(
                    devicesDir = tempDir,
                    procDevicesFile = File(tempDir, "non_existent"),
                    libc = fakeLibc,
                )

            var downCount = 0
            var upCount = 0

            val result =
                manager.register(
                    config = HotkeyConfig(keyName = "F8"),
                    onKeyDown = { downCount++ },
                    onKeyUp = { upCount++ },
                )

            assertTrue(result.isSuccess)
            assertTrue(manager.isRegistered)

            // 1. Enqueue Key Press (value 1)
            fakeLibc.enqueueEvent(EvdevKeycodes.EV_KEY, EvdevKeycodes.KEY_F8, 1)
            Thread.sleep(80)
            assertEquals(1, downCount)
            assertEquals(0, upCount)

            // 2. Enqueue Auto-Repeat (value 2) - should NOT trigger release or duplicate press
            fakeLibc.enqueueEvent(EvdevKeycodes.EV_KEY, EvdevKeycodes.KEY_F8, 2)
            Thread.sleep(80)
            assertEquals(1, downCount)
            assertEquals(0, upCount)

            // 3. Enqueue Key Release (value 0)
            fakeLibc.enqueueEvent(EvdevKeycodes.EV_KEY, EvdevKeycodes.KEY_F8, 0)
            Thread.sleep(80)
            assertEquals(1, downCount)
            assertEquals(1, upCount)

            manager.unregister()
            assertFalse(manager.isRegistered)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

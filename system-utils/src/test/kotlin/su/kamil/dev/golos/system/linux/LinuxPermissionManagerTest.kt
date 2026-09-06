package su.kamil.dev.golos.system.linux

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class LinuxPermissionManagerTest {
    @Test
    fun `test hasInputPermissions returns true when readable event files exist`() {
        val tempDir = Files.createTempDirectory("evdev_test_").toFile()
        try {
            val event0 = File(tempDir, "event0")
            event0.writeBytes(ByteArray(24))

            LinuxPermissionManager.devInputPath = tempDir.absolutePath
            if (LinuxPermissionManager.isLinux()) {
                assertTrue(LinuxPermissionManager.hasInputPermissions())
            }
        } finally {
            tempDir.deleteRecursively()
            LinuxPermissionManager.devInputPath = "/dev/input"
        }
    }

    @Test
    fun `test hasInputPermissions returns false when no event files exist`() {
        val tempDir = Files.createTempDirectory("evdev_empty_").toFile()
        try {
            LinuxPermissionManager.devInputPath = tempDir.absolutePath
            if (LinuxPermissionManager.isLinux()) {
                assertFalse(LinuxPermissionManager.hasInputPermissions())
            }
        } finally {
            tempDir.deleteRecursively()
            LinuxPermissionManager.devInputPath = "/dev/input"
        }
    }

    @Test
    fun `test manual command includes usermod and setfacl`() {
        val cmd = LinuxPermissionManager.getManualCommand()
        assertTrue(cmd.contains("usermod -aG input"))
        assertTrue(cmd.contains("setfacl -m u:"))
        assertTrue(cmd.contains("/dev/input/event*"))
    }
}

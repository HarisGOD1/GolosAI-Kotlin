package su.kamil.dev.golos.system.linux

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages Linux permission checks and elevation for /dev/input/event* (Evdev global hotkeys)
 * and /dev/uinput (Wayland virtual keyboard keystroke injection).
 */
@Suppress("TooGenericExceptionCaught", "MagicNumber", "ReturnCount")
object LinuxPermissionManager {
    private val logger = LoggerFactory.getLogger(LinuxPermissionManager::class.java)
    private const val PROCESS_TIMEOUT_SECONDS = 30L

    var devInputPath: String = "/dev/input"
    var devUinputPath: String = "/dev/uinput"

    fun isLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")

    fun isContainer(): Boolean = File("/run/.containerenv").exists() || File("/.dockerenv").exists()

    fun hasInputPermissions(): Boolean {
        if (!isLinux()) return true
        val devInput = File(devInputPath)
        if (!devInput.exists() || !devInput.isDirectory) return false
        val eventFiles = devInput.listFiles { f -> f.name.startsWith("event") } ?: return false
        return eventFiles.isNotEmpty() && eventFiles.any { it.canRead() }
    }

    fun hasUinputPermissions(): Boolean {
        if (!isLinux()) return true
        val uinput = File(devUinputPath)
        return uinput.exists() && uinput.canWrite()
    }

    fun needsPermissions(): Boolean = isLinux() && !hasInputPermissions()

    fun getManualCommand(): String {
        val user = System.getProperty("user.name") ?: "core"
        return "sudo usermod -aG input $user && sudo setfacl -m u:$user:rw /dev/input/event* /dev/uinput"
    }

    fun requestPermissionsAsync(onComplete: (Boolean) -> Unit = {}) {
        Thread({
            val granted = requestPermissionsSync()
            onComplete(granted)
        }, "Golos-PermissionRequester").apply {
            isDaemon = true
            start()
        }
    }

    fun requestPermissionsSync(): Boolean {
        if (!needsPermissions()) {
            logger.info("Linux input permissions are already satisfied.")
            return true
        }

        val user = System.getProperty("user.name") ?: "core"
        logger.info("Requesting Linux input permissions for user '{}' via pkexec...", user)

        val script =
            """
                        setfacl -m u:$user:rw /dev/input/event* /dev/uinput 2>/dev/null || true
                        usermod -aG input "$user" 2>/dev/null || true
                        cat << 'EOF' > /etc/udev/rules.d/99-golos-input.rules
            KERNEL=="event*", SUBSYSTEM=="input", MODE="0660", GROUP="input", TAG+="uaccess"
            KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", TAG+="uaccess"
            EOF
                        udevadm control --reload-rules 2>/dev/null || true
                        udevadm trigger 2>/dev/null || true
            """.trimIndent()

        val cmd = mutableListOf<String>()
        if (isContainer()) {
            cmd.addAll(listOf("flatpak-spawn", "--host", "pkexec", "sh", "-c", script))
        } else {
            cmd.addAll(listOf("pkexec", "sh", "-c", script))
        }

        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val success = finished && proc.exitValue() == 0 && hasInputPermissions()
            if (success) {
                logger.info("Linux input permissions successfully granted!")
            } else {
                logger.warn(
                    "Permission elevation process completed with exit code: {}",
                    if (finished) proc.exitValue() else "TIMEOUT",
                )
            }
            success
        } catch (e: Exception) {
            logger.warn("Could not request permissions via pkexec: {}", e.message)
            false
        }
    }
}

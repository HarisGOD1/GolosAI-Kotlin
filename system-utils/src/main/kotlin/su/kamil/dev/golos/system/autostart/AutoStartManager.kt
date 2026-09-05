package su.kamil.dev.golos.system.autostart

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Manages system auto-run on startup across Linux and Windows.
 */
class AutoStartManager {
    private val logger = LoggerFactory.getLogger(AutoStartManager::class.java)

    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    private val linuxDesktopFile: File
        get() = File(System.getProperty("user.home"), ".config/autostart/golos-ai.desktop")

    private val windowsStartupFile: File
        get() {
            val appData = System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming")
            return File(appData, "Microsoft\\Windows\\Start Menu\\Programs\\Startup\\golos-ai.bat")
        }

    fun isAutoStartEnabled(): Boolean {
        return when {
            isLinux -> linuxDesktopFile.exists()
            isWindows -> windowsStartupFile.exists()
            else -> false
        }
    }

    fun setAutoStart(enabled: Boolean): Result<Unit> =
        runCatching {
            if (isLinux) {
                val desktopFile = linuxDesktopFile
                if (enabled) {
                    desktopFile.parentFile.mkdirs()
                    val javaBin = System.getProperty("java.home") + "/bin/java"
                    val appJar = resolveAppLauncher()

                    val content =
                        """
                        [Desktop Entry]
                        Type=Application
                        Name=GolosAI
                        Comment=Local Speech-to-Text Dictation Assistant
                        Exec=$javaBin -jar $appJar
                        Terminal=false
                        Categories=Utility;Audio;
                        X-GNOME-Autostart-enabled=true
                        """.trimIndent()

                    desktopFile.writeText(content)
                    logger.info("Enabled Linux autostart desktop file at: {}", desktopFile.absolutePath)
                } else {
                    if (desktopFile.exists()) {
                        desktopFile.delete()
                        logger.info("Disabled Linux autostart by removing: {}", desktopFile.absolutePath)
                    }
                }
            } else if (isWindows) {
                val startupFile = windowsStartupFile
                if (enabled) {
                    startupFile.parentFile.mkdirs()
                    val javaBin = System.getProperty("java.home") + "\\bin\\javaw.exe"
                    val appJar = resolveAppLauncher()
                    startupFile.writeText("start \"\" \"$javaBin\" -jar \"$appJar\"\n")
                    logger.info("Enabled Windows autostart script at: {}", startupFile.absolutePath)
                } else {
                    if (startupFile.exists()) {
                        startupFile.delete()
                        logger.info("Disabled Windows autostart by removing: {}", startupFile.absolutePath)
                    }
                }
            }
        }

    private fun resolveAppLauncher(): String {
        return try {
            val codeSource = this::class.java.protectionDomain.codeSource
            val jarFile = File(codeSource?.location?.toURI() ?: File("golos-ai.jar").toURI())
            jarFile.absolutePath
        } catch (_: Exception) {
            "golos-ai.jar"
        }
    }
}

package su.kamil.dev.golos.system.window

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.ptr.PointerByReference
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.ActiveWindowInfo
import su.kamil.dev.golos.core.model.ApplicationProfile
import su.kamil.dev.golos.core.ports.ActiveWindowDetectorPort
import su.kamil.dev.golos.system.x11.X11Lib
import java.util.concurrent.TimeUnit

/**
 * Detects the active desktop foreground window and maps it to an application profile (Criteria J-01..J-05, M-02).
 */
@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")
class ActiveWindowDetector(
    private val x11: X11Lib? = X11Lib.INSTANCE,
) : ActiveWindowDetectorPort {
    private val logger = LoggerFactory.getLogger(ActiveWindowDetector::class.java)

    /** Optional simulated window context for automated testing or headless CI. */
    var simulatedWindow: ActiveWindowInfo? = null

    override fun detectActiveWindow(): ActiveWindowInfo {
        simulatedWindow?.let { return it }

        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("linux") -> detectLinux()
                os.contains("windows") -> detectWindows()
                os.contains("mac") -> detectMac()
                else -> ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
            }
        } catch (e: Exception) {
            logger.debug("Active window detection failed: {}", e.message)
            ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
        }
    }

    private fun detectLinux(): ActiveWindowInfo {
        if (x11 != null) {
            val windowInfo = queryX11ActiveWindow()
            if (windowInfo != null && windowInfo.appName.isNotEmpty()) {
                return windowInfo
            }
        }

        val cliInfo = queryLinuxCli()
        if (cliInfo != null && cliInfo.appName.isNotEmpty()) {
            return cliInfo
        }

        return ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
    }

    @Suppress("MagicNumber")
    private fun queryX11ActiveWindow(): ActiveWindowInfo? {
        val lib = x11 ?: return null
        val display = lib.XOpenDisplay(null) ?: return null
        try {
            val focusWinPtr = Memory(Native.LONG_SIZE.toLong())
            val revertToPtr = Memory(4)
            lib.XGetInputFocus(display, focusWinPtr, revertToPtr)
            val focusWin = if (Native.LONG_SIZE == 8) focusWinPtr.getLong(0) else focusWinPtr.getInt(0).toLong()

            if (focusWin <= 1L) {
                return null
            }

            var title = ""
            val nameRef = PointerByReference()
            if (lib.XFetchName(display, focusWin, nameRef) != 0 && nameRef.value != null) {
                val ptr = nameRef.value
                title = ptr.getString(0)
                lib.XFree(ptr)
            }

            val appName = deriveAppNameFromTitle(title)
            val profile = resolveProfile(appName, title)
            return ActiveWindowInfo(appName = appName, windowTitle = title, profile = profile)
        } catch (e: Exception) {
            logger.debug("Error querying X11 active window", e)
            return null
        } finally {
            lib.XCloseDisplay(display)
        }
    }

    @Suppress("MagicNumber")
    private fun queryLinuxCli(): ActiveWindowInfo? {
        try {
            val pb = ProcessBuilder("xdotool", "getactivewindow", "getwindowname")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            if (proc.waitFor(200, TimeUnit.MILLISECONDS) && proc.exitValue() == 0) {
                val title = proc.inputStream.bufferedReader().readText().trim()
                if (title.isNotEmpty()) {
                    val appName = deriveAppNameFromTitle(title)
                    return ActiveWindowInfo(
                        appName = appName,
                        windowTitle = title,
                        profile = resolveProfile(appName, title),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    @Suppress("MagicNumber")
    private fun detectWindows(): ActiveWindowInfo {
        return try {
            val user32 = com.sun.jna.platform.win32.User32.INSTANCE
            val hwnd =
                user32.GetForegroundWindow()
                    ?: return ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
            val chars = CharArray(512)
            val len = user32.GetWindowText(hwnd, chars, 512)
            val title = if (len > 0) String(chars, 0, len).trim() else "Desktop"
            val appName = deriveAppNameFromTitle(title)
            ActiveWindowInfo(appName = appName, windowTitle = title, profile = resolveProfile(appName, title))
        } catch (e: Exception) {
            logger.debug("Windows window detection error: {}", e.message)
            ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
        }
    }

    @Suppress("MagicNumber")
    private fun detectMac(): ActiveWindowInfo {
        return try {
            val script =
                "tell application \"System Events\" to " +
                    "get name of first application process whose frontmost is true"
            val pb = ProcessBuilder("osascript", "-e", script)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            if (proc.waitFor(500, TimeUnit.MILLISECONDS) && proc.exitValue() == 0) {
                val appName = proc.inputStream.bufferedReader().readText().trim()
                if (appName.isNotEmpty()) {
                    return ActiveWindowInfo(
                        appName = appName,
                        windowTitle = appName,
                        profile = resolveProfile(appName, appName),
                    )
                }
            }
            ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
        } catch (e: Exception) {
            logger.debug("Mac window detection error: {}", e.message)
            ActiveWindowInfo("Desktop", "Desktop", ApplicationProfile.GENERAL)
        }
    }

    fun deriveAppNameFromTitle(title: String): String {
        if (title.isBlank()) return "Desktop"
        val lower = title.lowercase()
        return when {
            lower.contains("telegram") -> "Telegram"
            lower.contains("slack") -> "Slack"
            lower.contains("discord") -> "Discord"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("element") -> "Element"
            lower.contains("signal") -> "Signal"
            lower.contains("visual studio code") || lower.contains("vs code") || lower.contains(" - code") -> "VS Code"
            lower.contains("intellij") || lower.contains("idea") -> "IntelliJ IDEA"
            lower.contains("android studio") -> "Android Studio"
            lower.contains("pycharm") -> "PyCharm"
            lower.contains("webstorm") -> "WebStorm"
            lower.contains("clion") -> "CLion"
            lower.contains("terminal") || lower.contains("bash") || lower.contains("zsh") ||
                lower.contains("konsole") -> "Terminal"
            lower.contains("thunderbird") -> "Thunderbird"
            lower.contains("outlook") -> "Outlook"
            lower.contains("mail") -> "Mail"
            lower.contains("firefox") -> "Firefox"
            lower.contains("chrome") -> "Google Chrome"
            lower.contains("chromium") -> "Chromium"
            lower.contains("brave") -> "Brave"
            lower.contains("edge") -> "Microsoft Edge"
            else -> {
                if (title.contains(Regex("\\s+[-—–]\\s+"))) {
                    title.split(Regex("\\s+[-—–]\\s+")).last().trim()
                } else {
                    title
                }
            }
        }
    }

    fun resolveProfile(
        appName: String,
        windowTitle: String,
    ): ApplicationProfile {
        val combined = "$appName $windowTitle".lowercase()
        return when {
            combined.contains("code") || combined.contains("intellij") || combined.contains("idea") ||
                combined.contains("studio") || combined.contains("pycharm") || combined.contains("webstorm") ||
                combined.contains("clion") || combined.contains("eclipse") || combined.contains("vim") ||
                combined.contains("nvim") || combined.contains("sublime") || combined.contains("terminal") ||
                combined.contains("bash") || combined.contains("zsh") || combined.contains("konsole") ||
                combined.contains("alacritty") || combined.contains("kitty") || combined.contains("wezterm") ->
                ApplicationProfile.CODE

            combined.contains("telegram") || combined.contains("slack") || combined.contains("discord") ||
                combined.contains("whatsapp") || combined.contains("element") || combined.contains("signal") ||
                combined.contains("viber") || combined.contains("skype") || combined.contains("mattermost") ->
                ApplicationProfile.MESSENGER

            combined.contains("thunderbird") || combined.contains("outlook") || combined.contains("mail") ||
                combined.contains("evolution") || combined.contains("kmail") || combined.contains("gmail") ->
                ApplicationProfile.MAIL

            else -> ApplicationProfile.GENERAL
        }
    }
}

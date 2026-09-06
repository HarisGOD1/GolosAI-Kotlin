package su.kamil.dev.golos.system.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.ActiveWindowInfo
import su.kamil.dev.golos.core.model.ApplicationProfile

class ActiveWindowDetectorTest {
    @Test
    fun `test app name derivation from window titles`() {
        val detector = ActiveWindowDetector(x11 = null)

        assertEquals("Telegram", detector.deriveAppNameFromTitle("Telegram (5)"))
        assertEquals("VS Code", detector.deriveAppNameFromTitle("Main.kt - GolosAI - Visual Studio Code"))
        assertEquals("IntelliJ IDEA", detector.deriveAppNameFromTitle("DictationOrchestrator.kt – IntelliJ IDEA"))
        assertEquals("Thunderbird", detector.deriveAppNameFromTitle("Inbox - user@example.com - Mozilla Thunderbird"))
        assertEquals("Terminal", detector.deriveAppNameFromTitle("core@fedora:~ (bash)"))
        assertEquals("Google Chrome", detector.deriveAppNameFromTitle("Google Chrome - New Tab"))
        assertEquals("LibreOffice Writer", detector.deriveAppNameFromTitle("Document1 - LibreOffice Writer"))
    }

    @Test
    fun `test profile mapping for messenger applications - Criterion J-01`() {
        val detector = ActiveWindowDetector(x11 = null)

        assertEquals(ApplicationProfile.MESSENGER, detector.resolveProfile("Telegram", "Telegram (1)"))
        assertEquals(ApplicationProfile.MESSENGER, detector.resolveProfile("Slack", "#general - Golos Team"))
        assertEquals(ApplicationProfile.MESSENGER, detector.resolveProfile("Discord", "Golos Dev Server"))
        assertEquals(ApplicationProfile.MESSENGER, detector.resolveProfile("WhatsApp", "WhatsApp Web"))
        assertEquals(ApplicationProfile.MESSENGER, detector.resolveProfile("Signal", "Signal"))
    }

    @Test
    fun `test profile mapping for mail clients - Criterion J-02`() {
        val detector = ActiveWindowDetector(x11 = null)

        assertEquals(ApplicationProfile.MAIL, detector.resolveProfile("Thunderbird", "Mozilla Thunderbird"))
        assertEquals(ApplicationProfile.MAIL, detector.resolveProfile("Outlook", "Inbox - Microsoft Outlook"))
        assertEquals(ApplicationProfile.MAIL, detector.resolveProfile("Mail", "KMail - Inbox"))
    }

    @Test
    fun `test profile mapping for developer tools and code editors - Criterion J-03`() {
        val detector = ActiveWindowDetector(x11 = null)

        assertEquals(ApplicationProfile.CODE, detector.resolveProfile("VS Code", "SpeechNormalizationTest.kt"))
        assertEquals(ApplicationProfile.CODE, detector.resolveProfile("IntelliJ IDEA", "Project View"))
        assertEquals(ApplicationProfile.CODE, detector.resolveProfile("Terminal", "bash - /var/home/core"))
        assertEquals(ApplicationProfile.CODE, detector.resolveProfile("PyCharm", "main.py"))
    }

    @Test
    fun `test profile mapping for general applications`() {
        val detector = ActiveWindowDetector(x11 = null)

        assertEquals(ApplicationProfile.GENERAL, detector.resolveProfile("Google Chrome", "Wikipedia"))
        assertEquals(ApplicationProfile.GENERAL, detector.resolveProfile("LibreOffice Writer", "Document 1"))
        assertEquals(ApplicationProfile.GENERAL, detector.resolveProfile("Desktop", "Desktop"))
    }

    @Test
    fun `test simulated active window override - Criterion J-05`() {
        val detector = ActiveWindowDetector(x11 = null)
        val simulated = ActiveWindowInfo("CustomApp", "CustomTitle", ApplicationProfile.CODE)
        detector.simulatedWindow = simulated

        val detected = detector.detectActiveWindow()
        assertEquals("CustomApp", detected.appName)
        assertEquals("CustomTitle", detected.windowTitle)
        assertEquals(ApplicationProfile.CODE, detected.profile)
    }

    @Test
    fun `test fallback detection returns valid object without exception`() {
        val detector = ActiveWindowDetector(x11 = null)
        val detected = detector.detectActiveWindow()
        assertNotNull(detected)
        assertNotNull(detected.appName)
        assertNotNull(detected.profile)
    }
}

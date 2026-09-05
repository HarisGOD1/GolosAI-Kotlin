package su.kamil.dev.golos.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.app.ui.AppLanguage
import su.kamil.dev.golos.app.ui.AppLocalization
import su.kamil.dev.golos.app.ui.BulbWidget
import su.kamil.dev.golos.app.ui.FontManager
import java.awt.Color

class UiAndLocalizationTest {

    @Test
    fun `FontManager initializes Hack font with correct default sizes`() {
        assertNotNull(FontManager.regularFont)
        assertNotNull(FontManager.boldFont)

        val reg16 = FontManager.regular(16f)
        val bld18 = FontManager.bold(18f)

        assertEquals(16f, reg16.size2D)
        assertEquals(18f, bld18.size2D)
        assertTrue(bld18.isBold)
    }

    @Test
    fun `AppLocalization translates keys across all supported interface languages`() {
        val testLanguages = listOf(
            AppLanguage.EN,
            AppLanguage.FR,
            AppLanguage.DE,
            AppLanguage.RU,
            AppLanguage.JP,
            AppLanguage.CN,
            AppLanguage.TR,
            AppLanguage.AR,
            AppLanguage.ES,
            AppLanguage.IT,
        )

        for (lang in testLanguages) {
            AppLocalization.setLanguage(lang)
            assertEquals(lang, AppLocalization.currentLanguage)

            val dashboardTitle = AppLocalization.tr("tab.dashboard")
            assertTrue(dashboardTitle.isNotBlank())

            val appTitle = AppLocalization.tr("bulb.app.title")
            assertTrue(appTitle.isNotBlank())

            val voiceListening = AppLocalization.tr("bulb.voice.listening")
            assertTrue(voiceListening.isNotBlank())
        }

        // Test fallback to English for unknown key
        val unknown = AppLocalization.tr("non.existent.key.xyz")
        assertEquals("non.existent.key.xyz", unknown)

        // Reset to English
        AppLocalization.setLanguage(AppLanguage.EN)
    }

    @Test
    fun `BulbWidget updates color and status correctly`() {
        val bulb = BulbWidget(
            bulbColor = Color.GREEN,
            glowColor = Color(0, 255, 0, 100),
            title = "TEST",
            statusText = "READY",
            compact = true,
        )

        assertEquals("TEST", bulb.title)
        assertEquals("READY", bulb.statusText)

        bulb.updateState(
            color = Color.RED,
            glow = Color(255, 0, 0, 100),
            titleText = "VOICE",
            stateText = "PROCESSING",
        )

        assertEquals(Color.RED, bulb.bulbColor)
        assertEquals("VOICE", bulb.title)
        assertEquals("PROCESSING", bulb.statusText)
        assertEquals("VOICE: PROCESSING", bulb.toolTipText)
    }
}

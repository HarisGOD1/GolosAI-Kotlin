package su.kamil.dev.golos.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.app.config.SettingsManager
import su.kamil.dev.golos.app.history.HistoryManager
import su.kamil.dev.golos.core.model.GolosConfig
import su.kamil.dev.golos.core.model.HotkeySettings
import su.kamil.dev.golos.core.model.InsertionSettings
import java.io.File

class SettingsAndHistoryTest {
    private lateinit var tempDir: File
    private lateinit var tempConfigFile: File
    private lateinit var tempHistoryFile: File

    @BeforeEach
    fun setUp() {
        tempDir =
            File.createTempFile("golos_test_", "").apply {
                delete()
                mkdirs()
            }
        tempConfigFile = File(tempDir, "config.yaml")
        tempHistoryFile = File(tempDir, "history.jsonl")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `SettingsManager initializes with defaults when file missing`() {
        val manager = SettingsManager(tempConfigFile)
        val config = manager.load()

        assertEquals("1.0", config.version)
        assertEquals("F8", config.hotkey.keyName)
        assertEquals("DIRECT_TYPING", config.insertion.mode)
        assertFalse(config.insertion.copyToClipboard)
        assertTrue(tempConfigFile.exists())
    }

    @Test
    fun `SettingsManager saves and reloads customized YAML configuration`() {
        val manager = SettingsManager(tempConfigFile)
        val customConfig =
            GolosConfig(
                version = "1.0",
                hotkey = HotkeySettings(keyName = "L", ctrl = true, shift = true),
                insertion = InsertionSettings(mode = "CLIPBOARD_PASTE", copyToClipboard = true),
                autostart = su.kamil.dev.golos.core.model.AutostartSettings(enabled = true),
            )

        manager.save(customConfig)

        val reloaded = manager.load()
        assertEquals("L", reloaded.hotkey.keyName)
        assertTrue(reloaded.hotkey.ctrl)
        assertTrue(reloaded.hotkey.shift)
        assertFalse(reloaded.hotkey.alt)
        assertEquals("CLIPBOARD_PASTE", reloaded.insertion.mode)
        assertEquals("ON_KEY_RELEASE", reloaded.insertion.timing)
        assertTrue(reloaded.insertion.copyToClipboard)
        assertTrue(reloaded.autostart.enabled)

        val yamlContent = tempConfigFile.readText()
        assertTrue(yamlContent.contains("keyName: L") || yamlContent.contains("keyName: \"L\""))
        assertTrue(yamlContent.contains("CLIPBOARD_PASTE"))
    }

    @Test
    fun `SettingsManager preserves on the fly timing configuration`() {
        val manager = SettingsManager(tempConfigFile)
        val config =
            GolosConfig(
                insertion =
                    InsertionSettings(
                        mode = "DIRECT_TYPING",
                        timing = "ON_THE_FLY",
                    ),
            )
        manager.save(config)

        val reloaded = manager.load()
        assertEquals("ON_THE_FLY", reloaded.insertion.timing)
        assertEquals(su.kamil.dev.golos.core.model.InjectionTiming.ON_THE_FLY, reloaded.insertion.toInjectionConfig().timing)
    }

    @Test
    fun `SettingsManager export and import restores configuration`() {
        val manager = SettingsManager(tempConfigFile)
        val exportFile = File(tempDir, "exported.yaml")

        val initialConfig =
            GolosConfig(
                hotkey = HotkeySettings(keyName = "F12", alt = true),
            )
        manager.save(initialConfig)
        manager.exportConfig(exportFile)

        assertTrue(exportFile.exists())

        // Reset to defaults
        manager.resetToDefaults()
        assertEquals("F8", manager.load().hotkey.keyName)

        // Import back
        val imported = manager.importConfig(exportFile)
        assertEquals("F12", imported.hotkey.keyName)
        assertTrue(imported.hotkey.alt)
        assertEquals("F12", manager.load().hotkey.keyName)
    }

    @Test
    fun `SettingsManager persists bilingualMode and uiLanguage configuration`() {
        val manager = SettingsManager(tempConfigFile)
        val config =
            GolosConfig(
                uiLanguage = "fr",
                engine =
                    su.kamil.dev.golos.core.model.EngineSettings(
                        selectedId = "whisper-cpp",
                        whisper =
                            su.kamil.dev.golos.core.model.WhisperSettings(
                                language = "ru",
                                bilingualMode = true,
                            ),
                    ),
            )
        manager.save(config)

        val reloaded = manager.load()
        assertEquals("fr", reloaded.uiLanguage)
        assertEquals("whisper-cpp", reloaded.engine.selectedId)
        assertEquals("ru", reloaded.engine.whisper.language)
        assertTrue(reloaded.engine.whisper.bilingualMode)
    }

    @Test
    fun `HistoryManager records, persists and retrieves entries in order`() {
        val historyManager = HistoryManager(tempHistoryFile)

        val e1 = historyManager.addEntry("First transcription", 1200, "MockEngine", "en")
        val e2 = historyManager.addEntry("Second transcription with special \"quotes\" & symbols", 2500, "Whisper.cpp", "ru")

        val all = historyManager.getAll()
        assertEquals(2, all.size)
        // Newest entry first
        assertEquals(e2.id, all[0].id)
        assertEquals("Second transcription with special \"quotes\" & symbols", all[0].text)
        assertEquals(e1.id, all[1].id)

        // Re-read from disk
        val reloadedHistory = HistoryManager(tempHistoryFile)
        val reloadedAll = reloadedHistory.getAll()
        assertEquals(2, reloadedAll.size)
        assertEquals(e2.text, reloadedAll[0].text)
        assertEquals(e1.text, reloadedAll[1].text)
        assertEquals(2500, reloadedAll[0].durationMs)
    }

    @Test
    fun `HistoryManager clear wipes entries from memory and disk`() {
        val historyManager = HistoryManager(tempHistoryFile)
        historyManager.addEntry("Testing clear", 800, "Mock")
        assertEquals(1, historyManager.getAll().size)

        historyManager.clear()
        assertTrue(historyManager.getAll().isEmpty())

        val reloaded = HistoryManager(tempHistoryFile)
        assertTrue(reloaded.getAll().isEmpty())
    }

    @Test
    fun `HistoryManager persists appName and profile and filters by app - Criteria M-02 and M-05`() {
        val historyManager = HistoryManager(tempHistoryFile)
        historyManager.addEntry(
            text = "Telegram message",
            durationMs = 500,
            engine = "Whisper",
            language = "ru",
            appName = "Telegram",
            profile = "MESSENGER",
        )
        historyManager.addEntry(
            text = "Code commit",
            durationMs = 900,
            engine = "Whisper",
            language = "en",
            appName = "IntelliJ IDEA",
            profile = "CODE",
        )
        historyManager.addEntry(
            text = "Another Telegram message",
            durationMs = 400,
            engine = "Whisper",
            language = "ru",
            appName = "Telegram",
            profile = "MESSENGER",
        )

        val all = historyManager.getAll()
        assertEquals(3, all.size)
        assertEquals("Telegram", all[0].appName)
        assertEquals("MESSENGER", all[0].profile)

        val uniqueApps = historyManager.getUniqueAppNames()
        assertEquals(listOf("IntelliJ IDEA", "Telegram"), uniqueApps)

        val telegramFiltered = historyManager.getFiltered(appName = "Telegram")
        assertEquals(2, telegramFiltered.size)
        assertTrue(telegramFiltered.all { it.appName == "Telegram" })

        val codeFiltered = historyManager.getFiltered(appName = "IntelliJ IDEA")
        assertEquals(1, codeFiltered.size)
        assertEquals("Code commit", codeFiltered[0].text)

        // Persistence test
        val reloaded = HistoryManager(tempHistoryFile)
        val reloadedAll = reloaded.getAll()
        assertEquals(3, reloadedAll.size)
        assertEquals("Telegram", reloadedAll[0].appName)
        assertEquals("MESSENGER", reloadedAll[0].profile)
        assertEquals("IntelliJ IDEA", reloadedAll[1].appName)
        assertEquals("CODE", reloadedAll[1].profile)
    }

    @Test
    fun `SettingsManager persists postProcessing activeAppProfile - Criterion J-05`() {
        val manager = SettingsManager(tempConfigFile)
        val config =
            GolosConfig(
                postProcessing =
                    su.kamil.dev.golos.core.model.PostProcessingSettings(
                        activeAppProfile = "MESSENGER",
                    ),
            )
        manager.save(config)

        val reloaded = manager.load()
        assertEquals(
            "MESSENGER",
            reloaded.postProcessing.activeAppProfile,
        )
    }
}

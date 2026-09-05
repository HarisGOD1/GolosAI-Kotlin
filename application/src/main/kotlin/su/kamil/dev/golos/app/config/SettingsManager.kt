package su.kamil.dev.golos.app.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import su.kamil.dev.golos.core.model.*
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Manages configuration persistence using a stable, unified YAML schema.
 */
class SettingsManager(
    val configFile: File = resolveDefaultConfigFile()
) {
    private val logger = LoggerFactory.getLogger(SettingsManager::class.java)

    companion object {
        fun resolveDefaultConfigFile(): File {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "GolosAI/config.yaml")
            } else {
                File(System.getProperty("user.home"), ".config/golos-ai/config.yaml")
            }
        }
    }

    private val yaml: Yaml by lazy {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        Yaml(options)
    }

    fun load(): GolosConfig {
        if (!configFile.exists()) {
            logger.info("Configuration file not found at {}. Initializing with defaults.", configFile.absolutePath)
            val defaultConfig = GolosConfig()
            save(defaultConfig)
            return defaultConfig
        }

        return try {
            FileReader(configFile).use { reader ->
                val data = yaml.load<Map<String, Any>>(reader) ?: emptyMap()
                parseConfig(data)
            }
        } catch (e: Exception) {
            logger.error("Error reading YAML configuration from {}: {}. Falling back to defaults.", configFile.absolutePath, e.message)
            GolosConfig()
        }
    }

    fun save(config: GolosConfig) {
        saveToFile(config, configFile)
    }

    fun resetToDefaults(): GolosConfig {
        val defaultConfig = GolosConfig()
        save(defaultConfig)
        logger.info("Reset configuration to factory defaults at: {}", configFile.absolutePath)
        return defaultConfig
    }

    fun exportConfig(targetFile: File) {
        val current = load()
        saveToFile(current, targetFile)
        logger.info("Exported configuration to: {}", targetFile.absolutePath)
    }

    fun importConfig(sourceFile: File): GolosConfig {
        if (!sourceFile.exists()) {
            throw IllegalArgumentException("Source settings file does not exist: ${sourceFile.absolutePath}")
        }
        val imported = FileReader(sourceFile).use { reader ->
            val data = yaml.load<Map<String, Any>>(reader) ?: emptyMap()
            parseConfig(data)
        }
        save(imported)
        logger.info("Successfully imported settings from: {}", sourceFile.absolutePath)
        return imported
    }

    private fun saveToFile(config: GolosConfig, file: File) {
        try {
            file.parentFile?.mkdirs()
            val map = toMap(config)
            FileWriter(file).use { writer ->
                yaml.dump(map, writer)
            }
            logger.info("Saved YAML configuration to: {}", file.absolutePath)
        } catch (e: Exception) {
            logger.error("Failed to write YAML configuration to {}: {}", file.absolutePath, e.message)
        }
    }

    private fun toMap(c: GolosConfig): Map<String, Any> {
        return linkedMapOf(
            "version" to c.version,
            "hotkey" to linkedMapOf(
                "keyName" to c.hotkey.keyName,
                "ctrl" to c.hotkey.ctrl,
                "shift" to c.hotkey.shift,
                "alt" to c.hotkey.alt,
                "meta" to c.hotkey.meta,
                "keyCode" to c.hotkey.keyCode
            ),
            "insertion" to linkedMapOf(
                "mode" to c.insertion.mode,
                "copyToClipboard" to c.insertion.copyToClipboard,
                "copyToClipboardIfNoField" to c.insertion.copyToClipboardIfNoField
            ),
            "audio" to linkedMapOf(
                "deviceName" to c.audio.deviceName,
                "provider" to c.audio.provider
            ),
            "engine" to linkedMapOf(
                "selectedId" to c.engine.selectedId,
                "whisper" to linkedMapOf(
                    "binaryPath" to c.engine.whisper.binaryPath,
                    "modelPath" to c.engine.whisper.modelPath,
                    "modelName" to c.engine.whisper.modelName,
                    "language" to c.engine.whisper.language,
                    "device" to c.engine.whisper.device,
                    "threads" to c.engine.whisper.threads
                )
            ),
            "autostart" to linkedMapOf(
                "enabled" to c.autostart.enabled
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(map: Map<String, Any>): GolosConfig {
        val version = map["version"]?.toString() ?: "1.0"

        val hkMap = map["hotkey"] as? Map<String, Any> ?: emptyMap()
        val hotkey = HotkeySettings(
            keyName = hkMap["keyName"]?.toString() ?: "F8",
            ctrl = hkMap["ctrl"] as? Boolean ?: false,
            shift = hkMap["shift"] as? Boolean ?: false,
            alt = hkMap["alt"] as? Boolean ?: false,
            meta = hkMap["meta"] as? Boolean ?: false,
            keyCode = (hkMap["keyCode"] as? Number)?.toInt() ?: 119
        )

        val insMap = map["insertion"] as? Map<String, Any> ?: emptyMap()
        val insertion = InsertionSettings(
            mode = insMap["mode"]?.toString() ?: "DIRECT_TYPING",
            copyToClipboard = insMap["copyToClipboard"] as? Boolean ?: false,
            copyToClipboardIfNoField = insMap["copyToClipboardIfNoField"] as? Boolean ?: true
        )

        val audMap = map["audio"] as? Map<String, Any> ?: emptyMap()
        val audio = AudioSettings(
            deviceName = audMap["deviceName"]?.toString() ?: "",
            provider = audMap["provider"]?.toString() ?: "JavaSound"
        )

        val engMap = map["engine"] as? Map<String, Any> ?: emptyMap()
        val whsMap = engMap["whisper"] as? Map<String, Any> ?: emptyMap()
        val whisper = WhisperSettings(
            binaryPath = whsMap["binaryPath"]?.toString() ?: "",
            modelPath = whsMap["modelPath"]?.toString() ?: "",
            modelName = whsMap["modelName"]?.toString() ?: "base",
            language = whsMap["language"]?.toString() ?: "auto",
            device = whsMap["device"]?.toString() ?: "CPU",
            threads = (whsMap["threads"] as? Number)?.toInt() ?: 4
        )
        val engine = EngineSettings(
            selectedId = engMap["selectedId"]?.toString() ?: "mock",
            whisper = whisper
        )

        val autoMap = map["autostart"] as? Map<String, Any> ?: emptyMap()
        val autostart = AutostartSettings(
            enabled = autoMap["enabled"] as? Boolean ?: false
        )

        return GolosConfig(
            version = version,
            hotkey = hotkey,
            insertion = insertion,
            audio = audio,
            engine = engine,
            autostart = autostart
        )
    }
}

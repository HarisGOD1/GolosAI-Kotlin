package su.kamil.dev.golos.app.config

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import su.kamil.dev.golos.core.model.AudioSettings
import su.kamil.dev.golos.core.model.AutostartSettings
import su.kamil.dev.golos.core.model.EngineSettings
import su.kamil.dev.golos.core.model.GolosConfig
import su.kamil.dev.golos.core.model.HotkeySettings
import su.kamil.dev.golos.core.model.InsertionSettings
import su.kamil.dev.golos.core.model.PostProcessingSettings
import su.kamil.dev.golos.core.model.SherpaSettings
import su.kamil.dev.golos.core.model.VoskSettings
import su.kamil.dev.golos.core.model.WhisperSettings
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Manages configuration persistence using a stable, unified YAML schema.
 */
class SettingsManager(
    val configFile: File = resolveDefaultConfigFile(),
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
        val options =
            DumperOptions().apply {
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
            logger.error(
                "Error reading YAML configuration from {}: {}. Falling back to defaults.",
                configFile.absolutePath,
                e.message,
            )
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
        val imported =
            FileReader(sourceFile).use { reader ->
                val data = yaml.load<Map<String, Any>>(reader) ?: emptyMap()
                parseConfig(data)
            }
        save(imported)
        logger.info("Successfully imported settings from: {}", sourceFile.absolutePath)
        return imported
    }

    private fun saveToFile(
        config: GolosConfig,
        file: File,
    ) {
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
            "uiLanguage" to c.uiLanguage,
            "hotkey" to
                linkedMapOf(
                    "keyName" to c.hotkey.keyName,
                    "ctrl" to c.hotkey.ctrl,
                    "shift" to c.hotkey.shift,
                    "alt" to c.hotkey.alt,
                    "meta" to c.hotkey.meta,
                    "keyCode" to c.hotkey.keyCode,
                ),
            "insertion" to
                linkedMapOf(
                    "mode" to c.insertion.mode,
                    "timing" to c.insertion.timing,
                    "copyToClipboard" to c.insertion.copyToClipboard,
                    "copyToClipboardIfNoField" to c.insertion.copyToClipboardIfNoField,
                ),
            "audio" to
                linkedMapOf(
                    "deviceName" to c.audio.deviceName,
                    "provider" to c.audio.provider,
                    "gain" to c.audio.gain,
                ),
            "engine" to
                linkedMapOf(
                    "selectedId" to c.engine.selectedId,
                    "whisper" to
                        linkedMapOf(
                            "binaryPath" to c.engine.whisper.binaryPath,
                            "modelPath" to c.engine.whisper.modelPath,
                            "modelName" to c.engine.whisper.modelName,
                            "language" to c.engine.whisper.language,
                            "device" to c.engine.whisper.device,
                            "threads" to c.engine.whisper.threads,
                            "bilingualMode" to c.engine.whisper.bilingualMode,
                        ),
                    "vosk" to
                        linkedMapOf(
                            "binaryPath" to c.engine.vosk.binaryPath,
                            "modelPath" to c.engine.vosk.modelPath,
                            "modelName" to c.engine.vosk.modelName,
                        ),
                    "sherpa" to
                        linkedMapOf(
                            "binaryPath" to c.engine.sherpa.binaryPath,
                            "modelPath" to c.engine.sherpa.modelPath,
                            "modelName" to c.engine.sherpa.modelName,
                            "threads" to c.engine.sherpa.threads,
                        ),
                ),
            "autostart" to
                linkedMapOf(
                    "enabled" to c.autostart.enabled,
                ),
            "postProcessing" to
                linkedMapOf(
                    "enabled" to c.postProcessing.enabled,
                    "autoPunctuation" to c.postProcessing.autoPunctuation,
                    "numberFormatting" to c.postProcessing.numberFormatting,
                    "fillerWordsRemoval" to c.postProcessing.fillerWordsRemoval,
                    "selfCorrection" to c.postProcessing.selfCorrection,
                    "dictionaryEnabled" to c.postProcessing.dictionaryEnabled,
                    "customDictionaryPath" to c.postProcessing.customDictionaryPath,
                    "activeAppProfile" to c.postProcessing.activeAppProfile,
                ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(map: Map<String, Any>): GolosConfig {
        val version = map["version"]?.toString() ?: "1.0"

        val hkMap = map["hotkey"] as? Map<String, Any> ?: emptyMap()
        val hotkey =
            HotkeySettings(
                keyName = hkMap["keyName"]?.toString() ?: "F8",
                ctrl = hkMap["ctrl"] as? Boolean ?: false,
                shift = hkMap["shift"] as? Boolean ?: false,
                alt = hkMap["alt"] as? Boolean ?: false,
                meta = hkMap["meta"] as? Boolean ?: false,
                keyCode = (hkMap["keyCode"] as? Number)?.toInt() ?: 119,
            )

        val insMap = map["insertion"] as? Map<String, Any> ?: emptyMap()
        val insertion =
            InsertionSettings(
                mode = insMap["mode"]?.toString() ?: "DIRECT_TYPING",
                timing = insMap["timing"]?.toString() ?: "ON_KEY_RELEASE",
                copyToClipboard = insMap["copyToClipboard"] as? Boolean ?: false,
                copyToClipboardIfNoField = insMap["copyToClipboardIfNoField"] as? Boolean ?: true,
            )

        val audMap = map["audio"] as? Map<String, Any> ?: emptyMap()
        val audio =
            AudioSettings(
                deviceName = audMap["deviceName"]?.toString() ?: "",
                provider = audMap["provider"]?.toString() ?: "JavaSound",
                gain = (audMap["gain"] as? Number)?.toFloat() ?: 1.0f,
            )

        val engMap = map["engine"] as? Map<String, Any> ?: emptyMap()
        val whsMap = engMap["whisper"] as? Map<String, Any> ?: emptyMap()
        val whisper =
            WhisperSettings(
                binaryPath = whsMap["binaryPath"]?.toString() ?: "",
                modelPath = whsMap["modelPath"]?.toString() ?: "",
                modelName = whsMap["modelName"]?.toString() ?: "base",
                language = whsMap["language"]?.toString() ?: "auto",
                device = whsMap["device"]?.toString() ?: "CPU",
                threads = (whsMap["threads"] as? Number)?.toInt() ?: 4,
                bilingualMode = whsMap["bilingualMode"] as? Boolean ?: false,
            )
        val voskMap = engMap["vosk"] as? Map<String, Any> ?: emptyMap()
        val vosk =
            VoskSettings(
                binaryPath = voskMap["binaryPath"]?.toString() ?: "",
                modelPath = voskMap["modelPath"]?.toString() ?: "",
                modelName = voskMap["modelName"]?.toString() ?: "vosk-model-small-en-us-0.15",
            )
        val sherpaMap = engMap["sherpa"] as? Map<String, Any> ?: emptyMap()
        val sherpa =
            SherpaSettings(
                binaryPath = sherpaMap["binaryPath"]?.toString() ?: "",
                modelPath = sherpaMap["modelPath"]?.toString() ?: "",
                modelName = sherpaMap["modelName"]?.toString() ?: "PengChengStarling",
                threads = (sherpaMap["threads"] as? Number)?.toInt() ?: 4,
            )
        val engine =
            EngineSettings(
                selectedId = engMap["selectedId"]?.toString() ?: "whisper",
                whisper = whisper,
                vosk = vosk,
                sherpa = sherpa,
            )

        val autoMap = map["autostart"] as? Map<String, Any> ?: emptyMap()
        val autostart =
            AutostartSettings(
                enabled = autoMap["enabled"] as? Boolean ?: false,
            )

        val ppMap = map["postProcessing"] as? Map<String, Any> ?: emptyMap()
        val postProcessing =
            PostProcessingSettings(
                enabled = ppMap["enabled"] as? Boolean ?: true,
                autoPunctuation = ppMap["autoPunctuation"] as? Boolean ?: true,
                numberFormatting = ppMap["numberFormatting"] as? Boolean ?: true,
                fillerWordsRemoval = ppMap["fillerWordsRemoval"] as? Boolean ?: true,
                selfCorrection = ppMap["selfCorrection"] as? Boolean ?: true,
                dictionaryEnabled = ppMap["dictionaryEnabled"] as? Boolean ?: true,
                customDictionaryPath = ppMap["customDictionaryPath"]?.toString() ?: "",
                activeAppProfile = ppMap["activeAppProfile"]?.toString() ?: "AUTO",
            )

        val uiLanguage = map["uiLanguage"]?.toString() ?: "en"

        return GolosConfig(
            version = version,
            uiLanguage = uiLanguage,
            hotkey = hotkey,
            insertion = insertion,
            audio = audio,
            engine = engine,
            autostart = autostart,
            postProcessing = postProcessing,
        )
    }
}

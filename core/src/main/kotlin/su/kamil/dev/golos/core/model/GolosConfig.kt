package su.kamil.dev.golos.core.model

/**
 * Stable, unified contract for GolosAI configuration.
 * Serialized and deserialized as YAML.
 */
data class HotkeySettings(
    val keyName: String = "F8",
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val keyCode: Int = 119
) {
    fun toHotkeyConfig(): HotkeyConfig = HotkeyConfig(
        keyName = keyName,
        ctrl = ctrl,
        shift = shift,
        alt = alt,
        meta = meta,
        keyCode = keyCode
    )

    companion object {
        fun from(c: HotkeyConfig) = HotkeySettings(
            keyName = c.keyName,
            ctrl = c.ctrl,
            shift = c.shift,
            alt = c.alt,
            meta = c.meta,
            keyCode = c.keyCode
        )
    }
}

data class InsertionSettings(
    val mode: String = "DIRECT_TYPING",
    val copyToClipboard: Boolean = false,
    val copyToClipboardIfNoField: Boolean = true
) {
    fun toInjectionConfig(): InjectionConfig = InjectionConfig(
        mode = runCatching { InsertionMode.valueOf(mode) }.getOrDefault(InsertionMode.DIRECT_TYPING),
        copyToClipboard = copyToClipboard,
        copyToClipboardIfNoField = copyToClipboardIfNoField
    )

    companion object {
        fun from(c: InjectionConfig) = InsertionSettings(
            mode = c.mode.name,
            copyToClipboard = c.copyToClipboard,
            copyToClipboardIfNoField = c.copyToClipboardIfNoField
        )
    }
}

data class AudioSettings(
    val deviceName: String = "",
    val provider: String = "JavaSound"
)

data class WhisperSettings(
    val binaryPath: String = "",
    val modelPath: String = "",
    val modelName: String = "base",
    val language: String = "auto",
    val device: String = "CPU",
    val threads: Int = 4
)

data class EngineSettings(
    val selectedId: String = "mock",
    val whisper: WhisperSettings = WhisperSettings()
)

data class AutostartSettings(
    val enabled: Boolean = false
)

data class GolosConfig(
    val version: String = "1.0",
    val hotkey: HotkeySettings = HotkeySettings(),
    val insertion: InsertionSettings = InsertionSettings(),
    val audio: AudioSettings = AudioSettings(),
    val engine: EngineSettings = EngineSettings(),
    val autostart: AutostartSettings = AutostartSettings()
)

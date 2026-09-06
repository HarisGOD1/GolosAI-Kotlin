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
    val keyCode: Int = 119,
    val triggerMode: String = "HOLD_TO_TALK",
) {
    fun toHotkeyConfig(): HotkeyConfig =
        HotkeyConfig(
            keyName = keyName,
            ctrl = ctrl,
            shift = shift,
            alt = alt,
            meta = meta,
            keyCode = keyCode,
            triggerMode = runCatching { TriggerMode.valueOf(triggerMode) }.getOrDefault(TriggerMode.HOLD_TO_TALK),
        )

    companion object {
        fun from(c: HotkeyConfig) =
            HotkeySettings(
                keyName = c.keyName,
                ctrl = c.ctrl,
                shift = c.shift,
                alt = c.alt,
                meta = c.meta,
                keyCode = c.keyCode,
                triggerMode = c.triggerMode.name,
            )
    }
}

data class InsertionSettings(
    val mode: String = "DIRECT_TYPING",
    val timing: String = "ON_KEY_RELEASE",
    val copyToClipboard: Boolean = false,
    val copyToClipboardIfNoField: Boolean = true,
) {
    fun toInjectionConfig(): InjectionConfig =
        InjectionConfig(
            mode = runCatching { InsertionMode.valueOf(mode) }.getOrDefault(InsertionMode.DIRECT_TYPING),
            timing = runCatching { InjectionTiming.valueOf(timing) }.getOrDefault(InjectionTiming.ON_KEY_RELEASE),
            copyToClipboard = copyToClipboard,
            copyToClipboardIfNoField = copyToClipboardIfNoField,
        )

    companion object {
        fun from(c: InjectionConfig) =
            InsertionSettings(
                mode = c.mode.name,
                timing = c.timing.name,
                copyToClipboard = c.copyToClipboard,
                copyToClipboardIfNoField = c.copyToClipboardIfNoField,
            )
    }
}

data class AudioSettings(
    val deviceName: String = "",
    val provider: String = "JavaSound",
    val gain: Float = 1.0f,
)

data class WhisperSettings(
    val binaryPath: String = "",
    val modelPath: String = "",
    val modelName: String = "base",
    val language: String = "auto",
    val device: String = "CPU",
    val threads: Int = 4,
    val bilingualMode: Boolean = false,
)

data class EngineSettings(
    val selectedId: String = "mock",
    val whisper: WhisperSettings = WhisperSettings(),
)

data class AutostartSettings(
    val enabled: Boolean = false,
)

data class GolosConfig(
    val version: String = "1.0",
    val uiLanguage: String = "en",
    val hotkey: HotkeySettings = HotkeySettings(),
    val insertion: InsertionSettings = InsertionSettings(),
    val audio: AudioSettings = AudioSettings(),
    val engine: EngineSettings = EngineSettings(),
    val autostart: AutostartSettings = AutostartSettings(),
)

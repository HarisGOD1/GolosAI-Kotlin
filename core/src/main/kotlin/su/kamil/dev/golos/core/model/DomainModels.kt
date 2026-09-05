package su.kamil.dev.golos.core.model

data class AudioDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean = false
)

data class TranscriptionResult(
    val text: String,
    val durationMs: Long = 0,
    val isFinal: Boolean = true,
    val confidence: Float = 1.0f
)

data class HotkeyConfig(
    val keyName: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val keyCode: Int = 0
) {
    val displayText: String
        get() = buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift) append("Shift+")
            if (meta) append("Meta+")
            append(keyName)
        }

    companion object {
        val DEFAULT = HotkeyConfig(keyName = "F8")

        fun parse(input: String): HotkeyConfig {
            val tokens = input.split("+", "-").map { it.trim() }.filter { it.isNotEmpty() }
            var ctrl = false
            var shift = false
            var alt = false
            var meta = false
            var primaryKey = "F8"

            for (token in tokens) {
                when (token.lowercase()) {
                    "ctrl", "control" -> ctrl = true
                    "shift" -> shift = true
                    "alt", "opt", "option" -> alt = true
                    "meta", "cmd", "command", "super", "win" -> meta = true
                    else -> primaryKey = token
                }
            }
            return HotkeyConfig(
                keyName = primaryKey,
                ctrl = ctrl,
                shift = shift,
                alt = alt,
                meta = meta
            )
        }
    }
}

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
    val keyCode: Int,
    val keyName: String,
    val requiresModifiers: Boolean = false,
    val modifiersMask: Int = 0
) {
    companion object {
        // Default: F8 or CapsLock or Right Alt
        val DEFAULT = HotkeyConfig(
            keyCode = 19, // KeyCode for Pause/Break or custom default
            keyName = "F8"
        )
    }
}

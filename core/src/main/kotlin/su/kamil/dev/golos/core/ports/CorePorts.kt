package su.kamil.dev.golos.core.ports

import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.TranscriptionResult

/**
 * Port for audio capture from input devices (e.g. microphones).
 */
interface AudioCapturePort {
    fun getAvailableDevices(): List<AudioDevice>

    fun startCapture(
        device: AudioDevice?,
        onChunkCaptured: (AudioChunk) -> Unit,
    )

    fun stopCapture(): AudioChunk?

    fun isCapturing(): Boolean

    var onAudioLevel: ((rmsDb: Float, peakDb: Float, isClipping: Boolean) -> Unit)?
        get() = null
        set(_) {}

    var gain: Float
        get() = 1.0f
        set(_) {}
}

/**
 * Port for speech-to-text processing engines (e.g. whisper.cpp, mock engine).
 */
interface SpeechToTextEngine {
    val id: String
    val displayName: String

    suspend fun initialize(): Result<Unit> = Result.success(Unit)

    suspend fun transcribe(audio: AudioChunk): TranscriptionResult

    suspend fun transcribeFile(file: java.io.File): TranscriptionResult {
        return transcribe(AudioChunk(file.readBytes()))
    }

    fun close() {}
}

/**
 * Port for listening to global push-to-talk hotkeys even when application is unfocused.
 */
interface GlobalHotkeyHook {
    fun register(
        config: HotkeyConfig,
        onKeyDown: () -> Unit,
        onKeyUp: () -> Unit,
    ): Result<Unit>

    fun unregister()

    val isRegistered: Boolean
}

/**
 * Port for injecting transcribed text into the user's currently focused input field.
 */
interface TextInjectorPort {
    fun initialize(): Result<Unit> = Result.success(Unit)

    fun injectText(
        text: String,
        config: InjectionConfig = InjectionConfig(),
    ): Result<Unit>

    val lastInjectionMethod: su.kamil.dev.golos.core.model.InjectionMethod?
        get() = null
}

/**
 * Port for detecting the active application window context (Criteria J-04, M-02).
 */
interface ActiveWindowDetectorPort {
    fun detectActiveWindow(): su.kamil.dev.golos.core.model.ActiveWindowInfo
}

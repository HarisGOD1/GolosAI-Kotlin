package su.kamil.dev.golos.voice.engine

import kotlinx.coroutines.delay
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.voice.audio.AudioPreprocessor

/**
 * Mock speech engine for development, automated tests, and offline testing without model weights.
 */
class MockSpeechToTextEngine(
    override val id: String = "mock-engine",
    override val displayName: String = "Mock Speech Engine (Simulated)",
    private val simulatedDelayMs: Long = 100,
    var predeterminedText: String? = null
) : SpeechToTextEngine {

    override suspend fun transcribe(audio: AudioChunk): TranscriptionResult {
        if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }

        val rms = AudioPreprocessor.calculateRms(audio)
        if (rms < 0.005f && predeterminedText == null) {
            return TranscriptionResult(
                text = "",
                durationMs = simulatedDelayMs,
                confidence = 0.0f
            )
        }

        val text = predeterminedText ?: "Hello, this is simulated GolosAI speech-to-text dictation."
        return TranscriptionResult(
            text = text,
            durationMs = simulatedDelayMs,
            confidence = 0.98f
        )
    }
}

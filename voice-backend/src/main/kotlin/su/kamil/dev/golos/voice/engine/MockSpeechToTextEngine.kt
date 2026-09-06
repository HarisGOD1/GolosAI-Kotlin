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
    var predeterminedText: String? = null,
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
                confidence = 0.0f,
            )
        }

        val baseText = predeterminedText ?: "Hello, this is simulated GolosAI speech-to-text dictation on the fly."
        val text =
            if (predeterminedText == null && audio.durationMs > 0) {
                val words = baseText.split(Regex("\\s+"))
                val count = ((audio.durationMs / 300L) + 1).coerceAtMost(words.size.toLong()).toInt()
                words.take(count).joinToString(" ")
            } else {
                baseText
            }
        return TranscriptionResult(
            text = text,
            durationMs = simulatedDelayMs,
            confidence = 0.98f,
        )
    }

    override suspend fun transcribeFile(file: java.io.File): TranscriptionResult {
        if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }
        val text = predeterminedText ?: "Simulated transcription of audio file ${file.name}."
        val segments =
            listOf(
                su.kamil.dev.golos.core.model.TimecodedSegment(
                    startMs = 0L,
                    endMs = maxOf(1000L, simulatedDelayMs),
                    text = text,
                ),
            )
        return TranscriptionResult(
            text = text,
            durationMs = simulatedDelayMs,
            confidence = 0.98f,
            segments = segments,
        )
    }
}

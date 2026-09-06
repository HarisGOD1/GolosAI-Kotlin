package su.kamil.dev.golos.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InjectionTiming
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.core.ports.AudioCapturePort
import su.kamil.dev.golos.core.ports.SpeechToTextEngine
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.core.state.DictationStateMachine
import su.kamil.dev.golos.system.keyboard.SimulatedHotkeyHook

@OptIn(ExperimentalCoroutinesApi::class)
class OnTheFlyStreamingHoldTest {
    private class StreamingAudioCapture : AudioCapturePort {
        var capturing = false
        var onChunk: ((AudioChunk) -> Unit)? = null
        override var onAudioLevel: ((rmsDb: Float, peakDb: Float, isClipping: Boolean) -> Unit)? = null
        override var gain: Float = 1.0f

        override fun getAvailableDevices(): List<AudioDevice> = emptyList()

        override fun startCapture(
            device: AudioDevice?,
            onChunkCaptured: (AudioChunk) -> Unit,
        ) {
            capturing = true
            onChunk = onChunkCaptured
        }

        override fun stopCapture(): AudioChunk? {
            capturing = false
            return AudioChunk(ByteArray(32000) { 0x40 }) // 1 second of audio
        }

        override fun isCapturing(): Boolean = capturing
    }

    private class IncrementalEngine : SpeechToTextEngine {
        var callCount = 0
        override val id: String = "incremental-mock"
        override val displayName: String = "Incremental Mock"

        override suspend fun transcribe(audio: AudioChunk): TranscriptionResult {
            callCount++
            val text =
                when (callCount) {
                    1 -> "Hello"
                    2 -> "Hello world"
                    3 -> "Hello world today"
                    else -> "Hello world today GolosAI"
                }
            return TranscriptionResult(
                text = text,
                durationMs = 1000L,
                confidence = 0.95f,
            )
        }
    }

    private class RecordingTextInjector : TextInjectorPort {
        val injectedDeltas = mutableListOf<String>()

        override fun injectText(
            text: String,
            config: InjectionConfig,
        ): Result<Unit> {
            injectedDeltas.add(text)
            return Result.success(Unit)
        }
    }

    @Test
    fun `test on-the-fly dictation streams words while holding without premature release`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val stateMachine = DictationStateMachine()
            val audioCapture = StreamingAudioCapture()
            val engine = IncrementalEngine()
            val hotkeyHook = SimulatedHotkeyHook()
            val textInjector = RecordingTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = audioCapture,
                    speechEngine = engine,
                    hotkeyHook = hotkeyHook,
                    textInjector = textInjector,
                    scope = testScope,
                )

            orchestrator.injectionConfig =
                InjectionConfig(
                    mode = InsertionMode.DIRECT_TYPING,
                    timing = InjectionTiming.ON_THE_FLY,
                )

            orchestrator.start()

            // 1. User presses and holds PTT button
            hotkeyHook.triggerKeyDown()
            assertEquals(DictationState.RECORDING, orchestrator.state.value)
            assertTrue(audioCapture.isCapturing())

            // Simulate incoming audio chunks (>= 16000 bytes)
            audioCapture.onChunk?.invoke(AudioChunk(ByteArray(16000) { 0x30 }))

            // Advance time past first streaming delay (400ms)
            testScheduler.advanceTimeBy(450)
            assertEquals(DictationState.RECORDING, orchestrator.state.value)
            assertTrue(audioCapture.isCapturing(), "Audio capture must remain active during streaming hold")

            // Simulate second chunk of audio
            audioCapture.onChunk?.invoke(AudioChunk(ByteArray(16000) { 0x30 }))
            testScheduler.advanceTimeBy(450)
            assertEquals(DictationState.RECORDING, orchestrator.state.value)

            // Verify delta words were injected while holding
            assertTrue(textInjector.injectedDeltas.isNotEmpty())
            assertEquals("Hello", textInjector.injectedDeltas[0])

            // 2. User physically releases key after talking
            hotkeyHook.triggerKeyUp()
            testScheduler.advanceUntilIdle()

            assertEquals(DictationState.IDLE, orchestrator.state.value)

            // Reconstructed text should match full sentence without duplication
            val fullReconstructed = textInjector.injectedDeltas.joinToString("")
            assertTrue(fullReconstructed.startsWith("Hello"))
            assertTrue(fullReconstructed.contains("world"))

            orchestrator.stop()
        }
}

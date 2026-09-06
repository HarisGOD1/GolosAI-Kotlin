package su.kamil.dev.golos.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.model.DictationState
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.ports.AudioCapturePort
import su.kamil.dev.golos.core.ports.TextInjectorPort
import su.kamil.dev.golos.core.state.DictationStateMachine
import su.kamil.dev.golos.system.keyboard.SimulatedHotkeyHook
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine

@OptIn(ExperimentalCoroutinesApi::class)
class DictationOrchestratorTest {
    private class FakeAudioCapture : AudioCapturePort {
        var startCount = 0
        var stopCount = 0
        var capturing = false
        var returnChunk: AudioChunk? = AudioChunk(ByteArray(16000) { 0x40 })
        override var onAudioLevel: ((rmsDb: Float, peakDb: Float, isClipping: Boolean) -> Unit)? = null
        override var gain: Float = 1.0f

        override fun getAvailableDevices(): List<AudioDevice> = emptyList()

        override fun startCapture(
            device: AudioDevice?,
            onChunkCaptured: (AudioChunk) -> Unit,
        ) {
            startCount++
            capturing = true
        }

        override fun stopCapture(): AudioChunk? {
            stopCount++
            capturing = false
            return returnChunk
        }

        override fun isCapturing(): Boolean = capturing
    }

    private class FakeTextInjector : TextInjectorPort {
        val injected = mutableListOf<String>()

        override fun injectText(
            text: String,
            config: su.kamil.dev.golos.core.model.InjectionConfig,
        ): Result<Unit> {
            injected.add(text)
            return Result.success(Unit)
        }
    }

    @Test
    fun `test full push-to-talk workflow from key press to injection`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val testScope = TestScope(testDispatcher)

            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine =
                MockSpeechToTextEngine(
                    simulatedDelayMs = 0,
                    predeterminedText = "Hello GolosAI",
                )
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = testScope,
                )

            orchestrator.start(HotkeyConfig(keyCode = 19, keyName = "F8"))
            assertEquals(DictationState.IDLE, orchestrator.state.value)

            // 1. User presses and holds PTT key
            fakeHotkeyHook.triggerKeyDown()
            assertEquals(DictationState.RECORDING, orchestrator.state.value)
            assertEquals(1, fakeAudioCapture.startCount)

            // 2. User releases PTT key
            fakeHotkeyHook.triggerKeyUp()
            assertEquals(1, fakeAudioCapture.stopCount)

            // Advance coroutines
            testScheduler.advanceUntilIdle()

            // 3. State should transition to IDLE after processing
            assertEquals(DictationState.IDLE, orchestrator.state.value)

            // 4. Text should have been injected
            assertEquals(1, fakeTextInjector.injected.size)
            assertEquals("Hello GolosAI", fakeTextInjector.injected[0])

            orchestrator.stop()
        }

    @Test
    fun `test transcribeFile transcribes audio file and invokes completion callback`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine =
                MockSpeechToTextEngine(
                    simulatedDelayMs = 0,
                    predeterminedText = "Transcribed file content",
                )
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = this,
                )

            var completedResultText = ""
            orchestrator.onTranscriptionCompleted = { result, _ ->
                completedResultText = result.text
            }

            val tempFile = java.io.File.createTempFile("test_audio_", ".wav")
            try {
                tempFile.writeBytes(ByteArray(100))
                val result = orchestrator.transcribeFile(tempFile)
                assertEquals("Transcribed file content", result.text)
                assertEquals("Transcribed file content", completedResultText)
            } finally {
                tempFile.delete()
            }
        }

    @Test
    fun `test on-the-fly injection config is accepted and processed`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine =
                MockSpeechToTextEngine(
                    simulatedDelayMs = 0,
                    predeterminedText = "Word1 Word2 Word3",
                )
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = this,
                )

            orchestrator.injectionConfig =
                su.kamil.dev.golos.core.model.InjectionConfig(
                    mode = su.kamil.dev.golos.core.model.InsertionMode.DIRECT_TYPING,
                    timing = su.kamil.dev.golos.core.model.InjectionTiming.ON_THE_FLY,
                )

            orchestrator.start()
            fakeHotkeyHook.triggerKeyDown()
            assertEquals(DictationState.RECORDING, orchestrator.state.value)
            fakeHotkeyHook.triggerKeyUp()

            testScheduler.advanceUntilIdle()
            assertEquals(DictationState.IDLE, orchestrator.state.value)
            assertEquals(1, fakeTextInjector.injected.size)
            assertEquals("Word1 Word2 Word3", fakeTextInjector.injected[0])

            orchestrator.stop()
        }

    @Test
    fun `test short keypress less than 200ms is ignored under criterion D-10`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            fakeAudioCapture.returnChunk = AudioChunk(ByteArray(1600) { 0x40 }) // ~50ms audio (< 200ms)
            val mockEngine = MockSpeechToTextEngine(simulatedDelayMs = 0, predeterminedText = "Should be ignored")
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = this,
                )

            orchestrator.start()
            fakeHotkeyHook.triggerKeyDown()
            assertEquals(DictationState.RECORDING, orchestrator.state.value)
            fakeHotkeyHook.triggerKeyUp()

            testScheduler.advanceUntilIdle()
            assertEquals(DictationState.IDLE, orchestrator.state.value)
            // Empty replica should NOT be created or injected
            assertEquals(0, fakeTextInjector.injected.size)

            orchestrator.stop()
        }

    @Test
    fun `test orchestrator stop cleanly resets state and unregisters hook`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine = MockSpeechToTextEngine()
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = this,
                )

            orchestrator.start()
            fakeHotkeyHook.triggerKeyDown()
            assertEquals(DictationState.RECORDING, orchestrator.state.value)

            orchestrator.stop()
            assertEquals(DictationState.IDLE, orchestrator.state.value)
            org.junit.jupiter.api.Assertions.assertFalse(fakeHotkeyHook.isRegistered)
            org.junit.jupiter.api.Assertions.assertFalse(fakeAudioCapture.isCapturing())
        }

    @Test
    fun `test orchestrator audio test lifecycle - Criterion C-07`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine = MockSpeechToTextEngine()
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = this,
                )

            var receivedDb = 0f
            orchestrator.startAudioTest { rms, _, _ ->
                receivedDb = rms
            }
            assertTrue(orchestrator.isTestingAudio())
            assertTrue(fakeAudioCapture.capturing)

            fakeAudioCapture.onAudioLevel?.invoke(-15.0f, -10.0f, false)
            assertEquals(-15.0f, receivedDb)

            orchestrator.stopAudioTest()
            assertFalse(orchestrator.isTestingAudio())
            assertFalse(fakeAudioCapture.capturing)
        }

    @Test
    fun `test orchestrator silence and clipping warnings - Criteria C-08 and E-07`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine = MockSpeechToTextEngine()
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    scope = this,
                )

            var lastWarning = su.kamil.dev.golos.core.model.AudioWarningType.NONE
            orchestrator.onAudioWarning = { lastWarning = it }

            orchestrator.start()
            fakeHotkeyHook.triggerKeyDown()
            assertEquals(DictationState.RECORDING, orchestrator.state.value)

            // Simulate clipping
            fakeAudioCapture.onAudioLevel?.invoke(-5.0f, 0.0f, true)
            assertEquals(su.kamil.dev.golos.core.model.AudioWarningType.CLIPPING, lastWarning)

            fakeHotkeyHook.triggerKeyUp()
            testScheduler.advanceUntilIdle()
            assertEquals(su.kamil.dev.golos.core.model.AudioWarningType.NONE, lastWarning)
            orchestrator.stop()
        }

    private class FakeActiveWindowDetector(
        var window: su.kamil.dev.golos.core.model.ActiveWindowInfo =
            su.kamil.dev.golos.core.model.ActiveWindowInfo(),
    ) : su.kamil.dev.golos.core.ports.ActiveWindowDetectorPort {
        override fun detectActiveWindow(): su.kamil.dev.golos.core.model.ActiveWindowInfo = window
    }

    @Test
    fun `test orchestrator captures active window and delivers context in callback`() =
        runTest {
            val stateMachine = DictationStateMachine()
            val fakeAudioCapture = FakeAudioCapture()
            val mockEngine =
                MockSpeechToTextEngine(
                    simulatedDelayMs = 0,
                    predeterminedText = "Test context detection",
                )
            val fakeHotkeyHook = SimulatedHotkeyHook()
            val fakeTextInjector = FakeTextInjector()
            val fakeWindowDetector =
                FakeActiveWindowDetector(
                    su.kamil.dev.golos.core.model.ActiveWindowInfo(
                        appName = "telegram",
                        windowTitle = "General Chat - Telegram",
                        profile = su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER,
                    ),
                )

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = stateMachine,
                    audioCapture = fakeAudioCapture,
                    speechEngine = mockEngine,
                    hotkeyHook = fakeHotkeyHook,
                    textInjector = fakeTextInjector,
                    activeWindowDetector = fakeWindowDetector,
                    scope = this,
                )

            var capturedWindow: su.kamil.dev.golos.core.model.ActiveWindowInfo? = null
            var capturedProfile: su.kamil.dev.golos.core.model.ApplicationProfile? = null

            orchestrator.onTranscriptionWithContextCompleted = { _, _, window, profile ->
                capturedWindow = window
                capturedProfile = profile
            }

            orchestrator.start()
            fakeHotkeyHook.triggerKeyDown()
            assertEquals(
                su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER,
                orchestrator.getEffectiveProfile(),
            )
            fakeHotkeyHook.triggerKeyUp()

            testScheduler.advanceUntilIdle()
            assertEquals(DictationState.IDLE, orchestrator.state.value)
            assertEquals("telegram", capturedWindow?.appName)
            assertEquals(su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER, capturedProfile)

            orchestrator.stop()
        }

    @Test
    fun `test manual profile overrides detected window profile and cycles correctly`() =
        runTest {
            val fakeWindowDetector =
                FakeActiveWindowDetector(
                    su.kamil.dev.golos.core.model.ActiveWindowInfo(
                        appName = "telegram",
                        windowTitle = "Telegram",
                        profile = su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER,
                    ),
                )

            val orchestrator =
                DictationOrchestrator(
                    stateMachine = DictationStateMachine(),
                    audioCapture = FakeAudioCapture(),
                    speechEngine = MockSpeechToTextEngine(),
                    hotkeyHook = SimulatedHotkeyHook(),
                    textInjector = FakeTextInjector(),
                    activeWindowDetector = fakeWindowDetector,
                    scope = this,
                )

            // Initially uses detected profile
            orchestrator.onPushToTalkPressed()
            assertEquals(
                su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER,
                orchestrator.getEffectiveProfile(),
            )

            // Set manual override
            orchestrator.manualProfile = su.kamil.dev.golos.core.model.ApplicationProfile.CODE
            assertEquals(
                su.kamil.dev.golos.core.model.ApplicationProfile.CODE,
                orchestrator.getEffectiveProfile(),
            )

            // Test cycling: CODE -> GENERAL -> AUTO (null) -> MESSENGER -> MAIL -> CODE
            assertEquals(su.kamil.dev.golos.core.model.ApplicationProfile.GENERAL, orchestrator.cycleManualProfile())
            assertEquals(null, orchestrator.cycleManualProfile())
            assertEquals(su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER, orchestrator.cycleManualProfile())
            assertEquals(su.kamil.dev.golos.core.model.ApplicationProfile.MAIL, orchestrator.cycleManualProfile())
            assertEquals(su.kamil.dev.golos.core.model.ApplicationProfile.CODE, orchestrator.cycleManualProfile())
        }

    @Test
    fun `test active window change during processing is detected and updated - Criterion K-24`() =
        runTest {
            val startWindow =
                su.kamil.dev.golos.core.model.ActiveWindowInfo(
                    appName = "telegram",
                    windowTitle = "Telegram Desktop",
                    profile = su.kamil.dev.golos.core.model.ApplicationProfile.MESSENGER,
                )
            val switchedWindow =
                su.kamil.dev.golos.core.model.ActiveWindowInfo(
                    appName = "code",
                    windowTitle = "Main.kt - GolosAI - Visual Studio Code",
                    profile = su.kamil.dev.golos.core.model.ApplicationProfile.CODE,
                )

            var currentWindowToReturn = startWindow
            val dynamicDetector =
                object : su.kamil.dev.golos.core.ports.ActiveWindowDetectorPort {
                    override fun detectActiveWindow(): su.kamil.dev.golos.core.model.ActiveWindowInfo = currentWindowToReturn
                }

            val fakeInjector = FakeTextInjector()
            val orchestrator =
                DictationOrchestrator(
                    stateMachine = DictationStateMachine(),
                    audioCapture = FakeAudioCapture(),
                    speechEngine = MockSpeechToTextEngine(),
                    hotkeyHook = SimulatedHotkeyHook(),
                    textInjector = fakeInjector,
                    activeWindowDetector = dynamicDetector,
                    scope = this,
                )

            // User starts speaking in Telegram
            orchestrator.onPushToTalkPressed()
            assertEquals("telegram", orchestrator.currentActiveWindow.appName)

            // While speaking / processing, user switches window to VS Code
            currentWindowToReturn = switchedWindow

            // User releases hotkey, triggering transcription and injection
            orchestrator.onPushToTalkReleased()
            testScheduler.advanceUntilIdle()

            // Verify active window was updated to the switched window before injection (Criterion K-24)
            assertEquals("code", orchestrator.currentActiveWindow.appName)
            assertEquals("Main.kt - GolosAI - Visual Studio Code", orchestrator.currentActiveWindow.windowTitle)
            assertTrue(fakeInjector.injected.isNotEmpty())
        }
}

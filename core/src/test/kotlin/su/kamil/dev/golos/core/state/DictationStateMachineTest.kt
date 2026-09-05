package su.kamil.dev.golos.core.state

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.DictationState

class DictationStateMachineTest {
    @Test
    fun `test valid state progression cycle`() {
        val sm = DictationStateMachine()
        assertEquals(DictationState.IDLE, sm.state.value)

        assertTrue(sm.startRecording())
        assertEquals(DictationState.RECORDING, sm.state.value)

        assertTrue(sm.startProcessing())
        assertEquals(DictationState.PROCESSING, sm.state.value)

        assertTrue(sm.finishProcessing())
        assertEquals(DictationState.IDLE, sm.state.value)
    }

    @Test
    fun `test invalid state transitions rejected`() {
        val sm = DictationStateMachine()

        // Cannot jump directly from IDLE to PROCESSING
        assertFalse(sm.startProcessing())
        assertEquals(DictationState.IDLE, sm.state.value)

        sm.startRecording()
        // Cannot record again while already recording
        assertFalse(sm.startRecording())
        assertEquals(DictationState.RECORDING, sm.state.value)
    }

    @Test
    fun `test reset returns to IDLE from any state`() {
        val sm = DictationStateMachine()
        sm.startRecording()
        sm.reset()
        assertEquals(DictationState.IDLE, sm.state.value)

        sm.startRecording()
        sm.startProcessing()
        sm.reset()
        assertEquals(DictationState.IDLE, sm.state.value)
    }

    @Test
    fun `test audio chunk float conversion`() {
        // 16-bit PCM little-endian: 0, 32767 (~1.0), -32768 (-1.0)
        val pcm =
            byteArrayOf(
                0x00,
                0x00, // 0
                0xFF.toByte(),
                0x7F, // 32767
                0x00,
                0x80.toByte(), // -32768
            )
        val chunk = AudioChunk(pcm, sampleRate = 16000, channels = 1, bitsPerSample = 16)
        val floats = chunk.toNormalizedFloatArray()

        assertEquals(3, floats.size)
        assertEquals(0.0f, floats[0], 0.001f)
        assertEquals(1.0f, floats[1], 0.001f)
        assertEquals(-1.0f, floats[2], 0.001f)
    }
}

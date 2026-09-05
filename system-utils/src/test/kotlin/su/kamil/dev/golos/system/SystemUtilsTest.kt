package su.kamil.dev.golos.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.system.audio.JavaSoundAudioCapture
import su.kamil.dev.golos.system.keyboard.SimulatedHotkeyHook

class SystemUtilsTest {

    @Test
    fun `test simulated hotkey hook triggers callbacks`() {
        val hook = SimulatedHotkeyHook()
        var downCount = 0
        var upCount = 0

        hook.register(
            HotkeyConfig(keyCode = 19, keyName = "F8"),
            onKeyDown = { downCount++ },
            onKeyUp = { upCount++ }
        )

        assertTrue(hook.isRegistered)
        hook.triggerKeyDown()
        assertEquals(1, downCount)
        assertEquals(0, upCount)

        hook.triggerKeyUp()
        assertEquals(1, downCount)
        assertEquals(1, upCount)

        hook.unregister()
        assertFalse(hook.isRegistered)

        hook.triggerKeyDown()
        assertEquals(1, downCount) // should not increase
    }

    @Test
    fun `test java sound audio capture device querying`() {
        val capture = JavaSoundAudioCapture()
        // Ensure querying devices does not throw
        val devices = capture.getAvailableDevices()
        assertNotNull(devices)
        assertFalse(capture.isCapturing())
    }
}

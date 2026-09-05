package su.kamil.dev.golos.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.HotkeyConfig
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.system.audio.JavaSoundAudioCapture
import su.kamil.dev.golos.system.input.ActiveWindowTextInjector
import su.kamil.dev.golos.system.keyboard.SimulatedHotkeyHook

class SystemUtilsTest {
    @Test
    fun `test simulated hotkey hook triggers callbacks`() {
        val hook = SimulatedHotkeyHook()
        var downCount = 0
        var upCount = 0

        hook.register(
            HotkeyConfig(keyName = "F8"),
            onKeyDown = { downCount++ },
            onKeyUp = { upCount++ },
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
    fun `test java sound audio capture device querying and loopback detection`() {
        val capture = JavaSoundAudioCapture()
        val devices = capture.getAvailableDevices()
        assertNotNull(devices)
        assertTrue(devices.isNotEmpty())
        assertFalse(capture.isCapturing())
        // Verify loopback monitor exists or is flagged properly
        assertTrue(devices.any { it.isLoopbackMonitor })
    }

    @Test
    fun `test portaudio audio capture device querying`() {
        val paCapture = su.kamil.dev.golos.system.audio.PortAudioAudioCapture()
        val devices = paCapture.getAvailableDevices()
        assertNotNull(devices)
        assertTrue(devices.isNotEmpty())
        // Every device returned by PortAudio provider must be tagged with pa: id
        assertTrue(devices.all { it.id.startsWith("pa:") })
        assertFalse(paCapture.isCapturing())
    }

    @Test
    fun `test hotkey config parsing`() {
        val combo = HotkeyConfig.parse("Ctrl+Shift+L")
        assertEquals("L", combo.keyName)
        assertTrue(combo.ctrl)
        assertTrue(combo.shift)
        assertFalse(combo.alt)
        assertFalse(combo.meta)
        assertEquals("Ctrl+Shift+L", combo.displayText)

        val single = HotkeyConfig.parse("F9")
        assertEquals("F9", single.keyName)
        assertFalse(single.ctrl)
    }

    @Test
    fun `test text injector empty input does not fail`() {
        val injector = ActiveWindowTextInjector()
        val res = injector.injectText("", InjectionConfig(mode = InsertionMode.DIRECT_TYPING, copyToClipboard = false))
        assertTrue(res.isSuccess)
    }

    @Test
    fun `test text injector handles text with punctuation and whitespace`() {
        val injector = ActiveWindowTextInjector(pasteDelayMs = 10)
        val res =
            injector.injectText(
                "Hello, world! Direct typing & test: 123.",
                InjectionConfig(mode = InsertionMode.DIRECT_TYPING, copyToClipboard = false),
            )
        assertTrue(res.isSuccess)
    }
}

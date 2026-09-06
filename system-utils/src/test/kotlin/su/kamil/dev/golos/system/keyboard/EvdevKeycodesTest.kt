package su.kamil.dev.golos.system.keyboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EvdevKeycodesTest {
    @Test
    fun `test function key scancodes resolution`() {
        assertEquals(EvdevKeycodes.KEY_F1, EvdevKeycodes.resolveScancode("F1"))
        assertEquals(EvdevKeycodes.KEY_F8, EvdevKeycodes.resolveScancode("f8"))
        assertEquals(EvdevKeycodes.KEY_F12, EvdevKeycodes.resolveScancode("F12"))
    }

    @Test
    fun `test special navigation and edit key scancodes`() {
        assertEquals(EvdevKeycodes.KEY_SPACE, EvdevKeycodes.resolveScancode("Space"))
        assertEquals(EvdevKeycodes.KEY_SPACE, EvdevKeycodes.resolveScancode(" "))
        assertEquals(EvdevKeycodes.KEY_ENTER, EvdevKeycodes.resolveScancode("Enter"))
        assertEquals(EvdevKeycodes.KEY_ENTER, EvdevKeycodes.resolveScancode("Return"))
        assertEquals(EvdevKeycodes.KEY_ESC, EvdevKeycodes.resolveScancode("Escape"))
        assertEquals(EvdevKeycodes.KEY_ESC, EvdevKeycodes.resolveScancode("Esc"))
        assertEquals(EvdevKeycodes.KEY_TAB, EvdevKeycodes.resolveScancode("Tab"))
        assertEquals(EvdevKeycodes.KEY_BACKSPACE, EvdevKeycodes.resolveScancode("Backspace"))
        assertEquals(EvdevKeycodes.KEY_CAPSLOCK, EvdevKeycodes.resolveScancode("Caps Lock"))
        assertEquals(EvdevKeycodes.KEY_DELETE, EvdevKeycodes.resolveScancode("Delete"))
        assertEquals(EvdevKeycodes.KEY_INSERT, EvdevKeycodes.resolveScancode("Insert"))
        assertEquals(EvdevKeycodes.KEY_PAGEUP, EvdevKeycodes.resolveScancode("Page Up"))
        assertEquals(EvdevKeycodes.KEY_PAGEDOWN, EvdevKeycodes.resolveScancode("Page Down"))
    }

    @Test
    fun `test alphanumeric scancodes`() {
        assertEquals(EvdevKeycodes.KEY_A, EvdevKeycodes.resolveScancode("A"))
        assertEquals(EvdevKeycodes.KEY_Z, EvdevKeycodes.resolveScancode("z"))
        assertEquals(EvdevKeycodes.KEY_0, EvdevKeycodes.resolveScancode("0"))
        assertEquals(EvdevKeycodes.KEY_9, EvdevKeycodes.resolveScancode("9"))
    }

    @Test
    fun `test unknown key returns zero`() {
        assertEquals(0, EvdevKeycodes.resolveScancode("UNKNOWN_FOOBAR"))
    }
}

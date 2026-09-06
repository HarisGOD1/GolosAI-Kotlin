package su.kamil.dev.golos.system.keyboard

/**
 * Standard Linux input event scancodes (<linux/input-event-codes.h>)
 * and key name scancode resolver.
 */
@Suppress("MagicNumber")
object EvdevKeycodes {
    const val EV_SYN: Int = 0
    const val EV_KEY: Int = 1
    const val SYN_REPORT: Int = 0

    const val KEY_ESC: Int = 1
    const val KEY_1: Int = 2
    const val KEY_2: Int = 3
    const val KEY_3: Int = 4
    const val KEY_4: Int = 5
    const val KEY_5: Int = 6
    const val KEY_6: Int = 7
    const val KEY_7: Int = 8
    const val KEY_8: Int = 9
    const val KEY_9: Int = 10
    const val KEY_0: Int = 11
    const val KEY_MINUS: Int = 12
    const val KEY_EQUAL: Int = 13
    const val KEY_BACKSPACE: Int = 14
    const val KEY_TAB: Int = 15
    const val KEY_Q: Int = 16
    const val KEY_W: Int = 17
    const val KEY_E: Int = 18
    const val KEY_R: Int = 19
    const val KEY_T: Int = 20
    const val KEY_Y: Int = 21
    const val KEY_U: Int = 22
    const val KEY_I: Int = 23
    const val KEY_O: Int = 24
    const val KEY_P: Int = 25
    const val KEY_ENTER: Int = 28
    const val KEY_LEFTCTRL: Int = 29
    const val KEY_A: Int = 30
    const val KEY_S: Int = 31
    const val KEY_D: Int = 32
    const val KEY_F: Int = 33
    const val KEY_G: Int = 34
    const val KEY_H: Int = 35
    const val KEY_J: Int = 36
    const val KEY_K: Int = 37
    const val KEY_L: Int = 38
    const val KEY_LEFTSHIFT: Int = 42
    const val KEY_Z: Int = 44
    const val KEY_X: Int = 45
    const val KEY_C: Int = 46
    const val KEY_V: Int = 47
    const val KEY_B: Int = 48
    const val KEY_N: Int = 49
    const val KEY_M: Int = 50
    const val KEY_RIGHTSHIFT: Int = 54
    const val KEY_LEFTALT: Int = 56
    const val KEY_SPACE: Int = 57
    const val KEY_CAPSLOCK: Int = 58
    const val KEY_F1: Int = 59
    const val KEY_F2: Int = 60
    const val KEY_F3: Int = 61
    const val KEY_F4: Int = 62
    const val KEY_F5: Int = 63
    const val KEY_F6: Int = 64
    const val KEY_F7: Int = 65
    const val KEY_F8: Int = 66
    const val KEY_F9: Int = 67
    const val KEY_F10: Int = 68
    const val KEY_NUMLOCK: Int = 69
    const val KEY_SCROLLLOCK: Int = 70
    const val KEY_F11: Int = 87
    const val KEY_F12: Int = 88
    const val KEY_RIGHTCTRL: Int = 97
    const val KEY_RIGHTALT: Int = 100
    const val KEY_HOME: Int = 102
    const val KEY_UP: Int = 103
    const val KEY_PAGEUP: Int = 104
    const val KEY_LEFT: Int = 105
    const val KEY_RIGHT: Int = 106
    const val KEY_END: Int = 107
    const val KEY_DOWN: Int = 108
    const val KEY_PAGEDOWN: Int = 109
    const val KEY_INSERT: Int = 110
    const val KEY_DELETE: Int = 111
    const val KEY_PAUSE: Int = 119
    const val KEY_LEFTMETA: Int = 125
    const val KEY_RIGHTMETA: Int = 126

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun resolveScancode(rawKeyName: String): Int {
        if (rawKeyName == " ") return KEY_SPACE
        val clean = rawKeyName.trim().uppercase()
        return when (clean) {
            "F1" -> KEY_F1
            "F2" -> KEY_F2
            "F3" -> KEY_F3
            "F4" -> KEY_F4
            "F5" -> KEY_F5
            "F6" -> KEY_F6
            "F7" -> KEY_F7
            "F8" -> KEY_F8
            "F9" -> KEY_F9
            "F10" -> KEY_F10
            "F11" -> KEY_F11
            "F12" -> KEY_F12
            "SPACE", " " -> KEY_SPACE
            "RETURN", "ENTER" -> KEY_ENTER
            "ESC", "ESCAPE" -> KEY_ESC
            "TAB" -> KEY_TAB
            "BACKSPACE", "BACK SPACE" -> KEY_BACKSPACE
            "CAPS_LOCK", "CAPSLOCK", "CAPS LOCK" -> KEY_CAPSLOCK
            "SCROLL_LOCK", "SCROLLLOCK" -> KEY_SCROLLLOCK
            "NUM_LOCK", "NUMLOCK" -> KEY_NUMLOCK
            "INSERT" -> KEY_INSERT
            "DELETE", "DEL" -> KEY_DELETE
            "HOME" -> KEY_HOME
            "END" -> KEY_END
            "PAGE_UP", "PAGEUP", "PAGE UP" -> KEY_PAGEUP
            "PAGE_DOWN", "PAGEDOWN", "PAGE DOWN" -> KEY_PAGEDOWN
            "PAUSE" -> KEY_PAUSE
            "LEFT" -> KEY_LEFT
            "RIGHT" -> KEY_RIGHT
            "UP" -> KEY_UP
            "DOWN" -> KEY_DOWN
            "A" -> KEY_A
            "B" -> KEY_B
            "C" -> KEY_C
            "D" -> KEY_D
            "E" -> KEY_E
            "F" -> KEY_F
            "G" -> KEY_G
            "H" -> KEY_H
            "I" -> KEY_I
            "J" -> KEY_J
            "K" -> KEY_K
            "L" -> KEY_L
            "M" -> KEY_M
            "N" -> KEY_N
            "O" -> KEY_O
            "P" -> KEY_P
            "Q" -> KEY_Q
            "R" -> KEY_R
            "S" -> KEY_S
            "T" -> KEY_T
            "U" -> KEY_U
            "V" -> KEY_V
            "W" -> KEY_W
            "X" -> KEY_X
            "Y" -> KEY_Y
            "Z" -> KEY_Z
            "0" -> KEY_0
            "1" -> KEY_1
            "2" -> KEY_2
            "3" -> KEY_3
            "4" -> KEY_4
            "5" -> KEY_5
            "6" -> KEY_6
            "7" -> KEY_7
            "8" -> KEY_8
            "9" -> KEY_9
            else -> 0
        }
    }
}

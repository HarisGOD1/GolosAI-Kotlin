package su.kamil.dev.golos.system.x11

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA binding for X11 C library functions on Linux.
 */
interface X11Lib : Library {
    companion object {
        val INSTANCE: X11Lib? =
            try {
                if (System.getProperty("os.name").lowercase().contains("linux") &&
                    System.getenv("DISPLAY") != null
                ) {
                    val lib = Native.load("X11", X11Lib::class.java)
                    try {
                        lib.XInitThreads()
                    } catch (_: Throwable) {
                    }
                    lib
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
    }

    fun XInitThreads(): Int

    fun XOpenDisplay(displayName: String?): Pointer?

    fun XCloseDisplay(display: Pointer?): Int

    fun XDefaultRootWindow(display: Pointer?): Long

    fun XStringToKeysym(string: String): Long

    fun XKeysymToKeycode(
        display: Pointer?,
        keysym: Long,
    ): Byte

    fun XGrabKey(
        display: Pointer?,
        keycode: Int,
        modifiers: Int,
        grab_window: Long,
        owner_events: Int,
        pointer_mode: Int,
        keyboard_mode: Int,
    ): Int

    fun XUngrabKey(
        display: Pointer?,
        keycode: Int,
        modifiers: Int,
        grab_window: Long,
    ): Int

    fun interface XErrorHandler : com.sun.jna.Callback {
        fun callback(
            display: Pointer?,
            errorEvent: Pointer?,
        ): Int
    }

    fun XSetErrorHandler(handler: XErrorHandler?): Pointer?

    fun XSync(
        display: Pointer?,
        discard: Boolean,
    ): Int

    fun XNextEvent(
        display: Pointer?,
        event_return: Pointer?,
    ): Int

    fun XPending(display: Pointer?): Int

    fun XPeekEvent(
        display: Pointer?,
        event_return: Pointer?,
    ): Int

    fun XGetInputFocus(
        display: Pointer?,
        focus_return: Pointer?,
        revert_to_return: Pointer?,
    ): Int

    fun XSendEvent(
        display: Pointer?,
        w: Long,
        propagate: Boolean,
        event_mask: Long,
        event_send: Pointer?,
    ): Int

    fun XFlush(display: Pointer?): Int

    fun XQueryKeymap(
        display: Pointer?,
        keys_return: ByteArray,
    ): Int

    fun XkbSetDetectableAutoRepeat(
        display: Pointer?,
        detectable: Boolean,
        supported_rtrn: Pointer?,
    ): Boolean
}

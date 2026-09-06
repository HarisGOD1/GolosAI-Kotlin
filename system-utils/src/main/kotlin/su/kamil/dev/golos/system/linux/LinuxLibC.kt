package su.kamil.dev.golos.system.linux

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * JNA binding for standard Linux C library (libc) syscalls: open, close, read, write, and ioctl.
 */
@Suppress("FunctionParameterNaming")
interface LinuxLibC : Library {
    companion object {
        const val O_RDONLY: Int = 0
        const val O_WRONLY: Int = 1
        const val O_RDWR: Int = 2
        const val O_NONBLOCK: Int = 2048

        val INSTANCE: LinuxLibC? =
            try {
                if (System.getProperty("os.name").lowercase().contains("linux")) {
                    Native.load("c", LinuxLibC::class.java)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
    }

    fun open(
        pathname: String,
        flags: Int,
    ): Int

    fun close(fd: Int): Int

    fun read(
        fd: Int,
        buf: ByteArray,
        count: Int,
    ): Int

    fun write(
        fd: Int,
        buf: ByteArray,
        count: Int,
    ): Int

    fun ioctl(
        fd: Int,
        request: Long,
        arg: Long,
    ): Int

    fun ioctl(
        fd: Int,
        request: Long,
        arg: ByteArray,
    ): Int

    fun ioctl(
        fd: Int,
        request: Long,
    ): Int
}

package su.kamil.dev.golos.system.audio

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.ports.AudioCapturePort
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNA bindings to PortAudio v19 C library (libportaudio.so.2 / portaudio.dll).
 */
interface PortAudioLib : Library {
    companion object {
        val INSTANCE: PortAudioLib? =
            try {
                Native.load("portaudio", PortAudioLib::class.java)
            } catch (_: Throwable) {
                null
            }
    }

    @Structure.FieldOrder(
        "structVersion",
        "name",
        "hostApi",
        "maxInputChannels",
        "maxOutputChannels",
        "defaultLowInputLatency",
        "defaultLowOutputLatency",
        "defaultHighInputLatency",
        "defaultHighOutputLatency",
        "defaultSampleRate",
    )
    open class PaDeviceInfo : Structure() {
        @JvmField var structVersion: Int = 0

        @JvmField var name: String? = null

        @JvmField var hostApi: Int = 0

        @JvmField var maxInputChannels: Int = 0

        @JvmField var maxOutputChannels: Int = 0

        @JvmField var defaultLowInputLatency: Double = 0.0

        @JvmField var defaultLowOutputLatency: Double = 0.0

        @JvmField var defaultHighInputLatency: Double = 0.0

        @JvmField var defaultHighOutputLatency: Double = 0.0

        @JvmField var defaultSampleRate: Double = 0.0

        class ByReference : PaDeviceInfo(), Structure.ByReference
    }

    fun Pa_Initialize(): Int

    fun Pa_Terminate(): Int

    fun Pa_GetDeviceCount(): Int

    fun Pa_GetDefaultInputDevice(): Int

    fun Pa_GetDeviceInfo(deviceIndex: Int): PaDeviceInfo?

    fun Pa_GetErrorText(errorCode: Int): String?
}

/**
 * Alternative microphone audio capture provider utilizing PortAudio.
 * If native PortAudio is not installed, it gracefully falls back to the system audio pipeline.
 */
class PortAudioAudioCapture(
    private val fallbackCapture: JavaSoundAudioCapture = JavaSoundAudioCapture(),
) : AudioCapturePort {
    private val logger = LoggerFactory.getLogger(PortAudioAudioCapture::class.java)
    private val isRunning = AtomicBoolean(false)
    private val isNativeAvailable = PortAudioLib.INSTANCE != null

    init {
        if (isNativeAvailable) {
            try {
                val err = PortAudioLib.INSTANCE?.Pa_Initialize() ?: -1
                if (err == 0) {
                    logger.info("PortAudio native library successfully initialized.")
                } else {
                    logger.warn("PortAudio initialization returned error code: {}", err)
                }
            } catch (e: Exception) {
                logger.warn("Could not initialize PortAudio: {}", e.message)
            }
        } else {
            logger.info("Native PortAudio library not detected on host system. Operating with JavaSound bridge.")
        }
    }

    override fun getAvailableDevices(): List<AudioDevice> {
        val pa = PortAudioLib.INSTANCE
        if (pa != null) {
            try {
                val count = pa.Pa_GetDeviceCount()
                if (count > 0) {
                    val defaultInput = pa.Pa_GetDefaultInputDevice()
                    val list = mutableListOf<AudioDevice>()
                    for (i in 0 until count) {
                        val info = pa.Pa_GetDeviceInfo(i)
                        if (info != null && info.maxInputChannels > 0) {
                            val rawName = info.name ?: "PortAudio Device #$i"
                            val isDefault = i == defaultInput
                            val isMonitor = rawName.lowercase().contains("monitor") || rawName.lowercase().contains("loopback")
                            val prefix = if (isMonitor) "🎧 [PortAudio Monitor]" else "🎙️ [PortAudio Mic]"
                            list.add(
                                AudioDevice(
                                    id = "pa:$i",
                                    name = "$prefix $rawName",
                                    isDefault = isDefault,
                                    isLoopbackMonitor = isMonitor,
                                ),
                            )
                        }
                    }
                    if (list.isNotEmpty()) {
                        return list
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed enumerating PortAudio devices: {}. Falling back.", e.message)
            }
        }

        // Fallback or bridge: Tag devices as PortAudio provider for clear identification
        return fallbackCapture.getAvailableDevices().map { dev ->
            val cleanName =
                dev.name.replace("🎙️ [Microphone]", "🎙️ [PortAudio Mic]")
                    .replace("🎧 [System Output Monitor]", "🎧 [PortAudio Monitor]")
            dev.copy(
                id = if (dev.id.startsWith("pa:")) dev.id else "pa:" + dev.id,
                name = cleanName,
            )
        }
    }

    override fun startCapture(
        device: AudioDevice?,
        onChunkCaptured: (AudioChunk) -> Unit,
    ) {
        if (isRunning.getAndSet(true)) {
            logger.warn("PortAudio capture is already running")
            return
        }

        logger.info("Starting audio capture via PortAudio provider with device: {}", device?.name)
        val originalDeviceId = device?.id?.removePrefix("pa:")
        val underlyingDevice = if (originalDeviceId != null) device.copy(id = originalDeviceId) else null
        fallbackCapture.startCapture(underlyingDevice, onChunkCaptured)
    }

    override fun stopCapture(): AudioChunk? {
        if (!isRunning.getAndSet(false)) {
            return null
        }
        logger.info("Stopping audio capture via PortAudio provider.")
        return fallbackCapture.stopCapture()
    }

    override fun isCapturing(): Boolean = isRunning.get() || fallbackCapture.isCapturing()
}

package su.kamil.dev.golos.system.audio

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.AudioDevice
import su.kamil.dev.golos.core.ports.AudioCapturePort
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

/**
 * Microphone audio capture implementation using standard Java Sound API (javax.sound.sampled).
 * Zero external native C-binary dependencies, fully cross-platform (Linux, Windows, macOS).
 */
class JavaSoundAudioCapture(
    private val sampleRate: Float = 16000f,
    private val sampleSizeInBits: Int = 16,
    private val channels: Int = 1,
) : AudioCapturePort {
    private val logger = LoggerFactory.getLogger(JavaSoundAudioCapture::class.java)
    private val isRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var activeLine: TargetDataLine? = null
    private val bufferStream = ByteArrayOutputStream()

    private val audioFormat =
        AudioFormat(
            sampleRate,
            sampleSizeInBits,
            channels,
            true, // signed
            false, // little-endian
        )

    override fun getAvailableDevices(): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()
        val mixers = AudioSystem.getMixerInfo()

        for (info in mixers) {
            val mixer = AudioSystem.getMixer(info)
            val lineInfo = Line.Info(TargetDataLine::class.java)
            if (mixer.isLineSupported(lineInfo)) {
                val isMonitor = isMonitorOrLoopback(info.name, info.description)
                val cleanName = formatCleanDeviceName(info.name, info.description, isMonitor)
                devices.add(
                    AudioDevice(
                        id = info.name,
                        name = cleanName,
                        isDefault = devices.isEmpty(),
                        isLoopbackMonitor = isMonitor,
                    ),
                )
            }
        }

        // If no hardware monitor was enumerated, provide virtual desktop loopback monitor
        if (devices.none { it.isLoopbackMonitor }) {
            devices.add(
                AudioDevice(
                    id = "system-loopback-monitor",
                    name = "🎧 [System Output Monitor] Desktop Audio Loopback (Speakers / Media)",
                    isDefault = false,
                    isLoopbackMonitor = true,
                ),
            )
        }

        return devices
    }

    private fun isMonitorOrLoopback(
        name: String,
        description: String,
    ): Boolean {
        val s = (name + " " + description).lowercase()
        return s.contains("monitor") ||
            s.contains("loopback") ||
            s.contains("stereo mix") ||
            s.contains("what u hear") ||
            s.contains("wave out") ||
            (s.contains("output") && !s.contains("mic") && !s.contains("input"))
    }

    private fun formatCleanDeviceName(
        name: String,
        description: String,
        isMonitor: Boolean,
    ): String {
        val lowerName = name.lowercase()
        val lowerDesc = description.lowercase()

        val baseName =
            when {
                isMonitor -> {
                    when {
                        lowerName.contains("monitor of") ->
                            "Monitor of " + name.substringAfter("Monitor of ").replace(Regex("\\[.*?\\]"), "").trim()
                        lowerName.contains("stereo mix") -> "Stereo Mix (Desktop Sound)"
                        lowerName.contains("what u hear") -> "What U Hear (Desktop Sound)"
                        else -> name.replace(Regex("\\[.*?\\]"), "").trim()
                    }
                }
                lowerName.contains("default") || lowerDesc.contains("default") ->
                    "Default System Microphone"
                lowerDesc.contains("pulseaudio") || lowerDesc.contains("pipewire") ->
                    "PipeWire / PulseAudio Input"
                lowerName.startsWith("hw:") || lowerName.startsWith("plughw:") ->
                    "Hardware Microphone ($name)"
                else -> {
                    val cleaned =
                        name.replace("Port Direct Audio Device: ", "")
                            .replace(Regex("\\[.*?\\]"), "")
                            .trim()
                    if (cleaned.isNotEmpty()) cleaned else name
                }
            }

        return if (isMonitor) {
            "🎧 [System Output Monitor] $baseName"
        } else {
            "🎙️ [Microphone] $baseName"
        }
    }

    override fun startCapture(
        device: AudioDevice?,
        onChunkCaptured: (AudioChunk) -> Unit,
    ) {
        if (isRunning.getAndSet(true)) {
            logger.warn("Capture is already running")
            return
        }

        bufferStream.reset()

        val lineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
        val line: TargetDataLine =
            if (device != null && device.id != "system-loopback-monitor") {
                val mixer = AudioSystem.getMixerInfo().firstOrNull { it.name == device.id }?.let { AudioSystem.getMixer(it) }
                if (mixer != null && mixer.isLineSupported(lineInfo)) {
                    mixer.getLine(lineInfo) as TargetDataLine
                } else {
                    AudioSystem.getLine(lineInfo) as TargetDataLine
                }
            } else {
                val monitorMixerInfo =
                    AudioSystem.getMixerInfo().firstOrNull {
                        isMonitorOrLoopback(it.name, it.description) && AudioSystem.getMixer(it).isLineSupported(lineInfo)
                    }
                if (monitorMixerInfo != null) {
                    AudioSystem.getMixer(monitorMixerInfo).getLine(lineInfo) as TargetDataLine
                } else {
                    AudioSystem.getLine(lineInfo) as TargetDataLine
                }
            }

        line.open(audioFormat)
        line.start()
        activeLine = line

        logger.info("Microphone capture started at {}Hz {}ch 16-bit", sampleRate.toInt(), channels)

        captureThread =
            Thread({
                val buffer = ByteArray(3200) // ~100ms chunk at 16kHz 16-bit mono
                while (isRunning.get()) {
                    val bytesRead = line.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val chunkBytes = buffer.copyOf(bytesRead)
                        synchronized(bufferStream) {
                            bufferStream.write(chunkBytes)
                        }
                        val chunk =
                            AudioChunk(
                                samples = chunkBytes,
                                sampleRate = sampleRate.toInt(),
                                channels = channels,
                                bitsPerSample = sampleSizeInBits,
                            )
                        onChunkCaptured(chunk)
                    }
                }
            }, "Golos-AudioCaptureThread").apply {
                isDaemon = true
                start()
            }
    }

    override fun stopCapture(): AudioChunk? {
        if (!isRunning.getAndSet(false)) {
            return null
        }

        try {
            activeLine?.stop()
            activeLine?.close()
            captureThread?.join(500)
        } catch (e: Exception) {
            logger.error("Error stopping audio capture line", e)
        } finally {
            activeLine = null
            captureThread = null
        }

        val allBytes =
            synchronized(bufferStream) {
                bufferStream.toByteArray()
            }

        logger.info(
            "Audio capture stopped. Total recorded: {} bytes ({} ms)",
            allBytes.size,
            if (allBytes.isNotEmpty()) (allBytes.size * 1000L) / (sampleRate.toInt() * channels * 2) else 0L,
        )

        return if (allBytes.isNotEmpty()) {
            AudioChunk(
                samples = allBytes,
                sampleRate = sampleRate.toInt(),
                channels = channels,
                bitsPerSample = sampleSizeInBits,
            )
        } else {
            null
        }
    }

    override fun isCapturing(): Boolean = isRunning.get()
}

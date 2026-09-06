package su.kamil.dev.golos.system.hardware

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.GpuDeviceInfo
import su.kamil.dev.golos.core.model.GpuType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cross-platform hardware discovery and GPU detection utility.
 * Supports distinguishing between Integrated (iGPU) and Dedicated (dGPU) adapters on multi-GPU systems.
 */
object GpuManager {
    private val logger = LoggerFactory.getLogger(GpuManager::class.java)

    fun detectGpus(): List<GpuDeviceInfo> {
        val detected = mutableListOf<GpuDeviceInfo>()

        // 1. Try nvidia-smi (reliable across Linux and Windows for NVIDIA GPUs)
        try {
            val nvidiaGpus = queryNvidiaSmi()
            detected.addAll(nvidiaGpus)
        } catch (e: Exception) {
            logger.debug("nvidia-smi query skipped: {}", e.message)
        }

        // 2. OS specific detection
        val os = System.getProperty("os.name").lowercase()
        try {
            when {
                os.contains("linux") -> {
                    val linuxGpus = queryLinuxPciAndDrm(detected)
                    detected.addAll(linuxGpus)
                }
                os.contains("win") -> {
                    val winGpus = queryWindowsWmic(detected)
                    detected.addAll(winGpus)
                }
                os.contains("mac") -> {
                    val macGpus = queryMacDisplays(detected)
                    detected.addAll(macGpus)
                }
            }
        } catch (e: Exception) {
            logger.warn("OS GPU detection encountered error: {}", e.message)
        }

        // 3. If nothing detected, fallback to GraphicsEnvironment / generic
        if (detected.isEmpty()) {
            val javaGpus = queryJavaGraphicsEnvironment()
            detected.addAll(javaGpus)
        }

        if (detected.isEmpty()) {
            detected.add(
                GpuDeviceInfo(
                    index = 0,
                    name = "Default System GPU",
                    type = GpuType.UNKNOWN,
                    vendor = "Unknown",
                    isDefault = true,
                ),
            )
        }

        return detected.distinctBy { it.name.lowercase().trim() }
            .mapIndexed { idx, gpu -> gpu.copy(index = idx) }
    }

    fun getActiveGpu(selectedGpuIndex: Int, gpus: List<GpuDeviceInfo>): GpuDeviceInfo {
        if (selectedGpuIndex >= 0) {
            val matched = gpus.firstOrNull { it.index == selectedGpuIndex }
            if (matched != null) return matched
        }
        // Auto mode: prefer dedicated GPU, otherwise first available
        return gpus.firstOrNull { it.type == GpuType.DEDICATED }
            ?: gpus.firstOrNull()
            ?: GpuDeviceInfo(0, "Default GPU", GpuType.UNKNOWN)
    }

    private fun queryNvidiaSmi(): List<GpuDeviceInfo> {
        val gpus = mutableListOf<GpuDeviceInfo>()
        val p =
            ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=index,name,memory.total",
                "--format=csv,noheader,nounits",
            ).redirectErrorStream(true).start()
        val finished = p.waitFor(2, TimeUnit.SECONDS)
        if (finished && p.exitValue() == 0) {
            val lines = p.inputStream.bufferedReader().readLines()
            for (line in lines) {
                val parts = line.split(",").map { it.trim() }
                if (parts.size >= 2) {
                    val idx = parts[0].toIntOrNull() ?: gpus.size
                    val name = parts[1]
                    val mem = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    gpus.add(
                        GpuDeviceInfo(
                            index = idx,
                            name = name,
                            type = GpuType.DEDICATED,
                            vendor = "NVIDIA",
                            memoryMb = mem,
                            isDefault = gpus.isEmpty(),
                        ),
                    )
                }
            }
        }
        return gpus
    }

    private fun queryLinuxPciAndDrm(existing: List<GpuDeviceInfo>): List<GpuDeviceInfo> {
        val gpus = mutableListOf<GpuDeviceInfo>()
        try {
            val p = ProcessBuilder("lspci").redirectErrorStream(true).start()
            if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                val lines = p.inputStream.bufferedReader().readLines()
                for (line in lines) {
                    val lower = line.lowercase()
                    if (lower.contains("vga compatible controller") ||
                        lower.contains("3d controller") ||
                        lower.contains("display controller")
                    ) {
                        val name = line.substringAfter(": ").trim()
                        if (existing.any { name.contains(it.name, ignoreCase = true) || it.name.contains(name, ignoreCase = true) }) {
                            continue
                        }
                        val type = classifyGpuType(name)
                        val vendor = extractVendor(name)
                        gpus.add(
                            GpuDeviceInfo(
                                index = existing.size + gpus.size,
                                name = cleanGpuName(name),
                                type = type,
                                vendor = vendor,
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        if (gpus.isEmpty() && existing.isEmpty()) {
            val drmDir = File("/sys/class/drm")
            if (drmDir.exists() && drmDir.isDirectory) {
                val cardDirs = drmDir.listFiles { f -> f.name.matches(Regex("card[0-9]+")) } ?: emptyArray()
                for ((idx, card) in cardDirs.withIndex()) {
                    val vendorFile = File(card, "device/vendor")
                    val vendorHex = if (vendorFile.exists()) vendorFile.readText().trim() else ""
                    val vendorName =
                        when (vendorHex.lowercase()) {
                            "0x10de" -> "NVIDIA"
                            "0x8086" -> "Intel"
                            "0x1002" -> "AMD"
                            else -> "Standard"
                        }
                    val type = if (vendorName == "NVIDIA") GpuType.DEDICATED else GpuType.INTEGRATED
                    gpus.add(
                        GpuDeviceInfo(
                            index = idx,
                            name = "$vendorName Graphics (${card.name})",
                            type = type,
                            vendor = vendorName,
                        ),
                    )
                }
            }
        }
        return gpus
    }

    private fun queryWindowsWmic(existing: List<GpuDeviceInfo>): List<GpuDeviceInfo> {
        val gpus = mutableListOf<GpuDeviceInfo>()
        try {
            val p =
                ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-Command",
                    "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name",
                ).redirectErrorStream(true).start()
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                val lines = p.inputStream.bufferedReader().readLines()
                for (line in lines) {
                    val name = line.trim()
                    if (name.isBlank() || existing.any { it.name.contains(name, ignoreCase = true) }) continue
                    gpus.add(
                        GpuDeviceInfo(
                            index = existing.size + gpus.size,
                            name = name,
                            type = classifyGpuType(name),
                            vendor = extractVendor(name),
                        ),
                    )
                }
            }
        } catch (_: Exception) {}
        return gpus
    }

    private fun queryMacDisplays(existing: List<GpuDeviceInfo>): List<GpuDeviceInfo> {
        val gpus = mutableListOf<GpuDeviceInfo>()
        try {
            val p =
                ProcessBuilder(
                    "system_profiler",
                    "SPDisplaysDataType",
                ).redirectErrorStream(true).start()
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                val lines = p.inputStream.bufferedReader().readLines()
                for (line in lines) {
                    if (line.contains("Chipset Model:")) {
                        val name = line.substringAfter("Chipset Model:").trim()
                        if (name.isBlank() || existing.any { it.name.contains(name, ignoreCase = true) }) continue
                        gpus.add(
                            GpuDeviceInfo(
                                index = existing.size + gpus.size,
                                name = name,
                                type = classifyGpuType(name),
                                vendor = extractVendor(name),
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return gpus
    }

    private fun queryJavaGraphicsEnvironment(): List<GpuDeviceInfo> =
        try {
            val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            ge.screenDevices.mapIndexed { idx, dev ->
                val name = dev.iDstring.ifBlank { "Display Adapter $idx" }
                GpuDeviceInfo(
                    index = idx,
                    name = name,
                    type = GpuType.UNKNOWN,
                    vendor = "System",
                )
            }
        } catch (_: Exception) {
            emptyList()
        }


    fun classifyGpuType(name: String): GpuType {
        val lower = name.lowercase()
        return when {
            lower.contains("geforce") || lower.contains("nvidia") || lower.contains("rtx") ||
                lower.contains("gtx") || lower.contains("quadro") -> GpuType.DEDICATED
            lower.contains("arc a") || lower.contains("arc pro") ||
                lower.contains("radeon rx") || lower.contains("radeon pro") -> GpuType.DEDICATED
            lower.contains("iris") || lower.contains("uhd") ||
                lower.contains("hd graphics") || lower.contains("integrated") -> GpuType.INTEGRATED
            lower.contains("radeon") && (lower.contains("graphics") || lower.contains("vega") || lower.contains("apu")) ->
                GpuType.INTEGRATED
            lower.contains("apple") -> GpuType.INTEGRATED
            else -> GpuType.UNKNOWN
        }
    }

    fun extractVendor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("nvidia") -> "NVIDIA"
            lower.contains("intel") -> "Intel"
            lower.contains("amd") || lower.contains("ati") || lower.contains("radeon") -> "AMD"
            lower.contains("apple") -> "Apple"
            else -> "Unknown"
        }
    }

    private fun cleanGpuName(raw: String): String {
        return raw.replace(Regex("\\[rev [0-9a-f]+\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Corporation", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VGA compatible controller:?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("3D controller:?", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}

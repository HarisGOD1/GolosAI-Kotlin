package su.kamil.dev.golos.system.hardware

import org.slf4j.LoggerFactory
import su.kamil.dev.golos.core.model.GpuAvailabilityStatus
import su.kamil.dev.golos.core.model.GpuDeviceInfo
import su.kamil.dev.golos.core.model.GpuResourceAllocation
import su.kamil.dev.golos.core.model.GpuResourceUsage
import su.kamil.dev.golos.core.model.GpuType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cross-platform hardware discovery and GPU detection utility.
 * Supports distinguishing between Integrated (iGPU) and Dedicated (dGPU) adapters on multi-GPU systems.
 */
object GpuManager {
    private val logger = LoggerFactory.getLogger(GpuManager::class.java)

    private const val DEFAULT_GPU_LAYERS = 99
    private const val MIN_GPU_LAYERS = 4
    private const val BYTES_PER_MB = 1024L * 1024L
    private const val MIN_REQUIRED_VRAM_MB = 400L
    private const val MIN_USABLE_VRAM_MB = 250L
    private const val DEFAULT_MODEL_ESTIMATE_MB = 140L
    private const val TIMEOUT_SECONDS = 2L

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

    fun getActiveGpu(
        selectedGpuIndex: Int,
        gpus: List<GpuDeviceInfo>,
    ): GpuDeviceInfo {
        if (selectedGpuIndex >= 0) {
            val matched = gpus.firstOrNull { it.index == selectedGpuIndex }
            if (matched != null) return matched
        }
        // Auto mode: prefer dedicated GPU, otherwise first available
        return gpus.firstOrNull { it.type == GpuType.DEDICATED }
            ?: gpus.firstOrNull()
            ?: GpuDeviceInfo(0, "Default GPU", GpuType.UNKNOWN)
    }

    @Suppress("TooGenericExceptionCaught")
    fun checkGpuAvailability(selectedGpuIndex: Int = -1): GpuAvailabilityStatus {
        val gpus = detectGpus()
        val active = getActiveGpu(selectedGpuIndex, gpus)
        val os = System.getProperty("os.name").lowercase()
        val issues = mutableListOf<String>()
        var provider = "CPU"
        var isAvailable = false

        val nvidiaSmiPresent = isNvidiaSmiWorking()
        if (nvidiaSmiPresent) {
            provider = "CUDA"
            isAvailable = true
            if (os.contains("linux")) {
                val devNodes = listOf(File("/dev/nvidiactl"), File("/dev/nvidia0"))
                for (node in devNodes) {
                    if (node.exists() && (!node.canRead() || !node.canWrite())) {
                        issues.add("Permission denied accessing ${node.name}. Add user to video or render group.")
                    }
                }
            }
        } else if (os.contains("mac")) {
            provider = "Metal"
            isAvailable = true
        } else if (os.contains("linux")) {
            val renderNodes = File("/dev/dri").listFiles { f -> f.name.startsWith("renderD") } ?: emptyArray()
            val cardNodes = File("/dev/dri").listFiles { f -> f.name.startsWith("card") } ?: emptyArray()
            val allDri = renderNodes + cardNodes
            if (allDri.isNotEmpty()) {
                val hasReadableDri = allDri.any { it.canRead() }
                if (!hasReadableDri) {
                    issues.add("Permission denied accessing /dev/dri device nodes. Add user to video or render group.")
                }
            }
            val icdDir = File("/usr/share/vulkan/icd.d")
            val hasVulkanIcd = icdDir.exists() && (icdDir.listFiles()?.isNotEmpty() == true)
            if (active.type == GpuType.DEDICATED || hasVulkanIcd) {
                provider = if (active.vendor.contains("AMD", ignoreCase = true)) "ROCm/Vulkan" else "Vulkan"
                isAvailable = issues.isEmpty() && active.type != GpuType.UNKNOWN
            }
        } else if (os.contains("win")) {
            if (active.type != GpuType.UNKNOWN) {
                provider = "DirectML"
                isAvailable = true
            }
        }

        val resources = queryGpuResources(active.index)

        val statusMsg =
            when {
                !isAvailable && issues.isNotEmpty() -> issues.joinToString("; ")
                !isAvailable -> "No hardware GPU acceleration detected (running on CPU)"
                issues.isNotEmpty() -> "$provider active with warnings: ${issues.joinToString("; ")}"
                resources.totalMemoryMb > 0L ->
                    "$provider (${active.name}, ${resources.freeMemoryMb}/${resources.totalMemoryMb} MB VRAM)"
                else -> "$provider active (${active.name})"
            }

        return GpuAvailabilityStatus(
            isAvailable = isAvailable && issues.isEmpty(),
            provider = if (isAvailable) provider else "CPU",
            activeGpu = active,
            resourceUsage = resources,
            issues = issues,
            statusMessage = statusMsg,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun queryGpuResources(selectedGpuIndex: Int = -1): GpuResourceUsage {
        // 1. Try nvidia-smi with memory and utilization metrics
        try {
            val cmd =
                mutableListOf(
                    "nvidia-smi",
                    "--query-gpu=memory.total,memory.used,memory.free,utilization.gpu,temperature.gpu",
                    "--format=csv,noheader,nounits",
                )
            if (selectedGpuIndex >= 0) {
                cmd.add(1, "-i")
                cmd.add(2, selectedGpuIndex.toString())
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0) {
                val line = p.inputStream.bufferedReader().readLine()
                if (!line.isNullOrBlank()) {
                    val parts = line.split(",").map { it.trim() }
                    val total = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    val used = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val free = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val util = parts.getOrNull(3)?.toIntOrNull() ?: -1
                    val temp = parts.getOrNull(4)?.toIntOrNull() ?: -1
                    if (total > 0L) {
                        return GpuResourceUsage(
                            totalMemoryMb = total,
                            usedMemoryMb = used,
                            freeMemoryMb = free,
                            utilizationPercent = util,
                            temperatureC = temp,
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Try Linux sysfs DRM mem_info_vram for AMD/Intel
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("linux")) {
            try {
                val drmDir = File("/sys/class/drm")
                val cardDirs = drmDir.listFiles { f -> f.name.matches(Regex("card[0-9]+")) } ?: emptyArray()
                for (card in cardDirs) {
                    val totalFile = File(card, "device/mem_info_vram_total")
                    val usedFile = File(card, "device/mem_info_vram_used")
                    val busyFile = File(card, "device/gpu_busy_percent")
                    if (totalFile.exists() && totalFile.canRead()) {
                        val totalBytes = totalFile.readText().trim().toLongOrNull() ?: 0L
                        val usedBytes = if (usedFile.exists()) usedFile.readText().trim().toLongOrNull() ?: 0L else 0L
                        val totalMb = totalBytes / BYTES_PER_MB
                        val usedMb = usedBytes / BYTES_PER_MB
                        val freeMb = maxOf(0L, totalMb - usedMb)
                        val busy = if (busyFile.exists()) busyFile.readText().trim().toIntOrNull() ?: -1 else -1
                        if (totalMb > 0L) {
                            return GpuResourceUsage(
                                totalMemoryMb = totalMb,
                                usedMemoryMb = usedMb,
                                freeMemoryMb = freeMb,
                                utilizationPercent = busy,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback to GpuDeviceInfo memoryMb if detected
        val gpus = detectGpus()
        val active = getActiveGpu(selectedGpuIndex, gpus)
        if (active.memoryMb > 0L) {
            return GpuResourceUsage(
                totalMemoryMb = active.memoryMb,
                usedMemoryMb = 0L,
                freeMemoryMb = active.memoryMb,
            )
        }

        return GpuResourceUsage()
    }

    fun requestGpuResources(
        modelFile: File?,
        selectedGpuIndex: Int = -1,
    ): GpuResourceAllocation {
        val estimatedSizeMb =
            if (modelFile != null && modelFile.exists() && modelFile.length() > 0) {
                modelFile.length() / BYTES_PER_MB
            } else {
                DEFAULT_MODEL_ESTIMATE_MB
            }
        val requiredVramMb = maxOf(MIN_REQUIRED_VRAM_MB, estimatedSizeMb * 2)

        val availability = checkGpuAvailability(selectedGpuIndex)
        if (!availability.isAvailable) {
            return GpuResourceAllocation(
                granted = false,
                allocatedLayers = 0,
                fallbackToCpu = true,
                requiredVramMb = requiredVramMb,
                availableVramMb = 0L,
                message = "GPU not available (${availability.statusMessage}). Falling back to CPU.",
            )
        }

        val freeVramMb = availability.resourceUsage.freeMemoryMb
        if (freeVramMb <= 0L) {
            return GpuResourceAllocation(
                granted = true,
                allocatedLayers = DEFAULT_GPU_LAYERS,
                fallbackToCpu = false,
                requiredVramMb = requiredVramMb,
                availableVramMb = 0L,
                message = "GPU compute granted (unified or shared memory). Offloading all layers.",
            )
        }

        if (freeVramMb >= requiredVramMb) {
            return GpuResourceAllocation(
                granted = true,
                allocatedLayers = DEFAULT_GPU_LAYERS,
                fallbackToCpu = false,
                requiredVramMb = requiredVramMb,
                availableVramMb = freeVramMb,
                message =
                    "GPU resources granted: $DEFAULT_GPU_LAYERS layers offloaded " +
                        "($requiredVramMb MB required, $freeVramMb MB available).",
            )
        }

        if (freeVramMb >= MIN_USABLE_VRAM_MB) {
            val partialRatio = freeVramMb.toDouble() / requiredVramMb.toDouble()
            val layers = maxOf(MIN_GPU_LAYERS, minOf(DEFAULT_GPU_LAYERS, (partialRatio * DEFAULT_GPU_LAYERS).toInt()))
            return GpuResourceAllocation(
                granted = true,
                allocatedLayers = layers,
                fallbackToCpu = false,
                requiredVramMb = requiredVramMb,
                availableVramMb = freeVramMb,
                message =
                    "Partial GPU resources granted: $layers/$DEFAULT_GPU_LAYERS layers offloaded " +
                        "($freeVramMb MB free of $requiredVramMb MB required).",
            )
        }

        return GpuResourceAllocation(
            granted = false,
            allocatedLayers = 0,
            fallbackToCpu = true,
            requiredVramMb = requiredVramMb,
            availableVramMb = freeVramMb,
            message = "Insufficient GPU VRAM ($freeVramMb MB free, $requiredVramMb MB required). Falling back to CPU.",
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun isNvidiaSmiWorking(): Boolean =
        try {
            val p = ProcessBuilder("nvidia-smi").redirectErrorStream(true).start()
            p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
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
        } catch (_: Exception) {
        }

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
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
        }
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

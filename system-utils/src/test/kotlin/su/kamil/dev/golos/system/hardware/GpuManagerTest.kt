package su.kamil.dev.golos.system.hardware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.GpuDeviceInfo
import su.kamil.dev.golos.core.model.GpuType

class GpuManagerTest {
    @Test
    fun `test classifyGpuType identifies dedicated GPUs`() {
        assertEquals(GpuType.DEDICATED, GpuManager.classifyGpuType("NVIDIA GeForce RTX 3060 Mobile"))
        assertEquals(GpuType.DEDICATED, GpuManager.classifyGpuType("GeForce GTX 1650 Ti"))
        assertEquals(GpuType.DEDICATED, GpuManager.classifyGpuType("NVIDIA Quadro P1000"))
        assertEquals(GpuType.DEDICATED, GpuManager.classifyGpuType("AMD Radeon RX 6700M"))
        assertEquals(GpuType.DEDICATED, GpuManager.classifyGpuType("Intel Arc A770"))
    }

    @Test
    fun `test classifyGpuType identifies integrated GPUs`() {
        assertEquals(GpuType.INTEGRATED, GpuManager.classifyGpuType("Intel Iris Xe Graphics"))
        assertEquals(GpuType.INTEGRATED, GpuManager.classifyGpuType("Intel UHD Graphics 630"))
        assertEquals(GpuType.INTEGRATED, GpuManager.classifyGpuType("Intel HD Graphics 4000"))
        assertEquals(GpuType.INTEGRATED, GpuManager.classifyGpuType("AMD Radeon Graphics (Renoir APU)"))
        assertEquals(GpuType.INTEGRATED, GpuManager.classifyGpuType("Apple M2 Pro"))
    }

    @Test
    fun `test extractVendor identifies major GPU vendors`() {
        assertEquals("NVIDIA", GpuManager.extractVendor("NVIDIA GeForce RTX 4080"))
        assertEquals("Intel", GpuManager.extractVendor("Intel Iris Xe"))
        assertEquals("AMD", GpuManager.extractVendor("AMD Radeon RX 7900"))
        assertEquals("Apple", GpuManager.extractVendor("Apple M3 Max"))
        assertEquals("Unknown", GpuManager.extractVendor("Generic Display Adapter"))
    }

    @Test
    fun `test getActiveGpu auto prefers dedicated GPU on dual-GPU laptop`() {
        val iGpu =
            GpuDeviceInfo(
                index = 0,
                name = "Intel Iris Xe Graphics",
                type = GpuType.INTEGRATED,
                vendor = "Intel",
            )
        val dGpu =
            GpuDeviceInfo(
                index = 1,
                name = "NVIDIA GeForce RTX 3060",
                type = GpuType.DEDICATED,
                vendor = "NVIDIA",
            )
        val gpus = listOf(iGpu, dGpu)

        // Auto mode (-1) must prefer dedicated GPU
        val active = GpuManager.getActiveGpu(-1, gpus)
        assertEquals(1, active.index)
        assertEquals("NVIDIA GeForce RTX 3060", active.name)
        assertEquals(GpuType.DEDICATED, active.type)
    }

    @Test
    fun `test getActiveGpu falls back to integrated if no dedicated GPU present`() {
        val iGpu =
            GpuDeviceInfo(
                index = 0,
                name = "Intel UHD Graphics 620",
                type = GpuType.INTEGRATED,
                vendor = "Intel",
            )
        val active = GpuManager.getActiveGpu(-1, listOf(iGpu))
        assertEquals(0, active.index)
        assertEquals("Intel UHD Graphics 620", active.name)
    }

    @Test
    fun `test getActiveGpu respects explicit user selection index`() {
        val iGpu =
            GpuDeviceInfo(
                index = 0,
                name = "Intel Iris Xe",
                type = GpuType.INTEGRATED,
                vendor = "Intel",
            )
        val dGpu =
            GpuDeviceInfo(
                index = 1,
                name = "NVIDIA RTX 4070",
                type = GpuType.DEDICATED,
                vendor = "NVIDIA",
            )
        val gpus = listOf(iGpu, dGpu)

        // Explicitly choose iGPU (index 0)
        val selected = GpuManager.getActiveGpu(0, gpus)
        assertEquals(0, selected.index)
        assertEquals("Intel Iris Xe", selected.name)
    }

    @Test
    fun `test detectGpus returns non-empty list on current host`() {
        val detected = GpuManager.detectGpus()
        assertNotNull(detected)
        assertTrue(detected.isNotEmpty())
        assertTrue(detected.first().name.isNotBlank())
    }

    @Test
    fun `test checkGpuAvailability returns valid status and provider`() {
        val status = GpuManager.checkGpuAvailability()
        assertNotNull(status)
        assertNotNull(status.provider)
        assertNotNull(status.resourceUsage)
        assertTrue(status.statusMessage.isNotBlank())
    }

    @Test
    fun `test queryGpuResources returns valid usage structure`() {
        val usage = GpuManager.queryGpuResources()
        assertNotNull(usage)
        assertTrue(usage.totalMemoryMb >= 0L)
        assertTrue(usage.freeMemoryMb >= 0L)
        assertTrue(usage.usedMemoryMb >= 0L)
    }

    @Test
    fun `test requestGpuResources handles nonexistent or null model gracefully`() {
        val allocation = GpuManager.requestGpuResources(null)
        assertNotNull(allocation)
        assertTrue(allocation.requiredVramMb >= 400L)
        assertTrue(allocation.allocatedLayers >= 0)
        assertTrue(allocation.message.isNotBlank())
    }
}

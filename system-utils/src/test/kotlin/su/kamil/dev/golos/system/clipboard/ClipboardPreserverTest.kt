package su.kamil.dev.golos.system.clipboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File

class ClipboardPreserverTest {
    @Test
    fun `test text clipboard snapshot and restoration preserves exact multiline content - Criteria L-01 and L-07`() {
        val clipboard = Clipboard("TestTextClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { clipboard })

        val multilineOriginal = "First line\r\nSecond line with tabs:\t\t123\nThird line   with spaces   \n\nFinal."
        clipboard.setContents(StringSelection(multilineOriginal), null)

        // Snapshot original content
        val snapshot = preserver.capture()
        assertTrue(snapshot is ClipboardSnapshot.Text)
        assertEquals(multilineOriginal, (snapshot as ClipboardSnapshot.Text).text)

        // Simulate transient dictation overwrite
        clipboard.setContents(StringSelection("Transient dictation text"), null)
        assertEquals("Transient dictation text", clipboard.getData(DataFlavor.stringFlavor))

        // Restore snapshot
        val restored = preserver.restore(snapshot)
        assertTrue(restored)

        // Verify exact character-by-character restoration
        val restoredText = clipboard.getData(DataFlavor.stringFlavor) as String
        assertEquals(multilineOriginal, restoredText)
    }

    @Test
    fun `test empty clipboard remains empty after capture and restoration - Criterion L-04`() {
        val clipboard = Clipboard("TestEmptyClipboard")
        clipboard.setContents(EmptyTransferable, EmptyTransferable)
        val preserver = ClipboardPreserver(clipboardSupplier = { clipboard })

        val snapshot = preserver.capture()
        assertTrue(snapshot is ClipboardSnapshot.Empty)

        // Simulate dictation overwrite
        clipboard.setContents(StringSelection("Temporary speech"), null)
        assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))

        // Restore empty snapshot
        val restored = preserver.restore(snapshot)
        assertTrue(restored)

        // Verify clipboard does not contain valid text
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
    }

    @Test
    fun `test image clipboard snapshot and restoration preserves image data - Criterion L-02`() {
        val clipboard = Clipboard("TestImageClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { clipboard })

        val originalImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        originalImage.setRGB(0, 0, 0x00FF00) // green pixel

        val imageTransferable = PreservedTransferable(mapOf(DataFlavor.imageFlavor to originalImage))
        clipboard.setContents(imageTransferable, imageTransferable)

        // Capture snapshot
        val snapshot = preserver.capture()
        assertTrue(snapshot is ClipboardSnapshot.ImageContent)

        // Overwrite clipboard with dictation string
        clipboard.setContents(StringSelection("Spoken text over image"), null)
        assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
        assertFalse(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))

        // Restore original image snapshot
        val restored = preserver.restore(snapshot)
        assertTrue(restored)

        // Verify image flavor is restored and accessible
        assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
        val restoredImage = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
        assertNotNull(restoredImage)
    }

    @Test
    fun `test file list clipboard snapshot and restoration preserves files`() {
        val clipboard = Clipboard("TestFileClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { clipboard })

        val files = listOf(File("/tmp/sample1.txt"), File("/tmp/sample2.wav"))
        val fileTransferable = PreservedTransferable(mapOf(DataFlavor.javaFileListFlavor to files))
        clipboard.setContents(fileTransferable, fileTransferable)

        val snapshot = preserver.capture()
        assertTrue(snapshot is ClipboardSnapshot.FileList)
        assertEquals(2, (snapshot as ClipboardSnapshot.FileList).files.size)

        // Overwrite
        clipboard.setContents(StringSelection("Some text"), null)

        // Restore
        val restored = preserver.restore(snapshot)
        assertTrue(restored)
        assertTrue(clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor))
    }

    @Test
    fun `test clipboard access lock retry handling - Criterion L-06`() {
        var attempts = 0
        val mockSupplier: () -> Clipboard? = {
            attempts++
            if (attempts < 3) {
                error("Clipboard locked by external process")
            }
            Clipboard("RetrySuccessClipboard")
        }

        val preserver = ClipboardPreserver(
            clipboardSupplier = mockSupplier,
            maxRetries = 4,
            retryDelayMs = 5,
        )

        val snapshot = preserver.capture()
        assertEquals(ClipboardSnapshot.Empty, snapshot)
        assertTrue(attempts >= 3)
    }
}

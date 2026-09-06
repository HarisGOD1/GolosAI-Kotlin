package su.kamil.dev.golos.system.clipboard

import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/**
 * Sealed hierarchy capturing distinct clipboard content types for accurate
 * preservation and restoration (Criteria L-01..L-08).
 */
sealed class ClipboardSnapshot {
    object Empty : ClipboardSnapshot()

    data class Text(val text: String) : ClipboardSnapshot()

    data class ImageContent(val image: Image) : ClipboardSnapshot()

    data class FileList(val files: List<File>) : ClipboardSnapshot()

    data class RichTransferable(val transferable: Transferable) : ClipboardSnapshot()
}

/**
 * In-memory Transferable implementation for restoring rich content
 * (such as images and files) safely (Criteria L-02, L-07).
 */
class PreservedTransferable(
    private val flavorDataMap: Map<DataFlavor, Any>,
) : Transferable, ClipboardOwner {
    override fun getTransferDataFlavors(): Array<DataFlavor> = flavorDataMap.keys.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavorDataMap.containsKey(flavor)

    override fun getTransferData(flavor: DataFlavor): Any {
        return flavorDataMap[flavor] ?: throw UnsupportedFlavorException(flavor)
    }

    override fun lostOwnership(
        clipboard: Clipboard?,
        contents: Transferable?,
    ) {
        // No action required on loss of clipboard ownership
    }
}

/**
 * Empty Transferable used to preserve clipboard emptiness (Criterion L-04).
 */
object EmptyTransferable : Transferable, ClipboardOwner {
    override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false

    override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)

    override fun lostOwnership(
        clipboard: Clipboard?,
        contents: Transferable?,
    ) {
        // No action required on loss of clipboard ownership
    }
}

/**
 * Manages clipboard snapshotting, atomic restoration, access denial retries,
 * and emergency cleanup (Criteria L-01..L-08).
 */
@Suppress("TooGenericExceptionCaught")
class ClipboardPreserver(
    private val clipboardSupplier: () -> Clipboard? = {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                headlessClipboard
            } else {
                Toolkit.getDefaultToolkit().systemClipboard
            }
        } catch (_: Throwable) {
            headlessClipboard
        }
    },
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    private val logger = LoggerFactory.getLogger(ClipboardPreserver::class.java)

    /**
     * Eagerly captures current clipboard contents before modification (Criteria L-01, L-02, L-04, L-07).
     */
    fun capture(): ClipboardSnapshot {
        return tryWithRetry("capture") {
            val clipboard = clipboardSupplier() ?: return@tryWithRetry ClipboardSnapshot.Empty
            val contents = clipboard.getContents(null) ?: return@tryWithRetry ClipboardSnapshot.Empty

            // 1. Check for Image content first (Criterion L-02)
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val img = contents.getTransferData(DataFlavor.imageFlavor) as? Image
                if (img != null) {
                    logger.debug("Captured clipboard image content (Criterion L-02).")
                    return@tryWithRetry ClipboardSnapshot.ImageContent(img)
                }
            }

            // 2. Check for File list
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                if (!files.isNullOrEmpty()) {
                    logger.debug("Captured clipboard file list ({} items).", files.size)
                    return@tryWithRetry ClipboardSnapshot.FileList(files)
                }
            }

            // 3. Check for Text content (Criteria L-01, L-07)
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val str = contents.getTransferData(DataFlavor.stringFlavor) as? String
                if (str != null) {
                    logger.debug("Captured clipboard text content (length: {}) (Criteria L-01, L-07).", str.length)
                    return@tryWithRetry ClipboardSnapshot.Text(str)
                }
            }

            ClipboardSnapshot.Empty
        } ?: ClipboardSnapshot.Empty
    }

    /**
     * Restores a previously captured clipboard snapshot (Criteria L-01, L-02, L-04, L-06, L-07, L-08).
     */
    fun restore(snapshot: ClipboardSnapshot): Boolean {
        val success =
            tryWithRetry("restore") {
                val clipboard = clipboardSupplier() ?: return@tryWithRetry false
                when (snapshot) {
                    is ClipboardSnapshot.Empty -> {
                        // Empty buffer remains empty (Criterion L-04)
                        clipboard.setContents(EmptyTransferable, EmptyTransferable)
                        logger.debug("Restored empty clipboard state (Criterion L-04).")
                        true
                    }
                    is ClipboardSnapshot.Text -> {
                        // Multiline character-by-character exact restoration (Criterion L-07)
                        clipboard.setContents(StringSelection(snapshot.text), null)
                        logger.debug(
                            "Restored clipboard text content (length: {}) (Criteria L-01, L-07).",
                            snapshot.text.length,
                        )
                        true
                    }
                    is ClipboardSnapshot.ImageContent -> {
                        // Image buffer restoration (Criterion L-02)
                        val transferable = PreservedTransferable(mapOf(DataFlavor.imageFlavor to snapshot.image))
                        clipboard.setContents(transferable, transferable)
                        logger.debug("Restored clipboard image content (Criterion L-02).")
                        true
                    }
                    is ClipboardSnapshot.FileList -> {
                        val transferable = PreservedTransferable(mapOf(DataFlavor.javaFileListFlavor to snapshot.files))
                        clipboard.setContents(transferable, transferable)
                        logger.debug("Restored clipboard file list.")
                        true
                    }
                    is ClipboardSnapshot.RichTransferable -> {
                        clipboard.setContents(snapshot.transferable, null)
                        logger.debug("Restored rich transferable.")
                        true
                    }
                }
            }
        return success == true
    }

    /**
     * Retries clipboard operation in case of lock or access denial (Criterion L-06).
     */
    private fun <T> tryWithRetry(
        actionName: String,
        block: () -> T,
    ): T? {
        var attempts = 0
        var result: T? = null
        var success = false
        while (attempts < maxRetries && !success) {
            try {
                result = block()
                success = true
            } catch (e: IllegalStateException) {
                attempts++
                logger.warn(
                    "Clipboard access denied/locked during {} (attempt {}/{}): {}",
                    actionName,
                    attempts,
                    maxRetries,
                    e.message,
                )
                if (attempts < maxRetries) {
                    Thread.sleep(retryDelayMs)
                }
            } catch (e: Exception) {
                logger.warn("Error during clipboard {}: {}", actionName, e.message)
                break
            }
        }
        if (!success) {
            logger.error("Clipboard access failed after {} attempts for action: {}", maxRetries, actionName)
        }
        return result
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_RETRY_DELAY_MS = 25L

        /** Fallback clipboard instance usable in headless testing or CI environments. */
        val headlessClipboard: Clipboard by lazy { Clipboard("GolosHeadlessClipboard") }
    }
}

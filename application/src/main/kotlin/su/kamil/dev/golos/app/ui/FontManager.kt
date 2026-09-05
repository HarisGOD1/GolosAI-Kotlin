package su.kamil.dev.golos.app.ui

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.InputStream
import javax.swing.UIManager

/**
 * Font manager that loads and registers the MIT-licensed Hack font family
 * and applies larger font sizes (13-15pt) across the Swing UI.
 */
object FontManager {
    private val logger = LoggerFactory.getLogger(FontManager::class.java)

    const val DEFAULT_SIZE = 14f
    const val SMALL_SIZE = 12f
    const val TITLE_SIZE = 15f
    const val INDICATOR_SIZE = 13f

    val regularFont: Font
    val boldFont: Font

    init {
        var reg: Font? = null
        var bld: Font? = null

        try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            loadFontFromResource("/fonts/Hack-Regular.ttf")?.let {
                reg = it
                ge.registerFont(it)
            }
            loadFontFromResource("/fonts/Hack-Bold.ttf")?.let {
                bld = it
                ge.registerFont(it)
            }
            logger.info("Loaded MIT-licensed Hack font successfully.")
        } catch (e: Exception) {
            logger.warn("Could not load Hack font from resources; falling back to system fonts: {}", e.message)
        }

        regularFont = reg ?: Font(Font.SANS_SERIF, Font.PLAIN, DEFAULT_SIZE.toInt())
        boldFont = bld ?: Font(Font.SANS_SERIF, Font.BOLD, DEFAULT_SIZE.toInt())
    }

    private fun loadFontFromResource(path: String): Font? {
        val stream: InputStream? = FontManager::class.java.getResourceAsStream(path)
        if (stream == null) {
            logger.warn("Font resource not found: {}", path)
            return null
        }
        return stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }

    fun regular(size: Float = DEFAULT_SIZE): Font = regularFont.deriveFont(size)

    fun bold(size: Float = DEFAULT_SIZE): Font = boldFont.deriveFont(size)

    /**
     * Installs Hack font into Swing UIManager defaults for labels, buttons,
     * text fields, menus, combo boxes, tabbed panes, and tables.
     */
    fun installGlobalSwingDefaults(baseSize: Float = DEFAULT_SIZE) {
        val baseFont = regular(baseSize)
        val boldBaseFont = bold(baseSize)

        val fontKeys =
            listOf(
                "Label.font",
                "Button.font",
                "ToggleButton.font",
                "RadioButton.font",
                "CheckBox.font",
                "ComboBox.font",
                "TextField.font",
                "PasswordField.font",
                "TextArea.font",
                "TextPane.font",
                "EditorPane.font",
                "Menu.font",
                "MenuItem.font",
                "PopupMenu.font",
                "ToolTip.font",
                "List.font",
                "Table.font",
                "TableHeader.font",
                "ProgressBar.font",
            )

        for (key in fontKeys) {
            UIManager.put(key, baseFont)
        }
        UIManager.put("TabbedPane.font", boldBaseFont)
        UIManager.put("TitledBorder.font", boldBaseFont)
    }
}

package su.kamil.dev.golos.app.ui

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.InputStream
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource

/**
 * Font manager providing:
 * - Clean, proportional SansSerif font for UI components (labels, buttons, tabs, forms),
 *   guaranteeing multi-lingual Unicode glyph support across X11/Linux, Windows, and macOS (Cyrillic, CJK, Arabic, etc.).
 * - MIT-licensed Hack monospace font for hotkey badges, transcripts, logs, and technical metrics.
 */
object FontManager {
    private val logger = LoggerFactory.getLogger(FontManager::class.java)

    const val DEFAULT_SIZE = 13f
    const val SMALL_SIZE = 11.5f
    const val TITLE_SIZE = 14f
    const val INDICATOR_SIZE = 12f

    val hackRegularFont: Font
    val hackBoldFont: Font

    @Volatile
    var currentFontFamily: String = Font.SANS_SERIF
        private set

    val uiRegularFont: Font get() = regular(DEFAULT_SIZE)
    val uiBoldFont: Font get() = bold(DEFAULT_SIZE)

    val regularFont: Font get() = uiRegularFont
    val boldFont: Font get() = uiBoldFont

    private val availableFamilies: Set<String> by lazy {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

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
            logger.warn("Could not load Hack font from resources: {}", e.message)
        }

        hackRegularFont = (reg ?: Font(Font.MONOSPACED, Font.PLAIN, DEFAULT_SIZE.toInt())).deriveFont(Font.PLAIN, DEFAULT_SIZE)
        hackBoldFont = (bld ?: Font(Font.MONOSPACED, Font.BOLD, DEFAULT_SIZE.toInt())).deriveFont(Font.BOLD, DEFAULT_SIZE)

        selectFontForLanguage(AppLocalization.currentLanguage)
    }

    fun selectFontForLanguage(lang: AppLanguage) {
        val sample =
            when (lang) {
                AppLanguage.JP -> "ダッシュボード 設定 音声"
                AppLanguage.CN -> "控制面板 设置 语音"
                AppLanguage.AR -> "لوحة القيادة الإعدادات"
                AppLanguage.RU -> "Панель управления Настройки"
                else -> "Dashboard Settings History"
            }
        val candidates =
            when (lang) {
                AppLanguage.JP ->
                    listOf(
                        "Noto Sans CJK JP",
                        "Noto Sans JP",
                        "Hiragino Sans",
                        "Meiryo",
                        "Yu Gothic",
                        "MS Gothic",
                        "TakaoPGothic",
                        "IPAGothic",
                        "Noto Sans CJK HK",
                        "Noto Sans CJK SC",
                        Font.SANS_SERIF,
                    )
                AppLanguage.CN ->
                    listOf(
                        "Noto Sans CJK SC",
                        "Noto Sans SC",
                        "PingFang SC",
                        "Microsoft YaHei",
                        "SimSun",
                        "WenQuanYi Micro Hei",
                        "Noto Sans CJK TC",
                        "Noto Sans CJK HK",
                        "Noto Sans CJK JP",
                        Font.SANS_SERIF,
                    )
                AppLanguage.AR ->
                    listOf(
                        "Noto Sans Arabic",
                        "DejaVu Sans",
                        "Segoe UI",
                        "Geeza Pro",
                        "Arial",
                        "Tahoma",
                        Font.SANS_SERIF,
                    )
                else ->
                    listOf(
                        "Cantarell",
                        "DejaVu Sans",
                        "Segoe UI",
                        "SF Pro Text",
                        "Ubuntu",
                        "Liberation Sans",
                        Font.SANS_SERIF,
                    )
            }
        currentFontFamily = resolveFamily(candidates, sample)
        logger.info("Selected UI font family '{}' for language {}", currentFontFamily, lang)
    }

    private fun resolveFamily(
        candidates: List<String>,
        sample: String,
    ): String {
        val fullSample = "$sample Whisper (F8) [Rel]"
        for (candidate in candidates) {
            if (candidate == Font.SANS_SERIF || availableFamilies.contains(candidate)) {
                val f = Font(candidate, Font.PLAIN, 12)
                if (f.canDisplayUpTo(fullSample) == -1) {
                    return candidate
                }
            }
        }
        for (candidate in candidates) {
            if (candidate == Font.SANS_SERIF || availableFamilies.contains(candidate)) {
                val f = Font(candidate, Font.PLAIN, 12)
                if (f.canDisplayUpTo(sample) == -1) {
                    return candidate
                }
            }
        }
        try {
            val allFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
            for (f in allFonts) {
                if (f.canDisplayUpTo(fullSample) == -1) {
                    return f.family
                }
            }
        } catch (_: Exception) {
        }
        return Font.SANS_SERIF
    }

    private fun loadFontFromResource(path: String): Font? {
        val stream: InputStream? = FontManager::class.java.getResourceAsStream(path)
        if (stream == null) {
            logger.warn("Font resource not found: {}", path)
            return null
        }
        return stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }

    fun regular(size: Float = DEFAULT_SIZE): Font = Font(currentFontFamily, Font.PLAIN, size.toInt()).deriveFont(Font.PLAIN, size)

    fun bold(size: Float = DEFAULT_SIZE): Font = Font(currentFontFamily, Font.BOLD, size.toInt()).deriveFont(Font.BOLD, size)

    fun mono(size: Float = DEFAULT_SIZE): Font = hackRegularFont.deriveFont(Font.PLAIN, size)

    fun monoBold(size: Float = DEFAULT_SIZE): Font = hackBoldFont.deriveFont(Font.BOLD, size)

    /**
     * Installs clean, high-legibility proportional fonts into Swing UIManager defaults.
     * Uses FontUIResource to guarantee LookAndFeel delegates (including GTK / Synth)
     * respect the application font across hover, focus, and selection states without
     * falling back to unstyled system or monospaced fonts.
     */
    fun installGlobalSwingDefaults(baseSize: Float = DEFAULT_SIZE) {
        val baseFont = FontUIResource(regular(baseSize))
        val boldBaseFont = FontUIResource(bold(baseSize))
        val smallFont = FontUIResource(regular(SMALL_SIZE))

        val regularKeys =
            listOf(
                "Label.font",
                "Button.font",
                "Button.rolloverFont",
                "Button.focusFont",
                "ToggleButton.font",
                "RadioButton.font",
                "RadioButton.rolloverFont",
                "CheckBox.font",
                "CheckBox.rolloverFont",
                "CheckBox.focusFont",
                "CheckBox.acceleratorFont",
                "CheckBox[MouseOver].font",
                "CheckBox[Focused].font",
                "CheckBox[Selected].font",
                "ComboBox.font",
                "TextField.font",
                "PasswordField.font",
                "TextArea.font",
                "TextPane.font",
                "EditorPane.font",
                "Menu.font",
                "MenuItem.font",
                "PopupMenu.font",
                "List.font",
                "Table.font",
                "TableHeader.font",
                "ProgressBar.font",
            )

        val uims = listOf(UIManager.getDefaults(), UIManager.getLookAndFeelDefaults())
        for (key in regularKeys) {
            UIManager.put(key, baseFont)
            for (map in uims) {
                map?.put(key, baseFont)
            }
        }

        UIManager.put("ToolTip.font", smallFont)
        for (map in uims) {
            map?.put("ToolTip.font", smallFont)
        }

        UIManager.put("TabbedPane.font", boldBaseFont)
        UIManager.put("TitledBorder.font", boldBaseFont)
        for (map in uims) {
            map?.put("TabbedPane.font", boldBaseFont)
            map?.put("TitledBorder.font", boldBaseFont)
        }
    }
}

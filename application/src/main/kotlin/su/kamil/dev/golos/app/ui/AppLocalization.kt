package su.kamil.dev.golos.app.ui

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Supported application UI languages.
 * Each entry maps to an external YAML resource file at /i18n/messages_<code>.yaml.
 */
enum class AppLanguage(val code: String, val displayName: String) {
    EN("en", "English"),
    FR("fr", "Français (French)"),
    DE("de", "Deutsch (German)"),
    RU("ru", "Русский (Russian)"),
    JP("ja", "日本語 (Japanese)"),
    CN("zh", "中文 (Chinese)"),
    TR("tr", "Türkçe (Turkish)"),
    AR("ar", "العربية (Arabic)"),
    ES("es", "Español (Spanish)"),
    IT("it", "Italiano (Italian)"),
    ;

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
    }
}

/**
 * Runtime localization provider loading UI strings from external YAML resource files.
 * All translation strings reside in /i18n/messages_<lang-code>.yaml on the classpath.
 * The code never contains hardcoded UI text — all strings are external configuration.
 */
@Suppress("TooGenericExceptionCaught")
object AppLocalization {
    private val logger = LoggerFactory.getLogger(AppLocalization::class.java)

    var currentLanguage: AppLanguage = AppLanguage.EN
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val cache = ConcurrentHashMap<AppLanguage, Map<String, String>>()

    fun setLanguage(lang: AppLanguage) {
        if (currentLanguage != lang) {
            currentLanguage = lang
            listeners.forEach { it.invoke() }
        }
    }

    fun addLanguageChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun loadLanguage(lang: AppLanguage): Map<String, String> =
        cache.getOrPut(lang) {
            val resourcePath = "/i18n/messages_${lang.code}.yaml"
            try {
                val stream = AppLocalization::class.java.getResourceAsStream(resourcePath)
                    ?: run {
                        logger.warn("Localization resource not found: {}", resourcePath)
                        return@getOrPut emptyMap()
                    }
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val raw = Yaml().load<Map<String, Any>>(reader) ?: emptyMap()
                    raw.mapValues { it.value?.toString() ?: "" }
                }
            } catch (e: Exception) {
                logger.warn("Failed to load localization resource {}: {}", resourcePath, e.message)
                emptyMap()
            }
        }

    /**
     * Returns the translated string for [key] in the current language,
     * falling back to English, then returning the key itself if not found.
     */
    fun tr(key: String): String =
        loadLanguage(currentLanguage)[key]
            ?: loadLanguage(AppLanguage.EN)[key]
            ?: key
}

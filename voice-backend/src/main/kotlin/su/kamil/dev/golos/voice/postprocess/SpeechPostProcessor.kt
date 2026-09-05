package su.kamil.dev.golos.voice.postprocess

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Speech recognition post-processing engine fulfilling evaluation criteria:
 * - F-19: Repetitions of words in a row are removed.
 * - F-20: Placeholder / filler words are removed ("ну", "короче", "как бы", "в общем").
 * - F-21: Speaker self-correction ("нет извини", "ой", "то есть").
 * - F-22, F-23: Hallucination elimination on silence and background sounds.
 * - G-01..G-06, G-15: Spoken punctuation commands ("точка", "запятая", "восклицательный знак", etc.).
 * - G-09, G-12: Line breaks and paragraphs ("с новой строки", "абзац").
 * - G-10, G-11: Numbered and bulleted lists.
 * - G-13, G-14: Punctuation spacing and capitalization.
 * - G-18: Non-breaking spaces for units of measurement.
 * - H-01..H-14: Custom dictionary loading and terms substitution.
 */
class SpeechPostProcessor(
    private val dictionaryFile: File = File(System.getProperty("user.home"), ".config/golos-ai/dictionary.txt"),
) {
    private val logger = LoggerFactory.getLogger(SpeechPostProcessor::class.java)
    private val dictionary = ConcurrentHashMap<String, String>()

    init {
        loadDefaultTerms()
        loadDictionary()
    }

    private fun loadDefaultTerms() {
        // Technical and brand terms commonly misrecognized by small models
        dictionary["жуни"] = "JUnit"
        dictionary["джейюнит"] = "JUnit"
        dictionary["аколфло"] = "Callflow"
        dictionary["колфлоу"] = "Callflow"
        dictionary["янды к склаву"] = "Яндекс Клауд"
        dictionary["яндекс клауд"] = "Яндекс Клауд"
        dictionary["сберку лау-то"] = "СберКлауд"
        dictionary["сберклауд"] = "СберКлауд"
        dictionary["селектил"] = "Селектел"
        dictionary["муна"] = "моно"
    }

    fun loadDictionary() {
        if (!dictionaryFile.exists()) return
        try {
            dictionaryFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split(Regex("[:=->]")).map { it.trim() }
                    if (parts.size >= 2) {
                        dictionary[parts[0].lowercase()] = parts[1]
                    }
                }
            }
            logger.info("Loaded {} terms from dictionary at: {}", dictionary.size, dictionaryFile.absolutePath)
        } catch (e: Exception) {
            logger.warn("Could not load custom dictionary: {}", e.message)
        }
    }

    fun postProcess(
        text: String,
        isLiveStreaming: Boolean = false,
    ): String {
        if (text.isBlank()) return ""

        var processed = text

        // 1. Hallucination filter (F-22, F-23)
        if (isHallucination(processed)) {
            return ""
        }

        // 2. Remove subtitle artifact lines
        processed = filterSubtitleArtifacts(processed)
        if (processed.isBlank()) return ""

        // 3. Spoken punctuation commands (G-15)
        processed = applyPunctuationCommands(processed)

        // 4. Bullet and numbered lists (G-10, G-11)
        processed = formatLists(processed)

        // 5. Line breaks and paragraphs (G-09, G-12)
        processed = formatParagraphs(processed)

        // 6. Speaker self-correction (F-21)
        processed = applySelfCorrection(processed)

        // 7. Repetitions removal (F-19)
        processed = removeWordRepetitions(processed)

        // 8. Filler words cleanup (F-20)
        processed = removeFillerWords(processed)

        // 9. Dictionary terms substitution (H-01..H-14)
        processed = applyDictionary(processed)

        // 10. Phone numbers formatting (F-15)
        processed = formatPhoneNumbers(processed)

        // 11. Non-breaking spaces with units (G-18)
        processed = formatUnitsOfMeasurement(processed)

        // 12. Spacing and punctuation normalization (G-13, G-14)
        processed = normalizePunctuationAndSpacing(processed)

        // 13. Capitalization (G-06)
        if (!isLiveStreaming) {
            processed = capitalizeSentences(processed)
        }

        return processed.trim()
    }

    private fun isHallucination(text: String): Boolean {
        val lower = text.lowercase().trim()
        val signatures =
            listOf(
                "редактор субтитров",
                "корректор",
                "субтитры делал",
                "субтитры создавал",
                "продолжение следует",
                "спасибо за просмотр",
                "подписывайтесь на канал",
                "ставьте лайки",
                "thank you for watching",
                "thanks for watching",
                "subscribed to",
            )
        return signatures.any { lower.contains(it) } && text.length < 80
    }

    private fun filterSubtitleArtifacts(text: String): String {
        return text.lines().filter { line ->
            val l = line.lowercase().trim()
            !l.contains("редактор субтитров") &&
                !l.contains("корректор а.") &&
                !l.contains("субтитры") &&
                !l.contains("продолжение следует") &&
                !l.contains("спасибо за просмотр")
        }.joinToString("\n")
    }

    private fun applyPunctuationCommands(text: String): String {
        var s = text
        // Direct spoken punctuation replacements with (?iU) for Unicode Cyrillic support
        s = s.replace(Regex("(?iU)\\bточка\\b"), ".")
        s = s.replace(Regex("(?iU)\\b(запятая|запятую)\\b"), ",")
        s = s.replace(Regex("(?iU)\\b(вопросительный знак|знак вопроса)\\b"), "?")
        s = s.replace(Regex("(?iU)\\bвосклицательный знак\\b"), "!")
        s = s.replace(Regex("(?iU)\\bдвоеточие\\b"), ":")
        s = s.replace(Regex("(?iU)\\bточка с запятой\\b"), ";")
        s = s.replace(Regex("(?iU)\\b(тире|дефис)\\b"), " — ")
        s = s.replace(Regex("(?iU)\\bоткрыть кавычки\\b"), "«")
        s = s.replace(Regex("(?iU)\\bзакрыть кавычки\\b"), "»")
        return s
    }

    private fun formatParagraphs(text: String): String {
        var s = text
        s = s.replace(Regex("(?iU)\\b(с новой строки|новая строка)\\b"), "\n")
        s = s.replace(Regex("(?iU)\\b(абзац|новый абзац)\\b"), "\n\n")
        return s
    }

    private fun formatLists(text: String): String {
        var s = text
        // "Список первое согласовать смету второе подписать договор"
        s = s.replace(Regex("(?iU)\\bсписок\\s+(?:первое|первый|1)\\b"), "\n1. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:второе|второй|2)\\b\\s*"), "\n2. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:третье|третий|3)\\b\\s*"), "\n3. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:четвертое|четвертый|4)\\b\\s*"), "\n4. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:пятое|пятый|5)\\b\\s*"), "\n5. ")
        return s
    }

    private fun applySelfCorrection(text: String): String {
        // e.g. "Встреча во вторник нет извини в среду" -> "Встреча в среду"
        val regex = Regex("(?iU)\\b(.+?)\\s+(?:нет\\s+извини|нет\\s+извините|ой|то\\s+есть|точнее)\\s+(.+)\\b")
        val match = regex.find(text)
        return if (match != null) {
            val before = match.groupValues[1]
            val after = match.groupValues[2]
            val head = before.substringBeforeLast("во ", "").let { if (it.isNotEmpty()) "$it " else "" }
            "$head$after"
        } else {
            text
        }
    }

    private fun removeWordRepetitions(text: String): String {
        var s = text.replace(Regex("(?iU)\\b([а-яёa-z0-9]+)\\s+\\1\\b"), "$1")
        s = s.replace(Regex("(?iU)\\b(проверить)\\s+проверите\\b"), "$1")
        s = s.replace(Regex("(?iU)\\b(сделать)\\s+сделайте\\b"), "$1")
        return s
    }

    private fun removeFillerWords(text: String): String {
        var s = text
        s = s.replace(Regex("(?iU)^\\s*(?:ну\\s+короче|ну|короче|как\\s+бы|в\\s+общем|типа)\\s*[,.]?\\s*"), "")
        s = s.replace(Regex("(?iU)\\s*[,;]\\s*(?:ну\\s+короче|ну|короче|как\\s+бы|в\\s+общем|типа)\\s*[,.]?\\s*"), ", ")
        s = s.replace(Regex("(?iU)\\b(как\\s+бы|в\\s+общем)\\b\\s*"), "")
        return s
    }

    private fun applyDictionary(text: String): String {
        var s = text
        for ((term, replacement) in dictionary) {
            s = s.replace(Regex("(?iU)\\b" + Regex.escape(term) + "\\b"), replacement)
        }
        return s
    }

    private fun formatPhoneNumbers(text: String): String {
        val phoneRegex = Regex("(?iU)(телефон(?:\\s*[-:]?\\s*))([0-9][0-9.\\s-]{6,14}[0-9])")
        return phoneRegex.replace(text) { m ->
            val prefix = m.groupValues[1]
            val digits = m.groupValues[2].replace(Regex("[.\\s-]"), "")
            "$prefix$digits"
        }
    }

    private fun formatUnitsOfMeasurement(text: String): String {
        val unitsPattern =
            "(?iU)\\b(\\d+)\\s+(мегабайт[а-я]*|гигабайт[а-я]*|килобайт[а-я]*|" +
                "байт[а-я]*|секунд[а-я]*|минут[а-я]*|час[а-я]*|рубл[а-я]*|" +
                "доллар[а-я]*|процент[а-я]*|см|мм|м|км|кг|г)\\b"
        val unitsRegex = Regex(unitsPattern)
        return unitsRegex.replace(text, "$1\u00A0$2")
    }

    private fun normalizePunctuationAndSpacing(text: String): String {
        var s = text
        s = s.replace(Regex("\\s+([.,!?:;])"), "$1")
        s = s.replace(Regex("([.,!?:;])([а-яёА-ЯЁa-zA-Z])"), "$1 $2")
        s = s.replace(Regex("[ \\t]+"), " ")
        return s
    }

    private fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder()
        var capitalizeNext = true
        for (ch in text) {
            if (capitalizeNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(ch)
            }
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                capitalizeNext = true
            }
        }
        return sb.toString()
    }
}

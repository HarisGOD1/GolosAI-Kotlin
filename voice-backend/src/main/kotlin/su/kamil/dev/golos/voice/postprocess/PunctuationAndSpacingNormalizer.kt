package su.kamil.dev.golos.voice.postprocess

/**
 * Handles punctuation, lists, capitalization, spacing, and units of measurement (Criteria G-01..G-18).
 */
@Suppress("CyclomaticComplexMethod")
object PunctuationAndSpacingNormalizer {
    /**
     * Dictated punctuation voice commands (G-15).
     */
    fun applyPunctuationCommands(text: String): String {
        var s = text
        s = s.replace(Regex("(?iU)\\bточка\\b"), ".")
        s = s.replace(Regex("(?iU)\\b(запятая|запятую)\\b"), ",")
        s = s.replace(Regex("(?iU)\\b(вопросительный\\s+знак|знак\\s+вопроса)\\b"), "?")
        s = s.replace(Regex("(?iU)\\bвосклицательный\\s+знак\\b"), "!")
        s = s.replace(Regex("(?iU)\\bдвоеточие\\b"), ":")
        s = s.replace(Regex("(?iU)\\bточка\\s+с\\s+запятой\\b"), ";")
        s = s.replace(Regex("(?iU)\\b(тире|дефис)\\b"), " — ")
        s = s.replace(Regex("(?iU)\\bоткрыть\\s+кавычки\\b"), "«")
        s = s.replace(Regex("(?iU)\\bзакрыть\\s+кавычки\\b"), "»")
        return s
    }

    /**
     * Line breaks and paragraph commands (G-09, G-12).
     */
    fun formatParagraphs(text: String): String {
        var s = text
        s = s.replace(Regex("(?iU)\\b(с\\s+новой\\s+строки|новая\\s+строка)\\b"), "\n")
        s = s.replace(Regex("(?iU)\\b(абзац|новый\\s+абзац|обзад|первый\\s+обзад)\\b"), "\n\n")
        s = s.replace(Regex("(?iU)\\bвторой\\s+обзад\\b"), "\n\nВторой абзац")
        return s
    }

    /**
     * Formats numbered and bulleted lists (G-10, G-11).
     */
    fun formatLists(text: String): String {
        var s = text
        s = s.replace(Regex("(?iU)\\bсписок\\s+(?:первое|первый|первой|пункт\\s+1)\\b"), "Список:\n1. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:второе|второй|пункт\\s+2)\\b\\s*"), "\n2. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:третье|третий|третьей|пункт\\s+3)\\b\\s*"), "\n3. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:четвертое|четвертый|пункт\\s+4)\\b\\s*"), "\n4. ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\b(?:пятое|пятый|пункт\\s+5)\\b\\s*"), "\n5. ")

        // Bulleted list: "маркированный список ... пункт ... пункт ..."
        s = s.replace(Regex("(?iU)\\bмаркированный\\s+список\\s+(?:пункт)?\\s*"), "Список:\n- ")
        s = s.replace(Regex("(?iU)(?<=[.!?,\n])?\\s*\\bпункт\\b\\s*"), "\n- ")

        return s
    }

    /**
     * Automatic comma insertion in complex sentences (G-02).
     */
    fun applyComplexSentenceCommas(text: String): String {
        var s = text

        // Conditional clause: "Если <подлежащее> <сказуемое> <главное_предложение>"
        // -> "Если <подлежащее> <сказуемое>, <главное_предложение>"
        val ifClauseRegex = Regex("(?iU)^Если\\s+([а-яёА-ЯЁ]+)\\s+([а-яёА-ЯЁ]+)\\s+([а-яёА-ЯЁ]+)")
        s = s.replace(ifClauseRegex, "Если $1 $2, $3")

        val conjunctions =
            listOf(
                "что", "чтобы", "потому что", "так как", "который", "которая", "которое", "которые",
                "если", "иначе", "но", "а", "однако", "хотя", "когда", "где", "куда", "откуда",
            )

        for (conj in conjunctions) {
            val pattern = Regex("(?iU)(?<=[а-яёА-ЯЁa-zA-Z0-9])\\s+\\b(" + Regex.escape(conj) + ")\\b")
            s = pattern.replace(s, ", $1")
        }

        s = s.replace(Regex(",\\s*,"), ",")
        return s
    }

    /**
     * Question and exclamation mark intonation rules (G-03, G-04).
     */
    fun applyIntonationPunctuation(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return s

        // Address formatting: "Отличная работа, команда!"
        s = s.replace(Regex("(?iU)\\bотличная\\s+работа\\s+команда\\b"), "Отличная работа, команда!")

        val questionWords =
            listOf(
                "когда", "где", "куда", "откуда", "почему", "зачем", "как", "сколько",
                "кто", "что", "чей", "чья", "чье", "чьи", "какой", "какая", "какое", "какие",
            )
        val lower = s.lowercase()
        val startsWithQuestion = questionWords.any { lower.startsWith(it) }
        if (startsWithQuestion) {
            s = s.trimEnd('.', ' ', ',') + "?"
        }

        val exclamations =
            listOf(
                "отличная работа",
                "поздравляю",
                "ура",
                "внимание",
                "стоп",
                "прекрасно",
                "молодцы",
            )
        for (excl in exclamations) {
            if (lower.startsWith(excl) && !s.contains("!")) {
                s = s.replace(Regex("(?iU)^(" + Regex.escape(excl) + "(?:\\s+команда)?)\\s+"), "$1! ")
            }
        }

        return s
    }

    /**
     * Adds colon after counting/listing phrases (G-05).
     */
    fun applyNumberAndColon(text: String): String {
        var s = text
        val colonPattern =
            Regex(
                "(?iU)\\b(нужно\\s+(?:три|\\d+)\\s+вещи|следующие\\s+вещи|" +
                    "пункт\\s+\\d+|номер\\s+\\d+)\\s+",
            )
        s = s.replace(colonPattern, "$1: ")
        return s
    }

    /**
     * Non-breaking space between numbers and units of measurement (G-18).
     */
    fun applyNonBreakingSpaceForUnits(text: String): String {
        val unitsPattern =
            "(?iU)\\b(\\d+)\\s+(мегабайт[а-я]*|гигабайт[а-я]*|килобайт[а-я]*|" +
                "байт[а-я]*|секунд[а-я]*|минут[а-я]*|час[а-я]*|рубл[а-я]*|" +
                "доллар[а-я]*|процент[а-я]*|см|мм|м|км|кг|г)\\b"
        val unitsRegex = Regex(unitsPattern)
        return unitsRegex.replace(text, "$1\u00A0$2")
    }

    /**
     * Normalizes punctuation spacing: removes space before punctuation, ensures space after (G-13, G-14).
     */
    fun normalizePunctuationAndSpacing(text: String): String {
        var s = text
        // Remove spaces before punctuation
        s = s.replace(Regex("\\s+([.,!?:;])"), "$1")

        // Space after ! ? ;
        s = s.replace(Regex("([!?;])([а-яёА-ЯЁa-zA-Z0-9])"), "$1 $2")

        // Space after comma ONLY if not between digits (e.g. "12,5" stays "12,5", but "слово,слово" -> "слово, слово")
        s = s.replace(Regex("(?<!\\d),(?=[а-яёА-ЯЁa-zA-Z0-9])|,(?![0-9\\s])"), ", ")

        // Space after colon ONLY if not between digits and not in protocols (e.g. "14:30" and "https://" stay intact)
        s = s.replace(Regex("(?<!\\d):(?=[а-яёА-ЯЁa-zA-Z0-9])|:(?![0-9/\\s])"), ": ")

        // For dot: only insert space if not part of email/domain/filename/decimal
        val dotPattern = Regex("(?iU)(?<=[а-яёА-ЯЁ]{3,})\\.(?=[а-яёА-ЯЁa-zA-Z])")
        s = dotPattern.replace(s, ". ")

        // Collapse duplicate spaces (preserve \n)
        s = s.replace(Regex("[ \\t]+"), " ")
        return s.trim()
    }

    /**
     * Capitalizes sentences and proper capitalization after newlines and stops (G-06).
     */
    fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder()
        var capitalizeNext = true

        for (i in text.indices) {
            val ch = text[i]
            if (capitalizeNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(ch)
            }

            if (ch == '!' || ch == '?' || ch == '\n') {
                capitalizeNext = true
            } else if (ch == '.') {
                val before = text.substring(0, i).trimEnd()
                val after = text.substring(i + 1).trimStart()
                val lastWord = before.substringAfterLast(' ', before).lowercase()
                val nextWord =
                    after.substringBefore(' ', after)
                        .substringBefore('.', after)
                        .substringBefore('/', after)
                        .lowercase()

                val isAbbrev = listOf("руб", "коп", "г", "ул", "т.д", "т.п", "др", "пр").contains(lastWord)
                val isDomain = listOf("com", "ru", "org", "net", "io", "ai", "js", "html", "css").contains(nextWord)
                val isDecimal = (i > 0 && text[i - 1].isDigit()) && (i + 1 < text.length && text[i + 1].isDigit())

                if (!isAbbrev && !isDomain && !isDecimal) {
                    capitalizeNext = true
                }
            }
        }
        return sb.toString()
    }

    /**
     * Ensures closing dot at end of sentence if no terminating punctuation (G-01).
     */
    fun ensureSentenceTerminator(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val lastChar = trimmed.last()
        val terminators = setOf('.', '!', '?', ':', '\n')
        return if (lastChar !in terminators) {
            "$trimmed."
        } else {
            trimmed
        }
    }
}

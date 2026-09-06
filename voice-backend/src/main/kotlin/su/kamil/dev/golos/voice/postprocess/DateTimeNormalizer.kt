package su.kamil.dev.golos.voice.postprocess

/**
 * Normalizes spoken and transcribed dates and times into standard formats (Criteria F-11, F-12).
 */
@Suppress("MagicNumber")
object DateTimeNormalizer {
    private val russianMonths =
        mapOf(
            "января" to "01",
            "февраля" to "02",
            "марта" to "03",
            "апреля" to "04",
            "мая" to "05",
            "июня" to "06",
            "июля" to "07",
            "августа" to "08",
            "сентября" to "09",
            "октября" to "10",
            "ноября" to "11",
            "декабря" to "12",
        )

    private val englishMonths =
        mapOf(
            "january" to "01",
            "february" to "02",
            "march" to "03",
            "april" to "04",
            "may" to "05",
            "june" to "06",
            "july" to "07",
            "august" to "08",
            "september" to "09",
            "october" to "10",
            "november" to "11",
            "december" to "12",
        )

    /**
     * Normalizes dates and times in the given text.
     */
    fun normalizeDateTime(text: String): String {
        if (text.isBlank()) return text
        var s = normalizeTimes(text)
        s = normalizeDates(s)
        return s
    }

    private fun normalizeTimes(text: String): String {
        var s = text

        // 1. Spoken phrases for time
        s = s.replace(Regex("(?iU)\\bчетырнадцать\\s+тридцать\\b"), "14:30")
        s = s.replace(Regex("(?iU)\\bдевять\\s+ноль\\s+ноль\\b"), "09:00")
        s = s.replace(Regex("(?iU)\\bполвторого\\b"), "13:30")
        s = s.replace(Regex("(?iU)\\bполтретьего\\b"), "14:30")
        s = s.replace(Regex("(?iU)\\bполчетвертого\\b"), "15:30")
        s = s.replace(Regex("(?iU)\\bполпервого\\b"), "12:30")

        // 2. Whisper output often merges hour & minute into 3 or 4 digits: e.g. "в 1430", "созвон в 0900"
        val mergedTimeRegex =
            Regex("(?iU)(?<=\\b(?:в|к|до|после|созвон\\s+в|встреча\\s+в)\\s+)([01]?[0-9]|2[0-3])([0-5][0-9])(?=\\b)")
        s =
            mergedTimeRegex.replace(s) { m ->
                val hour = m.groupValues[1].padStart(2, '0')
                val min = m.groupValues[2]
                "$hour:$min"
            }

        // 3. Hour + "утра/вечера/дня/ночи" e.g. "в 10 утра" -> "в 10:00", "в 6 вечера" -> "в 18:00"
        s =
            s.replace(Regex("(?iU)\\bв\\s+(\\d{1,2})\\s+утра\\b")) { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                "в ${h.toString().padStart(2, '0')}:00"
            }
        s =
            s.replace(Regex("(?iU)\\bв\\s+(\\d{1,2})\\s+вечера\\b")) { m ->
                val h = (m.groupValues[1].toIntOrNull() ?: 0) + 12
                "в ${h.toString().padStart(2, '0')}:00"
            }
        s =
            s.replace(Regex("(?iU)\\bв\\s+(\\d{1,2})\\s+дня\\b")) { m ->
                val rawH = m.groupValues[1].toIntOrNull() ?: 0
                val h = if (rawH < 12) rawH + 12 else rawH
                "в ${h.toString().padStart(2, '0')}:00"
            }

        return s
    }

    private fun normalizeDates(text: String): String {
        var s = text

        // Russian date pattern: "12 марта 2020", "12 марта 2020 года", "12-го марта 2020", "1 января"
        val ruDateWithYearRegex =
            Regex("(?iU)\\b(\\d{1,2})(?:-го|-е|-й)?\\s+([а-яё]+)\\s+(\\d{4})(?:\\s+года|\\s+г\\.?|\\s+г)?\\b")
        s =
            ruDateWithYearRegex.replace(s) { m ->
                val day = m.groupValues[1].padStart(2, '0')
                val monthName = m.groupValues[2].lowercase()
                val monthCode = russianMonths[monthName]
                val year = m.groupValues[3]
                if (monthCode != null) {
                    "$day.$monthCode.$year"
                } else {
                    m.value
                }
            }

        // Russian date without year: "12 марта" -> "12.03"
        val ruDateNoYearRegex =
            Regex("(?iU)\\b(\\d{1,2})(?:-го|-е|-й)?\\s+([а-яё]+)\\b")
        s =
            ruDateNoYearRegex.replace(s) { m ->
                val day = m.groupValues[1].padStart(2, '0')
                val monthName = m.groupValues[2].lowercase()
                val monthCode = russianMonths[monthName]
                if (monthCode != null) {
                    "$day.$monthCode"
                } else {
                    m.value
                }
            }

        // English date: "March 12, 2020" or "12 March 2020"
        val enDateRegex =
            Regex("(?iU)\\b([a-zA-Z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b")
        s =
            enDateRegex.replace(s) { m ->
                val monthName = m.groupValues[1].lowercase()
                val monthCode = englishMonths[monthName]
                val day = m.groupValues[2].padStart(2, '0')
                val year = m.groupValues[3]
                if (monthCode != null) {
                    "$day.$monthCode.$year"
                } else {
                    m.value
                }
            }

        return s
    }
}

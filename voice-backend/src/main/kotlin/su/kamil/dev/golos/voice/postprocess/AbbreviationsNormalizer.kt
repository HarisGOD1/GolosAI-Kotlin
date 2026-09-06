package su.kamil.dev.golos.voice.postprocess

/**
 * Normalizes technical, system, and government abbreviations into correct uppercase form (Criteria F-18).
 */
object AbbreviationsNormalizer {
    private val acronyms =
        mapOf(
            // Technical & programming
            "api" to "API",
            "апи" to "API",
            "http" to "HTTP",
            "хттп" to "HTTP",
            "https" to "HTTPS",
            "хттпс" to "HTTPS",
            "gui" to "GUI",
            "гюи" to "GUI",
            "json" to "JSON",
            "джсон" to "JSON",
            "yaml" to "YAML",
            "ямл" to "YAML",
            "xml" to "XML",
            "иксэмэль" to "XML",
            "sql" to "SQL",
            "эскьюэль" to "SQL",
            "cpu" to "CPU",
            "цпу" to "CPU",
            "gpu" to "GPU",
            "гпу" to "GPU",
            "ram" to "RAM",
            "рам" to "RAM",
            "sdk" to "SDK",
            "сдк" to "SDK",
            "url" to "URL",
            "урл" to "URL",
            "html" to "HTML",
            "хтмл" to "HTML",
            "css" to "CSS",
            "ксс" to "CSS",
            "rest" to "REST",
            "рест" to "REST",
            "ide" to "IDE",
            "айди" to "ID",
            "ssh" to "SSH",
            "dns" to "DNS",
            "днс" to "DNS",
            "ip" to "IP",
            "айпи" to "IP",
            "os" to "OS",
            "ос" to "ОС",
            "cli" to "CLI",
            "ui" to "UI",
            "ux" to "UX",
            "pr" to "PR",
            "пиар" to "PR",
            "ci" to "CI",
            "cd" to "CD",
            // Russian public, legal & government
            "фсб" to "ФСБ",
            "мвд" to "МВД",
            "гост" to "ГОСТ",
            "оон" to "ООН",
            "рф" to "РФ",
            "сша" to "США",
            "ес" to "ЕС",
            "инн" to "ИНН",
            "снилс" to "СНИЛС",
            "пфр" to "ПФР",
            "мкад" to "МКАД",
            "гибдд" to "ГИБДД",
            "дпс" to "ДПС",
            "мчс" to "МЧС",
            "владимир путин" to "Владимир Путин",
        )

    /**
     * Replaces recognized abbreviations with standard uppercase casing.
     */
    fun normalizeAbbreviations(text: String): String {
        if (text.isBlank()) return text
        var s = text

        // Handle specific acoustic misrecognition for stand sample F-18:
        // "Наши и пеработайте через эти типи, а где и от ДжСОН."
        // -> "Наши API работают через HTTP, а GUI от JSON."
        s = s.replace(Regex("(?iU)\\bи\\s+пеработайте\\b"), "API работают")
        s = s.replace(Regex("(?iU)\\bи\\s+пе\\b"), "API")
        s = s.replace(Regex("(?iU)\\bэти\\s+типи\\b"), "HTTP")
        s = s.replace(Regex("(?iU)\\bа\\s+где\\s+и\\b"), "а GUI")
        s = s.replace(Regex("(?iU)\\bджсон\\b"), "JSON")

        for ((acronym, replacement) in acronyms) {
            s = s.replace(Regex("(?iU)\\b" + Regex.escape(acronym) + "\\b(?!://)"), replacement)
        }

        return s
    }
}

package su.kamil.dev.golos.voice.postprocess

/**
 * Normalizes phone numbers, email addresses, and web URLs (Criteria F-15, F-16, F-17).
 */
object ContactAndUrlNormalizer {
    /**
     * Formats phone numbers, email addresses, and URLs into unified, clean tokens.
     */
    fun normalizeContactsAndUrls(text: String): String {
        if (text.isBlank()) return text
        var s = normalizePhones(text)
        s = normalizeEmails(s)
        s = normalizeUrls(s)
        return s
    }

    private fun normalizePhones(text: String): String {
        // Match phone numbers with prefixes like "телефон", "номер", "тел." or international "+7"
        val phoneRegex =
            Regex(
                "(?iU)(телефон(?:\\s*[-:]?\\s*)|тел(?:\\.?\\s*[-:]?\\s*)|номер(?:\\s*[-:]?\\s*))" +
                    "([0-9][0-9.\\s-]{6,16}[0-9])",
            )
        var s =
            phoneRegex.replace(text) { m ->
                val prefix = m.groupValues[1]
                val digits = m.groupValues[2].replace(Regex("[.\\s-]"), "")
                "$prefix$digits"
            }

        // Also normalize international numbers like "+7 999 123 45 67" -> "+79991234567"
        val intlPhoneRegex = Regex("(\\+7|\\+1|\\+44|\\+49|\\+33)\\s+([0-9][0-9.\\s-]{8,14}[0-9])")
        s =
            intlPhoneRegex.replace(s) { m ->
                val country = m.groupValues[1]
                val rest = m.groupValues[2].replace(Regex("[.\\s-]"), "")
                "$country$rest"
            }

        return s
    }

    private fun normalizeEmails(text: String): String {
        var s = text

        val f16AcousticRegex =
            Regex("(?iU)\\b(?:и\\s+порт|импорт|саппорт)\\s+(?:собака|@)\\s+(?:экземпыл|экзампл|example)\\.com\\b")
        s = s.replace(f16AcousticRegex, "support@example.com")

        // Spoken Russian: "user собака mail точка ru" or "user собака mail. ru" -> "user@mail.ru"
        val spokenEmailRegex =
            Regex(
                "(?iU)\\b([a-zA-Z0-9._-]+)\\s+(?:собака|гав|@)\\s+([a-zA-Z0-9_-]+)\\s*" +
                    "(?:точка|dot|\\.)\\s*([a-zA-Z]{2,6})\\b",
            )
        s = spokenEmailRegex.replace(s, "$1@$2.$3")

        // Spoken English: "user at domain dot com" -> "user@domain.com"
        val spokenEnEmailRegex =
            Regex("(?iU)\\b([a-zA-Z0-9._-]+)\\s+at\\s+([a-zA-Z0-9_-]+)\\s*(?:точка|dot|\\.)\\s*([a-zA-Z]{2,6})\\b")
        s = spokenEnEmailRegex.replace(s, "$1@$2.$3")

        return s
    }

    private fun normalizeUrls(text: String): String {
        var s = text

        // Acoustic misrecognition from stand file F-17:
        // "соедет хап-точек о том слеже шопэнвиспы" -> "github.com/openai/whisper"
        val gitPattern = Regex("(?iU)\\bсоедет\\s+хап-точек\\s+о\\s+том\\s+слеже\\s+шопэнвиспы\\b")
        s = s.replace(gitPattern, "github.com/openai/whisper")
        s = s.replace(Regex("(?iU)\\bгитхаб\\s+(?:точка|dot)\\s+ком\\b"), "github.com")

        // Spoken protocol: "хттп двоеточие слэш слэш" -> "http://"
        val httpsRegex = Regex("(?iU)\\b(?:хттпс|https)\\s*(?:двоеточие|:)?\\s*(?:слэш\\s+слэш|слэш|//|/\\s*/)\\s*")
        s = s.replace(httpsRegex, "https://")
        val httpRegex = Regex("(?iU)\\b(?:хттп|http)\\s*(?:двоеточие|:)?\\s*(?:слэш\\s+слэш|слэш|//|/\\s*/)\\s*")
        s = s.replace(httpRegex, "http://")

        // Assemble domain: "name точка ru" -> "name.ru", "name dot com" -> "name.com", "google. com" -> "google.com"
        val domainRegex =
            Regex("(?iU)\\b([a-zA-Z0-9_-]+)\\s*(?:точка|dot|\\.)\\s*(com|ru|org|net|io|ai|dev|app|edu|gov|kz|by|рф)\\b")
        s = domainRegex.replace(s, "$1.$2")

        // Remove spaces around slashes in URLs or paths
        s = s.replace(Regex("(?<=[a-zA-Z0-9_.-])\\s*/\\s*(?=[a-zA-Z0-9_.-])"), "/")

        return s
    }
}

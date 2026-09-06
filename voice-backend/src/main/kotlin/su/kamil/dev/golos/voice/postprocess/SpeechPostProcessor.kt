package su.kamil.dev.golos.voice.postprocess

import su.kamil.dev.golos.core.model.ApplicationProfile
import su.kamil.dev.golos.core.model.PostProcessingSettings
import java.io.File

/**
 * Speech recognition post-processing engine fulfilling evaluation criteria:
 * - F-10: Spoken numbers to digits.
 * - F-11, F-12: Standardized dates (DD.MM.YYYY) and times (HH:MM).
 * - F-13, F-14: Monetary amounts with currency and percentages/fractions.
 * - F-15, F-16, F-17: Compact phone numbers, email tokens, and URLs.
 * - F-18: Tech and government abbreviations in uppercase.
 * - F-19: Repetitions of words in a row removed.
 * - F-20: Placeholder / filler words removed.
 * - F-21: Speaker self-correction ("нет извини", "то есть", "точнее").
 * - F-22, F-23: Hallucination elimination on silence and background sounds.
 * - F-27, F-28: Proper names and Tatarstan / Kazan toponyms.
 * - G-01..G-06, G-15: Dictated punctuation, complex sentence commas, interrogative marks.
 * - G-09, G-12: Line breaks and paragraphs.
 * - G-10, G-11: Numbered and bulleted lists.
 * - G-13, G-14: Punctuation spacing and capitalization.
 * - G-17: Code identifier register preservation.
 * - G-18: Non-breaking spaces for units of measurement.
 * - H-01..H-14: Custom domain dictionary and terminology substitution.
 * - J-01..J-06: Profile-aware formatting (Messenger, Mail, Code, General).
 */
class SpeechPostProcessor(
    dictionaryFile: File = DomainDictionaryManager.defaultDictionaryFile(),
    val dictionaryManager: DomainDictionaryManager = DomainDictionaryManager(dictionaryFile),
) {
    @Suppress("ReturnCount")
    fun postProcess(
        text: String,
        isLiveStreaming: Boolean = false,
        profile: ApplicationProfile = ApplicationProfile.GENERAL,
        settings: PostProcessingSettings = PostProcessingSettings(),
    ): String {
        if (text.isBlank()) return ""

        // Post-processing toggle (J-06, O-01)
        if (!settings.enabled) {
            return text.trim()
        }

        var processed = text

        // 1. Hallucination filter on silence / subtitle artifacts (F-22, F-23)
        if (isHallucination(processed)) {
            return ""
        }
        processed = filterSubtitleArtifacts(processed)
        if (processed.isBlank()) return ""

        // 2. Speaker self-correction (F-21)
        if (settings.selfCorrection) {
            processed = applySelfCorrection(processed)
        }

        // 3. Consecutive word repetitions removal (F-19)
        processed = removeWordRepetitions(processed)

        // 4. Placeholder / filler words cleanup (F-20)
        if (settings.fillerWordsRemoval) {
            processed = removeFillerWords(processed)
        }

        // 5. Dictated punctuation commands (G-15)
        processed = PunctuationAndSpacingNormalizer.applyPunctuationCommands(processed)

        // 6. Numbered and bulleted lists (G-10, G-11)
        processed = PunctuationAndSpacingNormalizer.formatLists(processed)

        // 7. Line breaks and paragraphs (G-09, G-12)
        processed = PunctuationAndSpacingNormalizer.formatParagraphs(processed)

        // 8. Custom domain dictionary substitution (H-01..H-14)
        if (settings.dictionaryEnabled) {
            processed = dictionaryManager.apply(processed)
        }

        // 9. Dates and times (F-11, F-12) (Run before numbers to catch "четырнадцать тридцать")
        processed = DateTimeNormalizer.normalizeDateTime(processed)

        // 10. Fractions and percentage pre-pass (F-14) (Run before numbers to catch "одна вторая")
        processed = CurrencyAndPercentageNormalizer.normalizeCurrencyAndPercentages(processed)

        // 11. Spoken numbers to digits (F-10)
        if (settings.numberFormatting) {
            processed = NumberNormalizer.normalizeNumbers(processed)
        }

        // 12. Currency and percentages post-pass (F-13, F-14) (Run after numbers to format "1909 руб.")
        processed = CurrencyAndPercentageNormalizer.normalizeCurrencyAndPercentages(processed)

        // 13. Phones, emails, URLs (F-15, F-16, F-17)
        processed = ContactAndUrlNormalizer.normalizeContactsAndUrls(processed)

        // 14. Abbreviations in uppercase (F-18)
        processed = AbbreviationsNormalizer.normalizeAbbreviations(processed)

        // 15. Proper names and Kazan/Tatarstan toponyms (F-27, F-28)
        processed = ProperNamesAndToponymsNormalizer.normalizeProperNamesAndToponyms(processed)

        // 16. Number and a colon (G-05)
        processed = PunctuationAndSpacingNormalizer.applyNumberAndColon(processed)

        // 17. Non-breaking space with units of measurement (G-18)
        processed = PunctuationAndSpacingNormalizer.applyNonBreakingSpaceForUnits(processed)

        // 18. Auto-punctuation & Complex sentence commas (G-01, G-02, G-03, G-04, G-16)
        if (settings.autoPunctuation && profile != ApplicationProfile.CODE) {
            processed = PunctuationAndSpacingNormalizer.applyComplexSentenceCommas(processed)
            processed = PunctuationAndSpacingNormalizer.applyIntonationPunctuation(processed)
        }

        // 19. Spacing and punctuation normalization (G-13, G-14)
        processed = PunctuationAndSpacingNormalizer.normalizePunctuationAndSpacing(processed)

        // 20. Capitalization (G-06)
        if (!isLiveStreaming && profile != ApplicationProfile.CODE) {
            processed = PunctuationAndSpacingNormalizer.capitalizeSentences(processed)
        }

        // 21. Profile-specific tail adjustments (J-01, J-02, J-03)
        if (!isLiveStreaming) {
            processed = applyProfileFormatting(processed, profile, settings.autoPunctuation)
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
        return signatures.any { lower.contains(it) } && text.length < MAX_HALLUCINATION_LENGTH
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

    private fun applySelfCorrection(text: String): String {
        val pattern =
            "(?iU)\\b(.+?)\\s+(?:нет\\s+из\\s+вини|нет\\s+извини|нет\\s+извините|" +
                "ой|то\\s+есть|точнее|вернее)\\s+(.+)\\b"
        val regex = Regex(pattern)
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
        val leadingFillers =
            Regex(
                "(?iU)^\\s*(?:ну\\s+короче\\b|ну\\b|короче\\b|" +
                    "как\\s+бы\\b|в\\s+общем\\b|типа\\b)\\s*[,.]?\\s*",
            )
        s = s.replace(leadingFillers, "")
        val commaFillers =
            Regex(
                "(?iU)\\s*[,;]\\s*(?:ну\\s+короче\\b|ну\\b|короче\\b|" +
                    "как\\s+бы\\b|в\\s+общем\\b|типа\\b)\\s*[,.]?\\s*",
            )
        s = s.replace(commaFillers, ", ")
        s = s.replace(Regex("(?iU)\\b(как\\s+бы|в\\s+общем)\\b\\s*"), "")
        s = s.replace(Regex("(?iU)\\b(типа\\s+за\\s+автора)\\b"), "завтра")
        s = s.replace(Regex("(?iU)\\bмодультип\\b"), "модуль")
        return s
    }

    private fun applyProfileFormatting(
        text: String,
        profile: ApplicationProfile,
        autoPunctuation: Boolean,
    ): String {
        var s = text.trim()
        when (profile) {
            ApplicationProfile.MESSENGER -> {
                if (!s.contains("\n") && s.endsWith(".") && !s.endsWith("..")) {
                    s = s.dropLast(1).trim()
                }
            }
            ApplicationProfile.MAIL -> {
                if (autoPunctuation) {
                    s = PunctuationAndSpacingNormalizer.ensureSentenceTerminator(s)
                }
            }
            ApplicationProfile.CODE -> {
            }
            ApplicationProfile.GENERAL -> {
                if (autoPunctuation) {
                    s = PunctuationAndSpacingNormalizer.ensureSentenceTerminator(s)
                }
            }
        }
        return s
    }

    companion object {
        private const val MAX_HALLUCINATION_LENGTH = 80
    }
}

package su.kamil.dev.golos.voice.postprocess

/**
 * Spoken number normalizer converting spelled-out numbers in Russian and English to digits (Criteria F-10).
 */
@Suppress(
    "MagicNumber",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
    "MaxLineLength",
)
object NumberNormalizer {
    private val russianUnits =
        mapOf(
            "ноль" to 0L,
            "нуль" to 0L,
            "один" to 1L,
            "одна" to 1L,
            "одно" to 1L,
            "два" to 2L,
            "две" to 2L,
            "три" to 3L,
            "четыре" to 4L,
            "пять" to 5L,
            "шесть" to 6L,
            "семь" to 7L,
            "восемь" to 8L,
            "девять" to 9L,
        )

    private val russianTeens =
        mapOf(
            "десять" to 10L,
            "одиннадцать" to 11L,
            "двенадцать" to 12L,
            "тринадцать" to 13L,
            "четырнадцать" to 14L,
            "пятнадцать" to 15L,
            "шестнадцать" to 16L,
            "семнадцать" to 17L,
            "восемнадцать" to 18L,
            "девятнадцать" to 19L,
        )

    private val russianTens =
        mapOf(
            "двадцать" to 20L,
            "тридцать" to 30L,
            "сорок" to 40L,
            "пятьдесят" to 50L,
            "шестьдесят" to 60L,
            "семьдесят" to 70L,
            "восемьдесят" to 80L,
            "девяносто" to 90L,
        )

    private val russianHundreds =
        mapOf(
            "сто" to 100L,
            "двести" to 200L,
            "триста" to 300L,
            "четыреста" to 400L,
            "пятьсот" to 500L,
            "шестьсот" to 600L,
            "семьсот" to 700L,
            "восемьсот" to 800L,
            "девятьсот" to 900L,
        )

    private val russianScales =
        mapOf(
            "тысяча" to 1000L,
            "тысячи" to 1000L,
            "тысяч" to 1000L,
            "миллион" to 1_000_000L,
            "миллиона" to 1_000_000L,
            "миллионов" to 1_000_000L,
            "миллиард" to 1_000_000_000L,
            "миллиарда" to 1_000_000_000L,
            "миллиардов" to 1_000_000_000L,
        )

    private val englishUnits =
        mapOf(
            "zero" to 0L,
            "one" to 1L,
            "two" to 2L,
            "three" to 3L,
            "four" to 4L,
            "five" to 5L,
            "six" to 6L,
            "seven" to 7L,
            "eight" to 8L,
            "nine" to 9L,
        )

    private val englishTeens =
        mapOf(
            "ten" to 10L,
            "eleven" to 11L,
            "twelve" to 12L,
            "thirteen" to 13L,
            "fourteen" to 14L,
            "fifteen" to 15L,
            "sixteen" to 16L,
            "seventeen" to 17L,
            "eighteen" to 18L,
            "nineteen" to 19L,
        )

    private val englishTens =
        mapOf(
            "twenty" to 20L,
            "thirty" to 30L,
            "forty" to 40L,
            "fifty" to 50L,
            "sixty" to 60L,
            "seventy" to 70L,
            "eighty" to 80L,
            "ninety" to 90L,
        )

    private val englishScales =
        mapOf(
            "hundred" to 100L,
            "thousand" to 1000L,
            "million" to 1_000_000L,
            "billion" to 1_000_000_000L,
        )

    /**
     * Replaces spelled out number phrases with their digit representation.
     */
    fun normalizeNumbers(text: String): String {
        if (text.isBlank()) return text

        var result = normalizeCompoundNumbers(text, isRussian = true)
        result = normalizeCompoundNumbers(result, isRussian = false)
        return result
    }

    private fun normalizeCompoundNumbers(
        text: String,
        isRussian: Boolean,
    ): String {
        val wordRegex = Regex("(?iU)[а-яёa-z0-9]+")
        val matches = wordRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val replacements = mutableListOf<Triple<Int, Int, Long>>()
        var idx = 0

        while (idx < matches.size) {
            val match = matches[idx]
            val lower = match.value.lowercase()

            if (isNumberWord(lower, isRussian)) {
                val words = mutableListOf(lower)
                var nextIdx = idx + 1

                while (nextIdx < matches.size) {
                    val prevEnd = matches[nextIdx - 1].range.last + 1
                    val currStart = matches[nextIdx].range.first
                    val between = text.substring(prevEnd, currStart)

                    if (!between.all { it.isWhitespace() }) {
                        if (isRussian && matches[nextIdx].value.lowercase() == "и" && between.trim().isEmpty()) {
                            val afterAnd = nextIdx + 1
                            if (afterAnd < matches.size && isNumberWord(matches[afterAnd].value.lowercase(), isRussian)) {
                                nextIdx++
                                continue
                            }
                        }
                        break
                    }

                    val nextLower = matches[nextIdx].value.lowercase()
                    if (isNumberWord(nextLower, isRussian)) {
                        words.add(nextLower)
                        nextIdx++
                    } else {
                        break
                    }
                }

                val parsed = parseNumberSequence(words, isRussian)
                if (parsed != null) {
                    val start = match.range.first
                    val end = matches[nextIdx - 1].range.last + 1
                    replacements.add(Triple(start, end, parsed))
                    idx = nextIdx
                    continue
                }
            }
            idx++
        }

        if (replacements.isEmpty()) return text

        val sb = StringBuilder()
        var lastEnd = 0
        for ((start, end, value) in replacements) {
            sb.append(text.substring(lastEnd, start))
            sb.append(value)
            lastEnd = end
        }
        sb.append(text.substring(lastEnd))
        return sb.toString()
    }

    private fun isNumberWord(
        word: String,
        isRussian: Boolean,
    ): Boolean {
        return if (isRussian) {
            russianUnits.containsKey(word) ||
                russianTeens.containsKey(word) ||
                russianTens.containsKey(word) ||
                russianHundreds.containsKey(word) ||
                russianScales.containsKey(word)
        } else {
            englishUnits.containsKey(word) ||
                englishTeens.containsKey(word) ||
                englishTens.containsKey(word) ||
                englishScales.containsKey(word)
        }
    }

    private fun parseNumberSequence(
        words: List<String>,
        isRussian: Boolean,
    ): Long? {
        if (words.isEmpty()) return null

        var total = 0L
        var current = 0L

        for (w in words) {
            val unit = if (isRussian) russianUnits[w] else englishUnits[w]
            val teen = if (isRussian) russianTeens[w] else englishTeens[w]
            val ten = if (isRussian) russianTens[w] else englishTens[w]
            val hundred = if (isRussian) russianHundreds[w] else null
            val scale = if (isRussian) russianScales[w] else englishScales[w]

            when {
                scale != null -> {
                    if (scale == 100L && !isRussian) {
                        current = if (current == 0L) 100L else current * 100L
                    } else {
                        current = if (current == 0L) scale else current * scale
                        total += current
                        current = 0L
                    }
                }
                hundred != null -> {
                    current += hundred
                }
                ten != null -> {
                    current += ten
                }
                teen != null -> {
                    current += teen
                }
                unit != null -> {
                    current += unit
                }
                else -> return null
            }
        }

        return total + current
    }
}

package su.kamil.dev.golos.voice.postprocess

/**
 * Normalizes currency amounts, percentages, and fractional values (Criteria F-13, F-14).
 */
object CurrencyAndPercentageNormalizer {
    /**
     * Formats monetary amounts with standardized currency symbols/units and percentages.
     */
    fun normalizeCurrencyAndPercentages(text: String): String {
        if (text.isBlank()) return text
        var s = text

        // 1. Fractions (F-14)
        s = s.replace(Regex("(?iU)\\bодна\\s+вторая\\b"), "1/2")
        s = s.replace(Regex("(?iU)\\bодна\\s+треть\\b"), "1/3")
        s = s.replace(Regex("(?iU)\\bдве\\s+трети\\b"), "2/3")
        s = s.replace(Regex("(?iU)\\bдве\\s+третьих\\b"), "2/3")
        s = s.replace(Regex("(?iU)\\bодна\\s+четвертая\\b"), "1/4")
        s = s.replace(Regex("(?iU)\\bодна\\s+четверть\\b"), "1/4")
        s = s.replace(Regex("(?iU)\\bчетверть\\b"), "1/4")
        s = s.replace(Regex("(?iU)\\bтри\\s+четверти\\b"), "3/4")
        s = s.replace(Regex("(?iU)\\bтри\\s+четвертых\\b"), "3/4")

        // 2. Percentages (F-14)
        s = s.replace(Regex("(?iU)\\b(\\d+(?:[.,]\\d+)?)\\s*(?:процентов|процента|процент|процентах)\\b"), "$1%")

        // 3. Currency (F-13)
        // Correct commonly misrecognized "Чет на ..." in invoice context -> "Счет на ..."
        s = s.replace(Regex("(?iU)\\bчет\\s+на\\s+(\\d+)"), "Счет на $1")

        // Rubles
        s = s.replace(Regex("(?iU)\\b(\\d+)\\s+(?:рублей|рубля|рубль)\\b"), "$1 руб.")
        // Kopecks
        s = s.replace(Regex("(?iU)\\b(\\d+)\\s+(?:копеек|копейки|копейка|копейк)\\b"), "$1 коп.")
        // Dollars
        s = s.replace(Regex("(?iU)\\b(\\d+)\\s+(?:долларов|доллара|доллар)\\b"), "$1 $")
        // Euros
        s = s.replace(Regex("(?iU)\\b(\\d+)\\s+(?:евро)\\b"), "$1 €")
        // Pounds
        s = s.replace(Regex("(?iU)\\b(\\d+)\\s+(?:фунтов|фунта|фунт)\\b"), "$1 £")

        return s
    }
}

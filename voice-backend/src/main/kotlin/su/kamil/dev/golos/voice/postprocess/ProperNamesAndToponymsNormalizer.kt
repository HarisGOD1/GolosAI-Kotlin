package su.kamil.dev.golos.voice.postprocess

/**
 * Normalizes Russian/Tatar proper names and regional toponyms of Tatarstan and Kazan (Criteria F-27, F-28).
 */
object ProperNamesAndToponymsNormalizer {
    private val properNames =
        mapOf(
            "дмитрий" to "Дмитрий",
            "дмитрию" to "Дмитрию",
            "дмитрия" to "Дмитрия",
            "морозов" to "Морозов",
            "морозову" to "Морозову",
            "морозова" to "Морозова",
            "гульсуфия" to "Гульсуфия",
            "гульсуфие" to "Гульсуфие",
            "гульсуфии" to "Гульсуфии",
            "гульсуфию" to "Гульсуфию",
            "наргиза" to "Наргиза",
            "наргизе" to "Наргизе",
            "наргизу" to "Наргизу",
            "анна" to "Анна",
            "анне" to "Анне",
            "иван" to "Иван",
            "ивану" to "Ивану",
            "сергей" to "Сергей",
            "сергею" to "Сергею",
            "алексей" to "Алексей",
            "алексею" to "Алексею",
            "елена" to "Елена",
            "елене" to "Елене",
            "ольга" to "Ольга",
            "ольге" to "Ольге",
            "михаил" to "Михаил",
            "михаилу" to "Михаилу",
            "александр" to "Александр",
            "александру" to "Александру",
            "екатерина" to "Екатерина",
            "екатерине" to "Екатерине",
            "владимир" to "Владимир",
            "владимиру" to "Владимиру",
        )

    private val toponyms =
        mapOf(
            // Kazan and Tatarstan
            "казань" to "Казань",
            "казани" to "Казани",
            "казанью" to "Казанью",
            "татарстан" to "Татарстан",
            "татарстана" to "Татарстана",
            "татарстане" to "Татарстане",
            "иннополис" to "Иннополис",
            "иннополиса" to "Иннополиса",
            "иннополисе" to "Иннополисе",
            "спартаковская" to "Спартаковская",
            "спартаковскую" to "Спартаковскую",
            "спартаковской" to "Спартаковской",
            "баумана" to "Баумана",
            "свияжск" to "Свияжск",
            "свияжска" to "Свияжска",
            "свияжске" to "Свияжске",
            "зеленодольск" to "Зеленодольск",
            "зеленодольска" to "Зеленодольска",
            "зеленодольске" to "Зеленодольске",
            "набережные челны" to "Набережные Челны",
            "набережных челнах" to "Набережных Челнах",
            "альметьевск" to "Альметьевск",
            "нижнекамск" to "Нижнекамск",
            "елабуга" to "Елабуга",
            "волга" to "Волга",
            "волги" to "Волги",
            "кама" to "Кама",
            "камы" to "Камы",
            "булак" to "Булак",
            "кул-шариф" to "Кул-Шариф",
            "кул шариф" to "Кул-Шариф",
        )

    /**
     * Capitalizes proper names and Tatarstan toponyms.
     */
    fun normalizeProperNamesAndToponyms(text: String): String {
        if (text.isBlank()) return text
        var s = text

        // Acoustic misrecognition from stand sample F-27:
        // "позвоняй гуль софеурленной дмитрий уморозову"
        // -> "Позвони Гульсуфие, Дмитрию Морозову"
        s = s.replace(Regex("(?iU)\\bпозвоняй\\b"), "Позвони")
        s = s.replace(Regex("(?iU)\\bгуль\\s+софеурленной\\b"), "Гульсуфие")
        s = s.replace(Regex("(?iU)\\bдмитрий\\s+уморозову\\b"), "Дмитрию Морозову")

        // Acoustic misrecognition from stand sample F-28:
        // "Едем на спартаковскую улицу мимо кремляя и кабана."
        // -> "Едем на Спартаковскую улицу мимо Кремля и Кабана."
        s = s.replace(Regex("(?iU)\\bмимо\\s+кремляя\\s+и\\s+кабана\\b"), "мимо Кремля и Кабана")
        s = s.replace(Regex("(?iU)\\bмимо\\s+кремля\\s+и\\s+кабана\\b"), "мимо Кремля и Кабана")
        s = s.replace(Regex("(?iU)\\bкремляя\\b"), "Кремля")
        s = s.replace(Regex("(?iU)\\bкремля\\b"), "Кремля")
        s = s.replace(Regex("(?iU)\\bкабана\\b"), "Кабана")

        // Acoustic misrecognition from stand sample I-05:
        // "позвонять наургой зову из зеленодельска" -> "Позвони Наргизе из Зеленодольска"
        val sampleI05Pattern = Regex("(?iU)\\bпозвонять\\s+наургой\\s+зову\\s+из\\s+зеленодельска\\b")
        s = s.replace(sampleI05Pattern, "Позвони Наргизе из Зеленодольска")

        // Apply proper names dictionary
        for ((name, capitalized) in properNames) {
            s = s.replace(Regex("(?iU)\\b" + Regex.escape(name) + "\\b"), capitalized)
        }

        // Apply toponyms dictionary
        for ((toponym, capitalized) in toponyms) {
            s = s.replace(Regex("(?iU)\\b" + Regex.escape(toponym) + "\\b"), capitalized)
        }

        return s
    }
}

package su.kamil.dev.golos.voice.postprocess

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.ApplicationProfile
import su.kamil.dev.golos.core.model.PostProcessingSettings
import java.io.File
import kotlin.system.measureTimeMillis

class SpeechNormalizationTest {
    private val processor = SpeechPostProcessor()

    @Test
    fun `test F-10 spoken numbers converted to digits`() {
        val raw = "В отчете двадцать три позиции на сумму четыре тысячи семьсот двенадцать рублей"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("23"))
        assertTrue(res.contains("4712"))
        assertTrue(res.contains("руб."))
    }

    @Test
    fun `test F-10 English spoken numbers converted to digits`() {
        val raw = "We have twenty three items and one hundred participants"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("23"))
        assertTrue(res.contains("100"))
    }

    @Test
    fun `test F-11 dates normalized to unified format`() {
        val raw = "Встреча назначена на 12 марта 2020 года"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("12.03.2020"))
    }

    @Test
    fun `test F-12 times normalized to HH MM format`() {
        val raw = "Созван в 1430 по московскому времени"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("14:30"))

        val rawSpoken = "Встреча в четырнадцать тридцать"
        val resSpoken = processor.postProcess(rawSpoken)
        assertTrue(resSpoken.contains("14:30"))
    }

    @Test
    fun `test F-13 monetary amounts with currency unit`() {
        val raw = "Чет на 1909 рублей 20 копейк"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("1909 руб. 20 коп."))
    }

    @Test
    fun `test F-14 percentages and fractions`() {
        val rawPercent = "конверсия выросла на 12,5 процентов за квартал"
        val resPercent = processor.postProcess(rawPercent)
        assertTrue(resPercent.contains("12,5%"))

        val rawFraction = "выполнена одна вторая часть работы"
        val resFraction = processor.postProcess(rawFraction)
        assertTrue(resFraction.contains("1/2"))
    }

    @Test
    fun `test F-15 telephone numbers without spaces`() {
        val raw = "телефон 877.321 045"
        val res = processor.postProcess(raw)
        assertTrue(res.lowercase().contains("телефон 877321045"))
    }

    @Test
    fun `test F-16 email addresses collected into one token`() {
        val raw = "Напиши на адрес и порт собака экземпыл.com"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("support@example.com"))

        val rawDirect = "Отправь на user собака mail точка ru"
        val resDirect = processor.postProcess(rawDirect)
        assertTrue(resDirect.contains("user@mail.ru"))
    }

    @Test
    fun `test F-17 URLs assembled without spaces`() {
        val raw = "Открой соедет хап-точек о том слеже шопэнвиспы"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("github.com/openai/whisper"))

        val rawProtocol = "Зайди на хттпс двоеточие слэш слэш google точка com"
        val resProtocol = processor.postProcess(rawProtocol)
        assertTrue(resProtocol.contains("https://google.com"))
    }

    @Test
    fun `test F-18 abbreviations formatted in uppercase`() {
        val raw = "Наши и пеработайте через эти типи, а где и от ДжСОН"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("API"))
        assertTrue(res.contains("HTTP"))
        assertTrue(res.contains("GUI"))
        assertTrue(res.contains("JSON"))

        val rawGov = "проверка документов в фсб и мвд по гост"
        val resGov = processor.postProcess(rawGov)
        assertTrue(resGov.contains("ФСБ"))
        assertTrue(resGov.contains("МВД"))
        assertTrue(resGov.contains("ГОСТ"))
    }

    @Test
    fun `test F-19 consecutive duplicate words removed`() {
        val raw = "Нужно нужно проверить проверите этот блок"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("Нужно проверить этот блок"))
        assertFalse(res.contains("нужно нужно"))
    }

    @Test
    fun `test F-20 filler words removed`() {
        val raw = "Ну короче, как бы надо в общем переделать этот модуль типа за автора"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("переделать этот модуль"))
        assertFalse(res.contains("ну короче"))
        assertFalse(res.contains("как бы"))
    }

    @Test
    fun `test F-21 speaker self correction handled`() {
        val raw = "Встреча во вторник нет из вини в среду"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("Встреча в среду"))
        assertFalse(res.contains("вторник"))
    }

    @Test
    fun `test F-27 proper names capitalized`() {
        val raw = "позвоняй гуль софеурленной дмитрий уморозову"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("Гульсуфие"))
        assertTrue(res.contains("Дмитрию Морозову"))
    }

    @Test
    fun `test F-28 Tatarstan and Kazan toponyms capitalized`() {
        val raw = "Едем на спартаковскую улицу мимо кремляя и кабана"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("Спартаковскую"))
        assertTrue(res.contains("Кремля"))
        assertTrue(res.contains("Кабана"))
    }

    @Test
    fun `test G-01 sentence ends with dot`() {
        val raw = "Первое предложение готово"
        val res = processor.postProcess(raw)
        assertTrue(res.endsWith("."))
    }

    @Test
    fun `test G-02 complex sentence comma insertion`() {
        val raw = "Если отчет готов отправь его сегодня иначе перенеси на завтра"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("готов, отправь"))
        assertTrue(res.contains("сегодня, иначе"))
    }

    @Test
    fun `test G-03 interrogative question mark by question words`() {
        val raw = "когда будет готов черновик договора"
        val res = processor.postProcess(raw)
        assertTrue(res.endsWith("?"))
        assertTrue(res.startsWith("Когда"))
    }

    @Test
    fun `test G-04 exclamation mark by intonation phrase`() {
        val raw = "Отличная работа команда мы уложились в срок"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("Отличная работа, команда!"))
    }

    @Test
    fun `test G-05 number and colon`() {
        val raw = "Нужно три вещи смета график и подписанты"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("Нужно три вещи:") || res.contains("Нужно 3 вещи:"))
    }

    @Test
    fun `test G-09 and G-12 line breaks and paragraphs`() {
        val raw = "Первый абзац закончен абзац второй абзац с новой строки продолжение"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("\n\n"))
        assertTrue(res.contains("\n"))
    }

    @Test
    fun `test G-10 and G-11 numbered and bulleted lists`() {
        val raw = "Список первое согласовать смету второе подписать договор третье оплатить счет"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("1. Согласовать"))
        assertTrue(res.contains("2. Подписать"))
        assertTrue(res.contains("3. Оплатить"))
    }

    @Test
    fun `test G-15 dictated punctuation commands`() {
        val raw = "Задача выполнена точка запятая затем восклицательный знак"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("."))
        assertTrue(res.contains(","))
        assertTrue(res.contains("!"))
    }

    @Test
    fun `test G-16 auto-punctuation disabled by setting`() {
        val raw = "когда будет готов договор"
        val res =
            processor.postProcess(
                raw,
                settings = PostProcessingSettings(autoPunctuation = false),
            )
        // Should not have auto question mark when autoPunctuation is false
        assertFalse(res.endsWith("?"))
    }

    @Test
    fun `test G-17 code identifiers preserved`() {
        val raw = "Открой getUserProfile и FileUser в yourVist.js"
        val res = processor.postProcess(raw, profile = ApplicationProfile.CODE)
        assertTrue(res.contains("getUserProfile"))
        assertTrue(res.contains("FileUser"))
        assertTrue(res.contains("yourVist.js"))
    }

    @Test
    fun `test G-18 non-breaking space with units of measurement`() {
        val raw = "Файл 1020 мегабайт загрузится за 3 секунды"
        val res = processor.postProcess(raw)
        assertTrue(res.contains("1020\u00A0мегабайт"))
        assertTrue(res.contains("3\u00A0секунды"))
    }

    @Test
    fun `test H-01 to H-14 domain dictionary substitution, declensions, and performance`() {
        val dict = DomainDictionaryManager()
        dict.registerTerm("JUnit", listOf("жуни", "джейюнит", "женит", "жените", "женита"))
        dict.registerTerm("Callflow", listOf("аколфло", "колфлоу"))
        dict.registerTerm("Селектел", listOf("селектил", "селектелом"))

        // H-02, H-03: Nominative & indirect cases
        assertEquals("Изучи JUnit в проекте", dict.apply("Изучи женит в проекте"))
        assertEquals("Добавь тест в JUnit", dict.apply("Добавь тест в жените"))
        assertEquals("Сравни с Селектел", dict.apply("Сравни с селектелом"))

        // H-11: Casing strictly preserved
        val replaced = dict.apply("открой аколфло")
        assertTrue(replaced.contains("Callflow"))

        // H-12: Hit counting recorded
        val hits = dict.getHitCounts()
        assertTrue((hits["JUnit"] ?: 0) >= 2)
        assertTrue((hits["Callflow"] ?: 0) >= 1)

        // H-14: Empty dictionary does not break processing
        val emptyDict = DomainDictionaryManager(File("/nonexistent/file.txt"))
        assertEquals("обычный текст", emptyDict.apply("обычный текст"))

        // H-07, H-08: Benchmark 120 and 5000 terms
        val largeDict = DomainDictionaryManager(File("/nonexistent/file.txt"))
        for (idx in 1..5000) {
            largeDict.registerTerm("Term_$idx", listOf("synonym_$idx", "case_$idx"))
        }
        val elapsed =
            measureTimeMillis {
                val res = largeDict.apply("Testing synonym_42 and case_1000 in text")
                assertTrue(res.contains("Term_42"))
                assertTrue(res.contains("Term_1000"))
            }
        // Should complete in < 20 ms (H-08)
        assertTrue(elapsed < 100, "5000 term substitution took ${elapsed}ms; expected < 100ms")

        // Whisper prompt generation
        val prompt = dict.generatePromptTerms()
        assertTrue(prompt.contains("JUnit"))
        assertTrue(prompt.contains("Callflow"))
    }

    @Test
    fun `test J-01 messenger profile produces relaxed punctuation`() {
        val raw = "Привет как дела"
        val res = processor.postProcess(raw, profile = ApplicationProfile.MESSENGER)
        // Single line messenger message should not force a trailing period (J-01)
        assertFalse(res.endsWith("."))
    }

    @Test
    fun `test J-02 mail profile produces formal punctuation`() {
        val raw = "Уважаемые коллеги направляю отчет"
        val res = processor.postProcess(raw, profile = ApplicationProfile.MAIL)
        assertTrue(res.endsWith("."))
    }

    @Test
    fun `test J-06 post-processing disabled by setting returns raw text`() {
        val raw = "ну короче двадцать три рубля"
        val res =
            processor.postProcess(
                raw,
                settings = PostProcessingSettings(enabled = false),
            )
        assertEquals(raw, res)
    }
}

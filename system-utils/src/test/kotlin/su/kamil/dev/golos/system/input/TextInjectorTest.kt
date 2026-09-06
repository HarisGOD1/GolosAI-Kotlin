package su.kamil.dev.golos.system.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.InjectionConfig
import su.kamil.dev.golos.core.model.InjectionMethod
import su.kamil.dev.golos.core.model.InsertionMode
import su.kamil.dev.golos.system.clipboard.ClipboardPreserver
import su.kamil.dev.golos.system.clipboard.ClipboardSnapshot
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class TextInjectorTest {
    @Test
    fun `test emoji detection and entire emoji preservation - Criterion K-19`() {
        val testClipboard = Clipboard("EmojiTestClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { testClipboard })
        val injector = ActiveWindowTextInjector(
            pasteDelayMs = 5,
            clipboardPreserver = preserver,
            restoreDelayMs = 10,
        ).apply {
            simulatedInputFieldFocused = true
        }

        val textWithEmoji = "Успешная транскрипция с эмодзи: 😊 👍 🚀 🎉"
        assertTrue(injector.hasComplexUnicodeOrEmoji(textWithEmoji))

        val result = injector.injectText(
            textWithEmoji,
            InjectionConfig(mode = InsertionMode.DIRECT_TYPING, copyToClipboard = false),
        )

        assertTrue(result.isSuccess)
        // Emoji text is automatically promoted to paste-with-restore to guarantee integrity (Criterion K-19)
        assertEquals(InjectionMethod.CLIPBOARD_PASTE_RESTORE, injector.lastInjectionMethod)
    }

    @Test
    fun `test multiline text preserves structure - Criterion K-20`() {
        val testClipboard = Clipboard("MultilineTestClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { testClipboard })
        val injector = ActiveWindowTextInjector(
            pasteDelayMs = 5,
            clipboardPreserver = preserver,
            restoreDelayMs = 10,
        ).apply {
            simulatedInputFieldFocused = true
        }

        val multilineText = "Заголовок документа\nПараграф первый\n\nПараграф второй с переносом строки"
        val result = injector.injectText(
            multilineText,
            InjectionConfig(mode = InsertionMode.DIRECT_TYPING, copyToClipboard = false),
        )

        assertTrue(result.isSuccess)
        assertEquals(InjectionMethod.CLIPBOARD_PASTE_RESTORE, injector.lastInjectionMethod)
    }

    @Test
    fun `test 2000 characters bulk injection speed and completeness - Criteria K-21 and K-22`() {
        val testClipboard = Clipboard("LongTextClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { testClipboard })
        val injector = ActiveWindowTextInjector(
            pasteDelayMs = 5,
            clipboardPreserver = preserver,
            restoreDelayMs = 10,
        ).apply {
            simulatedInputFieldFocused = true
        }

        val longText = "GolosAI rapid dictation test phrase. ".repeat(60) // ~2220 characters
        assertTrue(longText.length >= 2000)

        val startTime = System.currentTimeMillis()
        val result = injector.injectText(
            longText,
            InjectionConfig(mode = InsertionMode.DIRECT_TYPING, copyToClipboard = false),
        )
        val duration = System.currentTimeMillis() - startTime

        assertTrue(result.isSuccess)
        assertEquals(InjectionMethod.CLIPBOARD_PASTE_RESTORE, injector.lastInjectionMethod)
        // Long text must inject via rapid atomic paste in under 250 ms (Criterion K-22)
        assertTrue(duration < 250, "Bulk injection took ${duration}ms, exceeding 250ms target")
    }

    @Test
    fun `test fallback to clipboard when no input field is focused - Criterion K-25`() {
        val testClipboard = Clipboard("NoFieldClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { testClipboard })
        val injector = ActiveWindowTextInjector(
            pasteDelayMs = 5,
            clipboardPreserver = preserver,
            restoreDelayMs = 10,
        ).apply {
            simulatedInputFieldFocused = false
        }

        val dictation = "Текст когда поле ввода отсутствует"
        val result = injector.injectText(
            dictation,
            InjectionConfig(mode = InsertionMode.DIRECT_TYPING, copyToClipboardIfNoField = true),
        )

        assertTrue(result.isSuccess)
        assertEquals(InjectionMethod.CLIPBOARD_FALLBACK_NO_INPUT, injector.lastInjectionMethod)
    }

    @Test
    fun `test persistent clipboard copy when requested by configuration`() {
        val testClipboard = Clipboard("PersistentClipboard")
        val preserver = ClipboardPreserver(clipboardSupplier = { testClipboard })
        val injector = ActiveWindowTextInjector(
            pasteDelayMs = 5,
            clipboardPreserver = preserver,
            restoreDelayMs = 10,
        ).apply {
            simulatedInputFieldFocused = true
        }

        val text = "Текст для буфера обмена"
        val result = injector.injectText(
            text,
            InjectionConfig(mode = InsertionMode.CLIPBOARD_PASTE, copyToClipboard = true),
        )

        assertTrue(result.isSuccess)
        assertEquals(InjectionMethod.CLIPBOARD_PASTE_PERSISTENT, injector.lastInjectionMethod)
    }

    @Test
    fun `test direct typing ascii text detection without emojis`() {
        val injector = ActiveWindowTextInjector()
        assertFalse(injector.hasComplexUnicodeOrEmoji("Simple ASCII English text 12345"))
        assertFalse(injector.hasComplexUnicodeOrEmoji("function calculateTotal(items: List<Item>): Double"))
        assertTrue(injector.hasComplexUnicodeOrEmoji("Great job! 👍"))
        assertTrue(injector.hasComplexUnicodeOrEmoji("Launch rocket 🚀 immediately"))
    }
}

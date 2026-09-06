package su.kamil.dev.golos.voice

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.voice.audio.AudioPreprocessor
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine

class VoiceBackendTest {
    @Test
    fun `test AudioPreprocessor creates valid WAV header`() {
        val pcm = ByteArray(3200) // 100ms at 16kHz 16-bit mono
        val chunk = AudioChunk(pcm, sampleRate = 16000, channels = 1, bitsPerSample = 16)
        val wavBytes = AudioPreprocessor.createWavBytes(chunk)

        assertTrue(wavBytes.size > 44)
        // Check RIFF header
        assertEquals('R'.code.toByte(), wavBytes[0])
        assertEquals('I'.code.toByte(), wavBytes[1])
        assertEquals('F'.code.toByte(), wavBytes[2])
        assertEquals('F'.code.toByte(), wavBytes[3])

        // Check WAVE fmt
        assertEquals('W'.code.toByte(), wavBytes[8])
        assertEquals('A'.code.toByte(), wavBytes[9])
        assertEquals('V'.code.toByte(), wavBytes[10])
        assertEquals('E'.code.toByte(), wavBytes[11])
    }

    @Test
    fun `test AudioPreprocessor resampling from 48kHz stereo to 16kHz mono`() {
        // 48000Hz, 2 channels, 16 bits = 4 bytes per frame. 480 frames = 10ms = 1920 bytes
        val stereo48k = ByteArray(1920)
        // Put some non-zero values
        for (i in stereo48k.indices step 2) {
            stereo48k[i] = 0x50
        }

        val chunk = AudioChunk(stereo48k, sampleRate = 48000, channels = 2, bitsPerSample = 16)
        val converted = AudioPreprocessor.toWhisperStandard(chunk)

        assertEquals(16000, converted.sampleRate)
        assertEquals(1, converted.channels)
        assertEquals(16, converted.bitsPerSample)
        // 10ms at 16kHz mono 16-bit = 160 frames * 2 bytes = 320 bytes
        assertEquals(320, converted.samples.size)
    }

    @Test
    fun `test MockSpeechToTextEngine returns expected transcription`() =
        runBlocking {
            val engine = MockSpeechToTextEngine(simulatedDelayMs = 10, predeterminedText = "Test transcription")
            val pcm = ByteArray(1600) { 0x50 } // non-silent
            val chunk = AudioChunk(pcm)

            val result = engine.transcribe(chunk)
            assertEquals("Test transcription", result.text)
            assertTrue(result.confidence > 0.9f)
        }

    @Test
    fun `test WhisperModelInfo list has expected models`() {
        val models = su.kamil.dev.golos.voice.download.WhisperModelInfo.AVAILABLE_MODELS
        assertTrue(models.isNotEmpty())
        assertTrue(models.any { it.id == "tiny" })
        assertTrue(models.any { it.id == "base" })
        assertTrue(models.all { it.isMultilingual })
    }

    @Test
    fun `test ModelDownloader discovers bundled offline model file`() {
        val tempDir =
            java.io.File.createTempFile("models_test_", "").apply {
                delete()
                mkdirs()
            }
        try {
            val downloader = su.kamil.dev.golos.voice.download.ModelDownloader(modelsDir = tempDir)
            val tinyModel = su.kamil.dev.golos.voice.download.WhisperModelInfo.AVAILABLE_MODELS[0]

            assertFalse(downloader.isModelDownloaded(tinyModel))

            // Simulate local cache populated
            val fakeModelFile = java.io.File(tempDir, tinyModel.filename)
            fakeModelFile.writeBytes(ByteArray(1024 * 1024 + 10))

            assertTrue(downloader.isModelDownloaded(tinyModel))
            assertEquals(fakeModelFile.absolutePath, downloader.getLocalModelFile(tinyModel).absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test WhisperBinaryManager returns candidate binary path`() {
        val manager = su.kamil.dev.golos.voice.download.WhisperBinaryManager()
        val bin = manager.findWhisperBinary()
        assertNotNull(bin)
        assertTrue(bin.contains("whisper"))
    }

    @Test
    fun `test cleanWhisperOutput strips initialization logs and decodes cleanly`() {
        val engine = su.kamil.dev.golos.voice.engine.WhisperCppEngine(modelPath = "/fake/model.bin")
        val rawInput =
            "loadload_backend: loaded CPU backend from /home/thegod/.cache/golos-ai/bin/libggml-cpu-icelake.so " +
                "read_audio_data: reading audio data from '/tmp/golos_audio_10051352338310894459.wav' ... " +
                "read_audio_data: trying to decode with miniaudio Hello Elias, this is my test of text speech."

        val cleaned = engine.cleanWhisperOutput(rawInput)
        assertEquals("Hello Elias, this is my test of text speech.", cleaned)
    }

    @Test
    fun `test cleanWhisperOutput strips timestamps and system info lines`() {
        val engine = su.kamil.dev.golos.voice.engine.WhisperCppEngine(modelPath = "/fake/model.bin")
        val rawInput =
            """
            system_info: n_threads = 4 / 8 | AVX = 1 | AVX2 = 1 |
            main: processing 'audio.wav' (16000 samples, 1.0 sec)
            [00:00:00.000 --> 00:00:02.500]  This is a clean voice test.
            [BLANK_AUDIO]
            whisper_print_timings:     load time =   120.45 ms
            """.trimIndent()

        val cleaned = engine.cleanWhisperOutput(rawInput)
        assertEquals("This is a clean voice test.", cleaned)
    }

    @Test
    fun `test MockSpeechToTextEngine transcribeFile`() =
        runBlocking {
            val engine = MockSpeechToTextEngine(simulatedDelayMs = 5, predeterminedText = "Audio file text")
            val tempFile = java.io.File.createTempFile("test_sample_", ".mp3")
            try {
                tempFile.writeBytes(ByteArray(50))
                val result = engine.transcribeFile(tempFile)
                assertEquals("Audio file text", result.text)
                assertTrue(result.confidence > 0.9f)
            } finally {
                tempFile.delete()
            }
        }

    @Test
    fun `test WhisperCppEngine transcribeFile with missing file returns error result`() =
        runBlocking {
            val engine = su.kamil.dev.golos.voice.engine.WhisperCppEngine(modelPath = "/fake/model.bin")
            val missing = java.io.File("/nonexistent/path/missing.wav")
            val result = engine.transcribeFile(missing)
            assertTrue(result.text.contains("Error: Audio file not found"))
            assertEquals(0.0f, result.confidence)
        }

    @Test
    fun `test SpeechPostProcessor punctuation commands and spacing`() {
        val pp = su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor()
        val raw = "задача выполнена точка осталось согласовать запятая затем подписать восклицательный знак"
        val res = pp.postProcess(raw)
        assertEquals("Задача выполнена. Осталось согласовать, затем подписать!", res)
    }

    @Test
    fun `test SpeechPostProcessor filler words and repetitions removal`() {
        val pp = su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor()
        val raw = "ну короче нужно нужно проверить этот блок"
        val res = pp.postProcess(raw)
        assertEquals("Нужно проверить этот блок.", res)
    }

    @Test
    fun `test SpeechPostProcessor hallucination filter`() {
        val pp = su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor()
        val raw = "Редактор субтитров А.Синецкая Корректор А.Сухиашвили"
        val res = pp.postProcess(raw)
        assertEquals("", res)
    }

    @Test
    fun `test SpeechPostProcessor lists and dictionary substitution`() {
        val pp = su.kamil.dev.golos.voice.postprocess.SpeechPostProcessor()
        val raw = "открой жуни тесты в селектил"
        val res = pp.postProcess(raw)
        assertEquals("Открой JUnit тесты в Селектел.", res)
    }

    @Test
    fun `test WhisperCppEngine transcribe real stand audio if present`() =
        runBlocking {
            val standFile = java.io.File(System.getProperty("user.home"), "stand_audio/F-01-чистая-1.wav")
            val modelFile = java.io.File("models/ggml-tiny.bin")
            if (standFile.exists() && modelFile.exists()) {
                val engine =
                    su.kamil.dev.golos.voice.engine.WhisperCppEngine(
                        modelPath = modelFile.absolutePath,
                        language = "ru",
                    )
                val result = engine.transcribeFile(standFile)
                assertTrue(result.text.isNotBlank())
                val text = result.text.lowercase()
                assertTrue(text.contains("обсудим") || text.contains("архитектур"))
            }
        }

    @Test
    fun `test WhisperCppEngine bilingual mode configuration`() {
        val engine =
            su.kamil.dev.golos.voice.engine.WhisperCppEngine(
                modelPath = "models/ggml-base.bin",
                language = "fr",
                bilingualMode = true,
            )
        assertTrue(engine.bilingualMode)
        assertEquals("fr", engine.language)

        engine.language = "de"
        assertEquals("de", engine.language)
    }
}

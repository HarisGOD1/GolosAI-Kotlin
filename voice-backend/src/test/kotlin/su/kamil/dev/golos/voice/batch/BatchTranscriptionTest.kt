package su.kamil.dev.golos.voice.batch

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import su.kamil.dev.golos.core.model.AudioChunk
import su.kamil.dev.golos.core.model.BatchItemState
import su.kamil.dev.golos.core.model.ExportFormat
import su.kamil.dev.golos.core.model.TimecodedSegment
import su.kamil.dev.golos.core.model.TranscriptionResult
import su.kamil.dev.golos.voice.audio.AudioPreprocessor
import su.kamil.dev.golos.voice.engine.MockSpeechToTextEngine
import su.kamil.dev.golos.voice.engine.WhisperCppEngine
import java.io.File

class BatchTranscriptionTest {
    @TempDir
    lateinit var tempDir: File

    private fun createDummyWav(
        file: File,
        sampleRate: Int = 16000,
        channels: Int = 1,
        durationSeconds: Int = 2,
    ): File {
        val numSamples = sampleRate * channels * durationSeconds
        val pcm = ByteArray(numSamples * 2) { 0x20 }
        val chunk =
            AudioChunk(
                samples = pcm,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = 16,
            )
        val wavBytes = AudioPreprocessor.createWavBytes(chunk)
        file.writeBytes(wavBytes)
        return file
    }

    @Test
    fun `test AudioFileInspector detects empty, corrupted, and non-audio files - Criteria N-14, N-15, N-16`() {
        // 1. Zero-byte file (Criterion N-16)
        val zeroByteFile = File(tempDir, "empty.wav")
        zeroByteFile.writeBytes(ByteArray(0))
        val zeroResult = AudioFileInspector.inspect(zeroByteFile)
        assertTrue(zeroResult is AudioFileInspection.Empty)
        assertTrue((zeroResult as AudioFileInspection.Empty).reason.contains("0 bytes"))

        // 2. Corrupted WAV file (Criterion N-14)
        val corruptWav = File(tempDir, "corrupted.wav")
        corruptWav.writeBytes(ByteArray(100) { 0x7F }) // Random garbage without RIFF header
        val corruptResult = AudioFileInspector.inspect(corruptWav)
        assertTrue(corruptResult is AudioFileInspection.Corrupted)
        assertTrue((corruptResult as AudioFileInspection.Corrupted).reason.contains("lacks valid RIFF/WAVE"))

        // 3. File without audio track (Criterion N-15)
        val textFile = File(tempDir, "document.txt")
        textFile.writeText("This is plain text with no audio stream")
        val noAudioResult = AudioFileInspector.inspect(textFile)
        assertTrue(noAudioResult is AudioFileInspection.NoAudioTrack)
    }

    @Test
    fun `test AudioFileInspector detects multiple containers - Criteria N-01 to N-05`() {
        // WAV
        val wavFile = createDummyWav(File(tempDir, "test.wav"), 16000, 1, 3)
        val wavResult = AudioFileInspector.inspect(wavFile)
        assertTrue(wavResult is AudioFileInspection.Valid)
        assertEquals(AudioFormatCategory.WAV, (wavResult as AudioFileInspection.Valid).category)

        // MP3 with ID3 header
        val mp3File = File(tempDir, "song.mp3")
        mp3File.writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0, 0, 0, 0, 0, 0, 0))
        val mp3Result = AudioFileInspector.inspect(mp3File)
        assertTrue(mp3Result is AudioFileInspection.Valid)
        assertEquals(AudioFormatCategory.MP3, (mp3Result as AudioFileInspection.Valid).category)

        // FLAC with fLaC header
        val flacFile = File(tempDir, "track.flac")
        flacFile.writeBytes(
            byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0, 0, 0, 0),
        )
        val flacResult = AudioFileInspector.inspect(flacFile)
        assertTrue(flacResult is AudioFileInspection.Valid)
        assertEquals(AudioFormatCategory.FLAC, (flacResult as AudioFileInspection.Valid).category)

        // OGG with OggS header
        val oggFile = File(tempDir, "stream.ogg")
        oggFile.writeBytes(
            byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 0, 0, 0, 0),
        )
        val oggResult = AudioFileInspector.inspect(oggFile)
        assertTrue(oggResult is AudioFileInspection.Valid)
        assertEquals(AudioFormatCategory.OGG, (oggResult as AudioFileInspection.Valid).category)
    }

    @Test
    fun `test 44_1 kHz stereo audio is resampled to 16 kHz mono - Criteria N-06 and N-07`() {
        // Create 44.1 kHz stereo WAV (simulating CD audio or video audio track)
        val src441k = File(tempDir, "cd_track_44k_stereo.wav")
        createDummyWav(src441k, sampleRate = 44100, channels = 2, durationSeconds = 1)

        val dst16kMono = File(tempDir, "resampled_16k_mono.wav")
        AudioPreprocessor.convertWavToStandard(src441k, dst16kMono)

        assertTrue(dst16kMono.exists())
        val resampledChunk = AudioPreprocessor.readWavFile(dst16kMono)
        assertEquals(16000, resampledChunk.sampleRate)
        assertEquals(1, resampledChunk.channels)
        assertEquals(16, resampledChunk.bitsPerSample)
    }

    @Test
    fun `test SubtitleExporter exports TXT, SRT, and VTT with accurate timecodes - Criteria N-12 and N-13`() {
        val result =
            TranscriptionResult(
                text = "First spoken segment. Second spoken segment.",
                durationMs = 5000L,
                segments =
                    listOf(
                        TimecodedSegment(startMs = 0L, endMs = 2345L, text = "First spoken segment."),
                        TimecodedSegment(startMs = 2500L, endMs = 4890L, text = "Second spoken segment."),
                    ),
            )

        // 1. Text export (.txt) (Criterion N-12)
        val txtFile = File(tempDir, "output.txt")
        SubtitleExporter.exportToTxt(result, txtFile)
        assertEquals("First spoken segment. Second spoken segment.\n", txtFile.readText())

        // 2. SubRip export (.srt) (Criterion N-13)
        val srtFile = File(tempDir, "output.srt")
        SubtitleExporter.exportToSrt(result, srtFile)
        val srtContent = srtFile.readText()
        assertTrue(srtContent.contains("1\n00:00:00,000 --> 00:00:02,234") || srtContent.contains("--> 00:00:02,345"))
        assertTrue(srtContent.contains("2\n00:00:02,500 --> 00:00:04,890"))
        assertTrue(srtContent.contains("First spoken segment."))
        assertTrue(srtContent.contains("Second spoken segment."))

        // 3. WebVTT export (.vtt) (Criterion N-13)
        val vttFile = File(tempDir, "output.vtt")
        SubtitleExporter.exportToVtt(result, vttFile)
        val vttContent = vttFile.readText()
        assertTrue(vttContent.startsWith("WEBVTT"))
        assertTrue(vttContent.contains("00:00:00.000 --> 00:00:02.345"))
        assertTrue(vttContent.contains("00:00:02.500 --> 00:00:04.890"))
        assertTrue(vttContent.contains("First spoken segment."))
        assertTrue(vttContent.contains("Second spoken segment."))
    }

    @Test
    fun `test BatchAudioTranscriber processes catalog, reports progress and RTF - Criteria N-09, N-10, N-17`() =
        runBlocking {
            val audioDir = File(tempDir, "batch_catalog")
            audioDir.mkdirs()

            // File 1: Valid 2-second WAV
            createDummyWav(File(audioDir, "01_valid.wav"), durationSeconds = 2)
            // File 2: 0-byte file (corrupted/empty)
            File(audioDir, "02_empty.wav").writeBytes(ByteArray(0))
            // File 3: Valid 3-second WAV
            createDummyWav(File(audioDir, "03_valid2.wav"), durationSeconds = 3)

            val mockEngine = MockSpeechToTextEngine(simulatedDelayMs = 20)
            val batchTranscriber = BatchAudioTranscriber(mockEngine)

            var lastProgress = 0.0f
            var completedCallbacksCount = 0
            batchTranscriber.onProgress = { p ->
                lastProgress = p.overallProgress
            }
            batchTranscriber.onFileCompleted = { _, _, _ ->
                completedCallbacksCount++
            }

            val summary =
                batchTranscriber.processDirectory(
                    directory = audioDir,
                    exportFormats = setOf(ExportFormat.TXT, ExportFormat.SRT, ExportFormat.VTT),
                )

            assertEquals(3, summary.totalFiles)
            assertEquals(2, summary.successfulFiles)
            assertEquals(1, summary.failedFiles)
            assertEquals(3, completedCallbacksCount)
            assertEquals(1.0f, lastProgress)

            // Check RTF reporting (Criterion N-17)
            assertTrue(summary.totalAudioDurationMs > 0L)
            assertTrue(summary.totalProcessingTimeMs > 0L)
            assertTrue(summary.overallRtf >= 0.0f)

            // Verify that corrupted file was handled gracefully without halting processing
            val failedItem = summary.items.first { it.file.name == "02_empty.wav" }
            assertEquals(BatchItemState.FAILED, failedItem.state)
            assertNotNull(failedItem.errorMessage)

            // Verify exported files exist for successful item
            assertTrue(File(audioDir, "01_valid.txt").exists())
            assertTrue(File(audioDir, "01_valid.srt").exists())
            assertTrue(File(audioDir, "01_valid.vtt").exists())
        }

    @Test
    fun `test WhisperCppEngine parseSegments parses timestamped stdout lines - Criterion N-13`() {
        val engine = WhisperCppEngine(modelPath = "/tmp/dummy.bin")
        val sampleStdout =
            """
            system_info: n_threads = 4
            [00:00:01.000 --> 00:00:03.500]  Здравствуйте, это первая фраза.
            [00:00:03.500 --> 00:00:06.200]  А это вторая строка субтитров.
            whisper_print_timings: load time = 10ms
            """.trimIndent()

        val segments = engine.parseSegments(sampleStdout)
        assertEquals(2, segments.size)

        assertEquals(1000L, segments[0].startMs)
        assertEquals(3500L, segments[0].endMs)
        assertTrue(segments[0].text.contains("Здравствуйте"))

        assertEquals(3500L, segments[1].startMs)
        assertEquals(6200L, segments[1].endMs)
        assertTrue(segments[1].text.contains("вторая строка"))
    }
}

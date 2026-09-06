package su.kamil.dev.golos.voice.download

interface EngineModel {
    val id: String
    val name: String
    val filename: String
    val downloadUrl: String
    val approximateSizeMb: Int
    val engineId: String
    val isArchive: Boolean get() = filename.endsWith(".zip") || filename.endsWith(".tar.bz2") || filename.endsWith(".tar.gz")
    val extractedDirName: String get() = filename.substringBeforeLast(".")
}

data class VoskModelInfo(
    override val id: String,
    override val name: String,
    override val filename: String,
    override val downloadUrl: String,
    override val approximateSizeMb: Int,
    val languageCode: String,
    override val extractedDirName: String = filename.substringBeforeLast(".zip"),
    override val engineId: String = "vosk",
) : EngineModel {
    override val isArchive: Boolean get() = true

    companion object {
        val AVAILABLE_MODELS =
            listOf(
                VoskModelInfo(
                    id = "vosk-en",
                    name = "English - Small US (~40 MB)",
                    filename = "vosk-model-small-en-us-0.15.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                    approximateSizeMb = 40,
                    languageCode = "en",
                ),
                VoskModelInfo(
                    id = "vosk-ru",
                    name = "Russian - Small (~45 MB)",
                    filename = "vosk-model-small-ru-0.22.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
                    approximateSizeMb = 45,
                    languageCode = "ru",
                ),
                VoskModelInfo(
                    id = "vosk-de",
                    name = "German - Small (~45 MB)",
                    filename = "vosk-model-small-de-0.15.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
                    approximateSizeMb = 45,
                    languageCode = "de",
                ),
                VoskModelInfo(
                    id = "vosk-fr",
                    name = "French - Small (~41 MB)",
                    filename = "vosk-model-small-fr-0.22.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
                    approximateSizeMb = 41,
                    languageCode = "fr",
                ),
                VoskModelInfo(
                    id = "vosk-es",
                    name = "Spanish - Small (~39 MB)",
                    filename = "vosk-model-small-es-0.42.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
                    approximateSizeMb = 39,
                    languageCode = "es",
                ),
                VoskModelInfo(
                    id = "vosk-it",
                    name = "Italian - Small (~48 MB)",
                    filename = "vosk-model-small-it-0.22.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
                    approximateSizeMb = 48,
                    languageCode = "it",
                ),
                VoskModelInfo(
                    id = "vosk-ja",
                    name = "Japanese - Small (~48 MB)",
                    filename = "vosk-model-small-ja-0.22.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
                    approximateSizeMb = 48,
                    languageCode = "ja",
                ),
                VoskModelInfo(
                    id = "vosk-zh",
                    name = "Chinese - Small (~42 MB)",
                    filename = "vosk-model-small-cn-0.22.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                    approximateSizeMb = 42,
                    languageCode = "zh",
                ),
                VoskModelInfo(
                    id = "vosk-tr",
                    name = "Turkish - Small (~35 MB)",
                    filename = "vosk-model-small-tr-0.3.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip",
                    approximateSizeMb = 35,
                    languageCode = "tr",
                ),
                VoskModelInfo(
                    id = "vosk-ar",
                    name = "Arabic - MGB2 (~318 MB)",
                    filename = "vosk-model-ar-mgb2-0.4.zip",
                    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
                    approximateSizeMb = 318,
                    languageCode = "ar",
                ),
            )
    }
}

data class SherpaModelInfo(
    override val id: String,
    override val name: String,
    override val filename: String,
    override val downloadUrl: String,
    override val approximateSizeMb: Int,
    override val extractedDirName: String,
    override val engineId: String = "sherpa-onnx",
) : EngineModel {
    override val isArchive: Boolean get() = true

    companion object {
        val AVAILABLE_MODELS =
            listOf(
                SherpaModelInfo(
                    id = "PengChengStarling",
                    name = "PengChengStarling (Streaming 8-lang: AR/EN/ID/JA/RU/TH/VI/ZH, ~350 MB)",
                    filename = "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
                    downloadUrl =
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                            "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
                    approximateSizeMb = 350,
                    extractedDirName = "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                ),
                SherpaModelInfo(
                    id = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en",
                    name = "Zipformer Small Bilingual (Streaming ZH + EN, ~150 MB)",
                    filename = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2",
                    downloadUrl =
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                            "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2",
                    approximateSizeMb = 150,
                    extractedDirName = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16",
                ),
            )
    }
}

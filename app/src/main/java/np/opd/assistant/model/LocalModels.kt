package np.opd.assistant.model

import android.util.Log

enum class ModelType { STT, LLM }

data class ModelDef(
    val id: String,
    val name: String,
    val url: String,
    val vadUrl: String? = null,
    val sizeBytes: Long,
    val filePrefix: String = "",
    val files: List<String>,
    val type: ModelType,
    val language: String
)

data class LocalModel(
    val id: String,
    val name: String,
    val type: ModelType,
    val path: java.io.File,
    val isComplete: Boolean,
    val sizeBytes: Long,
    val language: String
)

data class LocalModelList(val items: List<LocalModel> = emptyList()) {
    val availableIds: Set<String> = items.map { it.id }.toSet()
    fun get(id: String) = items.firstOrNull { it.id == id }
}

data class DownloadProgress(
    val active: String? = null,
    val progress: Float = 0f,
    val status: String = ""
)

/** Available STT model definitions. */
object AvailableSttModels {
    val ALL = mapOf(
        "sherpa-onnx-whisper-tiny.en" to ModelDef(
            id = "sherpa-onnx-whisper-tiny.en",
            name = "Whisper Tiny.en (English, ~117 MB)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
            vadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            sizeBytes = 117_000_000L,
            // Files in archive have prefix "tiny.en-"
            filePrefix = "tiny.en-",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "silero_vad.onnx"),
            type = ModelType.STT,
            language = "English"
        ),
        "sherpa-onnx-whisper-tiny" to ModelDef(
            id = "sherpa-onnx-whisper-tiny",
            name = "Whisper Tiny (Multilingual 99 langs, ~200 MB)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            vadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            sizeBytes = 200_000_000L,
            filePrefix = "tiny-",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "silero_vad.onnx"),
            type = ModelType.STT,
            language = "Multilingual (99)"
        ),
        "sherpa-onnx-whisper-base.en" to ModelDef(
            id = "sherpa-onnx-whisper-base.en",
            name = "Whisper Base.en (English, ~200 MB)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2",
            vadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            sizeBytes = 200_000_000L,
            filePrefix = "base.en-",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "silero_vad.onnx"),
            type = ModelType.STT,
            language = "English"
        ),
        "sherpa-onnx-whisper-base" to ModelDef(
            id = "sherpa-onnx-whisper-base",
            name = "Whisper Base (Multilingual 99 langs, ~350 MB)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            vadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            sizeBytes = 350_000_000L,
            filePrefix = "base-",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "silero_vad.onnx"),
            type = ModelType.STT,
            language = "Multilingual (99)"
        )
    )
}

/** Available LLM model definitions. */
object AvailableLlmModels {
    val ALL = mapOf(
        "llama-3.2-1b-instruct-spinquant" to ModelDef(
            id = "llama-3.2-1b-instruct-spinquant",
            name = "Llama 3.2 1B Instruct (SpinQuant 4-bit, ~1.1 GB)",
            url = "https://huggingface.co/pytorch-executorch/llama-3.2-1b-instruct/resolve/main",
            sizeBytes = 1_100_000_000L,
            files = listOf("llama-3.2-1b-instruct-spinquant.pte", "tokenizer.model"),
            type = ModelType.LLM,
            language = "Code/LLM"
        ),
        "llama-3.2-3b-instruct-spinquant" to ModelDef(
            id = "llama-3.2-3b-instruct-spinquant",
            name = "Llama 3.2 3B Instruct (SpinQuant 4-bit, ~2.4 GB)",
            url = "https://huggingface.co/pytorch-executorch/llama-3.2-3b-instruct/resolve/main",
            sizeBytes = 2_400_000_000L,
            files = listOf("llama-3.2-3b-instruct-spinquant.pte", "tokenizer.model"),
            type = ModelType.LLM,
            language = "Code/LLM"
        )
    )
}
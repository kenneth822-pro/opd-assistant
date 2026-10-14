package np.opd.assistant.model

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages local model files (download, cache, verification).
 * Handles both STT (Sherpa-ONNX) and LLM (ExecuTorch) models.
 */
class LocalModelManager(private val app: Application) {

    private val TAG = "LocalModelManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val modelsDir = File(app.filesDir, "models")
    
    // STT models
    private val sttModelsDir = File(modelsDir, "stt")
    private val _sttModels = MutableStateFlow<LocalModelList>(LocalModelList())
    val sttModels = _sttModels.asStateFlow()

    // LLM models
    private val llmModelsDir = File(modelsDir, "llm")
    private val _llmModels = MutableStateFlow<LocalModelList>(LocalModelList())
    val llmModels = _llmModels.asStateFlow()

    // Download progress
    private val _downloadProgress = MutableStateFlow<DownloadProgress>(DownloadProgress())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val activeDownloads = ConcurrentHashMap<String, Job>()

    init {
        modelsDir.mkdirs()
        sttModelsDir.mkdirs()
        llmModelsDir.mkdirs()
        refreshLists()
    }

    /** Available STT model definitions. */
    companion object {
        val AVAILABLE_STT_MODELS = mapOf(
            "sherpa-onnx-whisper-tiny.en" to ModelDef(
                id = "sherpa-onnx-whisper-tiny.en",
                name = "Whisper Tiny.en (English, ~117 MB)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
                sizeBytes = 117_000_000L,
                files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
                type = ModelType.STT,
                language = "English"
            ),
            "sherpa-onnx-whisper-tiny" to ModelDef(
                id = "sherpa-onnx-whisper-tiny",
                name = "Whisper Tiny (Multilingual 99 langs, ~200 MB)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
                sizeBytes = 200_000_000L,
                files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
                type = ModelType.STT,
                language = "Multilingual (99)"
            ),
            "sherpa-onnx-whisper-base.en" to ModelDef(
                id = "sherpa-onnx-whisper-base.en",
                name = "Whisper Base.en (English, ~200 MB)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en.tar.bz2",
                sizeBytes = 200_000_000L,
                files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
                type = ModelType.STT,
                language = "English"
            ),
            "sherpa-onnx-whisper-base" to ModelDef(
                id = "sherpa-onnx-whisper-base",
                name = "Whisper Base (Multilingual 99 langs, ~350 MB)",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
                sizeBytes = 350_000_000L,
                files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"),
                type = ModelType.STT,
                language = "Multilingual (99)"
            )
        )

        val AVAILABLE_LLM_MODELS = mapOf(
            "llama-3.2-1b-instruct-spinquant" to ModelDef(
                id = "llama-3.2-1b-instruct-spinquant",
                name = "Llama 3.2 1B Instruct (SpinQuant 4-bit, ~1.1 GB)",
                url = "https://huggingface.co/pytorch-executorch/llama-3.2-1b-instruct/resolve/main/llama-3.2-1b-instruct-spinquant.pte",
                sizeBytes = 1_100_000_000L,
                files = listOf("llama-3.2-1b-instruct-spinquant.pte", "tokenizer.model"),
                type = ModelType.LLM,
                language = "Code/LLM"
            ),
            "llama-3.2-3b-instruct-spinquant" to ModelDef(
                id = "llama-3.2-3b-instruct-spinquant",
                name = "Llama 3.2 3B Instruct (SpinQuant 4-bit, ~2.4 GB)",
                url = "https://huggingface.co/pytorch-executorch/llama-3.2-3b-instruct/resolve/main/llama-3.2-3b-instruct-spinquant.pte",
                sizeBytes = 2_400_000_000L,
                files = listOf("llama-3.2-3b-instruct-spinquant.pte", "tokenizer.model"),
                type = ModelType.LLM,
                language = "Code/LLM"
            )
        )
    }

    /** Refreshes the list of downloaded models. */
    fun refreshLists() {
        scope.launch {
            val sttList = scanSttModels()
            val llmList = scanLlmModels()
            _sttModels.update { sttList }
            _llmModels.update { llmList }
        }
    }

    private fun scanSttModels(): LocalModelList {
        val items = mutableListOf<LocalModel>()
        sttModelsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val hasEncoder = File(dir, "encoder.int8.onnx").exists()
            val hasDecoder = File(dir, "decoder.int8.onnx").exists()
            val hasTokens = File(dir, "tokens.txt").exists()
            val def = AVAILABLE_STT_MODELS[dir.name]
            items.add(LocalModel(
                id = dir.name,
                name = def?.name ?: dir.name,
                type = ModelType.STT,
                path = dir,
                isComplete = hasEncoder && hasDecoder && hasTokens,
                sizeBytes = def?.sizeBytes ?: 0,
                language = def?.language ?: "Unknown"
            ))
        }
        return LocalModelList(items)
    }

    private fun scanLlmModels(): LocalModelList {
        val items = mutableListOf<LocalModel>()
        llmModelsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val pteFile = dir.listFiles()?.firstOrNull { it.extension == "pte" }
            val tokenizerFile = File(dir, "tokenizer.model")
            val def = AVAILABLE_LLM_MODELS.values.firstOrNull { it.files.contains(pteFile?.name) }
            items.add(LocalModel(
                id = dir.name,
                name = def?.name ?: dir.name,
                type = ModelType.LLM,
                path = dir,
                isComplete = pteFile != null && tokenizerFile.exists(),
                sizeBytes = def?.sizeBytes ?: pteFile?.length() ?: 0,
                language = def?.language ?: "Code/LLM"
            ))
        }
        return LocalModelList(items)
    }

    /** Downloads an STT model. */
    fun downloadSttModel(modelId: String) {
        val def = AVAILABLE_STT_MODELS[modelId] ?: return
        val targetDir = File(sttModelsDir, modelId)
        downloadModel(def, targetDir)
    }

    /** Downloads an LLM model. */
    fun downloadLlmModel(modelId: String) {
        val def = AVAILABLE_LLM_MODELS[modelId] ?: return
        val targetDir = File(llmModelsDir, modelId)
        downloadModel(def, targetDir)
    }

    private fun downloadModel(def: ModelDef, targetDir: File) {
        if (activeDownloads.containsKey(def.id)) return
        
        val job = scope.launch {
            _downloadProgress.update { it.copy(active = def.id, progress = 0f, status = "Starting...") }
            targetDir.mkdirs()
            
            try {
                if (def.url.endsWith(".tar.bz2")) {
                    downloadAndExtractTarBz2(def.url, targetDir)
                } else {
                    downloadFiles(def.url, def.files, targetDir)
                }
                _downloadProgress.update { it.copy(active = null, progress = 1f, status = "Complete") }
                refreshLists()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${def.id}", e)
                _downloadProgress.update { it.copy(active = null, progress = 0f, status = "Failed: ${e.message}") }
            } finally {
                activeDownloads.remove(def.id)
            }
        }
        activeDownloads[def.id] = job
    }

    private fun downloadAndExtractTarBz2(url: String, targetDir: File) {
        // Download to temp file
        val tempFile = File(targetDir, "model.tar.bz2")
        downloadFile(url, tempFile) { progress ->
            _downloadProgress.update { it.copy(progress = progress * 0.5f, status = "Downloading...") }
        }
        
        // Extract tar.bz2 (requires apache commons-compress or similar)
        // For simplicity, we'll use a basic extraction
        _downloadProgress.update { it.copy(progress = 0.5f, status = "Extracting...") }
        extractTarBz2(tempFile, targetDir)
        tempFile.delete()
    }

    private fun downloadFiles(baseUrl: String, files: List<String>, targetDir: File) {
        for ((index, file) in files.withIndex()) {
            val fileUrl = if (baseUrl.endsWith("/")) "$baseUrl$file" else "$baseUrl/$file"
            val targetFile = File(targetDir, file)
            downloadFile(fileUrl, targetFile) { progress ->
                val overall = (index + progress) / files.size
                _downloadProgress.update { it.copy(progress = overall, status = "Downloading $file...") }
            }
        }
    }

    private fun downloadFile(urlString: String, targetFile: File, progressCallback: (Float) -> Unit) {
        val url = URL(urlString)
        val connection = url.openConnection()
        connection.connect()
        val fileSize = connection.contentLengthLong()
        
        val input = connection.getInputStream()
        val output = FileOutputStream(targetFile)
        val buffer = ByteArray(8192)
        var downloaded = 0L
        
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            downloaded += read
            if (fileSize > 0) {
                progressCallback(downloaded.toFloat() / fileSize)
            }
        }
        output.flush()
        output.close()
        input.close()
    }

    private fun extractTarBz2(tarFile: File, targetDir: File) {
        // Basic tar.bz2 extraction using Java's built-in GZIPInputStream + manual tar parsing
        // For production, consider using Apache Commons Compress
        try {
            val bis = java.io.BufferedInputStream(java.io.FileInputStream(tarFile))
            val bis2 = java.io.BufferedInputStream(bis)
            val bz2In = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(bis2)
            val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz2In)
            
            var entry = tarIn.getNextTarEntry()
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    val fos = FileOutputStream(outFile)
                    val buffer = ByteArray(8192)
                    var len = tarIn.read(buffer)
                    while (len != -1) {
                        fos.write(buffer, 0, len)
                        len = tarIn.read(buffer)
                    }
                    fos.close()
                }
                entry = tarIn.getNextTarEntry()
            }
            tarIn.close()
        } catch (e: Exception) {
            Log.e(TAG, "Tar extraction failed, trying alternative", e)
            // Fallback: try with just GZIP if it's actually .tar.gz
            throw e
        }
    }

    /** Cancels an active download. */
    fun cancelDownload(modelId: String) {
        activeDownloads[modelId]?.cancel()
        activeDownloads.remove(modelId)
        _downloadProgress.update { it.copy(active = null, progress = 0f, status = "Cancelled") }
    }

    /** Deletes a downloaded model. */
    fun deleteModel(model: LocalModel) {
        model.path.deleteRecursively()
        refreshLists()
    }

    /** Gets the model directory for a given model ID. */
    fun getModelDir(modelId: String, type: ModelType): File? {
        val baseDir = when (type) {
            ModelType.STT -> sttModelsDir
            ModelType.LLM -> llmModelsDir
        }
        val dir = File(baseDir, modelId)
        return if (dir.exists()) dir else null
    }
}

enum class ModelType { STT, LLM }

data class ModelDef(
    val id: String,
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val files: List<String>,
    val type: ModelType,
    val language: String
)

data class LocalModel(
    val id: String,
    val name: String,
    val type: ModelType,
    val path: File,
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
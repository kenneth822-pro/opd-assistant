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
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
            // Check both direct files and files in subdirectories
            val encoder = findFile(dir, "encoder.int8.onnx")
            val decoder = findFile(dir, "decoder.int8.onnx")
            val tokens = findFile(dir, "tokens.txt")
            val vad = findFile(dir, "silero_vad.onnx")
            val hasEncoder = encoder != null
            val hasDecoder = decoder != null
            val hasTokens = tokens != null
            val hasVad = vad != null
            val def = AvailableSttModels.ALL[dir.name]
            items.add(LocalModel(
                id = dir.name,
                name = def?.name ?: dir.name,
                type = ModelType.STT,
                path = dir,
                isComplete = hasEncoder && hasDecoder && hasTokens && hasVad,
                sizeBytes = def?.sizeBytes ?: 0,
                language = def?.language ?: "Unknown"
            ))
        }
        return LocalModelList(items)
    }

    private fun findFile(dir: File, name: String): File? {
        if (File(dir, name).exists()) return File(dir, name)
        dir.listFiles()?.firstOrNull { it.isDirectory }?.let { subDir ->
            if (File(subDir, name).exists()) return File(subDir, name)
        }
        return null
    }

    private fun scanLlmModels(): LocalModelList {
        val items = mutableListOf<LocalModel>()
        llmModelsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val pteFile = dir.listFiles()?.firstOrNull { it.extension == "pte" }
            val tokenizerFile = File(dir, "tokenizer.model")
            val def = AvailableLlmModels.ALL.values.firstOrNull { it.files.contains(pteFile?.name) }
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
        val def = AvailableSttModels.ALL[modelId] ?: return
        val targetDir = File(sttModelsDir, modelId)
        downloadModel(def, targetDir)
    }

    /** Downloads an LLM model. */
    fun downloadLlmModel(modelId: String) {
        val def = AvailableLlmModels.ALL[modelId] ?: return
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
        
        // Extract tar.bz2
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
        val fileSize = connection.contentLengthLong
        
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
        try {
            val bis = java.io.BufferedInputStream(java.io.FileInputStream(tarFile))
            val bz2In = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(bis)
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
            
            // If files were extracted into a subdirectory, move them up
            moveFilesFromSubdir(targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Tar extraction failed", e)
            throw e
        }
    }

    private fun moveFilesFromSubdir(dir: File) {
        val subdirs = dir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        subdirs.forEach { subdir ->
            subdir.listFiles()?.forEach { file ->
                val dest = File(dir, file.name)
                if (file.renameTo(dest)) {
                    Log.i(TAG, "Moved ${file.name} from subdir to model dir")
                }
            }
            // Clean up empty subdirectory
            if (subdir.listFiles()?.isEmpty() == true) {
                subdir.delete()
            }
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
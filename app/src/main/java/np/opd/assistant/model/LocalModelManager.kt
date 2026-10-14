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
            val encoder = findFile(dir, "encoder.int8.onnx")
            val decoder = findFile(dir, "decoder.int8.onnx")
            val tokens = findFile(dir, "tokens.txt")
            val vad = findFile(dir, "silero_vad.onnx")
            val hasEncoder = encoder != null
            val hasDecoder = decoder != null
            val hasTokens = tokens != null
            val hasVad = vad != null

            val allFiles = listAllFiles(dir)
            Log.i(TAG, "Scanning model dir ${dir.name}: encoder=$encoder, decoder=$decoder, tokens=$tokens, vad=$vad")
            Log.i(TAG, "All files in ${dir.name}: ${allFiles.joinToString(", ")}")

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
        return findFileRecursive(dir, name)
    }

    private fun findFileRecursive(dir: File, name: String): File? {
        val files = dir.listFiles() ?: return null
        for (file in files) {
            if (file.name == name) return file
            if (file.isDirectory) {
                val found = findFileRecursive(file, name)
                if (found != null) return found
            }
        }
        return null
    }

    private fun listAllFiles(dir: File, prefix: String = ""): List<String> {
        val result = mutableListOf<String>()
        val files = dir.listFiles() ?: return result
        for (file in files) {
            result.add("$prefix${file.name}${if (file.isDirectory) "/" else ""}")
            if (file.isDirectory) {
                result.addAll(listAllFiles(file, "$prefix${file.name}/"))
            }
        }
        return result
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
            var retryCount = 0
            val maxRetries = 3
            
            while (retryCount <= maxRetries) {
                try {
                    _downloadProgress.update { it.copy(active = def.id, progress = 0f, status = if (retryCount > 0) "Retry $retryCount of $maxRetries..." else "Starting...") }
                    targetDir.mkdirs()

                    try {
                        if (def.url.endsWith(".tar.bz2")) {
                            downloadAndExtractTarBz2(def.url, targetDir, def)
                        } else {
                            downloadFiles(def.url, def.files, targetDir)
                        }
                        _downloadProgress.update { it.copy(active = null, progress = 1f, status = "Complete") }
                        refreshLists()
                        return@launch // Success, exit retry loop
                    } catch (e: Exception) {
                        retryCount++
                        if (retryCount > maxRetries) {
                            throw e
                        }
                        Log.w(TAG, "Download failed for ${def.id}, retrying... (attempt $retryCount)", e)
                        _downloadProgress.update { it.copy(progress = 0f, status = "Retry $retryCount of $maxRetries...") }
                        delay(2000 * retryCount) // Exponential backoff
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed for ${def.id} after $maxRetries retries", e)
                    _downloadProgress.update { it.copy(active = null, progress = 0f, status = "Failed after $maxRetries retries: ${e.message}") }
                } finally {
                    activeDownloads.remove(def.id)
                }
            }
        }
        activeDownloads[def.id] = job
    }

    private fun downloadAndExtractTarBz2(url: String, targetDir: File, def: ModelDef) {
        val tempFile = File(targetDir, "model.tar.bz2")
        downloadFile(url, tempFile) { progress ->
            _downloadProgress.update { it.copy(progress = progress * 0.4f, status = "Downloading model...") }
        }

        _downloadProgress.update { it.copy(progress = 0.4f, status = "Extracting model...") }
        extractTarBz2(tempFile, targetDir, def.filePrefix)
        tempFile.delete()

        if (def.type == ModelType.STT && def.vadUrl != null) {
            _downloadProgress.update { it.copy(progress = 0.8f, status = "Downloading VAD model...") }
            val vadFile = File(targetDir, "silero_vad.onnx")
            downloadFile(def.vadUrl!!, vadFile) { progress ->
                _downloadProgress.update { it.copy(progress = 0.8f + progress * 0.2f, status = "Downloading VAD model...") }
            }
        }
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

    private fun extractTarBz2(tarFile: File, targetDir: File, filePrefix: String) {
        try {
            Log.i(TAG, "Starting tar.bz2 extraction to $targetDir (prefix: '$filePrefix')")
            val bis = java.io.BufferedInputStream(java.io.FileInputStream(tarFile))
            val bz2In = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(bis)
            val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz2In)

            var entry = tarIn.getNextTarEntry()
            var entryCount = 0
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
                    entryCount++
                }
                entry = tarIn.getNextTarEntry()
            }
            tarIn.close()
            Log.i(TAG, "Extracted $entryCount files from tar.bz2")

            val allFiles = listAllFiles(targetDir)
            Log.i(TAG, "All files after extraction: ${allFiles.joinToString(", ")}")

            if (filePrefix.isNotBlank()) {
                renameFilesWithPrefix(targetDir, filePrefix)
            }

            moveFilesFromSubdir(targetDir)

            val allFilesAfter = listAllFiles(targetDir)
            Log.i(TAG, "All files after processing: ${allFilesAfter.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(TAG, "Tar extraction failed", e)
            throw e
        }
    }

    private fun renameFilesWithPrefix(dir: File, prefix: String) {
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix)) {
                val newName = file.name.substring(prefix.length)
                val newFile = File(dir, newName)
                if (file.renameTo(newFile)) {
                    Log.i(TAG, "Renamed ${file.name} -> $newName")
                } else {
                    Log.w(TAG, "Failed to rename ${file.name}")
                }
            }
            dir.listFiles()?.filter { it.isDirectory }?.forEach { subdir ->
                renameFilesWithPrefix(subdir, prefix)
            }
        }
    }

    private fun moveFilesFromSubdir(dir: File) {
        val subdirs = dir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        Log.i(TAG, "Checking subdirs for moving: ${subdirs.map { it.name }}")
        subdirs.forEach { subdir ->
            val files = subdir.listFiles() ?: emptyArray()
            Log.i(TAG, "Moving files from subdir ${subdir.name}: ${files.map { it.name }}")
            files.forEach { file ->
                val dest = File(dir, file.name)
                if (file.renameTo(dest)) {
                    Log.i(TAG, "Moved ${file.name} from subdir to model dir")
                } else {
                    Log.w(TAG, "Failed to move ${file.name} from ${subdir.name}")
                }
            }
            if (subdir.listFiles()?.isEmpty() == true) {
                subdir.delete()
                Log.i(TAG, "Deleted empty subdir ${subdir.name}")
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
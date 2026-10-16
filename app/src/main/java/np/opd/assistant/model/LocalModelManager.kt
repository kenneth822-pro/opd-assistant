package np.opd.assistant.model

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import np.opd.assistant.ai.LocalLlmClient
import np.opd.assistant.ai.LocalWhisperClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
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
            val ready = LocalWhisperClient.isValidModelDir(dir)

            val allFiles = listAllFiles(dir)
            Log.i(TAG, "Scanning model dir ${dir.name}: ready=$ready")
            Log.i(TAG, "All files in ${dir.name}: ${allFiles.joinToString(", ")}")

            val def = AvailableSttModels.ALL[dir.name]
            items.add(LocalModel(
                id = dir.name,
                name = def?.name ?: dir.name,
                type = ModelType.STT,
                path = dir,
                isComplete = ready,
                sizeBytes = def?.sizeBytes ?: 0,
                language = def?.language ?: "Unknown"
            ))
        }
        return LocalModelList(items)
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
            val files = LocalLlmClient.findModelFiles(dir)
            val pteFile = files?.first
            val def = AvailableLlmModels.ALL.values.firstOrNull { it.files.contains(pteFile?.name) }
            items.add(LocalModel(
                id = dir.name,
                name = def?.name ?: dir.name,
                type = ModelType.LLM,
                path = dir,
                isComplete = files != null,
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
            val stagingDir = File(targetDir.parentFile, ".${targetDir.name}.download")
            try {
                while (retryCount <= maxRetries) {
                    try {
                        _downloadProgress.update { it.copy(active = def.id, progress = 0f, status = if (retryCount > 0) "Retry $retryCount of $maxRetries..." else "Starting...") }
                        stagingDir.deleteRecursively()
                        stagingDir.mkdirs()

                        if (def.url.endsWith(".tar.bz2")) {
                            downloadAndExtractTarBz2(def.url, stagingDir, def)
                        } else {
                            downloadFiles(def.url, def.files, stagingDir)
                        }
                        if (!isComplete(def, stagingDir)) {
                            throw IOException("Downloaded ${def.id} is incomplete")
                        }
                        promote(stagingDir, targetDir)
                        _downloadProgress.update { it.copy(active = null, progress = 1f, status = "Complete") }
                        refreshLists()
                        return@launch // Success, exit retry loop
                    } catch (e: CancellationException) {
                        stagingDir.deleteRecursively()
                        throw e
                    } catch (e: Exception) {
                        retryCount++
                        if (retryCount > maxRetries) {
                            Log.e(TAG, "Download failed for ${def.id} after $maxRetries retries", e)
                            _downloadProgress.update { it.copy(active = null, progress = 0f, status = "Failed after $maxRetries retries: ${e.message}") }
                            stagingDir.deleteRecursively()
                            return@launch
                        }
                        Log.w(TAG, "Download failed for ${def.id}, retrying... (attempt $retryCount)", e)
                        _downloadProgress.update { it.copy(progress = 0f, status = "Retry $retryCount of $maxRetries...") }
                        delay((2000L * retryCount)) // Exponential backoff
                    }
                }
            } finally {
                activeDownloads.remove(def.id)
            }
        }
        activeDownloads[def.id] = job
    }

    private fun isComplete(def: ModelDef, dir: File): Boolean = when (def.type) {
        ModelType.STT -> LocalWhisperClient.isValidModelDir(dir)
        ModelType.LLM -> LocalLlmClient.isValidModelDir(dir)
    }

    /** Installs a fully downloaded model in one rename so inference never sees partial files. */
    private fun promote(stagingDir: File, targetDir: File) {
        targetDir.deleteRecursively()
        if (!stagingDir.renameTo(targetDir)) {
            throw IOException("Could not install model ${targetDir.name}")
        }
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
        val connection = url.openConnection() as? HttpURLConnection
            ?: throw IOException("Unsupported download URL: $urlString")
        val partFile = File(targetFile.parentFile, ".${targetFile.name}.part")
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} while downloading ${url.host}")
            }
            val fileSize = connection.contentLengthLong
            val buffer = ByteArray(8192)
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (fileSize > 0) progressCallback(downloaded.toFloat() / fileSize)
                    }
                    output.fd.sync()
                }
            }
            targetFile.delete()
            if (!partFile.renameTo(targetFile)) throw IOException("Could not install ${targetFile.name}")
        } finally {
            partFile.delete()
            connection.disconnect()
        }
    }

    private fun extractTarBz2(tarFile: File, targetDir: File, filePrefix: String) {
        try {
            Log.i(TAG, "Starting tar.bz2 extraction to $targetDir (prefix: '$filePrefix')")
            val bis = java.io.BufferedInputStream(java.io.FileInputStream(tarFile))
            val bz2In = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(bis)
            val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bz2In)
            val root = targetDir.canonicalFile
            val rootPrefix = root.path + File.separator

            var entry = tarIn.getNextTarEntry()
            var entryCount = 0
            while (entry != null) {
                if (entry.isSymbolicLink || entry.isLink) {
                    throw IOException("Refusing link in model archive: ${entry.name}")
                }
                val outFile = File(root, entry.name).canonicalFile
                if (outFile.path != root.path && !outFile.path.startsWith(rootPrefix)) {
                    throw IOException("Refusing path outside model directory: ${entry.name}")
                }
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

            val allFilesAfter = listAllFiles(targetDir)
            Log.i(TAG, "All files after processing: ${allFilesAfter.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(TAG, "Tar extraction failed", e)
            throw e
        }
    }

    private fun renameFilesWithPrefix(dir: File, prefix: String) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                renameFilesWithPrefix(file, prefix)
            } else if (file.name.startsWith(prefix)) {
                val newName = file.name.substring(prefix.length)
                val newFile = File(file.parentFile, newName)
                if (newFile.exists() || !file.renameTo(newFile)) {
                    throw IOException("Could not rename ${file.name} in model archive")
                }
                Log.i(TAG, "Renamed ${file.name} -> $newName")
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

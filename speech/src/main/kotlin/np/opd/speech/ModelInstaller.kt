package np.opd.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import np.opd.engine.J
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where the model is in its life on this phone. */
sealed class InstallState {
    /** Nothing installed and a download is possible. */
    object NotInstalled : InstallState()

    /** Nothing installed and the manifest has no pinned checksum/URL: only "Import file" can install it. */
    data class NeedsFile(val reason: String) : InstallState()

    data class Downloading(val fileName: String, val bytes: Long, val totalBytes: Long?) : InstallState() {
        val percent: Int? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes * 100 / it).toInt().coerceIn(0, 100) }
    }

    object Verifying : InstallState()
    object Installing : InstallState()

    /**
     * @param pinnedMatch the file's sha256 equals the one pinned in the manifest.
     * @param updateAvailable the manifest now pins a different sha256 than the installed file.
     */
    data class Ready(val file: File, val sha256: String, val pinnedMatch: Boolean, val source: String, val updateAvailable: Boolean) : InstallState()

    data class Failed(val message: String) : InstallState()
}

/** Fetches one URL into [dest]. If [resumeFrom] > 0 it appends from that byte (a server that ignores the range must restart the file). */
interface Downloader {
    suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (bytes: Long, total: Long?) -> Unit)
}

/**
 * Installs, verifies and removes a speech model in app-private storage. Files go to `<root>/<model id>/`;
 * `installed.json` records what was installed, so start-up needs only a size check, not a re-hash of a ~600 MB file.
 *
 * Safety rules: a download is installed only if its sha256 equals the pinned value; a partial download is never
 * used; an imported file must look like the right format; and one operation runs at a time.
 */
class ModelInstaller(
    val spec: ModelSpec,
    private val root: File,
    private val downloader: Downloader?,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.NotInstalled)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val busy = Mutex()
    private val dir get() = File(root, spec.id)
    private val marker get() = File(dir, MARKER)

    init { refresh() }

    /** The installed model file, or null if not installed. */
    fun modelFile(): File? = (state.value as? InstallState.Ready)?.file

    /** Looks at the disk and sets the state. Cheap: it never re-hashes. */
    fun refresh() {
        if (busy.isLocked) return // an install/import is in progress and owns the state
        _state.value = inspect()
    }

    private fun inspect(): InstallState {
        val m = readMarker()
        if (m != null) {
            val primary = File(dir, spec.primaryFile.name)
            val ok = m.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.size } } && primary.isFile
            if (ok) {
                val pinned = spec.primaryFile.sha256
                return InstallState.Ready(
                    file = primary,
                    sha256 = m.sha256,
                    pinnedMatch = pinned != null && pinned == m.sha256,
                    source = m.source,
                    updateAvailable = pinned != null && pinned != m.sha256
                )
            }
            return InstallState.Failed("The installed model file is missing or was changed. Delete it and install again.")
        }
        return if (spec.downloadable && downloader != null) InstallState.NotInstalled
        else InstallState.NeedsFile(
            if (!spec.downloadable) "This model has no pinned checksum yet, so it cannot be downloaded automatically. Use 'Import file'."
            else "No downloader is available."
        )
    }

    /** Downloads and installs the model. Does nothing if another install/import is already running. */
    suspend fun install() {
        if (!busy.tryLock()) return
        try {
            val dl = downloader
            if (!spec.downloadable || dl == null) { _state.value = inspect(); return }
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val installed = ArrayList<MarkerFile>()
                try {
                    for (f in spec.files) {
                        val dest = File(dir, f.name)
                        val part = File(dir, f.name + PART)
                        val expected = f.sha256!!
                        if (dest.isFile && sha256(dest) == expected) { installed += MarkerFile(f.name, dest.length(), expected); continue }
                        val resume = if (part.isFile) part.length() else 0L
                        _state.value = InstallState.Downloading(f.name, resume, f.sizeBytes)
                        dl.download(f.url!!, part, resume) { bytes, total -> _state.value = InstallState.Downloading(f.name, bytes, total ?: f.sizeBytes) }
                        _state.value = InstallState.Verifying
                        val actual = sha256(part)
                        if (f.sizeBytes != null && part.length() != f.sizeBytes) {
                            part.delete()
                            throw IOException("${f.name}: downloaded ${part.length()} bytes, expected ${f.sizeBytes}")
                        }
                        if (actual != expected) {
                            part.delete()
                            throw IOException("${f.name}: checksum mismatch (expected ${expected.take(12)}…, got ${actual.take(12)}…). The file was discarded.")
                        }
                        _state.value = InstallState.Installing
                        moveInto(part, dest)
                        installed += MarkerFile(f.name, dest.length(), actual)
                    }
                    writeMarker(Marker(spec.id, installed, installed.first().sha256, "download", now()))
                    _state.value = inspect()
                } catch (e: CancellationException) {
                    _state.value = inspect() // partial files are kept so the next install resumes
                    throw e
                } catch (e: Exception) {
                    _state.value = InstallState.Failed("Install failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } finally {
            busy.unlock()
        }
    }

    /**
     * Installs a model file the doctor copied to the phone. The sha256 is computed while copying and shown, so it can
     * be pinned in the manifest. If the manifest already pins one, a file with a different checksum is refused.
     */
    suspend fun importFrom(input: InputStream) {
        if (!busy.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val f = spec.primaryFile
                val part = File(dir, f.name + PART)
                try {
                    _state.value = InstallState.Installing
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    input.use { src ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); digest.update(buf, 0, n); total += n
                            }
                        }
                    }
                    val sha = digest.digest().toHex()
                    if (total < MIN_MODEL_BYTES) throw IOException("the file is only $total bytes: not a complete model")
                    if (spec.format == "ggml-whisper" && !hasGgmlMagic(part)) throw IOException("this is not a GGML Whisper model file (.bin made with whisper.cpp)")
                    val pinned = f.sha256
                    if (pinned != null && pinned != sha) throw IOException("its checksum (${sha.take(12)}…) differs from the pinned one (${pinned.take(12)}…): wrong file or version")
                    moveInto(part, File(dir, f.name))
                    writeMarker(Marker(spec.id, listOf(MarkerFile(f.name, total, sha)), sha, "import", now()))
                    _state.value = inspect()
                } catch (e: CancellationException) {
                    part.delete(); _state.value = inspect(); throw e
                } catch (e: Exception) {
                    part.delete()
                    _state.value = InstallState.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } finally {
            busy.unlock()
        }
    }

    /** Removes the model and any partial download. */
    fun delete() {
        if (busy.isLocked) return
        dir.deleteRecursively()
        _state.value = inspect()
    }

    /** Re-hashes the installed file (slow: a few seconds). Returns true if it still matches what was recorded. */
    suspend fun verifyNow(): Boolean = withContext(Dispatchers.IO) {
        val m = readMarker() ?: return@withContext false
        m.files.all { f -> File(dir, f.name).let { it.isFile && sha256(it) == f.sha256 } }
    }

    /* ------------------------- helpers ------------------------- */

    private data class MarkerFile(val name: String, val size: Long, val sha256: String)
    private data class Marker(val modelId: String, val files: List<MarkerFile>, val sha256: String, val source: String, val installedAt: Long)

    private fun writeMarker(m: Marker) {
        val json = J.obj(
            "model_id" to m.modelId,
            "sha256" to m.sha256,
            "source" to m.source,
            "installed_at" to m.installedAt,
            "files" to m.files.map { J.obj("name" to it.name, "size" to it.size, "sha256" to it.sha256) }
        ).toJson()
        val tmp = File(dir, MARKER + PART)
        tmp.writeText(json, Charsets.UTF_8)
        moveInto(tmp, marker)
    }

    private fun readMarker(): Marker? = try {
        if (!marker.isFile) null else {
            val o = J.parse(marker.readText(Charsets.UTF_8)) as J.Obj
            if (o.str("model_id") != spec.id) null
            else Marker(
                spec.id,
                o.objs("files").map { MarkerFile(it.req("name"), it.dbl("size")?.toLong() ?: -1L, it.req("sha256")) },
                o.req("sha256"), o.strOr("source", "unknown"), o.dbl("installed_at")?.toLong() ?: 0L
            ).takeIf { it.files.isNotEmpty() }
        }
    } catch (e: Exception) { null }

    /** Atomic where the filesystem allows it; falls back to copy for the rare cross-volume case. */
    private fun moveInto(from: File, to: File) {
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    companion object {
        const val MARKER = "installed.json"
        const val PART = ".part"
        /** Smallest plausible Whisper model (tiny, quantised) is tens of MB; anything under this is a broken copy. */
        const val MIN_MODEL_BYTES = 1_000_000L

        fun sha256(file: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            }
            return d.digest().toHex()
        }

        /** whisper.cpp .bin files start with the ggml magic 0x67676d6c ("ggml") stored little-endian: 6c 6d 67 67. */
        fun hasGgmlMagic(file: File): Boolean = file.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x6c.toByte() && b[1] == 0x6d.toByte() && b[2] == 0x67.toByte() && b[3] == 0x67.toByte()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

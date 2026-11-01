package np.opd.assistant.ward

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import np.opd.assistant.OpdApplication
import np.opd.assistant.data.MediaStore
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.MediaCategory
import np.opd.assistant.data.local.entities.MediaEntity
import np.opd.assistant.data.local.entities.MediaSide
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class MediaUiState(
    val items: List<MediaEntity> = emptyList(),
    val filter: MediaCategory? = null,
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** Pending capture destination; the camera writes here and we attach metadata on return. */
    val pendingCapturePath: String? = null
) {
    val filtered: List<MediaEntity> get() = if (filter == null) items else items.filter { it.category == filter }
}

/**
 * Backs the media vault: records captures taken with the system camera into app-private storage,
 * tags them with a clinical category, lists them chronologically, deletes them, and can burn a
 * black-bar anonymisation band over patient identifiers before sharing.
 */
class MediaViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WardRepository((app as OpdApplication).database)

    private val _ui = MutableStateFlow(MediaUiState())
    val ui: StateFlow<MediaUiState> = _ui.asStateFlow()

    private var patientId: String? = null
    private var collectJob: kotlinx.coroutines.Job? = null

    fun load(patientId: String) {
        this.patientId = patientId
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            repository.mediaDao.observeForPatient(patientId).collect { list ->
                _ui.update { it.copy(items = list) }
            }
        }
    }

    fun setFilter(category: MediaCategory?) = _ui.update { it.copy(filter = category) }
    fun showMessage(m: String?) = _ui.update { it.copy(message = m, error = null) }
    fun showError(e: String?) = _ui.update { it.copy(error = e, message = null) }

    /** Creates the destination file and returns it so the caller can launch the camera. */
    fun prepareCapture(): File? {
        val pid = patientId ?: return null
        val file = MediaStore.newCaptureFile(getApplication(), pid)
        _ui.update { it.copy(pendingCapturePath = file.absolutePath, error = null) }
        return file
    }

    fun captureFailed(reason: String) {
        _ui.update { it.copy(pendingCapturePath = null, error = reason) }
    }

    /** Registers the captured photo with its category/label/side after the camera returns. */
    fun registerCapture(category: MediaCategory, label: String, side: MediaSide, notes: String?) {
        val pid = patientId ?: return
        val path = _ui.value.pendingCapturePath ?: return
        if (!File(path).exists() || File(path).length() == 0L) {
            _ui.update { it.copy(pendingCapturePath = null, error = "No photo was captured.") }
            return
        }
        viewModelScope.launch {
            try {
                repository.mediaDao.upsert(
                    MediaEntity(
                        id = UUID.randomUUID().toString(),
                        patientId = pid,
                        filePath = path,
                        category = category,
                        captureDatetime = System.currentTimeMillis(),
                        notes = notes?.ifBlank { null },
                        label = label.ifBlank { null },
                        side = side
                    )
                )
                _ui.update { it.copy(pendingCapturePath = null, message = "Photo saved to the vault.") }
            } catch (e: Exception) {
                _ui.update { it.copy(pendingCapturePath = null, error = "Could not save photo: ${e.message}") }
            }
        }
    }

    fun delete(media: MediaEntity) {
        viewModelScope.launch {
            try {
                repository.mediaDao.delete(media.id)
                withContext(Dispatchers.IO) { MediaStore.deleteMediaFile(media.filePath) }
                _ui.update { it.copy(message = "Photo deleted.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not delete: ${e.message}") }
            }
        }
    }

    /**
     * Applies a black-bar band over the bottom 12% of the image (where bedside charts usually
     * carry the patient name/number) and over a top strip, writes a new file next to the original,
     * and marks the media anonymised. This is a best-effort on-device redaction, not a guarantee.
     */
    fun anonymise(media: MediaEntity) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Anonymising…") }
            try {
                val outPath = withContext(Dispatchers.IO) { burnBars(media.filePath) }
                if (outPath == null) {
                    _ui.update { it.copy(busy = null, error = "Could not read this image.") }
                    return@launch
                }
                repository.mediaDao.upsert(media.copy(filePath = outPath, anonymised = true, notes = (media.notes?.plus(" | ") ?: "") + "Anonymised copy"))
                _ui.update { it.copy(busy = null, message = "Anonymised copy saved.") }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Anonymise failed: ${e.message}") }
            }
        }
    }

    private fun burnBars(path: String): String? {
        val original = BitmapFactory.decodeFile(path) ?: return null
        val mutable = original.copy(Bitmap.Config.ARGB_8888, true) ?: original
        val canvas = Canvas(mutable)
        val paint = Paint().apply { color = Color.BLACK }
        val h = mutable.height
        val w = mutable.width
        // Top strip (hospital name/date headers) and bottom strip (name/number).
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.06f, paint)
        canvas.drawRect(0f, h * 0.88f, w.toFloat(), h.toFloat(), paint)
        val out = File(File(path).parentFile, "anon_${File(path).name}")
        return try {
            FileOutputStream(out).use { fos -> mutable.compress(Bitmap.CompressFormat.JPEG, 90, fos) }
            out.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            if (mutable !== original) mutable.recycle()
            original.recycle()
        }
    }
}

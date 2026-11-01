package np.opd.assistant.data

import android.content.Context
import java.io.File

/**
 * Owns the on-device media vault layout. Photos live in app-private storage under
 * `files/patients/{patientId}/media/` and are exposed to the system camera only through a
 * FileProvider, so they never appear in the public Android gallery or Google Photos.
 */
object MediaStore {

    fun patientMediaDir(context: Context, patientId: String): File =
        File(context.filesDir, "patients/$patientId/media").apply { mkdirs() }

    /** A fresh destination file for a new capture, with a timestamped name. */
    fun newCaptureFile(context: Context, patientId: String, now: Long = System.currentTimeMillis()): File =
        File(patientMediaDir(context, patientId), "img_$now.jpg")

    fun deleteMediaFile(path: String): Boolean {
        return try {
            val f = File(path)
            if (f.exists()) f.delete() else true
        } catch (e: Exception) {
            false
        }
    }

    fun totalBytes(context: Context, patientId: String): Long =
        patientMediaDir(context, patientId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

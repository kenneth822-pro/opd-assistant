package np.opd.assistant

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records the consultation as a series of short AAC files (default 20 s each).
 * Each finished segment is crash-safe on disk and can be sent to the AI on its own.
 * Note: switching segments takes a fraction of a second, so a syllable may be lost at the joins.
 */
class SegmentRecorder(private val context: Context, private val dir: File) {

    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var index = 0

    val isRecording: Boolean get() = recorder != null

    fun startNew() {
        if (recorder != null) return
        index++
        val file = File(dir, "seg_" + index.toString().padStart(3, '0') + ".aac")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        current = file
    }

    /** Stops the current segment and returns its file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        val file = current
        recorder = null
        current = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // happens if stopped within a few ms of starting
        }
        r.release()
        return if (ok && file != null && file.length() > 0) file else {
            file?.delete()
            null
        }
    }
}

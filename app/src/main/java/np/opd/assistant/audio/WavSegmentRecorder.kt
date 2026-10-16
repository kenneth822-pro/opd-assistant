package np.opd.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Records continuously from one AudioRecord and cuts the stream into WAV files
 * (16 kHz, mono, 16-bit) without ever stopping the microphone, so no audio is lost
 * between segments. A segment is closed at the first quiet moment after
 * [targetSeconds], or at most 8 seconds later.
 *
 * [onSegment] is called on the recording thread with (file, startMs, durationMs, voicedFraction).
 * voicedFraction = share of the segment louder than the silence threshold (0 = silence).
 */
class WavSegmentRecorder(
    private val dir: File,
    private val targetSeconds: () -> Int,
    private val onSegment: (File, Long, Long, Float) -> Unit,
    private val onRecorderError: (String) -> Unit = {}
) {
    @Volatile private var running = false
    @Volatile private var cutRequested = false
    @Volatile var level: Float = 0f
        private set
    /** Length of the segment currently being recorded. */
    @Volatile var currentSegmentMs: Long = 0
        private set
    private var thread: Thread? = null
    private var index = 0
    private var totalSamples = 0L

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // permission is requested before recording starts
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("The microphone reported an invalid buffer size")
        val chunkBytes = maxOf(minBuf, RATE / 5 * 2) // at least 200 ms per read
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, chunkBytes * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone could not be opened (is another app using it?)")
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            throw IllegalStateException("The microphone could not start: ${e.message}", e)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("The microphone did not enter recording state")
        }
        running = true
        thread = Thread({ loop(rec, chunkBytes / 2) }, "wav-recorder").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops recording; the last (partial) segment is emitted before this returns. */
    fun stop() {
        running = false
        thread?.join(4000)
        thread = null
        level = 0f
        currentSegmentMs = 0
    }

    fun cutNow() {
        cutRequested = true
    }

    private fun loop(rec: AudioRecord, samplesPerRead: Int) {
        val buf = ShortArray(samplesPerRead)
        var seg: SegmentWriter? = null
        var consecutiveReadErrors = 0
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    consecutiveReadErrors++
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                        running = false
                        reportError(if (n == AudioRecord.ERROR_DEAD_OBJECT) "The microphone was disconnected or taken by another app." else "The microphone stopped returning audio.")
                        break
                    }
                    Thread.sleep(40)
                    continue
                }
                consecutiveReadErrors = 0
                val writer = seg ?: SegmentWriter(nextFile(), totalSamples).also { seg = it }
                writer.write(buf, n)
                totalSamples += n

                var sum = 0.0
                for (i in 0 until n) {
                    val v = buf[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / n)
                level = (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()
                writer.reads++
                if (rms >= VOICE_RMS) writer.voicedReads++
                currentSegmentMs = writer.samples * 1000 / RATE

                val seconds = writer.samples / RATE.toDouble()
                val target = targetSeconds()
                if (cutRequested || (seconds >= target && rms < SILENCE_RMS) || seconds >= target + 8) {
                    cutRequested = false
                    finish(writer)
                    seg = null
                    currentSegmentMs = 0
                }
            }
        } finally {
            try { rec.stop() } catch (e: Exception) { /* already stopped */ }
            rec.release()
            seg?.let { finish(it) }
        }
    }

    private fun reportError(message: String) {
        try {
            onRecorderError(message)
        } catch (_: Exception) {
            // A UI callback must never prevent the native recorder from being released.
        }
    }

    private fun nextFile(): File {
        index++
        return File(dir, "seg_" + index.toString().padStart(3, '0') + ".wav")
    }

    private fun finish(w: SegmentWriter) {
        val file = w.close()
        val voiced = if (w.reads == 0) 0f else w.voicedReads.toFloat() / w.reads
        if (w.samples >= RATE / 2) onSegment(file, w.startSample * 1000 / RATE, w.samples * 1000L / RATE, voiced)
        else file.delete() // shorter than half a second: nothing useful
    }

    private class SegmentWriter(private val file: File, val startSample: Long) {
        private val raf = RandomAccessFile(file, "rw")
        var samples = 0L
            private set
        var reads = 0
        var voicedReads = 0
        private var bytes: ByteBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

        init {
            raf.setLength(0)
            raf.write(ByteArray(44)) // header written on close
        }

        fun write(buf: ShortArray, n: Int) {
            if (bytes.capacity() < n * 2) bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.clear()
            for (i in 0 until n) bytes.putShort(buf[i])
            raf.write(bytes.array(), 0, n * 2)
            samples += n
        }

        fun close(): File {
            val dataBytes = (samples * 2).toInt()
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII)); h.putInt(36 + dataBytes)
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII)); h.putInt(16)
            h.putShort(1.toShort()) // PCM
            h.putShort(1.toShort()) // mono
            h.putInt(RATE)
            h.putInt(RATE * 2) // byte rate
            h.putShort(2.toShort()) // block align
            h.putShort(16.toShort()) // bits per sample
            h.put("data".toByteArray(Charsets.US_ASCII)); h.putInt(dataBytes)
            raf.seek(0)
            raf.write(h.array())
            raf.close()
            return file
        }
    }

    companion object {
        const val RATE = 16000
        private const val SILENCE_RMS = 450.0
        /** Quieter than the cut threshold, so soft speech still counts as speech. */
        private const val VOICE_RMS = 300.0
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10
    }
}

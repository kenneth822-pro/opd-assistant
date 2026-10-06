package np.opd.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** A 16-bit PCM WAV like the app's recorder writes (44-byte header). */
fun wavOf(samples: ShortArray, rate: Int = 16000, channels: Int = 1, extraChunk: Boolean = false, claimedDataBytes: Int? = null): ByteArray {
    val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putShort(it) } }.array()
    val out = ByteArrayOutputStream()
    fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    val list = if (extraChunk) "LIST".toByteArray() + le32(5) + byteArrayOf(1, 2, 3, 4, 5, 0) else ByteArray(0) // odd size + pad byte
    out.write("RIFF".toByteArray()); out.write(le32(36 + list.size + data.size)); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(channels))
    out.write(le32(rate)); out.write(le32(rate * 2 * channels)); out.write(le16(2 * channels)); out.write(le16(16))
    out.write(list)
    out.write("data".toByteArray()); out.write(le32(claimedDataBytes ?: data.size)); out.write(data)
    return out.toByteArray()
}

/** A recogniser whose behaviour the test controls. */
class FakeRecognizer(
    override val backend: AsrBackend = AsrBackend.CPU_GGML,
    var segments: List<RecognizedSegment> = listOf(RecognizedSegment("नमस्ते", 0, 1000)),
    var initError: Throwable? = null,
    var inferError: Throwable? = null,
    var inferDelayMs: Long = 0,
    override val supportsPrompt: Boolean = false
) : LocalSpeechRecognizer {
    val initCalls = AtomicInteger()
    val inferCalls = AtomicInteger()
    val running = AtomicInteger()
    val maxRunning = AtomicInteger()
    val threads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val prompts = java.util.Collections.synchronizedList(mutableListOf<String?>())
    @Volatile var released = false
    @Volatile var cancelled = false
    @Volatile private var ready = false
    /** If set, inference blocks until [cancel] is called (simulates a hung native call). */
    @Volatile var hangUntilCancelled = false

    override suspend fun initialize() {
        initCalls.incrementAndGet()
        Thread.sleep(20)
        initError?.let { throw it }
        ready = true; released = false
    }

    override suspend fun transcribe(pcm: FloatArray, config: AsrConfig, prompt: String?): List<RecognizedSegment> {
        inferCalls.incrementAndGet()
        threads += Thread.currentThread().name
        prompts += prompt
        val now = running.incrementAndGet()
        maxRunning.updateAndGet { maxOf(it, now) }
        try {
            if (hangUntilCancelled) { while (!cancelled) Thread.sleep(5) }
            else if (inferDelayMs > 0) Thread.sleep(inferDelayMs)
            inferError?.let { throw it }
            return segments
        } finally {
            running.decrementAndGet()
        }
    }

    override fun isReady() = ready
    override fun cancel() { cancelled = true }
    override fun release() { released = true; ready = false }
}

/** Writes `size` bytes (starting with [head]) and reports progress; can be told to fail or to stop partway. */
class FakeDownloader(private val content: ByteArray) : Downloader {
    val calls = AtomicInteger()
    val resumeOffsets = java.util.Collections.synchronizedList(mutableListOf<Long>())
    var failAfter: Long? = null
    var corrupt = false
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long, Long?) -> Unit) {
        calls.incrementAndGet()
        resumeOffsets += resumeFrom
        gate?.await()
        val bytes = if (corrupt) content.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } else content
        val limit = failAfter?.toInt()?.coerceAtMost(bytes.size) ?: bytes.size
        val out = if (resumeFrom > 0) java.io.FileOutputStream(dest, true) else java.io.FileOutputStream(dest, false)
        out.use { it.write(bytes, resumeFrom.toInt(), limit - resumeFrom.toInt()) }
        onProgress(limit.toLong(), bytes.size.toLong())
        if (failAfter != null && limit < bytes.size) { failAfter = null; throw java.io.IOException("connection reset") }
    }
}

fun sha256Hex(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** A fake GGML Whisper file: right magic, plausible size. */
fun fakeModelBytes(sizeBytes: Int = 2_000_000, magicOk: Boolean = true): ByteArray =
    ByteArray(sizeBytes) { (it * 31 + 7).toByte() }.also {
        if (magicOk) { it[0] = 0x6c; it[1] = 0x6d; it[2] = 0x67; it[3] = 0x67 } else { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }
    }

fun specFor(content: ByteArray?, id: String = "test-model", pinned: Boolean = true, size: Boolean = true) = ModelSpec(
    id = id, displayName = "Test", language = "ne", backend = AsrBackend.CPU_GGML, format = "ggml-whisper",
    files = listOf(ModelFileSpec("m.bin", if (pinned) "https://example.test/m.bin" else null, if (pinned && content != null) sha256Hex(content) else null, if (size && content != null) content.size.toLong() else null)),
    source = SourceInfo("owner/repo", null, false, ""), license = LicenseInfo("MIT", false, false, "")
)

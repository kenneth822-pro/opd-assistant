package np.opd.speech

/** One timed inference. Real-time factor (RTF) = inference time / audio time; below 1.0 is faster than real time. */
data class SegmentTiming(val audioMs: Long, val inferMs: Long, val ok: Boolean = true) {
    val rtf: Double get() = if (audioMs <= 0) 0.0 else inferMs.toDouble() / audioMs
}

/**
 * Measured, never guessed: what the engine actually did on this phone. Shown in the developer area and used
 * to decide segment length and thread count. Thread-safe.
 */
class AsrStats(private val keep: Int = 50) {
    private val lock = Any()
    private val timings = ArrayDeque<SegmentTiming>()
    private var initMs: Long? = null
    private var failures = 0
    private var total = 0
    private var backend: AsrBackend? = null
    private var firstInferMs: Long? = null

    fun recordInit(ms: Long, backend: AsrBackend) = synchronized(lock) { initMs = ms; this.backend = backend }

    fun record(t: SegmentTiming) = synchronized(lock) {
        total++
        if (!t.ok) { failures++; return@synchronized }
        if (firstInferMs == null) firstInferMs = t.inferMs
        timings.addLast(t)
        while (timings.size > keep) timings.removeFirst()
    }

    data class Snapshot(
        val backend: AsrBackend?, val initMs: Long?, val firstInferMs: Long?, val segments: Int, val failures: Int,
        val lastRtf: Double?, val medianRtf: Double?, val worstRtf: Double?, val audioSeconds: Double
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        val rtfs = timings.map { it.rtf }.sorted()
        Snapshot(
            backend = backend,
            initMs = initMs,
            firstInferMs = firstInferMs,
            segments = total,
            failures = failures,
            lastRtf = timings.lastOrNull()?.rtf,
            medianRtf = if (rtfs.isEmpty()) null else rtfs[rtfs.size / 2],
            worstRtf = rtfs.lastOrNull(),
            audioSeconds = timings.sumOf { it.audioMs } / 1000.0
        )
    }

    /** One short line for the developer area. */
    fun summary(): String {
        val s = snapshot()
        fun f(x: Double?) = if (x == null) "-" else String.format(java.util.Locale.US, "%.2f", x)
        return "Local ASR (${s.backend?.label ?: "?"}): init ${s.initMs?.let { "${it / 100 / 10.0} s" } ?: "-"}" +
            " · first segment ${s.firstInferMs?.let { "${it / 100 / 10.0} s" } ?: "-"}" +
            " · RTF last ${f(s.lastRtf)}, median ${f(s.medianRtf)}, worst ${f(s.worstRtf)}" +
            " · ${s.segments} segment(s), ${s.failures} failed"
    }
}

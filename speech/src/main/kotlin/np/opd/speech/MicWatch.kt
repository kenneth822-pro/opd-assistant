package np.opd.speech

/**
 * Notices when the microphone is delivering nothing but digital silence.
 *
 * Android keeps an app "recording" but feeds it all-zero audio when it loses the microphone (a phone call, another
 * app taking it, or this app being sent to the background). A real quiet room still has a noise floor (RMS of
 * several units on 16-bit samples), so exact silence for many seconds means the mic is blocked, not that nobody is
 * talking. Without this the screen keeps saying "listening…" while nothing is captured.
 */
class MicWatch(
    private val silentRms: Double = 1.5,
    private val warnAfterMs: Long = 12_000
) {
    @Volatile var silentMs: Long = 0
        private set

    /** Feed each chunk read from the microphone ([samples] at [rate] Hz with the given RMS). Called from the recording thread. */
    fun onChunk(rms: Double, samples: Int, rate: Int) {
        silentMs = if (rms < silentRms) silentMs + samples * 1000L / rate else 0
    }

    fun reset() { silentMs = 0 }

    val blocked: Boolean get() = silentMs >= warnAfterMs

    /** Text for the doctor, or null while the microphone is fine. */
    fun message(): String? = if (!blocked) null else
        "No sound from the microphone for ${silentMs / 1000} s. Another app or a call may be using it, or this app was sent to the background. Keep this app on screen, then tap Restart microphone."
}

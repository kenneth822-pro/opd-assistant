package np.opd.speech

/**
 * Which speech-to-text path the doctor chose.
 *
 *  - [ON_DEVICE]  audio never leaves the phone. If the local engine is not ready, recording is blocked with an
 *                 explicit reason; audio is NEVER sent to a cloud provider in this mode.
 *  - [CLOUD]      the existing behaviour: a cloud speech model transcribes the audio.
 *  - [AUTO]       local first; the cloud is used only when the local engine is unavailable or fails, and only if a
 *                 cloud speech model is configured. With no local engine installed this behaves exactly like [CLOUD].
 */
enum class SpeechEngine(val label: String) {
    ON_DEVICE("On-device Nepali"),
    CLOUD("Cloud provider"),
    AUTO("Automatic (on-device first)");

    companion object {
        /** Unknown or missing text gives [default]; the stored value is never trusted blindly. */
        fun parse(text: String?, default: SpeechEngine = AUTO): SpeechEngine =
            entries.firstOrNull { it.name == text?.trim()?.uppercase() } ?: default
    }
}

/** What routing needs to know about the local engine; deliberately tiny so routing stays a pure function. */
sealed class LocalReadiness {
    object Ready : LocalReadiness()
    data class Unavailable(val reason: String) : LocalReadiness()
}

/** Where one audio segment goes. */
sealed class SpeechRoute {
    /** Local engine only. */
    object Local : SpeechRoute()
    /** Cloud speech model only (existing behaviour). */
    object Cloud : SpeechRoute()
    /** Local first; the cloud only if the local engine FAILS on this segment. */
    object LocalThenCloud : SpeechRoute()
    /** Nothing can transcribe: say why, never silently produce an empty transcript. */
    data class Blocked(val reason: String) : SpeechRoute()

    /** True if this route may send the segment's audio off the phone. */
    val mayUseCloud: Boolean get() = this is Cloud || this is LocalThenCloud
}

object SpeechRouting {

    /**
     * @param cloudUsable a cloud speech model is selected AND has the key/server it needs.
     */
    fun plan(engine: SpeechEngine, local: LocalReadiness, cloudUsable: Boolean): SpeechRoute = when (engine) {
        SpeechEngine.ON_DEVICE -> when (local) {
            LocalReadiness.Ready -> SpeechRoute.Local
            is LocalReadiness.Unavailable -> SpeechRoute.Blocked("On-device speech is not ready: ${local.reason}")
        }
        SpeechEngine.CLOUD ->
            if (cloudUsable) SpeechRoute.Cloud else SpeechRoute.Blocked("No cloud speech model is set up (Settings).")
        SpeechEngine.AUTO -> when (local) {
            LocalReadiness.Ready -> if (cloudUsable) SpeechRoute.LocalThenCloud else SpeechRoute.Local
            is LocalReadiness.Unavailable ->
                if (cloudUsable) SpeechRoute.Cloud
                else SpeechRoute.Blocked("On-device speech is not ready (${local.reason}) and no cloud speech model is set up.")
        }
    }
}

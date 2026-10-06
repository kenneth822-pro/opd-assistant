package np.opd.assistant.asr

import android.content.Context
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.SettingsStore
import np.opd.engine.TranscriptLine
import np.opd.speech.AsrConfig
import np.opd.speech.InstallState
import np.opd.speech.LocalAsrService
import np.opd.speech.LocalAsrStatus
import np.opd.speech.LocalReadiness
import np.opd.speech.ModelCatalog
import np.opd.speech.ModelInstaller
import np.opd.speech.ModelSpec
import java.io.File
import java.io.InputStream

/**
 * The app's single handle on local (on-device) speech recognition: which model, whether it is installed, and the
 * engine that runs it. It keeps all model/runtime details out of [np.opd.assistant.SessionViewModel] and the UI,
 * which only see statuses and transcript lines.
 *
 * Nothing here needs an API key or the network, except downloading a model whose checksum is pinned.
 */
class OnDeviceAsr(context: Context, private val settings: SettingsStore) {

    /** The model this build offers; null only if the bundled manifest could not be read. */
    val spec: ModelSpec?
    /** Problems found in the manifest (empty = fine); shown in the developer area. */
    val manifestProblems: List<String>
    private val service: LocalAsrService?

    init {
        val models = try { ModelCatalog.loadBundled() } catch (e: Exception) { emptyList() }
        manifestProblems = if (models.isEmpty()) listOf("The speech model manifest could not be loaded.") else ModelCatalog.check(models)
        spec = models.firstOrNull { it.id == settings.localModelId } ?: models.firstOrNull()
        service = spec?.let { s ->
            val installer = ModelInstaller(s, File(context.filesDir, "asr"), OkHttpDownloader())
            LocalAsrService(installer, NativeFactory.get(), config = { AsrConfig(language = "ne", translate = false, threads = settings.localAsrThreads) })
        }
    }

    /** True if this build contains the native engine at all. */
    val engineIncluded: Boolean = NativeFactory.get() != null

    val statusFlow: Flow<LocalAsrStatus>? get() = service?.statusFlow
    val installFlow get() = service?.installer?.state

    fun status(): LocalAsrStatus = service?.status() ?: LocalAsrStatus.NotInstalled
    fun installState(): InstallState = service?.installer?.state?.value ?: InstallState.NeedsFile("No speech model is listed in this build.")
    fun readiness(): LocalReadiness = service?.readiness() ?: LocalReadiness.Unavailable("no speech model is listed in this build")

    /** Loads the model now so the first segment is not delayed. Throws [np.opd.speech.AsrException]. */
    suspend fun prepare() { service?.prepare() }

    suspend fun transcribeWav(wav: ByteArray, voicedFraction: Float?): List<TranscriptLine> =
        (service ?: throw np.opd.speech.AsrException(np.opd.speech.AsrErrorKind.MODEL_MISSING)).transcribeWav(wav, voicedFraction)

    suspend fun install() { service?.installer?.install() }
    suspend fun importFrom(input: InputStream) { service?.installer?.importFrom(input) }
    suspend fun verify(): Boolean = service?.installer?.verifyNow() ?: false
    fun deleteModel() { service?.deleteModel() }
    fun release() { service?.release() }

    /** Short developer-area text: measured speed and what is actually running. */
    fun diagnostics(): String = listOfNotNull(
        service?.statsSummary(),
        service?.engineDiagnostics()?.takeIf { it.isNotBlank() }
    ).joinToString("\n")
}

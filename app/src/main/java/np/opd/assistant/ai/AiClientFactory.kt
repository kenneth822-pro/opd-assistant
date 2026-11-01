package np.opd.assistant.ai

import android.app.Application
import np.opd.assistant.SettingsStore
import np.opd.assistant.model.LocalModelManager
import np.opd.assistant.model.ModelType

/**
 * Builds an [AiClient] for a chosen [ModelInfo]. Extracted so the OPD session flow and the ward
 * round flow share one place to resolve provider keys, custom base URLs and on-device models.
 */
object AiClientFactory {

    fun create(
        app: Application,
        settings: SettingsStore,
        localModels: LocalModelManager,
        model: ModelInfo,
        attempts: Int = 4,
        fast: Boolean = false
    ): AiClient = when (model.provider) {
        Provider.GEMINI -> GeminiClient(settings.keyFor(Provider.GEMINI), model, attempts, fast)
        Provider.LOCAL_WHISPER -> {
            val dir = localModels.getModelDir(model.id, ModelType.STT)
                ?: throw ApiException("Local Whisper model not found: ${model.id}")
            LocalWhisperClient(app, dir)
        }
        Provider.LOCAL_LLM -> {
            val dir = localModels.getModelDir(model.id, ModelType.LLM)
                ?: throw ApiException("Local LLM model not found: ${model.id}")
            val (pte, tok) = LocalLlmClient.findModelFiles(dir)
                ?: throw ApiException("Local LLM model files incomplete: ${model.id}")
            LocalLlmClient(pte, tok)
        }
        else -> OpenAiCompatClient(settings.baseUrlFor(model.provider), settings.keyFor(model.provider), model, attempts)
    }
}

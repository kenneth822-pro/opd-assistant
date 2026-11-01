package np.opd.assistant.ward

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import np.opd.assistant.OpdApplication
import np.opd.assistant.SettingsStore
import np.opd.assistant.data.WardRepository
import np.opd.assistant.sync.CloudSyncManager
import np.opd.assistant.sync.SyncConfig
import np.opd.assistant.sync.SyncReport
import np.opd.assistant.sync.SyncState

data class CloudSyncUiState(
    val url: String = "",
    val doctorId: String = "",
    val hasKey: Boolean = false,
    val keyInput: String = "",
    val wifiOnly: Boolean = true,
    val state: SyncState = SyncState.IDLE,
    val message: String? = null
)

/** Backs the Settings "Cloud Sync (Supabase)" card: store config, test the connection, sync now. */
class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val repository = WardRepository((app as OpdApplication).database)

    private val manager = CloudSyncManager(
        context = app,
        repository = repository,
        configProvider = { config() },
        lastPullProvider = { settings.supabaseLastPull },
        onLastPull = { settings.supabaseLastPull = it }
    )

    private val _ui = MutableStateFlow(
        CloudSyncUiState(
            url = settings.supabaseUrl,
            doctorId = settings.supabaseDoctorId,
            hasKey = settings.supabaseAnonKey.isNotBlank(),
            wifiOnly = settings.autoSyncWifiOnly
        )
    )
    val ui: StateFlow<CloudSyncUiState> = _ui.asStateFlow()

    val syncState: StateFlow<SyncState> = manager.state
    val lastReport: StateFlow<SyncReport> = manager.lastReport

    private fun config() = SyncConfig(
        supabaseUrl = settings.supabaseUrl,
        supabaseAnonKey = settings.supabaseAnonKey,
        doctorId = settings.supabaseDoctorId,
        autoSyncWifiOnly = settings.autoSyncWifiOnly
    )

    fun setUrl(v: String) { settings.supabaseUrl = v; _ui.update { it.copy(url = v) } }
    fun setDoctorId(v: String) { settings.supabaseDoctorId = v; _ui.update { it.copy(doctorId = v) } }
    fun setKeyInput(v: String) = _ui.update { it.copy(keyInput = v) }
    fun setWifiOnly(v: Boolean) { settings.autoSyncWifiOnly = v; _ui.update { it.copy(wifiOnly = v) } }

    fun saveKey() {
        val k = _ui.value.keyInput
        if (k.isBlank()) return
        settings.supabaseAnonKey = k
        _ui.update { it.copy(hasKey = true, keyInput = "", message = "Key saved (encrypted).") }
    }

    fun clearKey() {
        settings.supabaseAnonKey = ""
        _ui.update { it.copy(hasKey = false, message = "Key removed.") }
    }

    fun testConnection() {
        viewModelScope.launch {
            _ui.update { it.copy(message = "Testing…") }
            val (ok, msg) = manager.testConnection()
            _ui.update { it.copy(message = msg) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val report = manager.sync()
            val summary = if (report.error != null) "Sync error: ${report.error}"
            else "Synced — pushed ${report.pushed}, pulled ${report.pulled}, conflicts ${report.conflicts}."
            _ui.update { it.copy(message = summary) }
        }
    }

    fun showMessage(m: String?) = _ui.update { it.copy(message = m) }
}

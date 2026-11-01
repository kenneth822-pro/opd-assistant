package np.opd.assistant.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import np.opd.assistant.OpdApplication
import np.opd.assistant.SettingsStore
import np.opd.assistant.data.WardRepository

/**
 * Watches connectivity and runs a background Supabase sync when the network comes back.
 *
 * It only fires when cloud sync is configured, when the current transport matches the configured
 * policy (`autoSyncWifiOnly`), and after a short debounce so transient network flaps do not trigger
 * back-to-back syncs. All work happens on [Dispatchers.IO]; the monitor never touches the UI.
 */
class NetworkSyncMonitor private constructor(context: Context) {

    private val app = context.applicationContext as OpdApplication
    private val settings = SettingsStore(app)
    private val repository = WardRepository(app.database)
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val manager = CloudSyncManager(
        context = app,
        repository = repository,
        configProvider = {
            SyncConfig(
                supabaseUrl = settings.supabaseUrl,
                supabaseAnonKey = settings.supabaseAnonKey,
                doctorId = settings.supabaseDoctorId,
                autoSyncWifiOnly = settings.autoSyncWifiOnly
            )
        },
        lastPullProvider = { settings.supabaseLastPull },
        onLastPull = { settings.supabaseLastPull = it }
    )

    private val _state = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = manager.state

    @Volatile private var wifiOnly: Boolean = true
    private var debounceJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleSync()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) scheduleSync()
        }
    }

    fun start() {
        wifiOnly = isWifiSatisfied()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    fun stop() {
        try { connectivity.unregisterNetworkCallback(callback) } catch (e: Exception) { }
        debounceJob?.cancel()
    }

    /** Debounced to avoid a burst of syncs during transient network flaps. */
    @Synchronized
    private fun scheduleSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            maybeSync()
        }
    }

    private suspend fun maybeSync() {
        if (!settings.supabaseUrl.isNotBlank() || !settings.supabaseAnonKey.isNotBlank() || !settings.supabaseDoctorId.isNotBlank()) {
            _state.value = SyncState.IDLE
            return
        }
        if (settings.autoSyncWifiOnly && !isWifiSatisfied()) {
            _state.value = SyncState.IDLE
            return
        }
        manager.sync()
    }

    private fun isWifiSatisfied(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        private const val TAG = "NetworkSyncMonitor"
        private const val DEBOUNCE_MS = 8_000L

        @Volatile private var instance: NetworkSyncMonitor? = null

        /** Starts the singleton monitor (idempotent). */
        fun start(context: Context) {
            instance ?: synchronized(this) {
                instance ?: NetworkSyncMonitor(context).also { it.start(); instance = it }
            }
        }

        fun stop() {
            instance?.stop()
            instance = null
        }
    }
}

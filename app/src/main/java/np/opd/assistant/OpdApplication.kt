package np.opd.assistant

import android.app.Application
import np.opd.assistant.data.local.ClinicalDatabase
import np.opd.assistant.sync.NetworkSyncMonitor

/**
 * Application entry point. Owns process-scoped singletons — the Room clinical database and the
 * network-reactive sync monitor — so screens and view models can depend on them without rebuilding
 * connections.
 */
class OpdApplication : Application() {

    val database: ClinicalDatabase by lazy { ClinicalDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Start watching connectivity so a background Supabase sync runs when the network returns.
        // The monitor stays idle until cloud sync is configured in Settings.
        NetworkSyncMonitor.start(this)
    }
}

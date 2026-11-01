package np.opd.assistant

import android.app.Application
import np.opd.assistant.data.local.ClinicalDatabase

/**
 * Application entry point. Owns process-scoped singletons — currently the Room clinical database —
 * so screens and view models can depend on them without rebuilding connections.
 */
class OpdApplication : Application() {

    val database: ClinicalDatabase by lazy { ClinicalDatabase.get(this) }
}

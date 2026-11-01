package np.opd.assistant.sync

/**
 * Offline-first sync contracts. The device is the source of truth for the ward workspace; sync
 * pushes local changes to Supabase (PostgREST) and pulls remote changes back, resolving conflicts
 * with Last-Write-Wins on `updatedAt`.
 */

data class SyncConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val doctorId: String,
    val autoSyncWifiOnly: Boolean = true
) {
    val configured: Boolean
        get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank() && doctorId.isNotBlank()

    /** Normalised PostgREST base, e.g. "https://xyz.supabase.co/rest/v1". */
    fun restBase(): String {
        val base = supabaseUrl.trim().trimEnd('/')
        return if (base.endsWith("/rest/v1")) base else "$base/rest/v1"
    }
}

enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR_OFFLINE, ERROR_AUTH }

/** Outcome of one sync cycle. */
data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val error: String? = null
)

/** A table row ready to upsert, keyed by its PostgREST table name. */
data class SyncRow(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val json: String
)

/** PostgREST column name aliases (the wire format uses snake_case). */
object SyncTables {
    const val PATIENTS = "patients"
    const val ENCOUNTERS = "encounters"
    const val CARDEX = "cardex_items"
    const val TASKS = "patient_tasks"
    const val MEDIA = "patient_media"
    const val CONSULTS = "consult_requests"
    const val DISCHARGES = "discharges"
    const val FLUID_IO = "fluid_io_entries"
    const val VITALS = "vitals_logs"
    const val OT = "ot_schedules"

    val ALL = listOf(PATIENTS, ENCOUNTERS, CARDEX, TASKS, MEDIA, CONSULTS, DISCHARGES, FLUID_IO, VITALS, OT)
}

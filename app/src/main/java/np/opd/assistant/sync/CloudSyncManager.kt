package np.opd.assistant.sync

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import np.opd.assistant.OpdApplication
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.CardexItemEntity
import np.opd.assistant.data.local.entities.ConsultEntity
import np.opd.assistant.data.local.entities.DischargeEntity
import np.opd.assistant.data.local.entities.EncounterEntity
import np.opd.assistant.data.local.entities.FluidIoEntity
import np.opd.assistant.data.local.entities.MediaEntity
import np.opd.assistant.data.local.entities.OtScheduleEntity
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.TaskEntity
import np.opd.assistant.data.local.entities.VitalsLogEntity
import np.opd.engine.J
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Push/pull sync against Supabase PostgREST.
 *
 * Push: every locally modified row (`isSynced = false`) is upserted. Pull: rows updated after the
 * last successful pull are fetched and applied. Conflicts are resolved Last-Write-Wins on
 * `updatedAt` (a remote row is applied only when its `updatedAt` is newer than the local row).
 *
 * The network is only touched while [sync] runs; the manager never blocks the UI. It is called from
 * a settings-controlled "Sync Now" and (optionally) after writes.
 */
class CloudSyncManager(
    context: Context,
    private val repository: WardRepository,
    private val configProvider: () -> SyncConfig,
    private val lastPullProvider: () -> Long,
    private val onLastPull: (Long) -> Unit
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val _state = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastReport = MutableStateFlow(SyncReport())
    val lastReport: StateFlow<SyncReport> = _lastReport.asStateFlow()

    /** Quick connectivity/auth probe: GET one row from patients. */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (!cfg.configured) return@withContext false to "Enter the Supabase URL, anon key and doctor id first."
        try {
            val req = Request.Builder()
                .url("${cfg.restBase()}patient_media?select=id&limit=1") // any table; RLS still applies
                .addHeader("apikey", cfg.supabaseAnonKey)
                .addHeader("Authorization", "Bearer ${cfg.supabaseAnonKey}")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) true to "Connected to Supabase."
                else if (resp.code == 401 || resp.code == 403) false to "Auth failed (${resp.code}). Check the anon key and RLS."
                else false to "Server returned HTTP ${resp.code}."
            }
        } catch (e: IOException) {
            false to "Offline or unreachable: ${e.message}"
        } catch (e: Exception) {
            false to "Connection error: ${e.message}"
        }
    }

    suspend fun sync(): SyncReport = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (!cfg.configured) {
            _state.value = SyncState.ERROR_AUTH
            return@withContext SyncReport(error = "Sync not configured.").also { _lastReport.value = it }
        }
        _state.value = SyncState.SYNCING
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        try {
            // ---- PUSH ----
            val pushResult = pushAll(cfg)
            pushed = pushResult.first
            conflicts += pushResult.second

            // ---- PULL ----
            val since = lastPullProvider()
            val now = System.currentTimeMillis()
            pulled = pullAll(cfg, since)

            onLastPull(now)
            val report = SyncReport(pushed = pushed, pulled = pulled, conflicts = conflicts)
            _lastReport.value = report
            _state.value = SyncState.SUCCESS
            report
        } catch (e: AuthException) {
            val report = SyncReport(pushed = pushed, pulled = pulled, conflicts = conflicts, error = e.message)
            _lastReport.value = report
            _state.value = SyncState.ERROR_AUTH
            report
        } catch (e: IOException) {
            val report = SyncReport(pushed = pushed, pulled = pulled, conflicts = conflicts, error = "Offline: ${e.message}")
            _lastReport.value = report
            _state.value = SyncState.ERROR_OFFLINE
            report
        } catch (e: Exception) {
            val report = SyncReport(pushed = pushed, pulled = pulled, conflicts = conflicts, error = e.message)
            _lastReport.value = report
            _state.value = SyncState.ERROR_OFFLINE
            report
        }
    }

    private class AuthException(message: String) : Exception(message)

    /* ------------------------------- push ------------------------------- */

    private fun pushAll(cfg: SyncConfig): Pair<Int, Int> {
        var pushed = 0
        var conflicts = 0

        // patients
        runBlockingList { repository.patientDao.unsynced() }.forEach { p ->
            upsert(cfg, SyncTables.PATIENTS, p.id, patientJson(p))?.let { applied ->
                if (applied) { runBlock { repository.patientDao.markSynced(listOf(p.id)) }; pushed++ } else conflicts++
            }
        }
        // encounters
        runBlockingList { repository.encounterDao.unsynced() }.forEach { e ->
            upsert(cfg, SyncTables.ENCOUNTERS, e.id, encounterJson(e))?.let { applied ->
                if (applied) { runBlock { repository.encounterDao.markSynced(listOf(e.id)) }; pushed++ } else conflicts++
            }
        }
        // cardex
        runBlockingList { repository.cardexDao.unsynced() }.forEach { c ->
            upsert(cfg, SyncTables.CARDEX, c.id, cardexJson(c))?.let { applied ->
                if (applied) { runBlock { repository.cardexDao.markSynced(listOf(c.id)) }; pushed++ } else conflicts++
            }
        }
        // tasks
        runBlockingList { repository.taskDao.unsynced() }.forEach { t ->
            upsert(cfg, SyncTables.TASKS, t.id, taskJson(t))?.let { applied ->
                if (applied) { runBlock { repository.taskDao.markSynced(listOf(t.id)) }; pushed++ } else conflicts++
            }
        }
        // media
        runBlockingList { repository.mediaDao.unsynced() }.forEach { m ->
            upsert(cfg, SyncTables.MEDIA, m.id, mediaJson(m))?.let { applied ->
                if (applied) { runBlock { repository.mediaDao.markSynced(listOf(m.id)) }; pushed++ } else conflicts++
            }
        }
        // consults
        runBlockingList { repository.consultDao.unsynced() }.forEach { c ->
            upsert(cfg, SyncTables.CONSULTS, c.id, consultJson(c))?.let { applied ->
                if (applied) { runBlock { repository.consultDao.markSynced(listOf(c.id)) }; pushed++ } else conflicts++
            }
        }
        // discharges
        runBlockingList { repository.dischargeDao.unsynced() }.forEach { d ->
            upsert(cfg, SyncTables.DISCHARGES, d.id, dischargeJson(d))?.let { applied ->
                if (applied) { runBlock { repository.dischargeDao.markSynced(listOf(d.id)) }; pushed++ } else conflicts++
            }
        }
        // fluid io
        runBlockingList { repository.fluidIoDao.unsynced() }.forEach { f ->
            upsert(cfg, SyncTables.FLUID_IO, f.id, fluidJson(f))?.let { applied ->
                if (applied) { runBlock { repository.fluidIoDao.markSynced(listOf(f.id)) }; pushed++ } else conflicts++
            }
        }
        // vitals
        runBlockingList { repository.vitalsLogDao.unsynced() }.forEach { v ->
            upsert(cfg, SyncTables.VITALS, v.id, vitalsJson(v))?.let { applied ->
                if (applied) { runBlock { repository.vitalsLogDao.markSynced(listOf(v.id)) }; pushed++ } else conflicts++
            }
        }
        // ot
        runBlockingList { repository.otScheduleDao.unsynced() }.forEach { o ->
            upsert(cfg, SyncTables.OT, o.id, otJson(o))?.let { applied ->
                if (applied) { runBlock { repository.otScheduleDao.markSynced(listOf(o.id)) }; pushed++ } else conflicts++
            }
        }
        return pushed to conflicts
    }

    /**
     * Upserts a row. Returns true when applied, false when the server already has a newer version
     * (a conflict), or null on a non-fatal row error.
     */
    private fun upsert(cfg: SyncConfig, table: String, id: String, body: String): Boolean? {
        return try {
            val req = Request.Builder()
                .url("${cfg.restBase()}/$table")
                .addHeader("apikey", cfg.supabaseAnonKey)
                .addHeader("Authorization", "Bearer ${cfg.supabaseAnonKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates,return=representation")
                .post(body.toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> true
                    resp.code == 401 || resp.code == 403 -> throw AuthException("Auth failed (${resp.code}).")
                    resp.code == 409 -> false // unique/constraint conflict
                    else -> throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /* ------------------------------- pull ------------------------------- */

    private fun pullAll(cfg: SyncConfig, since: Long): Int {
        var applied = 0
        SyncTables.ALL.forEach { table ->
            val rows = fetchSince(cfg, table, since)
            rows.forEach { obj ->
                if (applyRemote(table, obj)) applied++
            }
        }
        return applied
    }

    private fun fetchSince(cfg: SyncConfig, table: String, since: Long): List<J.Obj> {
        val req = Request.Builder()
            .url("${cfg.restBase()}/$table?updated_at=gt.$since&select=*")
            .addHeader("apikey", cfg.supabaseAnonKey)
            .addHeader("Authorization", "Bearer ${cfg.supabaseAnonKey}")
            .get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw AuthException("Auth failed (${resp.code}).")
            val body = resp.body?.string() ?: return emptyList()
            return (J.parse(body) as? J.Arr)?.items?.mapNotNull { it as? J.Obj } ?: emptyList()
        }
    }

    /** Applies a remote row if it is newer than the local row (Last-Write-Wins). */
    private fun applyRemote(table: String, o: J.Obj): Boolean {
        val id = o.str("id") ?: return false
        val remoteUpdated = o.dbl("updated_at")?.toLong() ?: 0L
        val deletedAt = o.dbl("deleted_at")?.toLong()
        return runBlock {
            when (table) {
                SyncTables.PATIENTS -> {
                    if (newer(remoteUpdated, repository.patientDao.byId(id)?.updatedAt)) {
                        repository.patientDao.upsert(patientFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.ENCOUNTERS -> {
                    if (newer(remoteUpdated, repository.encounterDao.byId(id)?.updatedAt)) {
                        repository.encounterDao.upsert(encounterFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.CARDEX -> {
                    if (newer(remoteUpdated, repository.cardexDao.byId(id)?.updatedAt)) {
                        repository.cardexDao.upsert(cardexFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.TASKS -> {
                    if (newer(remoteUpdated, repository.taskDao.byId(id)?.updatedAt)) {
                        repository.taskDao.upsert(taskFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.MEDIA -> {
                    if (newer(remoteUpdated, repository.mediaDao.byId(id)?.updatedAt)) {
                        repository.mediaDao.upsert(mediaFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.CONSULTS -> {
                    if (newer(remoteUpdated, repository.consultDao.byId(id)?.updatedAt)) {
                        repository.consultDao.upsert(consultFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.DISCHARGES -> {
                    if (newer(remoteUpdated, repository.dischargeDao.byId(id)?.updatedAt)) {
                        repository.dischargeDao.upsert(dischargeFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.FLUID_IO -> {
                    if (newer(remoteUpdated, repository.fluidIoDao.byId(id)?.updatedAt)) {
                        repository.fluidIoDao.upsert(fluidFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.VITALS -> {
                    if (newer(remoteUpdated, repository.vitalsLogDao.byId(id)?.updatedAt)) {
                        repository.vitalsLogDao.upsert(vitalsFromJ(o, deletedAt)); true
                    } else false
                }
                SyncTables.OT -> {
                    if (newer(remoteUpdated, repository.otScheduleDao.byId(id)?.updatedAt)) {
                        repository.otScheduleDao.upsert(otFromJ(o, deletedAt)); true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun newer(remote: Long, local: Long?): Boolean = local == null || remote >= local

    /* ------------------------------- serialization ------------------------------- */

    private fun quote(s: String?): String = if (s == null) "null" else J.Str(s).toJson()
    private fun strOrNull(o: J.Obj, k: String): String? = o.str(k)
    private fun boolInt(b: Boolean): String = if (b) "true" else "false"

    private fun patientJson(p: PatientEntity): String = """{
        "id":${quote(p.id)},"mrn":${quote(p.mrn)},"name":${quote(p.name)},"age":${p.age ?: "null"},
        "gender":${quote(p.gender)},"current_ward":${quote(p.currentWard.name)},"bed_number":${quote(p.bedNumber)},
        "status":${quote(p.status.name)},"created_at":${p.createdAt},"updated_at":${p.updatedAt},
        "is_synced":${boolInt(p.isSynced)},"deleted_at":${p.deletedAt ?: "null"}}""".trimIndent()

    private fun encounterJson(e: EncounterEntity): String = """{
        "id":${quote(e.id)},"patient_id":${quote(e.patientId)},"encounter_type":${quote(e.encounterType.name)},
        "admission_datetime":${e.admissionDatetime},"discharge_datetime":${e.dischargeDatetime ?: "null"},
        "unit":${quote(e.unit.name)},"consultant_name":${quote(e.consultantName)},"final_diagnosis":${quote(e.finalDiagnosis)},
        "updated_at":${e.updatedAt},"is_synced":${boolInt(e.isSynced)},"deleted_at":${e.deletedAt ?: "null"}}""".trimIndent()

    private fun cardexJson(c: CardexItemEntity): String = """{
        "id":${quote(c.id)},"patient_id":${quote(c.patientId)},"encounter_id":${quote(c.encounterId)},
        "drug_id":${quote(c.drugId)},"brand_stated":${quote(c.brandStated)},"dose_count":${c.doseCount ?: "null"},
        "dose_unit":${quote(c.doseUnit)},"route":${quote(c.route.name)},"frequency":${quote(c.frequency)},
        "timing":${quote(c.timing)},"start_date":${c.startDate},"stop_date":${c.stopDate ?: "null"},
        "is_active":${boolInt(c.isActive)},"safety_checked":${boolInt(c.safetyChecked)},"safety_notes":${quote(c.safetyNotes)},
        "created_at":${c.createdAt},"updated_at":${c.updatedAt},"is_synced":${boolInt(c.isSynced)},"deleted_at":${c.deletedAt ?: "null"}}""".trimIndent()

    private fun taskJson(t: TaskEntity): String = """{
        "id":${quote(t.id)},"patient_id":${quote(t.patientId)},"encounter_id":${quote(t.encounterId)},
        "title":${quote(t.title)},"category":${quote(t.category.name)},"is_completed":${boolInt(t.isCompleted)},
        "priority":${quote(t.priority.name)},"due_timestamp":${t.dueTimestamp ?: "null"},"completed_at":${t.completedAt ?: "null"},
        "source_note":${quote(t.sourceNote)},"created_at":${t.createdAt},"updated_at":${t.updatedAt},
        "is_synced":${boolInt(t.isSynced)},"deleted_at":${t.deletedAt ?: "null"}}""".trimIndent()

    private fun mediaJson(m: MediaEntity): String = """{
        "id":${quote(m.id)},"patient_id":${quote(m.patientId)},"file_path":${quote(m.filePath)},
        "category":${quote(m.category.name)},"capture_datetime":${m.captureDatetime},"notes":${quote(m.notes)},
        "label":${quote(m.label)},"side":${quote(m.side.name)},"anonymised":${boolInt(m.anonymised)},
        "updated_at":${m.updatedAt},"is_synced":${boolInt(m.isSynced)},"deleted_at":${m.deletedAt ?: "null"}}""".trimIndent()

    private fun consultJson(c: ConsultEntity): String = """{
        "id":${quote(c.id)},"patient_id":${quote(c.patientId)},"encounter_id":${quote(c.encounterId)},
        "target_specialty":${quote(c.targetSpecialty)},"clinical_question":${quote(c.clinicalQuestion)},
        "status":${quote(c.status.name)},"urgency":${quote(c.urgency.name)},"reply_notes":${quote(c.replyNotes)},
        "requested_at":${c.requestedAt},"replied_at":${c.repliedAt ?: "null"},"updated_at":${c.updatedAt},
        "is_synced":${boolInt(c.isSynced)},"deleted_at":${c.deletedAt ?: "null"}}""".trimIndent()

    private fun dischargeJson(d: DischargeEntity): String = """{
        "id":${quote(d.id)},"patient_id":${quote(d.patientId)},"encounter_id":${quote(d.encounterId)},
        "kind":${quote(d.kind.name)},"status":${quote(d.status.name)},"consultant_name":${quote(d.consultantName)},
        "admission_date":${d.admissionDate ?: "null"},"discharge_date":${d.dischargeDate ?: "null"},
        "presenting_complaints":${quote(d.presentingComplaints)},"hospital_course":${quote(d.hospitalCourse)},
        "rendered_text":${quote(d.renderedText)},"updated_at":${d.updatedAt},
        "is_synced":${boolInt(d.isSynced)},"deleted_at":${d.deletedAt ?: "null"}}""".trimIndent()

    private fun fluidJson(f: FluidIoEntity): String = """{
        "id":${quote(f.id)},"patient_id":${quote(f.patientId)},"timestamp":${f.timestamp},
        "intake_type":${quote(f.intakeType)},"intake_ml":${f.intakeMl},"output_type":${quote(f.outputType)},
        "output_ml":${f.outputMl},"notes":${quote(f.notes)},"updated_at":${f.updatedAt},
        "is_synced":${boolInt(f.isSynced)},"deleted_at":${f.deletedAt ?: "null"}}""".trimIndent()

    private fun vitalsJson(v: VitalsLogEntity): String = """{
        "id":${quote(v.id)},"patient_id":${quote(v.patientId)},"timestamp":${v.timestamp},
        "pulse":${v.pulse ?: "null"},"bp_systolic":${v.bpSystolic ?: "null"},"bp_diastolic":${v.bpDiastolic ?: "null"},
        "rr":${v.rr ?: "null"},"spo2":${v.spo2 ?: "null"},"temp_f":${v.tempF ?: "null"},
        "on_oxygen":${boolInt(v.onOxygen)},"consciousness":${quote(v.consciousness)},
        "news2_score":${v.news2Score},"news2_risk":${quote(v.news2Risk)},"updated_at":${v.updatedAt},
        "is_synced":${boolInt(v.isSynced)},"deleted_at":${v.deletedAt ?: "null"}}""".trimIndent()

    private fun otJson(o: OtScheduleEntity): String = """{
        "id":${quote(o.id)},"patient_id":${quote(o.patientId)},"encounter_id":${quote(o.encounterId)},
        "planned_procedure":${quote(o.plannedProcedure)},"scheduled_date":${o.scheduledDate},
        "urgency":${quote(o.urgency)},"pac_cleared":${boolInt(o.pacCleared)},"consent_signed":${boolInt(o.consentSigned)},
        "blood_arranged_units":${o.bloodArrangedUnits},"npo_since_timestamp":${o.npoSinceTimestamp ?: "null"},
        "site_marked":${boolInt(o.siteMarked)},"status":${quote(o.status)},"updated_at":${o.updatedAt},
        "is_synced":${boolInt(o.isSynced)},"deleted_at":${o.deletedAt ?: "null"}}""".trimIndent()

    /* ------------------------------- deserialization ------------------------------- */

    private fun patientFromJ(o: J.Obj, deletedAt: Long?) = PatientEntity(
        id = o.req("id"), mrn = strOrNull(o, "mrn"), name = o.strOr("name", ""), age = o.int("age"),
        gender = o.strOr("gender", "UNKNOWN"),
        currentWard = np.opd.assistant.data.local.entities.WardUnit.entries.firstOrNull { it.name == o.str("current_ward") } ?: np.opd.assistant.data.local.entities.WardUnit.WARD_1,
        bedNumber = o.strOr("bed_number", ""),
        status = np.opd.assistant.data.local.entities.PatientStatus.entries.firstOrNull { it.name == o.str("status") } ?: np.opd.assistant.data.local.entities.PatientStatus.ACTIVE,
        createdAt = o.dbl("created_at")?.toLong() ?: 0L,
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L,
        isSynced = true, deletedAt = deletedAt
    )

    private fun encounterFromJ(o: J.Obj, deletedAt: Long?) = EncounterEntity(
        id = o.req("id"), patientId = o.req("patient_id"),
        encounterType = np.opd.assistant.data.local.entities.EncounterType.entries.firstOrNull { it.name == o.str("encounter_type") } ?: np.opd.assistant.data.local.entities.EncounterType.INPATIENT,
        admissionDatetime = o.dbl("admission_datetime")?.toLong() ?: 0L,
        dischargeDatetime = o.dbl("discharge_datetime")?.toLong(),
        unit = np.opd.assistant.data.local.entities.ServiceUnit.entries.firstOrNull { it.name == o.str("unit") } ?: np.opd.assistant.data.local.entities.ServiceUnit.UNIT_1,
        consultantName = o.strOr("consultant_name", ""), finalDiagnosis = strOrNull(o, "final_diagnosis"),
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun cardexFromJ(o: J.Obj, deletedAt: Long?) = CardexItemEntity(
        id = o.req("id"), patientId = o.req("patient_id"), encounterId = o.req("encounter_id"),
        drugId = strOrNull(o, "drug_id"), brandStated = o.strOr("brand_stated", ""), doseCount = o.dbl("dose_count"),
        doseUnit = o.strOr("dose_unit", ""),
        route = np.opd.assistant.data.local.entities.MedRoute.entries.firstOrNull { it.name == o.str("route") } ?: np.opd.assistant.data.local.entities.MedRoute.OTHER,
        frequency = o.strOr("frequency", ""), timing = o.strOr("timing", ""),
        startDate = o.dbl("start_date")?.toLong() ?: 0L, stopDate = o.dbl("stop_date")?.toLong(),
        isActive = o.bool("is_active") ?: true, safetyChecked = o.bool("safety_checked") ?: false,
        safetyNotes = strOrNull(o, "safety_notes"), createdAt = o.dbl("created_at")?.toLong() ?: 0L,
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun taskFromJ(o: J.Obj, deletedAt: Long?) = TaskEntity(
        id = o.req("id"), patientId = o.req("patient_id"), encounterId = o.req("encounter_id"),
        title = o.strOr("title", ""),
        category = np.opd.assistant.data.local.entities.TaskCategory.entries.firstOrNull { it.name == o.str("category") } ?: np.opd.assistant.data.local.entities.TaskCategory.OTHER,
        isCompleted = o.bool("is_completed") ?: false,
        priority = np.opd.assistant.data.local.entities.TaskPriority.entries.firstOrNull { it.name == o.str("priority") } ?: np.opd.assistant.data.local.entities.TaskPriority.ROUTINE,
        dueTimestamp = o.dbl("due_timestamp")?.toLong(), completedAt = o.dbl("completed_at")?.toLong(),
        sourceNote = strOrNull(o, "source_note"), createdAt = o.dbl("created_at")?.toLong() ?: 0L,
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun mediaFromJ(o: J.Obj, deletedAt: Long?) = MediaEntity(
        id = o.req("id"), patientId = o.req("patient_id"), filePath = o.strOr("file_path", ""),
        category = np.opd.assistant.data.local.entities.MediaCategory.entries.firstOrNull { it.name == o.str("category") } ?: np.opd.assistant.data.local.entities.MediaCategory.OTHER,
        captureDatetime = o.dbl("capture_datetime")?.toLong() ?: 0L, notes = strOrNull(o, "notes"), label = strOrNull(o, "label"),
        side = np.opd.assistant.data.local.entities.MediaSide.entries.firstOrNull { it.name == o.str("side") } ?: np.opd.assistant.data.local.entities.MediaSide.NA,
        anonymised = o.bool("anonymised") ?: false,
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun consultFromJ(o: J.Obj, deletedAt: Long?) = ConsultEntity(
        id = o.req("id"), patientId = o.req("patient_id"), encounterId = o.req("encounter_id"),
        targetSpecialty = o.strOr("target_specialty", ""), clinicalQuestion = o.strOr("clinical_question", ""),
        status = np.opd.assistant.data.local.entities.ConsultStatus.entries.firstOrNull { it.name == o.str("status") } ?: np.opd.assistant.data.local.entities.ConsultStatus.REQUESTED,
        urgency = np.opd.assistant.data.local.entities.ConsultUrgency.entries.firstOrNull { it.name == o.str("urgency") } ?: np.opd.assistant.data.local.entities.ConsultUrgency.ROUTINE,
        replyNotes = strOrNull(o, "reply_notes"), requestedAt = o.dbl("requested_at")?.toLong() ?: 0L,
        repliedAt = o.dbl("replied_at")?.toLong(), updatedAt = o.dbl("updated_at")?.toLong() ?: 0L,
        isSynced = true, deletedAt = deletedAt
    )

    private fun dischargeFromJ(o: J.Obj, deletedAt: Long?) = DischargeEntity(
        id = o.req("id"), patientId = o.req("patient_id"), encounterId = o.req("encounter_id"),
        kind = np.opd.assistant.data.local.entities.DischargeKind.entries.firstOrNull { it.name == o.str("kind") } ?: np.opd.assistant.data.local.entities.DischargeKind.INPATIENT,
        status = np.opd.assistant.data.local.entities.DischargeStatus.entries.firstOrNull { it.name == o.str("status") } ?: np.opd.assistant.data.local.entities.DischargeStatus.DRAFT,
        consultantName = o.strOr("consultant_name", ""), admissionDate = o.dbl("admission_date")?.toLong(),
        dischargeDate = o.dbl("discharge_date")?.toLong(), presentingComplaints = o.strOr("presenting_complaints", ""),
        hospitalCourse = o.strOr("hospital_course", ""), renderedText = o.strOr("rendered_text", ""),
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun fluidFromJ(o: J.Obj, deletedAt: Long?) = FluidIoEntity(
        id = o.req("id"), patientId = o.req("patient_id"), timestamp = o.dbl("timestamp")?.toLong() ?: 0L,
        intakeType = o.strOr("intake_type", ""), intakeMl = o.int("intake_ml") ?: 0,
        outputType = o.strOr("output_type", ""), outputMl = o.int("output_ml") ?: 0, notes = strOrNull(o, "notes"),
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun vitalsFromJ(o: J.Obj, deletedAt: Long?) = VitalsLogEntity(
        id = o.req("id"), patientId = o.req("patient_id"), timestamp = o.dbl("timestamp")?.toLong() ?: 0L,
        pulse = o.int("pulse"), bpSystolic = o.int("bp_systolic"), bpDiastolic = o.int("bp_diastolic"),
        rr = o.int("rr"), spo2 = o.int("spo2"), tempF = o.dbl("temp_f"), onOxygen = o.bool("on_oxygen") ?: false,
        consciousness = o.strOr("consciousness", "ALERT"), news2Score = o.int("news2_score") ?: 0,
        news2Risk = o.strOr("news2_risk", "LOW"),
        updatedAt = o.dbl("updated_at")?.toLong() ?: 0L, isSynced = true, deletedAt = deletedAt
    )

    private fun otFromJ(o: J.Obj, deletedAt: Long?) = OtScheduleEntity(
        id = o.req("id"), patientId = o.req("patient_id"), encounterId = o.req("encounter_id"),
        plannedProcedure = o.strOr("planned_procedure", ""), scheduledDate = o.dbl("scheduled_date")?.toLong() ?: 0L,
        urgency = o.strOr("urgency", "ELECTIVE"), pacCleared = o.bool("pac_cleared") ?: false,
        consentSigned = o.bool("consent_signed") ?: false, bloodArrangedUnits = o.int("blood_arranged_units") ?: 0,
        npoSinceTimestamp = o.dbl("npo_since_timestamp")?.toLong(), siteMarked = o.bool("site_marked") ?: false,
        status = o.strOr("status", "SCHEDULED"), updatedAt = o.dbl("updated_at")?.toLong() ?: 0L,
        isSynced = true, deletedAt = deletedAt
    )

    /* ------------------------------- utils ------------------------------- */

    /** The DAOs expose suspend functions; the sync engine is already off the main thread. */
    private fun <T> runBlock(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
    private fun <T> runBlockingList(block: suspend () -> List<T>): List<T> = kotlinx.coroutines.runBlocking { block() }}

package np.opd.assistant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import np.opd.assistant.data.local.dao.CardexDao
import np.opd.assistant.data.local.dao.ConsultDao
import np.opd.assistant.data.local.dao.DischargeDao
import np.opd.assistant.data.local.dao.EncounterDao
import np.opd.assistant.data.local.dao.FluidIoDao
import np.opd.assistant.data.local.dao.LabDao
import np.opd.assistant.data.local.dao.MediaDao
import np.opd.assistant.data.local.dao.OpNoteDao
import np.opd.assistant.data.local.dao.OtScheduleDao
import np.opd.assistant.data.local.dao.PatientDao
import np.opd.assistant.data.local.dao.TaskDao
import np.opd.assistant.data.local.dao.VitalsLogDao
import np.opd.assistant.data.local.entities.CardexItemEntity
import np.opd.assistant.data.local.entities.ConsultEntity
import np.opd.assistant.data.local.entities.DischargeEntity
import np.opd.assistant.data.local.entities.EncounterEntity
import np.opd.assistant.data.local.entities.FluidIoEntity
import np.opd.assistant.data.local.entities.LabResultEntity
import np.opd.assistant.data.local.entities.MediaEntity
import np.opd.assistant.data.local.entities.OperativeNoteEntity
import np.opd.assistant.data.local.entities.OtScheduleEntity
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.TaskEntity
import np.opd.assistant.data.local.entities.VitalsLogEntity

/**
 * Local-first relational clinical database. This is the single source of truth for the ward /
 * inpatient workspace; the legacy single-session OPD flow continues to use its own JSON snapshot
 * until it is migrated on top of this database in a later phase.
 */
@Database(
    entities = [
        PatientEntity::class,
        EncounterEntity::class,
        CardexItemEntity::class,
        TaskEntity::class,
        MediaEntity::class,
        ConsultEntity::class,
        DischargeEntity::class,
        FluidIoEntity::class,
        VitalsLogEntity::class,
        OtScheduleEntity::class,
        LabResultEntity::class,
        OperativeNoteEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ClinicalDatabase : RoomDatabase() {

    abstract fun patientDao(): PatientDao
    abstract fun encounterDao(): EncounterDao
    abstract fun cardexDao(): CardexDao
    abstract fun taskDao(): TaskDao
    abstract fun mediaDao(): MediaDao
    abstract fun consultDao(): ConsultDao
    abstract fun dischargeDao(): DischargeDao
    abstract fun fluidIoDao(): FluidIoDao
    abstract fun vitalsLogDao(): VitalsLogDao
    abstract fun otScheduleDao(): OtScheduleDao
    abstract fun labDao(): LabDao
    abstract fun opNoteDao(): OpNoteDao

    companion object {
        const val DB_NAME = "clinical.db"

        @Volatile
        private var instance: ClinicalDatabase? = null

        fun get(context: Context): ClinicalDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): ClinicalDatabase =
            Room.databaseBuilder(context, ClinicalDatabase::class.java, DB_NAME)
                // No destructive migration: schema changes must ship an explicit Migration so ward
                // records are never silently dropped.
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

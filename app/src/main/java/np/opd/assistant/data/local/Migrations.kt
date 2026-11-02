package np.opd.assistant.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. Each is explicit so ward/discharge records are never silently dropped.
 *
 * The SQL for each migration is produced by a pure function ([statements1_2], [statements2_3]) so
 * it can be unit-tested on the JVM (structure, coverage, syntax of the statements) without an
 * Android device. The [Migration] objects simply execute those statements in order.
 */
object Migrations {

    /** v1 -> v2: add the discharges table (phase 4 discharge builder). */
    fun statements1_2(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `discharges` (
            `id` TEXT NOT NULL,
            `patientId` TEXT NOT NULL,
            `encounterId` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `consultantName` TEXT NOT NULL,
            `admissionDate` INTEGER,
            `dischargeDate` INTEGER,
            `procedureName` TEXT,
            `preoperativeDiagnosis` TEXT,
            `postoperativeDiagnosis` TEXT,
            `operativeFindings` TEXT,
            `presentingComplaints` TEXT NOT NULL,
            `hospitalCourse` TEXT NOT NULL,
            `dischargeVitals` TEXT NOT NULL,
            `homeMedications` TEXT NOT NULL,
            `woundNursingAdvice` TEXT NOT NULL,
            `redFlagsReturn` TEXT NOT NULL,
            `followUp` TEXT NOT NULL,
            `renderedText` TEXT NOT NULL,
            `renderedMarkdown` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`encounterId`) REFERENCES `encounters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_discharges_encounterId` ON `discharges` (`encounterId`)",
        "CREATE INDEX IF NOT EXISTS `index_discharges_patientId` ON `discharges` (`patientId`)",
        "CREATE INDEX IF NOT EXISTS `index_discharges_status` ON `discharges` (`status`)"
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements1_2().forEach { db.execSQL(it) }
        }
    }

    /**
     * v2 -> v3: offline-first sync metadata + fluid I/O, vitals logs and the OT slate.
     *
     * Sync columns are additive with SQLite defaults, so existing rows are preserved: `updatedAt`
     * defaults to 0 (or is already present), `isSynced` to 0 (not yet synced), `deletedAt` stays
     * NULL. Tables that already had `updatedAt` (patients, discharges) only get the two new columns.
     */
    fun statements2_3(): List<String> = buildList {
        // --- sync metadata on existing tables ---
        addAll(syncColumns("patients", hasUpdatedAt = true))
        addAll(syncColumns("encounters", hasUpdatedAt = false))
        addAll(syncColumns("cardex_items", hasUpdatedAt = false))
        addAll(syncColumns("patient_tasks", hasUpdatedAt = false))
        addAll(syncColumns("patient_media", hasUpdatedAt = false))
        addAll(syncColumns("consult_requests", hasUpdatedAt = false))
        addAll(syncColumns("discharges", hasUpdatedAt = true))

        // --- fluid_io_entries ---
        add(
            """
            CREATE TABLE IF NOT EXISTS `fluid_io_entries` (
                `id` TEXT NOT NULL,
                `patientId` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `intakeType` TEXT NOT NULL,
                `intakeMl` INTEGER NOT NULL,
                `outputType` TEXT NOT NULL,
                `outputMl` INTEGER NOT NULL,
                `notes` TEXT,
                `updatedAt` INTEGER NOT NULL,
                `isSynced` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        add("CREATE INDEX IF NOT EXISTS `index_fluid_io_entries_patientId` ON `fluid_io_entries` (`patientId`)")
        add("CREATE INDEX IF NOT EXISTS `index_fluid_io_entries_timestamp` ON `fluid_io_entries` (`timestamp`)")

        // --- vitals_logs ---
        add(
            """
            CREATE TABLE IF NOT EXISTS `vitals_logs` (
                `id` TEXT NOT NULL,
                `patientId` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `pulse` INTEGER,
                `bpSystolic` INTEGER,
                `bpDiastolic` INTEGER,
                `rr` INTEGER,
                `spo2` INTEGER,
                `tempF` REAL,
                `onOxygen` INTEGER NOT NULL,
                `consciousness` TEXT NOT NULL,
                `news2Score` INTEGER NOT NULL,
                `news2Risk` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `isSynced` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        add("CREATE INDEX IF NOT EXISTS `index_vitals_logs_patientId` ON `vitals_logs` (`patientId`)")
        add("CREATE INDEX IF NOT EXISTS `index_vitals_logs_timestamp` ON `vitals_logs` (`timestamp`)")

        // --- ot_schedules ---
        add(
            """
            CREATE TABLE IF NOT EXISTS `ot_schedules` (
                `id` TEXT NOT NULL,
                `patientId` TEXT NOT NULL,
                `encounterId` TEXT NOT NULL,
                `plannedProcedure` TEXT NOT NULL,
                `scheduledDate` INTEGER NOT NULL,
                `urgency` TEXT NOT NULL,
                `pacCleared` INTEGER NOT NULL,
                `consentSigned` INTEGER NOT NULL,
                `bloodArrangedUnits` INTEGER NOT NULL,
                `npoSinceTimestamp` INTEGER,
                `siteMarked` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `isSynced` INTEGER NOT NULL,
                `deletedAt` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`encounterId`) REFERENCES `encounters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        add("CREATE INDEX IF NOT EXISTS `index_ot_schedules_encounterId` ON `ot_schedules` (`encounterId`)")
        add("CREATE INDEX IF NOT EXISTS `index_ot_schedules_patientId` ON `ot_schedules` (`patientId`)")
        add("CREATE INDEX IF NOT EXISTS `index_ot_schedules_status` ON `ot_schedules` (`status`)")
    }

    private fun syncColumns(table: String, hasUpdatedAt: Boolean): List<String> = buildList {
        if (!hasUpdatedAt) add("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE `$table` ADD COLUMN `isSynced` INTEGER NOT NULL DEFAULT 0")
        add("ALTER TABLE `$table` ADD COLUMN `deletedAt` INTEGER")
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements2_3().forEach { db.execSQL(it) }
        }
    }

    /** v3 -> v4: cumulative lab results and surgical operative notes (phase 7). */
    fun statements3_4(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `lab_results` (
            `id` TEXT NOT NULL,
            `patientId` TEXT NOT NULL,
            `encounterId` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `testCode` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `category` TEXT NOT NULL,
            `numericValue` REAL NOT NULL,
            `unit` TEXT NOT NULL,
            `isAbnormal` INTEGER NOT NULL,
            `abnormalDirection` TEXT NOT NULL,
            `isPanic` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `isSynced` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`patientId`) REFERENCES `patients`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_lab_results_patientId` ON `lab_results` (`patientId`)",
        "CREATE INDEX IF NOT EXISTS `index_lab_results_encounterId` ON `lab_results` (`encounterId`)",
        "CREATE INDEX IF NOT EXISTS `index_lab_results_timestamp` ON `lab_results` (`timestamp`)",
        "CREATE INDEX IF NOT EXISTS `index_lab_results_updatedAt` ON `lab_results` (`updatedAt`)",
        """
        CREATE TABLE IF NOT EXISTS `operative_notes` (
            `id` TEXT NOT NULL,
            `patientId` TEXT NOT NULL,
            `encounterId` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `dataJson` TEXT NOT NULL,
            `fullText` TEXT NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `isSynced` INTEGER NOT NULL,
            `deletedAt` INTEGER,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`encounterId`) REFERENCES `encounters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_operative_notes_encounterId` ON `operative_notes` (`encounterId`)",
        "CREATE INDEX IF NOT EXISTS `index_operative_notes_patientId` ON `operative_notes` (`patientId`)",
        "CREATE INDEX IF NOT EXISTS `index_operative_notes_createdAt` ON `operative_notes` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_operative_notes_updatedAt` ON `operative_notes` (`updatedAt`)"
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements3_4().forEach { db.execSQL(it) }
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}

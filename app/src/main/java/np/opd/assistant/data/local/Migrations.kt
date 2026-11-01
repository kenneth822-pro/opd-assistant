package np.opd.assistant.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. Each is explicit so ward/discharge records are never silently dropped.
 */
object Migrations {

    /** v1 -> v2: add the discharges table (phase 4 discharge builder). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
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
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_discharges_encounterId` ON `discharges` (`encounterId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_discharges_patientId` ON `discharges` (`patientId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_discharges_status` ON `discharges` (`status`)")
        }
    }

    /**
     * v2 -> v3: offline-first sync metadata + fluid I/O, vitals logs and the OT slate.
     *
     * Sync columns (`updatedAt`, `isSynced`, `deletedAt`) are additive with SQLite defaults, so
     * existing rows are preserved: `updatedAt` defaults to 0, `isSynced` to 0 (not yet synced) and
     * `deletedAt` stays NULL. New tables are created with Room's exact expected column set.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // --- sync metadata on existing tables ---
            // patients already has createdAt/updatedAt; add isSynced + deletedAt.
            addSyncColumns(db, "patients", hasUpdatedAt = true)
            addSyncColumns(db, "encounters", hasUpdatedAt = false)
            addSyncColumns(db, "cardex_items", hasUpdatedAt = false)
            addSyncColumns(db, "patient_tasks", hasUpdatedAt = false)
            addSyncColumns(db, "patient_media", hasUpdatedAt = false)
            addSyncColumns(db, "consult_requests", hasUpdatedAt = false)
            // discharges already has updatedAt; add isSynced + deletedAt.
            addSyncColumns(db, "discharges", hasUpdatedAt = true)

            // --- fluid_io_entries ---
            db.execSQL(
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
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_fluid_io_entries_patientId` ON `fluid_io_entries` (`patientId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_fluid_io_entries_timestamp` ON `fluid_io_entries` (`timestamp`)")

            // --- vitals_logs ---
            db.execSQL(
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
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vitals_logs_patientId` ON `vitals_logs` (`patientId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vitals_logs_timestamp` ON `vitals_logs` (`timestamp`)")

            // --- ot_schedules ---
            db.execSQL(
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
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ot_schedules_encounterId` ON `ot_schedules` (`encounterId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ot_schedules_patientId` ON `ot_schedules` (`patientId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ot_schedules_status` ON `ot_schedules` (`status`)")
        }

        private fun addSyncColumns(db: SupportSQLiteDatabase, table: String, hasUpdatedAt: Boolean) {
            if (!hasUpdatedAt) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `isSynced` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `deletedAt` INTEGER")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}

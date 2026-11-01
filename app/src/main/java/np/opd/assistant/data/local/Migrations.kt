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

    val ALL = arrayOf(MIGRATION_1_2)
}

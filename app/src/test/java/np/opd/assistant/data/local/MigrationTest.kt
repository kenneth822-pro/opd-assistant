package np.opd.assistant.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration structure tests. These run on the JVM (no Android device) by verifying the SQL
 * statement lists that each migration executes: the right tables/columns are created or altered,
 * destructive operations are absent, and the statements are balanced/well-formed.
 *
 * A device-level `MigrationTestHelper` run (androidTest) is the complementary end-to-end check;
 * this guards the migration's intent in CI where no emulator is available.
 */
class MigrationTest {

    private val migration23 = Migrations.statements2_3()

    @Test
    fun migrationVersionsAreCorrect() {
        assertEquals(1, Migrations.MIGRATION_1_2.startVersion)
        assertEquals(2, Migrations.MIGRATION_1_2.endVersion)
        assertEquals(2, Migrations.MIGRATION_2_3.startVersion)
        assertEquals(3, Migrations.MIGRATION_2_3.endVersion)
    }

    @Test
    fun v2to3CreatesAllNewTables() {
        val joined = migration23.joinToString("\n")
        listOf("fluid_io_entries", "vitals_logs", "ot_schedules").forEach { table ->
            assertTrue("missing CREATE TABLE for $table", joined.contains("CREATE TABLE IF NOT EXISTS `$table`"))
        }
    }

    @Test
    fun v2to3AddsSyncColumnsToExistingTables() {
        val joined = migration23.joinToString("\n")
        val existing = listOf(
            "patients", "encounters", "cardex_items", "patient_tasks",
            "patient_media", "consult_requests", "discharges"
        )
        existing.forEach { table ->
            assertTrue("missing isSynced on $table", joined.contains("ALTER TABLE `$table` ADD COLUMN `isSynced`"))
            assertTrue("missing deletedAt on $table", joined.contains("ALTER TABLE `$table` ADD COLUMN `deletedAt`"))
        }
        // updatedAt is added only where it did not already exist.
        listOf("encounters", "cardex_items", "patient_tasks", "patient_media", "consult_requests").forEach { table ->
            assertTrue("missing updatedAt on $table", joined.contains("ALTER TABLE `$table` ADD COLUMN `updatedAt`"))
        }
        assertFalse("patients already has updatedAt", joined.contains("ALTER TABLE `patients` ADD COLUMN `updatedAt`"))
        assertFalse("discharges already has updatedAt", joined.contains("ALTER TABLE `discharges` ADD COLUMN `updatedAt`"))
    }

    @Test
    fun newTablesCarrySyncColumnsAndIndices() {
        val joined = migration23.joinToString("\n")
        listOf("fluid_io_entries", "vitals_logs", "ot_schedules").forEach { table ->
            assertTrue("$table missing updatedAt column", joined.contains("`updatedAt` INTEGER NOT NULL"))
            assertTrue("$table missing isSynced column", joined.contains("`isSynced` INTEGER NOT NULL"))
            assertTrue("$table missing deletedAt column", joined.contains("`deletedAt` INTEGER"))
        }
        assertTrue(joined.contains("index_vitals_logs_patientId"))
        assertTrue(joined.contains("index_fluid_io_entries_patientId"))
        assertTrue(joined.contains("index_ot_schedules_encounterId"))
    }

    @Test
    fun migrationsAreNonDestructive() {
        val all = (Migrations.statements1_2() + migration23).joinToString("\n").uppercase()
        assertFalse("migrations must never DROP tables", all.contains("DROP TABLE"))
        assertFalse("migrations must never DELETE rows", all.contains("DELETE FROM"))
    }

    @Test
    fun everyStatementLooksWellFormed() {
        (Migrations.statements1_2() + migration23).forEach { stmt ->
            val trimmed = stmt.trim()
            assertTrue("empty statement", trimmed.isNotEmpty())
            val upper = trimmed.uppercase()
            assertTrue(
                "unexpected statement head: ${trimmed.take(30)}",
                upper.startsWith("CREATE TABLE") || upper.startsWith("CREATE INDEX") || upper.startsWith("ALTER TABLE")
            )
            assertEquals("unbalanced parentheses in: ${trimmed.take(40)}", count(trimmed, '('), count(trimmed, ')'))
        }
    }

    @Test
    fun v1to2CreatesDischargesWithForeignKey() {
        val joined = Migrations.statements1_2().joinToString("\n")
        assertTrue(joined.contains("CREATE TABLE IF NOT EXISTS `discharges`"))
        assertTrue(joined.contains("FOREIGN KEY(`encounterId`) REFERENCES `encounters`(`id`)"))
    }

    private fun count(s: String, ch: Char): Int = s.count { it == ch }
}

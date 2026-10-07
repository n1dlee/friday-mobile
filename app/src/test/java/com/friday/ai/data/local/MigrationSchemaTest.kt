package com.friday.ai.data.local

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the hand-written migrations against the schema Room actually
 * generates.
 *
 * This exists because a mismatch here is invisible until the app is opened on
 * a phone that already has data — and then it crashes on launch instead of
 * upgrading. The first version of `MIGRATION_5_6` declared an inline
 * `PRIMARY KEY` where Room emits a trailing `PRIMARY KEY(...)` clause, which
 * this test catches without needing a device.
 */
class MigrationSchemaTest {

    private val schemaDir = File("schemas/com.friday.ai.data.local.FridayDatabase")

    private val migrationSql: String by lazy {
        File("src/main/java/com/friday/ai/data/local/Migrations.kt").readText()
    }

    private fun latestSchema(): JSONObject {
        assertTrue(
            "No exported schemas — is room.schemaLocation still configured?",
            schemaDir.exists()
        )
        val newest = schemaDir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
        assertTrue("No schema json found in $schemaDir", newest != null)
        return JSONObject(newest!!.readText())
    }

    private fun createSqlFor(table: String): String {
        val entities = latestSchema().getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == table) {
                return entity.getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        throw AssertionError("Table '$table' is not in the exported schema")
    }

    /** Column definitions, order-independent and whitespace-insensitive. */
    private fun columnsOf(createSql: String): Set<String> {
        val body = createSql.substringAfter('(').substringBeforeLast(')')
        return body.split(Regex(",(?![^(]*\\))"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Pulls a table's CREATE statement back out of the migration source.
     *
     * The statements are written both as triple-quoted blocks and as
     * concatenated literals, so rather than guessing where the Kotlin string
     * ends, this strips the quoting and then stops at the parenthesis that
     * closes the CREATE itself.
     */
    private fun migrationCreateFor(table: String): String {
        val marker = "CREATE TABLE IF NOT EXISTS `$table`"
        val start = migrationSql.indexOf(marker)
        assertTrue("Migrations.kt does not create `$table`", start >= 0)

        val cleaned = migrationSql.substring(start)
            .replace("\" +", " ")
            .replace("\"", " ")
            .replace(Regex("\\s+"), " ")

        var depth = 0
        var started = false
        for ((i, ch) in cleaned.withIndex()) {
            when (ch) {
                '(' -> { depth++; started = true }
                ')' -> {
                    depth--
                    if (started && depth == 0) return cleaned.substring(0, i + 1)
                }
            }
        }
        throw AssertionError("Unbalanced parentheses in the CREATE for `$table`")
    }

    @Test
    fun `session summaries migration matches the generated schema`() {
        val expected = columnsOf(createSqlFor("session_summaries"))
        val actual = columnsOf(migrationCreateFor("session_summaries"))
        assertEquals(
            "MIGRATION_5_6 would be rejected by Room at open time",
            expected, actual
        )
    }

    @Test
    fun `notifications migration matches the generated schema`() {
        val expected = columnsOf(createSqlFor("notifications"))
        val actual = columnsOf(migrationCreateFor("notifications"))
        assertEquals(
            "MIGRATION_4_5 would be rejected by Room at open time",
            expected, actual
        )
    }

    @Test
    fun `memories migration matches the generated schema`() {
        val expected = columnsOf(createSqlFor("memories"))
        val actual = columnsOf(migrationCreateFor("memories"))
        assertEquals(
            "MIGRATION_1_2 would be rejected by Room at open time",
            expected, actual
        )
    }

    @Test
    fun `modes migration matches the generated schema`() {
        val expected = columnsOf(createSqlFor("modes"))
        val actual = columnsOf(migrationCreateFor("modes"))
        assertEquals("MIGRATION_7_8 would be rejected by Room at open time", expected, actual)
    }

    @Test
    fun `there is a migration for every version step`() {
        val version = latestSchema().getJSONObject("database").getInt("version")
        for (from in 1 until version) {
            assertTrue(
                "No MIGRATION_${from}_${from + 1} — upgrading from $from would crash",
                migrationSql.contains("MIGRATION_${from}_${from + 1}")
            )
        }
    }

    @Test
    fun `destructive fallback is not used`() {
        // Losing the user's memory to a routine schema change is the whole
        // thing this work exists to prevent.
        val di = File("src/main/java/com/friday/ai/di/AppModule.kt").readText()
        assertTrue(
            "fallbackToDestructiveMigration would wipe the database on upgrade",
            !di.contains("fallbackToDestructiveMigration()")
        )
        assertTrue("Migrations are not registered", di.contains("addMigrations"))
    }
}

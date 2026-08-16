package dev.kaiharimoto.masterkey.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Guards the mistake that broke v1.0.1.
 *
 * A `countInBars` column was added to [SongEntity] without bumping the database
 * version. Room stores a hash of the schema in the file, saw the version was
 * unchanged, skipped migration and then failed its identity check — crashing the
 * app on every launch, because the first query happens at startup.
 *
 * CI enforces this properly by regenerating the schema and diffing it against the
 * committed JSON. This test is the same check locally, so the mistake is caught
 * before a push rather than after.
 *
 * (Parsed with kotlinx-serialization rather than `org.json`, which is a
 * throw-on-call stub in Android unit tests.)
 */
class SchemaTest {

    private val schemaDir = sequenceOf(
        File("schemas/dev.kaiharimoto.masterkey.data.MasterKeyDatabase"),
        File("app/schemas/dev.kaiharimoto.masterkey.data.MasterKeyDatabase"),
    ).firstOrNull { it.isDirectory }

    private fun latestSchema(): Pair<Int, JsonObject> {
        val dir = requireNotNull(schemaDir) {
            "No exported Room schemas found. exportSchema must stay true and " +
                "app/schemas must be committed."
        }
        val newest = dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: -1 }
        requireNotNull(newest) { "No schema JSON in ${dir.path}" }
        val database = Json.parseToJsonElement(newest.readText())
            .jsonObject.getValue("database").jsonObject
        return newest.nameWithoutExtension.toInt() to database
    }

    private fun columnsOf(schema: JsonObject, table: String): Set<String> {
        val entity = schema.getValue("entities").jsonArray
            .map { it.jsonObject }
            .firstOrNull { it.getValue("tableName").jsonPrimitive.content == table }
            ?: error("No table '$table' in the exported schema")

        return entity.getValue("fields").jsonArray
            .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            .toSet()
    }

    /**
     * Column names, taken from the entity's backing fields.
     *
     * Java reflection rather than Kotlin's, so the test needs no kotlin-reflect
     * on the unit-test classpath. For these data classes the backing fields map
     * one-to-one onto the properties, and none of them override the column name
     * via `@ColumnInfo(name = …)`.
     */
    private inline fun <reified T : Any> declaredColumns(): Set<String> =
        T::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    @Test
    fun `exported schema version matches the declared database version`() {
        val (fileVersion, schema) = latestSchema()

        assertThat(schema.getValue("version").jsonPrimitive.content.toInt())
            .isEqualTo(fileVersion)
        // v1.0.1's schema differed from v1.0.0's while both claimed version 1.
        // Anything below 2 means that fix was lost.
        assertThat(fileVersion).isAtLeast(2)
    }

    @Test
    fun `songs table matches SongEntity exactly`() {
        val (_, schema) = latestSchema()

        // Drift in either direction is a bug: a column in the entity but not the
        // schema means the export is stale, and the reverse means a migration
        // added something the model forgot.
        assertThat(columnsOf(schema, "songs")).isEqualTo(declaredColumns<SongEntity>())
    }

    @Test
    fun `countInBars is present, since shipping it without a migration is what broke v1_0_1`() {
        val (_, schema) = latestSchema()

        assertThat(columnsOf(schema, "songs")).contains("countInBars")
    }

    @Test
    fun `practice tables match their entities`() {
        val (_, schema) = latestSchema()

        assertThat(columnsOf(schema, "practice_sections"))
            .isEqualTo(declaredColumns<PracticeSectionEntity>())
        assertThat(columnsOf(schema, "practice_sessions"))
            .isEqualTo(declaredColumns<PracticeSessionEntity>())
    }
}

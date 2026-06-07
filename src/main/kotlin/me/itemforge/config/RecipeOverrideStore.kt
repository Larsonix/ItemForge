package me.itemforge.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.itemforge.config.migration.MigrationResult
import me.itemforge.config.migration.MigrationRunner
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Persistent storage for recipe overrides.
 *
 * Same architecture as [ItemOverrideStore] — nullable overlay pattern,
 * Gson+JSON, debounced atomic writes, schema validation, migration support.
 * See ItemOverrideStore for detailed documentation of the shared patterns.
 *
 * ## Recipe-Specific Design (CONFIG_VERSIONING.md §3.3)
 *
 * Recipe input overrides use **full list replacement** — if inputs are modified,
 * ALL inputs must be listed. This avoids fragile per-index patching that would
 * break when a mod reorders its recipe inputs between updates.
 *
 * @param filePath Path to overrides/recipes.json
 * @param debouncedWriter Shared debounced writer (may differ from item store's writer)
 * @param migrationRunner For schema version upgrades
 * @param backupManager For periodic snapshots
 * @param logger For load/save logging
 */
class RecipeOverrideStore(
    private val filePath: Path,
    private val debouncedWriter: DebouncedWriter,
    private val migrationRunner: MigrationRunner,
    private val backupManager: BackupManager,
    private val logger: HytaleLogger? = null
) {

    /** In-memory override data: recipe ID → override JsonObject. */
    private val overrides = HashMap<String, JsonObject>()
    private val lock = Any()

    private var saveCount = 0

    // ── Loading ─────────────────────────────────────────────────────────

    /**
     * Loads recipe overrides from disk with recovery hierarchy.
     * @return Number of recipe overrides loaded
     */
    fun load(): Int {
        val json = readWithRecovery() ?: run {
            logger?.atInfo()?.log("No recipe overrides found — starting fresh")
            return 0
        }

        val validation = SchemaValidator.validateRecipeOverrides(json)
        validation.warnings.forEach { logger?.atWarning()?.log(it) }

        if (validation.hasErrors) {
            logger?.atSevere()?.log("Recipe override validation failed:")
            validation.errors.forEach { logger?.atSevere()?.log("  - $it") }
            return 0
        }

        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            logger?.atSevere()?.log("Failed to parse recipes.json: ${e.message}")
            return 0
        }

        val migrated = migrateIfNeeded(root) ?: return 0
        return loadFromParsed(migrated)
    }

    fun reload(): Int {
        synchronized(lock) { overrides.clear() }
        return load()
    }

    // ── Queries ─────────────────────────────────────────────────────────

    fun getOverride(recipeId: String): JsonObject? {
        synchronized(lock) {
            return overrides[recipeId]?.deepCopy()
        }
    }

    fun hasOverride(recipeId: String): Boolean {
        synchronized(lock) {
            return overrides.containsKey(recipeId)
        }
    }

    fun getOverriddenRecipeIds(): Set<String> {
        synchronized(lock) {
            return overrides.keys.toSet()
        }
    }

    fun overrideCount(): Int {
        synchronized(lock) {
            return overrides.size
        }
    }

    fun getSnapshot(): Map<String, JsonObject> {
        synchronized(lock) {
            return overrides.mapValues { it.value.deepCopy() }
        }
    }

    // ── Mutations ───────────────────────────────────────────────────────

    /**
     * Saves or replaces an override for the given recipe.
     *
     * Unlike ItemOverrideStore which MERGES fields, recipe overrides use
     * full REPLACEMENT — the entire override object is replaced because
     * recipe inputs must be provided as a complete list.
     */
    fun saveOverride(recipeId: String, overrideFields: JsonObject) {
        synchronized(lock) {
            overrides[recipeId] = overrideFields.deepCopy()
        }
        scheduleSave()
    }

    fun removeOverride(recipeId: String) {
        val removed = synchronized(lock) {
            overrides.remove(recipeId) != null
        }
        if (removed) scheduleSave()
    }

    // ── Serialization ───────────────────────────────────────────────────

    private fun scheduleSave() {
        val json = serialize()
        debouncedWriter.schedule(filePath, json)

        saveCount++
        if (saveCount % SNAPSHOT_INTERVAL == 0) {
            debouncedWriter.flush()
            backupManager.createSnapshot(filePath)
        }
    }

    private fun serialize(): String {
        val root = JsonObject()
        root.addProperty("schema_version", SchemaValidator.CURRENT_RECIPE_SCHEMA_VERSION)

        val overridesObj = JsonObject()
        synchronized(lock) {
            for (recipeId in overrides.keys.sorted()) {
                overridesObj.add(recipeId, overrides[recipeId]!!.deepCopy())
            }
        }
        root.add("overrides", overridesObj)

        return GSON.toJson(root)
    }

    // ── File I/O ────────────────────────────────────────────────────────

    private fun readWithRecovery(): String? {
        if (Files.exists(filePath)) {
            try {
                val content = Files.readString(filePath)
                if (content.isNotBlank()) return content
            } catch (e: IOException) {
                logger?.atWarning()?.log("Failed to read $filePath: ${e.message}")
            }
        }

        val tmpPath = filePath.resolveSibling("${filePath.fileName}.tmp")
        if (Files.exists(tmpPath)) {
            logger?.atWarning()?.log("Primary recipes.json missing/corrupt — trying .tmp recovery")
            try {
                val content = Files.readString(tmpPath)
                if (content.isNotBlank()) {
                    val validation = SchemaValidator.validateRecipeOverrides(content)
                    if (!validation.hasErrors) {
                        logger?.atInfo()?.log("Recovered recipes from .tmp file")
                        return content
                    }
                }
            } catch (_: IOException) { /* .tmp unreadable — fall through to backup recovery */ }
        }

        val baseName = filePath.fileName.toString().substringBeforeLast(".")
        val backup = backupManager.findLatestBackup(baseName)
        if (backup != null) {
            try {
                val content = Files.readString(backup)
                if (content.isNotBlank()) {
                    logger?.atInfo()?.log("Recovered recipes from backup: ${backup.fileName}")
                    return content
                }
            } catch (_: IOException) { /* backup unreadable — fall through to return null (caller handles) */ }
        }

        return null
    }

    private fun migrateIfNeeded(root: JsonObject): JsonObject? {
        return when (val result = migrationRunner.migrate(root, SchemaValidator.CURRENT_RECIPE_SCHEMA_VERSION)) {
            is MigrationResult.NoChange -> root
            is MigrationResult.Migrated -> {
                backupManager.createMigrationBackup(filePath, result.fromVersion)
                try {
                    val json = GSON.toJson(result.data)
                    debouncedWriter.flush()
                    AtomicFileWriter().write(filePath, json)
                } catch (e: IOException) {
                    logger?.atSevere()?.log("Failed to write migrated recipes.json: ${e.message}")
                }
                result.data
            }
            is MigrationResult.Error -> {
                logger?.atSevere()?.log("Recipe config migration failed: ${result.reason}")
                null
            }
        }
    }

    private fun loadFromParsed(root: JsonObject): Int {
        val overridesObj = root.getAsJsonObject("overrides") ?: return 0

        synchronized(lock) {
            for ((recipeId, element) in overridesObj.entrySet()) {
                if (element.isJsonObject) {
                    overrides[recipeId] = element.asJsonObject.deepCopy()
                }
            }
        }

        val count = synchronized(lock) { overrides.size }
        logger?.atInfo()?.log("Loaded $count recipe override(s) from recipes.json")
        return count
    }

    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private const val SNAPSHOT_INTERVAL = 50
    }
}

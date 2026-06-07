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
 * Persistent storage for item stat overrides.
 *
 * Manages the `overrides/items.json` file — the nullable overlay pattern where
 * only overridden values are stored (null/absent = use original mod value).
 *
 * ## JSON Schema (from ARCHITECTURE.md §6.2)
 *
 * ```json
 * {
 *   "schema_version": 1,
 *   "overrides": {
 *     "Armor_Iron_Chest": {
 *       "MaxDurability": 150,
 *       "Armor": {
 *         "StatModifiers": {
 *           "Health": [{"Amount": 25, "CalculationType": "Additive"}]
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * Override keys match the Item codec's PascalCase JSON keys exactly — they can
 * be fed directly to `BuilderField.decode()` without key translation.
 *
 * ## Thread Safety
 *
 * The in-memory [overrides] map is accessed from the server thread (reads during
 * override application) and the debounce thread (writes during save). Mutations
 * are synchronized via [lock]. Reads during iteration should use [getSnapshot].
 *
 * ## Lifecycle
 *
 * 1. [load] during plugin start — reads file, validates, migrates if needed
 * 2. [saveOverride] / [removeOverride] during editor saves — updates in-memory + debounced write
 * 3. [reload] on `/itemforge reload` — re-reads from disk
 * 4. DebouncedWriter.shutdown() on plugin disable — flushes pending writes
 *
 * @param filePath Path to overrides/items.json
 * @param debouncedWriter For async disk writes
 * @param migrationRunner For schema version upgrades
 * @param backupManager For periodic snapshots
 * @param logger For load/save logging
 */
class ItemOverrideStore(
    private val filePath: Path,
    private val debouncedWriter: DebouncedWriter,
    private val migrationRunner: MigrationRunner,
    private val backupManager: BackupManager,
    private val logger: HytaleLogger? = null
) {

    /** In-memory override data: item ID → override JsonObject. */
    private val overrides = HashMap<String, JsonObject>()
    private val lock = Any()

    /** Save counter for periodic snapshots. */
    private var saveCount = 0

    // ── Loading ─────────────────────────────────────────────────────────

    /**
     * Loads overrides from disk. Validates schema, runs migrations if needed.
     *
     * Recovery hierarchy (CONFIG_VERSIONING.md §9.1):
     * 1. Try items.json
     * 2. If corrupt, try items.json.tmp (interrupted atomic write)
     * 3. If both fail, try latest backup
     * 4. If no backup, start with empty overrides
     *
     * @return Number of item overrides loaded
     */
    fun load(): Int {
        val json = readWithRecovery()

        if (json == null) {
            logger?.atInfo()?.log("No item overrides found — starting fresh")
            return 0
        }

        // Validate
        val validation = SchemaValidator.validateItemOverrides(json)
        validation.warnings.forEach { logger?.atWarning()?.log(it) }

        if (validation.hasErrors) {
            logger?.atSevere()?.log("Item override validation failed:")
            validation.errors.forEach { logger?.atSevere()?.log("  - $it") }
            logger?.atSevere()?.log("Starting with empty overrides. Fix the file and /itemforge reload.")
            return 0
        }

        // Parse
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            logger?.atSevere()?.log("Failed to parse items.json: ${e.message}")
            return 0
        }

        // Migrate if needed
        val migrated = migrateIfNeeded(root) ?: return 0

        // Extract overrides
        return loadFromParsed(migrated)
    }

    /**
     * Reloads overrides from disk. Clears in-memory state first.
     *
     * @return Number of item overrides loaded
     */
    fun reload(): Int {
        synchronized(lock) {
            overrides.clear()
        }
        return load()
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /**
     * Returns the override BSON-compatible JsonObject for an item.
     * null if no override exists.
     *
     * The returned object's keys match the codec's PascalCase JSON keys
     * and can be fed directly to OverrideEngine for application.
     */
    fun getOverride(itemId: String): JsonObject? {
        synchronized(lock) {
            return overrides[itemId]?.deepCopy()
        }
    }

    /** Whether an override exists for the given item. */
    fun hasOverride(itemId: String): Boolean {
        synchronized(lock) {
            return overrides.containsKey(itemId)
        }
    }

    /** Returns the set of all item IDs that have overrides. */
    fun getOverriddenItemIds(): Set<String> {
        synchronized(lock) {
            return overrides.keys.toSet()
        }
    }

    /**
     * Returns all field IDs that are overridden for the given item.
     *
     * Walks the override JsonObject keys (including nested dot-paths).
     * Used by CodecScanner to set FieldState.MODIFIED on scanned fields.
     */
    fun getOverriddenFieldIds(itemId: String): Set<String> {
        val override = synchronized(lock) { overrides[itemId] } ?: return emptySet()
        val fieldIds = mutableSetOf<String>()
        collectFieldIds(override, "", fieldIds)
        return fieldIds
    }

    /** Total number of items with overrides. */
    fun overrideCount(): Int {
        synchronized(lock) {
            return overrides.size
        }
    }

    /**
     * Returns a snapshot of all overrides for iteration.
     * The returned map is a copy — safe to iterate without holding the lock.
     */
    fun getSnapshot(): Map<String, JsonObject> {
        synchronized(lock) {
            return overrides.mapValues { it.value.deepCopy() }
        }
    }

    // ── Mutations ───────────────────────────────────────────────────────

    /**
     * Saves or updates an override for the given item.
     *
     * Merges the new override fields into any existing override for the item
     * (doesn't replace wholesale — preserves fields that weren't changed).
     *
     * @param itemId The item's asset ID
     * @param overrideFields JsonObject with PascalCase keys matching codec fields
     */
    fun saveOverride(itemId: String, overrideFields: JsonObject) {
        synchronized(lock) {
            val existing = overrides[itemId]
            if (existing != null) {
                // Deep merge: preserves earlier changes within the same component.
                // Without deep merge, saving Armor.StatModifiers.Stamina after
                // Armor.StatModifiers.Mana would REPLACE the entire Armor object,
                // losing the Mana entry.
                deepMergeJson(existing, overrideFields)
            } else {
                overrides[itemId] = overrideFields.deepCopy()
            }
        }

        scheduleSave()
    }

    /**
     * Deep-merges [source] into [target], modifying [target] in place.
     *
     * Recursion rule: if both target and source have a JsonObject for the same key,
     * recurse into that sub-object. Otherwise, source replaces target (scalars, arrays).
     *
     * This mirrors [BsonHelper.deepMerge] for Gson's JsonObject.
     */
    private fun deepMergeJson(target: JsonObject, source: JsonObject) {
        for ((key, sourceValue) in source.entrySet()) {
            val targetValue = target[key]
            if (sourceValue.isJsonObject && targetValue != null && targetValue.isJsonObject) {
                deepMergeJson(targetValue.asJsonObject, sourceValue.asJsonObject)
            } else {
                target.add(key, sourceValue)
            }
        }
    }

    /**
     * Removes the entire override for the given item.
     * The item will revert to its original mod/vanilla values.
     */
    fun removeOverride(itemId: String) {
        val removed = synchronized(lock) {
            overrides.remove(itemId) != null
        }

        if (removed) {
            scheduleSave()
        }
    }

    /**
     * Removes a specific field from an item's override.
     *
     * If the override becomes empty after removal, removes the entire entry.
     *
     * @param itemId The item's asset ID
     * @param fieldKey The top-level PascalCase field key to remove
     */
    fun removeField(itemId: String, fieldKey: String) {
        val changed = synchronized(lock) {
            val override = overrides[itemId] ?: return
            override.remove(fieldKey)
            if (override.size() == 0) {
                overrides.remove(itemId)
            }
            true
        }

        if (changed) {
            scheduleSave()
        }
    }

    // ── Serialization ───────────────────────────────────────────────────

    /**
     * Serializes the current in-memory state to JSON and schedules a debounced write.
     */
    private fun scheduleSave() {
        val json = serialize()
        debouncedWriter.schedule(filePath, json)

        // Periodic snapshot
        saveCount++
        if (saveCount % SNAPSHOT_INTERVAL == 0) {
            // Snapshot is of the file on disk — flush first
            debouncedWriter.flush()
            backupManager.createSnapshot(filePath)
        }
    }

    /**
     * Serializes the current overrides to a JSON string.
     *
     * Uses pretty-printing for readability — server owners may inspect this file.
     */
    private fun serialize(): String {
        val root = JsonObject()
        root.addProperty("schema_version", SchemaValidator.CURRENT_ITEM_SCHEMA_VERSION)

        val overridesObj = JsonObject()
        synchronized(lock) {
            // Sort by item ID for stable output (easier diffing)
            for (itemId in overrides.keys.sorted()) {
                overridesObj.add(itemId, overrides[itemId]!!.deepCopy())
            }
        }
        root.add("overrides", overridesObj)

        return GSON.toJson(root)
    }

    // ── File I/O ────────────────────────────────────────────────────────

    /**
     * Reads the override file with corruption recovery.
     *
     * Tries: primary file → .tmp file (interrupted write) → latest backup.
     * Returns null if nothing readable exists.
     */
    private fun readWithRecovery(): String? {
        // Tier 1: Primary file
        if (Files.exists(filePath)) {
            try {
                val content = Files.readString(filePath)
                if (content.isNotBlank()) return content
            } catch (e: IOException) {
                logger?.atWarning()?.log("Failed to read $filePath: ${e.message}")
            }
        }

        // Tier 2: Temp file from interrupted atomic write
        val tmpPath = filePath.resolveSibling("${filePath.fileName}.tmp")
        if (Files.exists(tmpPath)) {
            logger?.atWarning()?.log("Primary config missing/corrupt — trying .tmp recovery file")
            try {
                val content = Files.readString(tmpPath)
                if (content.isNotBlank()) {
                    // Validate before using — the .tmp may be incomplete
                    val validation = SchemaValidator.validateItemOverrides(content)
                    if (!validation.hasErrors) {
                        logger?.atInfo()?.log("Recovered from .tmp file")
                        return content
                    }
                }
            } catch (e: IOException) {
                logger?.atWarning()?.log("Failed to read .tmp recovery file: ${e.message}")
            }
        }

        // Tier 3: Latest backup
        val baseName = filePath.fileName.toString().substringBeforeLast(".")
        val backup = backupManager.findLatestBackup(baseName)
        if (backup != null) {
            logger?.atWarning()?.log("Primary and .tmp both failed — trying backup: ${backup.fileName}")
            try {
                val content = Files.readString(backup)
                if (content.isNotBlank()) {
                    logger?.atInfo()?.log("Recovered from backup: ${backup.fileName}")
                    return content
                }
            } catch (e: IOException) {
                logger?.atSevere()?.log("Backup also unreadable: ${e.message}")
            }
        }

        return null
    }

    /**
     * Runs migration if the schema version is older than current.
     * Returns the (possibly migrated) root JsonObject, or null on failure.
     */
    private fun migrateIfNeeded(root: JsonObject): JsonObject? {
        val result = migrationRunner.migrate(root, SchemaValidator.CURRENT_ITEM_SCHEMA_VERSION)

        return when (result) {
            is MigrationResult.NoChange -> root
            is MigrationResult.Migrated -> {
                // Back up before overwriting with migrated data
                backupManager.createMigrationBackup(filePath, result.fromVersion)
                // Write migrated data to disk immediately (not debounced — this is critical)
                try {
                    val json = GSON.toJson(result.data)
                    debouncedWriter.flush() // Ensure no pending write clobbers us
                    AtomicFileWriter().write(filePath, json)
                    logger?.atInfo()?.log("Migrated items.json from v${result.fromVersion} to v${result.toVersion}")
                } catch (e: IOException) {
                    logger?.atSevere()?.log("Failed to write migrated items.json: ${e.message}")
                }
                result.data
            }
            is MigrationResult.Error -> {
                logger?.atSevere()?.log("Item config migration failed: ${result.reason}")
                logger?.atSevere()?.log("Starting with empty overrides. Fix the file and /itemforge reload.")
                null
            }
        }
    }

    /**
     * Populates the in-memory overrides map from a parsed + validated root object.
     */
    private fun loadFromParsed(root: JsonObject): Int {
        val overridesObj = root.getAsJsonObject("overrides") ?: return 0

        synchronized(lock) {
            for ((itemId, itemElement) in overridesObj.entrySet()) {
                if (itemElement.isJsonObject) {
                    overrides[itemId] = itemElement.asJsonObject.deepCopy()
                }
            }
        }

        val count = synchronized(lock) { overrides.size }
        logger?.atInfo()?.log("Loaded $count item override(s) from items.json")
        return count
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Recursively collects dot-separated field IDs from a JsonObject.
     *
     * For `{"MaxDurability": 150, "Armor": {"StatModifiers": {"Health": [...]}}}`:
     * → {"MaxDurability", "Armor", "Armor.StatModifiers", "Armor.StatModifiers.Health"}
     */
    private fun collectFieldIds(obj: JsonObject, prefix: String, out: MutableSet<String>) {
        for ((key, value) in obj.entrySet()) {
            val id = if (prefix.isEmpty()) key else "$prefix.$key"
            out.add(id)
            if (value.isJsonObject) {
                collectFieldIds(value.asJsonObject, id, out)
            }
        }
    }

    companion object {
        /** Pretty-printing Gson for config output. */
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        /** Create a periodic snapshot every N saves. */
        private const val SNAPSHOT_INTERVAL = 50
    }
}

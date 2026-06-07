package me.itemforge.config.migration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Executes sequential config migrations from one schema version to another.
 *
 * ## Migration Flow (from CONFIG_VERSIONING.md §4.1)
 *
 * ```
 * 1. Extract schema_version from parsed JSON
 * 2. If version == current → no migration needed
 * 3. If version > current → ERROR (newer plugin created this config)
 * 4. If version < current → run migrations sequentially:
 *    a. Deep-copy the data (original preserved for backup)
 *    b. For each step from version to current:
 *       - Run migration.migrate(data)
 *       - Verify schema_version was updated
 *    c. Return migrated data
 * ```
 *
 * ## Safety
 *
 * - Operates on a deep copy — original data is never mutated
 * - Each step's output is verified before proceeding
 * - Missing migration steps cause an error (migration chain must be contiguous)
 * - The caller (ConfigManager) handles backup creation before calling this
 *
 * @param migrations Registered migrations, in order. Must form a contiguous chain.
 * @param logger Logger for migration progress and errors
 */
class MigrationRunner(
    migrations: List<ConfigMigration>,
    private val logger: HytaleLogger? = null
) {

    /**
     * Migrations indexed by their [ConfigMigration.fromVersion].
     * Allows O(1) lookup for chained execution.
     */
    private val migrationsByVersion: Map<Int, ConfigMigration>

    init {
        // Validate migration chain
        val byVersion = HashMap<Int, ConfigMigration>()
        for (migration in migrations) {
            require(migration.toVersion == migration.fromVersion + 1) {
                "Migration must be sequential: fromVersion=${migration.fromVersion}, " +
                "toVersion=${migration.toVersion} (expected ${migration.fromVersion + 1})"
            }
            require(!byVersion.containsKey(migration.fromVersion)) {
                "Duplicate migration for version ${migration.fromVersion}"
            }
            byVersion[migration.fromVersion] = migration
        }
        migrationsByVersion = byVersion
    }

    /**
     * Migrates config data to the target schema version.
     *
     * Returns a [MigrationResult] indicating what happened:
     * - [MigrationResult.NoChange] — data is already at target version
     * - [MigrationResult.Migrated] — data was transformed (contains new data)
     * - [MigrationResult.Error] — migration failed (contains reason)
     *
     * @param data The parsed config JSON
     * @param currentVersion The target schema version (what this plugin expects)
     * @return The migration result
     */
    fun migrate(data: JsonObject, currentVersion: Int): MigrationResult {
        val fileVersion = extractVersion(data)

        // Already at current version
        if (fileVersion == currentVersion) {
            return MigrationResult.NoChange
        }

        // Config from a newer plugin version — refuse to touch it
        if (fileVersion > currentVersion) {
            return MigrationResult.Error(
                "Config schema version $fileVersion is newer than this plugin supports " +
                "(max: $currentVersion). Update ItemForge or use a matching config."
            )
        }

        // Config from an older version — migrate forward
        if (fileVersion < 1) {
            return MigrationResult.Error(
                "Invalid schema version: $fileVersion (must be >= 1)"
            )
        }

        // Deep copy — never mutate the original
        val migrated = deepCopy(data)
        var version = fileVersion

        while (version < currentVersion) {
            val migration = migrationsByVersion[version]
            if (migration == null) {
                return MigrationResult.Error(
                    "No migration registered for version $version → ${version + 1}. " +
                    "Migration chain is broken."
                )
            }

            logger?.atInfo()?.log(
                "Migrating config from v$version to v${version + 1}: ${migration.description}"
            )

            try {
                migration.migrate(migrated)
            } catch (e: Exception) {
                return MigrationResult.Error(
                    "Migration v$version → v${version + 1} failed: ${e.message}"
                )
            }

            // Verify the migration updated the schema version
            val newVersion = extractVersion(migrated)
            if (newVersion != version + 1) {
                return MigrationResult.Error(
                    "Migration v$version → v${version + 1} did not update schema_version " +
                    "(expected ${version + 1}, got $newVersion)"
                )
            }

            version = newVersion
        }

        logger?.atInfo()?.log("Config migration complete: v$fileVersion → v$currentVersion")

        return MigrationResult.Migrated(
            fromVersion = fileVersion,
            toVersion = currentVersion,
            data = migrated
        )
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Extracts the schema_version from a config JSON object.
     * Returns 1 if missing (pre-versioning files default to v1).
     */
    private fun extractVersion(data: JsonObject): Int {
        val versionElement = data["schema_version"] ?: return 1
        if (!versionElement.isJsonPrimitive) return 1
        val primitive = versionElement.asJsonPrimitive
        return if (primitive.isNumber) primitive.asInt else 1
    }

    /**
     * Deep-copies a JsonObject via serialization.
     * Simple, correct, and fast enough for config-sized documents.
     */
    private fun deepCopy(obj: JsonObject): JsonObject {
        return JsonParser.parseString(obj.toString()).asJsonObject
    }
}

/**
 * Result of a migration attempt.
 */
sealed class MigrationResult {

    /** Config is already at the target version. No changes needed. */
    data object NoChange : MigrationResult()

    /**
     * Config was successfully migrated.
     * @param fromVersion The original schema version
     * @param toVersion The new schema version
     * @param data The migrated config data (deep copy — safe to use directly)
     */
    data class Migrated(
        val fromVersion: Int,
        val toVersion: Int,
        val data: JsonObject
    ) : MigrationResult()

    /**
     * Migration failed. The original config data is untouched.
     * @param reason Human-readable error description
     */
    data class Error(val reason: String) : MigrationResult()
}

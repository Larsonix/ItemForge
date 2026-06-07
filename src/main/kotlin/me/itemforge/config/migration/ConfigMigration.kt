package me.itemforge.config.migration

import com.google.gson.JsonObject

/**
 * A single migration step that transforms config data from one schema version to the next.
 *
 * Migrations are sequential — each transforms data from version N to version N+1.
 * The [MigrationRunner] chains them together to migrate across multiple versions.
 *
 * ## Rules (from CONFIG_VERSIONING.md §4)
 *
 * 1. Schema version is an integer, monotonically increasing, never skipped.
 * 2. New versions are created ONLY for breaking changes (key renames, type changes,
 *    restructured nesting, removed fields, semantic changes).
 * 3. Additive changes (new optional fields with defaults) do NOT bump the version.
 * 4. The plugin supports current version AND immediately previous (N and N-1).
 *
 * ## Implementation Contract
 *
 * - [fromVersion] + 1 MUST equal [toVersion] (sequential only)
 * - [migrate] receives a mutable JsonObject and returns the transformed version
 * - [migrate] MUST update "schema_version" in the returned object
 * - [migrate] MUST NOT throw — return the best-effort result and log issues
 * - [description] is logged during migration for audit trail
 */
interface ConfigMigration {

    /** The schema version this migration reads. */
    val fromVersion: Int

    /** The schema version this migration produces. Always [fromVersion] + 1. */
    val toVersion: Int

    /** Human-readable description of what changed. Logged during migration. */
    val description: String

    /**
     * Transforms the config data from [fromVersion] to [toVersion].
     *
     * The caller provides a deep copy — mutations are safe. The returned object
     * must have its "schema_version" set to [toVersion].
     *
     * @param data The config data at schema version [fromVersion]
     * @return The transformed config data at schema version [toVersion]
     */
    fun migrate(data: JsonObject): JsonObject
}

package me.itemforge.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Manages config file backups — pre-migration safety copies and periodic snapshots.
 *
 * ## Backup Types
 *
 * **Migration backups** — created before any schema migration runs. Named with the
 * old schema version so the admin can identify exactly which version the backup
 * represents: `items.v1.bak`. These are permanent (never rotated).
 *
 * **Periodic snapshots** — created after every N saves (configurable). Rotated
 * to keep only the last [maxSnapshots] copies. Named with timestamps:
 * `items.snapshot.2026-05-25_14-30-00.json`.
 * Allows recovery from logical errors (admin broke everything over many saves),
 * not just corruption.
 *
 * ## File Layout (from CONFIG_VERSIONING.md §8)
 *
 * ```
 * plugins/ItemForge/
 * ├── overrides/
 * │   ├── items.json          ← live config
 * │   └── recipes.json        ← live config
 * └── backups/
 *     ├── items.v1.bak        ← pre-migration backup
 *     ├── recipes.v1.bak      ← pre-migration backup
 *     ├── items.snapshot.2026-05-25_14-30-00.json   ← periodic
 *     └── items.snapshot.2026-05-25_12-00-00.json   ← periodic
 * ```
 *
 * @param backupDir The backup directory path (e.g., plugins/ItemForge/backups/)
 * @param maxSnapshots Maximum periodic snapshots to keep per file (default 3)
 * @param logger Logger for backup operations
 */
class BackupManager(
    private val backupDir: Path,
    private val maxSnapshots: Int = DEFAULT_MAX_SNAPSHOTS,
    private val logger: HytaleLogger? = null
) {

    /**
     * Creates a pre-migration backup of the given config file.
     *
     * The backup is named `{filename}.v{version}.bak` in the backup directory.
     * If the backup already exists (migration already attempted), it is NOT
     * overwritten — the first backup is the most valuable.
     *
     * @param sourceFile The config file to back up
     * @param schemaVersion The current schema version (before migration)
     * @return true if backup was created, false if it already existed or failed
     */
    fun createMigrationBackup(sourceFile: Path, schemaVersion: Int): Boolean {
        if (!Files.exists(sourceFile)) return false

        ensureBackupDir()

        val baseName = sourceFile.fileName.toString().substringBeforeLast(".")
        val extension = sourceFile.fileName.toString().substringAfterLast(".", "bak")
        val backupName = "$baseName.v$schemaVersion.$extension.bak"
        val backupPath = backupDir.resolve(backupName)

        // Don't overwrite existing migration backup — first one is most valuable
        if (Files.exists(backupPath)) {
            logger?.atInfo()?.log("Migration backup already exists: $backupName")
            return false
        }

        return try {
            Files.copy(sourceFile, backupPath)
            logger?.atInfo()?.log("Created migration backup: $backupName")
            true
        } catch (e: IOException) {
            logger?.atSevere()?.log("Failed to create migration backup $backupName: ${e.message}")
            false
        }
    }

    /**
     * Creates a periodic snapshot of the given config file.
     *
     * The snapshot is named `{filename}.snapshot.{timestamp}.{ext}` in the backup
     * directory. After creation, rotates old snapshots to keep only [maxSnapshots].
     *
     * @param sourceFile The config file to snapshot
     * @return true if snapshot was created successfully
     */
    fun createSnapshot(sourceFile: Path): Boolean {
        if (!Files.exists(sourceFile)) return false

        ensureBackupDir()

        val baseName = sourceFile.fileName.toString().substringBeforeLast(".")
        val extension = sourceFile.fileName.toString().substringAfterLast(".", "json")
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        val snapshotName = "$baseName.snapshot.$timestamp.$extension"
        val snapshotPath = backupDir.resolve(snapshotName)

        return try {
            Files.copy(sourceFile, snapshotPath, StandardCopyOption.REPLACE_EXISTING)
            rotateSnapshots(baseName, extension)
            logger?.atInfo()?.log("Created snapshot: $snapshotName")
            true
        } catch (e: IOException) {
            logger?.atSevere()?.log("Failed to create snapshot $snapshotName: ${e.message}")
            false
        }
    }

    /**
     * Lists all backup files for a given config file, sorted newest first.
     * Includes both migration backups and periodic snapshots.
     */
    fun listBackups(configBaseName: String): List<Path> {
        if (!Files.isDirectory(backupDir)) return emptyList()

        return try {
            Files.list(backupDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith(configBaseName) }
                    .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed())
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /**
     * Finds the most recent backup for the given config file.
     * Checks snapshots first (more recent), then migration backups.
     *
     * Used by the corruption recovery hierarchy (CONFIG_VERSIONING.md §9.1):
     * if the primary config is corrupt, try loading from the most recent backup.
     */
    fun findLatestBackup(configBaseName: String): Path? {
        return listBackups(configBaseName).firstOrNull()
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Rotates periodic snapshots to keep only the newest [maxSnapshots].
     *
     * Finds all snapshot files for the given base name, sorts by modification time,
     * and deletes the oldest ones beyond the limit.
     */
    private fun rotateSnapshots(baseName: String, extension: String) {
        if (!Files.isDirectory(backupDir)) return

        val prefix = "$baseName.snapshot."

        try {
            val snapshots = Files.list(backupDir).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName.toString()
                        name.startsWith(prefix) && name.endsWith(".$extension")
                    }
                    .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed())
                    .toList()
            }

            // Delete oldest snapshots beyond the limit
            if (snapshots.size > maxSnapshots) {
                for (i in maxSnapshots until snapshots.size) {
                    try {
                        Files.deleteIfExists(snapshots[i])
                        logger?.atFine()?.log("Rotated old snapshot: ${snapshots[i].fileName}")
                    } catch (_: IOException) {
                        // Non-critical — stale snapshot is harmless
                    }
                }
            }
        } catch (_: IOException) {
            // Non-critical — rotation failure just means extra snapshots remain
        }
    }

    /**
     * Ensures the backup directory exists. Creates it if necessary.
     */
    private fun ensureBackupDir() {
        try {
            Files.createDirectories(backupDir)
        } catch (e: IOException) {
            logger?.atSevere()?.log("Failed to create backup directory: ${e.message}")
        }
    }

    companion object {
        /** Default max periodic snapshots to keep per config file. */
        const val DEFAULT_MAX_SNAPSHOTS = 3

        /** Timestamp format for snapshot file names. Filesystem-safe (no colons). */
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}

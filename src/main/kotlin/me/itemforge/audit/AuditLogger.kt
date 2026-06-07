package me.itemforge.audit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Append-only audit logger for all ItemForge changes.
 *
 * Writes to `plugins/ItemForge/logs/itemforge-changes.log` — a human-readable
 * record of every modification made through the plugin. Essential for
 * accountability on multi-admin servers.
 *
 * ## Format
 *
 * One line per change:
 * ```
 * [2026-05-25T14:30:00Z] AdminPlayer | edit | Armor_Iron_Chest | MaxDurability | 100.0 -> 150.0 | Changed via editor
 * [2026-05-25T14:31:00Z] AdminPlayer | batch | (4 items) | Armor.StatModifiers.Health | - | Scale 80%
 * [2026-05-25T14:32:00Z] AdminPlayer | reset | Armor_Iron_Chest | - | - | Reverted to original
 * ```
 *
 * ## Security
 *
 * Append-only — the logger never reads, modifies, or deletes existing entries.
 * The file can only grow. Server owners can independently verify the audit trail.
 *
 * ## Thread Safety
 *
 * Writes are synchronized — safe to call from any thread (server thread,
 * UI event callbacks, batch operations).
 *
 * @param logFile Path to the audit log file
 * @param enabled Whether audit logging is active (from PluginConfig)
 * @param logger Plugin logger for error reporting
 */
class AuditLogger(
    private val logFile: Path,
    private val enabled: Boolean,
    private val logger: HytaleLogger? = null
) {

    private val lock = Any()

    init {
        if (enabled) {
            ensureLogDir()
        }
    }

    // ── Logging Methods ─────────────────────────────────────────────────

    /**
     * Records a single-item edit.
     */
    fun logEdit(
        playerName: String,
        itemId: String,
        fieldId: String,
        oldValue: Any?,
        newValue: Any?
    ) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = itemId,
            action = "edit",
            description = "Changed via editor",
            fieldId = fieldId,
            oldValue = oldValue?.toString(),
            newValue = newValue?.toString()
        ))
    }

    /**
     * Records a single-item reset.
     */
    fun logReset(playerName: String, itemId: String) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = itemId,
            action = "reset",
            description = "Reverted to original"
        ))
    }

    /**
     * Records a batch operation.
     */
    fun logBatch(
        playerName: String,
        itemCount: Int,
        fieldId: String,
        operation: String,
        operand: String
    ) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = "($itemCount items)",
            action = "batch",
            description = "$operation $operand",
            fieldId = fieldId
        ))
    }

    /**
     * Records a batch undo.
     */
    fun logBatchUndo(playerName: String, itemCount: Int, fieldId: String) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = "($itemCount items)",
            action = "batch_undo",
            description = "Undid batch operation",
            fieldId = fieldId
        ))
    }

    /**
     * Records a single recipe edit (Recipe tab save).
     */
    fun logRecipeEdit(playerName: String, recipeId: String, itemId: String) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = recipeId,
            action = "recipe_edit",
            description = "Recipe modified for item $itemId"
        ))
    }

    /**
     * Records a recipe reset (reverted to original).
     */
    fun logRecipeReset(playerName: String, recipeId: String, itemId: String) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = recipeId,
            action = "recipe_reset",
            description = "Recipe reverted for item $itemId"
        ))
    }

    /**
     * Records a batch recipe operation.
     */
    fun logBatchRecipe(
        playerName: String,
        recipeCount: Int,
        operation: String,
        detail: String
    ) {
        record(AuditEntry(
            timestamp = Instant.now(),
            playerName = playerName,
            targetId = "($recipeCount recipes)",
            action = "batch_recipe",
            description = "$operation: $detail"
        ))
    }

    /**
     * Records an arbitrary audit entry.
     */
    fun record(entry: AuditEntry) {
        if (!enabled) return

        val line = entry.formatLogLine()

        synchronized(lock) {
            try {
                Files.writeString(
                    logFile,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            } catch (e: IOException) {
                logger?.atWarning()?.log("AuditLogger: Failed to write: ${e.message}")
            }
        }
    }

    /**
     * Flushes any buffered writes. Currently a no-op since we write
     * immediately, but provided for shutdown lifecycle consistency.
     */
    fun flush() {
        // Writes are immediate — nothing to flush
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun ensureLogDir() {
        val parent = logFile.parent ?: return
        try {
            Files.createDirectories(parent)
        } catch (e: IOException) {
            logger?.atWarning()?.log("AuditLogger: Failed to create log directory: ${e.message}")
        }
    }
}

package me.itemforge.audit

import java.time.Instant

/**
 * A single auditable change record.
 *
 * Captures who changed what, when, and what the old/new values were.
 * Written to itemforge-changes.log by [AuditLogger].
 */
data class AuditEntry(
    /** When the change occurred. */
    val timestamp: Instant,

    /** Player name who made the change (from EntityRef). */
    val playerName: String,

    /** The item or recipe ID that was modified. */
    val targetId: String,

    /** What kind of change: "edit", "reset", "batch", "batch_undo", "batch_recipe". */
    val action: String,

    /** Human-readable description of the change. */
    val description: String,

    /** The changed field ID (e.g., "MaxDurability", "Armor.StatModifiers.Health"). null for resets. */
    val fieldId: String? = null,

    /** Old value before the change. null for new overrides. */
    val oldValue: String? = null,

    /** New value after the change. null for resets. */
    val newValue: String? = null
) {
    /**
     * Formats the entry as a single log line for the audit file.
     *
     * Format: `[timestamp] player | action | targetId | fieldId | old → new | description`
     */
    fun formatLogLine(): String {
        val field = fieldId ?: "-"
        val change = when {
            oldValue != null && newValue != null -> "$oldValue -> $newValue"
            newValue != null -> "-> $newValue"
            oldValue != null -> "$oldValue -> (removed)"
            else -> "-"
        }
        return "[$timestamp] $playerName | $action | $targetId | $field | $change | $description"
    }
}

package me.itemforge.scanner

/**
 * The modification state of a discovered field relative to its original value.
 *
 * Used in the editor UI to visually distinguish modified fields (gold highlight,
 * "was: X" indicator) from unmodified ones, and to track fields that have become
 * stale due to mod updates.
 */
enum class FieldState {

    /** No ItemForge override — field holds its original mod/vanilla value. */
    DEFAULT,

    /** ItemForge has overridden this field — current value differs from original. */
    MODIFIED,

    /**
     * Override exists in config but the field no longer exists on the item
     * (mod updated, field renamed or removed). The override is preserved
     * in config but cannot be applied. Shown as a warning in the editor.
     */
    STALE,

    /**
     * Field can be read but not written. This occurs when:
     * - encode() works but decode() would throw (e.g., ContainedAssetCodec fields
     *   that require AssetExtraInfo — use Path B instead of direct field decode)
     * - A validator rejects all possible values
     * - The field is structurally complex (arrays, maps) and not yet supported
     *   for direct editing
     *
     * Read-only fields are displayed in the editor but their inputs are disabled.
     */
    READ_ONLY
}

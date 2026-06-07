package me.itemforge.scanner

/**
 * Value constraints for an editable field, derived from two sources:
 *
 * 1. **Codec validators** — BuilderField.getValidators() returns Validator objects
 *    that enforce min/max/step at the SDK level (e.g., MinValidator, MaxValidator).
 *    These are hard limits — values outside them cause ThrowingValidationResults
 *    to throw during decode().
 *
 * 2. **Plugin config limits** — PluginConfig defines soft caps set by the server owner
 *    (e.g., max_damage=10000). These are UI-enforced — the admin can't exceed them
 *    through the editor, but the SDK wouldn't reject the value.
 *
 * The effective constraint is the intersection: the tighter of the two.
 * For example, if the codec allows 0-100000 but plugin config caps at 10000,
 * the effective max is 10000.
 *
 * All fields are nullable — null means no constraint for that dimension.
 * A field with all-null constraints has no restrictions (free numeric input).
 */
data class FieldConstraints(

    /** Minimum allowed value (inclusive). From codec MinValidator or plugin config. */
    val min: Double? = null,

    /** Maximum allowed value (inclusive). From codec MaxValidator or plugin config. */
    val max: Double? = null,

    /**
     * Step increment for the UI number input. From codec StepValidator if present.
     * Used by NumberFieldBuilder.withStep() for increment/decrement arrows.
     * null = any value within min/max is valid (no stepping).
     */
    val step: Double? = null,

    /**
     * Maximum decimal places displayed/accepted. From codec or inferred:
     * - Integer fields: 0
     * - Float/Double fields with step: derived from step precision
     * - Float/Double fields without step: null (any precision)
     */
    val maxDecimals: Int? = null,

    /**
     * Valid options for enum-like string fields (e.g., Quality: "Common", "Uncommon", ...).
     * Populated from codec validators that constrain to a set of values.
     * null = free-form string (no restricted options).
     * When non-null, the UI renders a dropdown instead of a text input.
     */
    val options: List<String>? = null
) {
    companion object {
        /** No constraints — any value is valid. */
        val NONE = FieldConstraints()
    }
}

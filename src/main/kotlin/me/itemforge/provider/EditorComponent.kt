package me.itemforge.provider

/**
 * One UI element in a mod-built editor panel ([EditorExtension.buildPanel]).
 *
 * This is ItemForge's component **palette**: a mod composes its own panel by returning a list
 * of these, and ItemForge renders them with its own themed widgets (so the look stays
 * consistent — mods choose *what*, ItemForge owns *how it looks*). It is also the exact wire
 * shape sent to the Vue renderer (Gson-serializable), so no separate mapping layer is needed.
 *
 * Construct via the [Companion] factory methods — clean from both Kotlin and Java:
 * ```java
 * List.of(
 *   EditorComponent.section("Enchantments"),
 *   EditorComponent.numberField("power", "Power", 5, 0.0, 100.0, 1.0, null),
 *   EditorComponent.dropdown("element", "Element", "Fire", List.of("Fire","Ice","Void"), null),
 *   EditorComponent.toggle("cursed", "Cursed", false, null),
 *   EditorComponent.button("reroll", "Reroll", "secondary")
 * );
 * ```
 *
 * Field components carry an [id] echoed back to [EditorExtension.applyChanges]; buttons carry
 * an [id] (the action id) echoed back to [EditorExtension.onAction]. Sections/labels/spacers
 * are presentational only.
 *
 * @property kind      One of: `field`, `button`, `section`, `label`, `spacer`.
 * @property id        Field id (→ applyChanges) or action id (→ onAction). null for presentational.
 * @property label     Field label / button text / section heading / label text.
 * @property inputType For `field`: `text`, `number`, `integer`, `toggle`, `dropdown`. Maps to the
 *                     editor's value types (text/dropdown → STRING, number → DOUBLE,
 *                     integer → INTEGER, toggle → BOOLEAN).
 * @property value     Current value of a field (String/Number/Boolean — enum as its name).
 * @property options   Allowed values for a `dropdown` field → rendered as a selector.
 * @property min       Inclusive minimum for numeric fields (null = unconstrained).
 * @property max       Inclusive maximum for numeric fields (null = unconstrained).
 * @property step      Step increment for numeric fields (null = default).
 * @property readOnly  Field shown for reference but not editable.
 * @property tooltip   Optional hover description (fields, buttons, labels).
 * @property style     Button style hint: `primary` or `secondary` (default `secondary`).
 */
// copy() is kept internal (matching the constructor) so mods compose only via the factory
// methods below — the wire shape stays ItemForge-owned and can't be bypassed from outside.
@ConsistentCopyVisibility
data class EditorComponent internal constructor(
    val kind: String,
    val id: String? = null,
    val label: String? = null,
    val inputType: String? = null,
    val value: Any? = null,
    val options: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val readOnly: Boolean = false,
    val tooltip: String? = null,
    val style: String? = null
) {
    companion object {
        /** Free-text input. */
        @JvmStatic
        @JvmOverloads
        fun textField(id: String, label: String, value: String?, tooltip: String? = null) =
            EditorComponent(KIND_FIELD, id = id, label = label, inputType = "text", value = value ?: "", tooltip = tooltip)

        /** Decimal number input. */
        @JvmStatic
        @JvmOverloads
        fun numberField(
            id: String, label: String, value: Number?,
            min: Double? = null, max: Double? = null, step: Double? = null, tooltip: String? = null
        ) = EditorComponent(KIND_FIELD, id = id, label = label, inputType = "number",
            value = value, min = min, max = max, step = step, tooltip = tooltip)

        /** Whole-number input. */
        @JvmStatic
        @JvmOverloads
        fun integerField(
            id: String, label: String, value: Number?,
            min: Double? = null, max: Double? = null, tooltip: String? = null
        ) = EditorComponent(KIND_FIELD, id = id, label = label, inputType = "integer",
            value = value, min = min, max = max, step = 1.0, tooltip = tooltip)

        /** Yes/No toggle. */
        @JvmStatic
        @JvmOverloads
        fun toggle(id: String, label: String, value: Boolean, tooltip: String? = null) =
            EditorComponent(KIND_FIELD, id = id, label = label, inputType = "toggle", value = value, tooltip = tooltip)

        /** Dropdown selector. [value] is the selected option (or its enum name). */
        @JvmStatic
        @JvmOverloads
        fun dropdown(id: String, label: String, value: String?, options: List<String>, tooltip: String? = null) =
            EditorComponent(KIND_FIELD, id = id, label = label, inputType = "dropdown",
                value = value ?: "", options = options, tooltip = tooltip)

        /** A clickable action button. [actionId] is echoed to [EditorExtension.onAction]. */
        @JvmStatic
        @JvmOverloads
        fun button(actionId: String, label: String, style: String = "secondary", tooltip: String? = null) =
            EditorComponent(KIND_BUTTON, id = actionId, label = label, style = style, tooltip = tooltip)

        /** A section heading with a divider — groups the components that follow it. */
        @JvmStatic
        fun section(label: String) = EditorComponent(KIND_SECTION, label = label)

        /** A read-only line of text / note. */
        @JvmStatic
        @JvmOverloads
        fun label(text: String, tooltip: String? = null) =
            EditorComponent(KIND_LABEL, label = text, tooltip = tooltip)

        /** Vertical spacing. */
        @JvmStatic
        fun spacer() = EditorComponent(KIND_SPACER)

        const val KIND_FIELD = "field"
        const val KIND_BUTTON = "button"
        const val KIND_SECTION = "section"
        const val KIND_LABEL = "label"
        const val KIND_SPACER = "spacer"
    }

    /** True if this is an editable field whose value should be collected on Save. */
    fun isEditableField(): Boolean = kind == KIND_FIELD && !readOnly && id != null
}

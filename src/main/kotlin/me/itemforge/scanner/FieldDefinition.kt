package me.itemforge.scanner

/**
 * A discovered editable field on an item, produced by [CodecScanner].
 *
 * This is the core data structure that flows through the entire system:
 * - **CodecScanner** creates FieldDefinitions by walking BuilderCodec.getEntries()
 * - **EditorBridge** serializes them to SerializedFieldDefinition for the Vue UI
 * - **OverrideEngine** uses [id] to match fields to BSON override keys
 * - **BatchEngine** uses [id] to find the same field across multiple items
 * - **SaveFlowHandler** uses [constraints] to validate admin input before applying
 *
 * FieldDefinition is a pure data class — no SDK types, no BuilderField references.
 * The CodecScanner extracts everything needed during scanning so downstream systems
 * never need to touch the codec directly.
 *
 * ## ID Format
 *
 * The [id] is a dot-separated BSON path from the item root to the leaf value:
 *
 * ```
 * "MaxDurability"                                    → top-level primitive
 * "Armor.StatModifiers.Health"                       → component → map → entry
 * "Armor.DamageResistance.Physical"                  → component → map → entry
 * "Tool.Speed"                                       → component → primitive
 * "Glider.TerminalVelocity"                          → component → primitive
 * "Container.Capacity"                               → component → primitive
 * "InteractionVars.Swing_Left_Damage"                → interaction var (Path B)
 * ```
 *
 * The OverrideEngine splits on "." to reconstruct the nested BSON structure
 * for override JSON. The first segment matches a top-level codec entry key.
 */
data class FieldDefinition(

    // ── Identity ─────────────────────────────────────────────────────────

    /**
     * Dot-separated BSON path from item root to this field's value.
     * Unique within an item. Used as the key in override JSON and change tracking.
     * See class doc for format examples.
     */
    val id: String,

    /**
     * The leaf-level JSON key (PascalCase). For top-level fields this equals [id].
     * For nested fields, it's the last segment: "Health" for "Armor.StatModifiers.Health".
     */
    val jsonKey: String,

    // ── Display ──────────────────────────────────────────────────────────

    /**
     * Human-readable name shown in the editor UI.
     * Computed by CodecScanner from the JSON key + context:
     * - "Max Durability" (top-level, key formatted)
     * - "Health" (stat modifier entry, key is already the name)
     * - "Physical Resistance" (damage resistance entry + category context)
     */
    val displayName: String,

    /**
     * Group heading in the editor UI. Fields with the same category are shown
     * together under a section header. Examples:
     * - "General" — MaxDurability, ItemLevel, MaxStack, Quality, etc.
     * - "Armor Stats" — Armor.StatModifiers entries
     * - "Damage Resistance" — Armor.DamageResistance entries
     * - "Tool Properties" — Tool.Speed, Tool.Specs entries
     * - "Flight Physics" — Glider.* fields
     * - "Container" — Container.Capacity, Container.GlobalFilter
     */
    val category: String,

    // ── Type & Value ─────────────────────────────────────────────────────

    /**
     * The data type of this field's value. Determines which UI input element
     * to render (NumberField, CheckBox, DropdownBox) and how to construct
     * the BSON value for decode().
     */
    val valueType: ValueType,

    /**
     * Current value read from the live item via BuilderField.encode().
     * Type matches [valueType]:
     * - DOUBLE → kotlin.Double
     * - INTEGER → kotlin.Int
     * - BOOLEAN → kotlin.Boolean
     * - STRING → kotlin.String
     *
     * null if the field exists in the codec but has no value on this item
     * (e.g., a component not present on this item type).
     */
    val currentValue: Any?,

    /**
     * The calculation type for stat modifier entries: "Additive" or "Multiplicative".
     *
     * Only populated for fields scanned from modifier arrays — the BSON structure
     * `[{Amount: 17.0, CalculationType: "Additive"}]` inside StatModifiers,
     * DamageResistance, DamageEnhancement, DamageClassEnhancement, KnockbackResistances,
     * and KnockbackEnhancements map entries.
     *
     * null for all non-modifier fields: top-level primitives (MaxDurability),
     * component primitives (Tool.Speed), direct numeric map entries, InteractionVars,
     * strings, and booleans.
     *
     * ## Consumers
     *
     * - **UI**: Rendered as an editable dropdown next to the amount input (UX_DESIGN.md §5.4).
     *   Changing from Additive to Multiplicative fundamentally alters the stat's effect —
     *   the extreme value overlay (UX §5.5.1) warns for multiplicative > 5.
     * - **Save flow**: Used by EditorBridge to reconstruct the full modifier array BSON
     *   `[{Amount: newValue, CalculationType: calcType}]` when saving. Without this,
     *   the save would produce a flat double instead of the array structure the codec expects.
     * - **Effect descriptions**: Used by ValueFormatter to compute "+25 Health" (Additive)
     *   vs "25% Health" (Multiplicative) below the input field.
     *
     * ## Implicit Signal
     *
     * `calculationType != null` reliably indicates this field is a modifier array entry.
     * No separate `isModifierEntry` flag is needed.
     */
    val calculationType: String? = null,

    // ── Constraints ──────────────────────────────────────────────────────

    /** Value constraints from codec validators + plugin config limits. */
    val constraints: FieldConstraints,

    // ── State & Metadata ─────────────────────────────────────────────────

    /** Modification state relative to the original mod/vanilla value. */
    val state: FieldState,

    /**
     * Documentation string from BuilderField.getDocumentation().
     * Shown as a tooltip when hovering over the field label in the editor.
     * null if the codec doesn't provide documentation for this field.
     */
    val tooltip: String?,

    // ── Context for Override Operations ───────────────────────────────────

    /**
     * The parent component key, if this field is inside a component sub-object.
     * null for top-level fields (MaxDurability, ItemLevel, etc.).
     * "Armor" for Armor.StatModifiers.Health, "Tool" for Tool.Speed, etc.
     *
     * Used by the editor UI to group fields into dynamic tabs per component.
     */
    val componentKey: String?,

    /**
     * Whether modifying this field requires Path B (full BSON re-decode via
     * assetStore.decode()) instead of Path A (direct BuilderField.decode()).
     *
     * true for InteractionVars and Interactions fields — these use
     * ContainedAssetCodec which requires AssetExtraInfo that only
     * assetStore.decode() provides (SDK_FINDINGS.md §9.4, §9.8).
     *
     * false for all other fields — direct field decode with default ExtraInfo works.
     */
    val requiresPathB: Boolean,

    /**
     * Whether this field currently has no value on the item.
     *
     * True when the codec defines the field but the item doesn't populate it
     * (null in the BSON output from encode()). The admin can set a value,
     * which creates an override from null to the new value.
     *
     * Used by the UI to render a dimmed "Not set" indicator, distinguishing
     * absent fields from fields with a zero/false/empty default.
     */
    val isNotSet: Boolean = false,

    /**
     * The mod that registered this field's underlying definition, if it isn't vanilla.
     *
     * Currently set only for `StatModifiers` entries whose stat type ([jsonKey]) was
     * registered by a mod's `EntityStatType` asset (e.g., Hexcode's `Magic_Power`,
     * `Volatility`). Resolved at scan time from the live stat-type registry via
     * [me.itemforge.metadata.ModSourceTracker]. null for vanilla stats and every
     * non-stat field.
     *
     * The editor uses this to group modded stats under their source mod in the
     * source dropdown — so a mod's custom stats surface by name, the same place
     * API-connected mods appear, with zero per-mod code.
     */
    val sourceMod: String? = null
)

/**
 * The primitive data type of an editable field value.
 *
 * Maps to both BSON types (for codec encode/decode) and UI input elements
 * (for Vuetale rendering). Only covers leaf-level types that an admin can
 * directly edit — compound types (DOCUMENT, ARRAY) are recursed into by
 * CodecScanner to produce leaf FieldDefinitions.
 *
 * Discovered from Probe 1.2 runtime BSON analysis:
 * - DOUBLE: MaxDurability, FuelQuality, Scale, TerminalVelocity, Speed, etc.
 * - INTEGER: ItemLevel, MaxStack, Container.Capacity
 * - BOOLEAN: Consumable, DropOnDeath, UsePlayerAnimations, etc.
 * - STRING: Quality, SoundEventId (free-form or enum-like with options)
 */
enum class ValueType {
    /** 64-bit floating point. UI: NumberField. BSON: BsonDouble. */
    DOUBLE,

    /** 32-bit integer. UI: NumberField (step=1, maxDecimals=0). BSON: BsonInt32. */
    INTEGER,

    /** True/false. UI: CheckBox. BSON: BsonBoolean. */
    BOOLEAN,

    /**
     * Text value. UI: TextField or DropdownBox (if FieldConstraints.options is set).
     * BSON: BsonString. Includes enum-like fields (Quality, ArmorSlot) where the
     * set of valid values is known from codec validators.
     */
    STRING
}

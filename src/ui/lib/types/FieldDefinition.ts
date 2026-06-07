/**
 * A single editable field serialized from the server.
 * Matches SerializedFieldDefinition in DataContracts.kt exactly.
 *
 * ARCHITECTURE.md §7.1
 */
export interface FieldDef {
  /** Dot-separated BSON path: "MaxDurability", "Armor.StatModifiers.Health" */
  id: string
  /** Human-readable name: "Max Durability", "Health" */
  displayName: string
  /** Group heading: "General", "Armor Stats", "Damage Resistance" */
  category: string
  /** Data type: "DOUBLE", "INTEGER", "BOOLEAN", "STRING" */
  valueType: 'DOUBLE' | 'INTEGER' | 'BOOLEAN' | 'STRING'
  /** Current value (after overrides). Type matches valueType. */
  currentValue: number | boolean | string | null
  /**
   * Calculation type for stat modifier entries: "Additive" or "Multiplicative".
   * null for non-modifier fields. Rendered as dropdown next to amount input;
   * used by save flow to reconstruct modifier array BSON on the server.
   */
  calculationType: string | null
  /** Original value before ItemForge override. null if never overridden. */
  originalValue: number | boolean | string | null
  /** Minimum allowed value (inclusive). null = no minimum. */
  min: number | null
  /** Maximum allowed value (inclusive). null = no maximum. */
  max: number | null
  /** Step increment for number inputs. null = any value. */
  step: number | null
  /** Max decimal places. 0 for integers, null for unconstrained. */
  maxDecimals: number | null
  /** Valid options for enum-like string fields (Quality). null = free text. */
  options: string[] | null
  /** Whether the field is read-only (display only, input disabled). */
  readOnly: boolean
  /** Modification state: "DEFAULT", "MODIFIED", "STALE", "READ_ONLY" */
  state: 'DEFAULT' | 'MODIFIED' | 'STALE' | 'READ_ONLY'
  /** Tooltip from codec documentation. null if no docs. */
  tooltip: string | null
  /** Computed description: "+25 Health", "12% Physical Resistance". null if N/A. */
  effectDescription: string | null
  /** Whether the field currently has no value on the item */
  isNotSet: boolean
  /**
   * Source mod that registered this stat type, or null for vanilla / non-stat fields.
   * Set for modded StatModifiers entries (e.g. Hexcode's Magic_Power). The editor groups
   * these under their mod in the source dropdown — the same place API extensions appear.
   */
  sourceMod: string | null

  // ── Per-item ("This Item") scope capability ────────────────
  /**
   * Whether this field can be edited at GLOBAL scope (asset override, all copies). True for every
   * codec-scanned asset field; false for instance-only synthetic fields (the held stack's Quantity /
   * Durability) — those are HIDDEN in Global scope. Absent → treated as true (back-compat).
   */
  globalCapable?: boolean
  /**
   * Whether this field can be edited at LOCAL scope (per-instance, affects only the held item).
   * Free tier: MaxDurability + the synthetic Quantity / Durability. All other fields are GREYED in
   * Local scope until their runtime applier ships. Absent → treated as false.
   */
  localCapable?: boolean
  /**
   * The field's current per-instance value on the held stack, shown in Local scope (e.g. this
   * specific sword's MaxDurability, distinct from currentValue = the asset default). null → fall
   * back to currentValue for display.
   */
  localValue?: number | boolean | string | null
  /**
   * Render this STRING field as a MULTILINE text box (lore/description) instead of a single-line
   * input. Used by the header Lore field. Absent → single line.
   */
  multiline?: boolean
}

/**
 * Complete payload for the editor page.
 * Matches EditorPayload in DataContracts.kt exactly.
 *
 * Pushed via setData("editor", json) when the editor opens,
 * and returned by bridge methods after mutations (save, reset).
 *
 * ARCHITECTURE.md §7.1
 */
import type { FieldDef } from './FieldDefinition'
import type { RecipeData } from './RecipeData'

export interface EditorPayload {
  itemId: string
  itemName: string
  itemMod: string
  itemType: string | null
  hasOverride: boolean
  fields: FieldDef[]
  /**
   * Fields the item could have but currently doesn't (`isNotSet`, no override) — the "addable"
   * universe (e.g. every registered StatModifiers stat). NOT rendered as rows; fed to
   * the "+ Add a stat" FieldPicker and mounted on demand. Splitting these out of `fields` keeps
   * the editor's element count (and mount time) bounded regardless of installed mod count.
   * Optional for back-compat with any payload built before the split (treated as empty).
   */
  addableFields?: FieldDef[]
  originalValues: Record<string, number | boolean | string | null>
  tabs: string[]
  recipeData: RecipeData | null
  /** Per-tab edit permissions for the viewing player. */
  permissions: EditorPermissions
  /**
   * Selectable editor sources. Empty/absent → no dropdown (plain base editor).
   * When a mod extension contributes a panel: [Base Item, ...one per managing extension].
   */
  editorSources?: EditorSource[]
  /** Extension id → its declarative component panel (rendered by ExtensionPanel.vue). */
  extensionPanels?: Record<string, EditorComponent[]>
  /**
   * Whether per-item ("This Item") editing is available. True only when
   * the editor was opened by inspecting a held item (a concrete ItemStack exists to write back to).
   * Enables the Global/Local scope toggle; false (dashboard/command opens) locks scope to Global.
   */
  localScopeAvailable?: boolean
  /**
   * The item's custom NAME as an editable header field (single-line), editable in BOTH scopes
   * (global = the item type's translation broadcast; local = this held stack's ItemDisplay). null →
   * the viewer can't edit it (no General permission); the header then shows a static name.
   */
  nameField?: FieldDef | null
  /** The item's custom LORE/description as an editable header field (multiline). null → not editable. */
  loreField?: FieldDef | null
  /** Present only on error payloads */
  error?: string
}

/**
 * One entry in the editor's source dropdown.
 * - `"BASE"` — the base item (all fields, vanilla + modded inline).
 * - `"MOD:<name>"` — auto-detected mod whose registered stats appear on this item;
 *   selecting it focuses the editor on just that mod's stats (still saved to the base item).
 * - any other id — an API EditorExtension with its own declarative panel.
 */
export interface EditorSource {
  /** "BASE", "MOD:<name>", or an extension id. */
  id: string
  /** Display label shown in the dropdown. */
  name: string
}

/**
 * One UI element in a mod-built extension panel. Matches EditorComponent.kt.
 * Mods declare these; ItemForge renders them with its themed widgets.
 */
export interface EditorComponent {
  /** "field" | "button" | "section" | "label" | "spacer" */
  kind: string
  /** field id (→ save) or action id (→ extensionAction); null for presentational. */
  id?: string | null
  /** field label / button text / section heading / label text */
  label?: string | null
  /** for field: "text" | "number" | "integer" | "toggle" | "dropdown" */
  inputType?: string | null
  value?: unknown
  options?: string[] | null
  min?: number | null
  max?: number | null
  step?: number | null
  readOnly?: boolean
  tooltip?: string | null
  /** button style: "primary" | "secondary" */
  style?: string | null
}

/**
 * What the viewing player may edit. Maps to tabs:
 * canEditStats → Properties/Defense/Damage, canEditRecipes → Recipe,
 * canEditGeneral → General, canReset → Reset button.
 * Matches EditorPermissions in DataContracts.kt.
 */
export interface EditorPermissions {
  canEditStats: boolean
  canEditRecipes: boolean
  canEditGeneral: boolean
  canReset: boolean
}

/**
 * Response from the save bridge method.
 * Matches SaveResponse in DataContracts.kt.
 */
export interface SaveResponse {
  success: boolean
  payload: EditorPayload | null
  error: string | null
  validationErrors: FieldValidationError[]
  /** True when rejected for lack of permission — show denial overlay. */
  permissionDenied?: boolean
}

export interface FieldValidationError {
  fieldId: string
  message: string
}

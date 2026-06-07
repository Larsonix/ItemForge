/**
 * Recipe data payload from the server.
 * Matches the Kotlin RecipeData in DataContracts.kt.
 * Carried within EditorPayload.recipeData when the item has a recipe.
 */

/** A single input material in a recipe */
export interface RecipeInput {
  /** Index in the original Input[] array (0-based). */
  index: number
  /** Specific item ID (e.g., "Ingredient_Bar_Iron"). null if using resourceTypeId. */
  itemId: string | null
  /** Resource type wildcard (e.g., "Wood_All", "Fuel"). null if using itemId. */
  resourceTypeId: string | null
  /** Amount required. Always >= 1. */
  quantity: number
  /** Resolved display name (from ItemNameResolver or formatted ResourceTypeId). */
  displayName: string
}

/** A single bench requirement entry */
export interface RecipeBench {
  /** Index in the original BenchRequirement[] array (0-based). */
  index: number
  /** Bench type: "Crafting", "Processing", "DiagramCrafting", "StructuralCrafting" */
  type: string
  /** Bench ID: "Workbench", "Weapon_Bench", "Armor_Bench", etc. */
  benchId: string
  /** Human-readable bench name */
  benchDisplayName: string
  /** Panel categories: ["Weapon_Sword"], etc. null for Processing. */
  categories: string[] | null
  /** Minimum bench tier level (0 = base). */
  requiredTierLevel: number
}

/** Output item entry (read-only in v1) */
export interface RecipeOutput {
  itemId: string
  quantity: number
  displayName: string
}

/** Bench info for dropdown population */
export interface BenchInfo {
  id: string
  type: string
  displayName: string
  panels: string[]
  maxTier: number
}

/** Complete recipe data for a single item */
export interface RecipeData {
  recipeId: string
  inputs: RecipeInput[]
  /** Output pattern: "quantity" (most common), "array" (salvage), "primary" (rare) */
  outputPattern: 'quantity' | 'array' | 'primary'
  outputQuantity: number | null
  outputs: RecipeOutput[]
  timeSeconds: number
  knowledgeRequired: boolean
  requiredMemoriesLevel: number
  benchRequirements: RecipeBench[]
  hasOverride: boolean
  // Original values for "was:" indicators (null if never overridden)
  originalInputs: RecipeInput[] | null
  originalOutputQuantity: number | null
  originalTimeSeconds: number | null
  originalKnowledgeRequired: boolean | null
  originalRequiredMemoriesLevel: number | null
  originalBenchRequirements: RecipeBench[] | null
  // Bench data for dropdowns
  benchRegistry: Record<string, BenchInfo>
  /**
   * True when the item has no recipe yet and this is a blank, creatable template
   * (output pre-bound to this item, empty inputs/benches). The editor shows the
   * same form; the first save creates a brand-new recipe. False for existing recipes.
   */
  isNew: boolean
}

/** Partial recipe changes sent to the server on save */
export interface RecipeChanges {
  /** Per-index input changes (quantity-only, backward compat). Mutually exclusive with inputsFull. */
  inputs?: Record<number, Partial<{ itemId: string | null; resourceTypeId: string | null; quantity: number }>>
  /** Full input list replacement. Used when inputs are added, removed, or materials changed. */
  inputsFull?: Array<{ itemId: string | null; resourceTypeId: string | null; quantity: number }>
  outputQuantity?: number
  timeSeconds?: number
  knowledgeRequired?: boolean
  requiredMemoriesLevel?: number
  benches?: Record<number, Partial<{ requiredTierLevel: number; benchId: string; categories: string[] }>>
  /**
   * Full bench-requirement list replacement. Used when bench requirements are
   * added, removed, or their bench/type changed. Mutually exclusive with `benches`
   * (the per-index modify path). An empty array is a valid, intentional value:
   * it removes ALL bench requirements, which makes the recipe uncraftable
   * (verified: CraftingManager.isValidBenchForRecipe rejects recipes with no bench).
   */
  benchesFull?: Array<{ type: string; benchId: string; categories: string[]; requiredTierLevel: number }>
}

/** Material search result from bridge searchMaterials() */
export interface MaterialSearchResult {
  /** Asset ID (item ID or ResourceTypeId) */
  id: string
  /** Human-readable display name */
  displayName: string
  /** "item" for specific items, "resource" for ResourceTypeId wildcards */
  type: 'item' | 'resource'
}

/** Local representation of a recipe input during editing (supports add/remove/modify) */
export interface LocalInput {
  /** Stable key for v-for (auto-increment counter, never reused) */
  uid: number
  /** Specific item ID. null if using resourceTypeId. */
  itemId: string | null
  /** Resource type wildcard. null if using itemId. */
  resourceTypeId: string | null
  /** Amount required. Always >= 1. */
  quantity: number
  /** Resolved display name for the UI */
  displayName: string
  /** Index in original server inputs (null if newly added) */
  originalIndex: number | null
  /** Whether this input was added during this editing session */
  isNew: boolean
}

/**
 * Local representation of a bench requirement during editing (supports add/remove/modify).
 *
 * Parallels [LocalInput]: a reactive working copy keyed by a stable `uid`, seeded from
 * the server's [RecipeBench] list. The Recipe tab edits this array directly; on every
 * change `syncBenchChanges()` projects it into [RecipeChanges] — `benchesFull` (full
 * replacement) for structural edits (add/remove/bench-or-type change), or the lighter
 * per-index `benches` map for tier/panel-only modifies.
 */
export interface LocalBench {
  /** Stable key for v-for (auto-increment counter, never reused). */
  uid: number
  /** BenchType name: "Crafting" | "Processing" | "DiagramCrafting" | "StructuralCrafting". Derived from the bench id via BenchRegistry. */
  type: string
  /** Bench ID (e.g. "Workbench", "Weapon_Bench"). */
  benchId: string
  /** Panel categories. Full array preserved from the server; only collapsed to one when the panel dropdown is actively changed. */
  categories: string[]
  /** Minimum bench tier level (0 = base). */
  requiredTierLevel: number
  /** Index in the original server benchRequirements (null if newly added). Used for "was:" lookups. */
  originalIndex: number | null
  /** Whether this bench was added during this editing session. */
  isNew: boolean
}

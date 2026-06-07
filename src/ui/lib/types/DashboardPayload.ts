/**
 * TypeScript interfaces for the Dashboard data payload.
 *
 * Mirrors the Kotlin data classes in DataContracts.kt.
 * Pushed via setData("dashboard", json) when the dashboard opens.
 */

export interface DashboardPayload {
  items: DashboardItem[]
  typeOptions: string[]
  modOptions: string[]
  overrideCount: number
  totalCount: number
}

export interface DashboardItem {
  id: string
  name: string
  type: string
  mod: string
  hasOverride: boolean
  overrideCount: number
  durability: number | null
  health: number | null
  damage: number | null
  speed: number | null
  /** Physical DamageResistance as %. Multiplicative values ×100 at extraction (0.09 → 9). */
  defense: number | null
  level: number | null
  quality: string | null
  stamina: number | null
  /** Fire DamageResistance as %. Same ×100 treatment as defense. */
  fireRes: number | null
  /** Armor equipment slot: Head, Chest, Hands, Legs. null for non-armor. */
  slot: string | null
  maxStack: number | null
}

/**
 * One row of a stat-batch preview (from dashboardBridge.batchPreview).
 * Mirrors BatchPreviewRow in BatchEngine.kt.
 */
export interface BatchPreviewRow {
  itemId: string
  itemName: string
  oldValue: number | null
  newValue: number | null
  skipped: boolean
  reason: string | null
}

/** A single before→after change within a recipe preview (mirrors RecipeFieldChange). */
export interface RecipeFieldChange {
  label: string
  oldValue: number
  newValue: number
}

/**
 * One row of a recipe-batch preview (from dashboardBridge.batchRecipePreview).
 * `changes` is one entry per input (INPUTS target) or a single entry (TIME).
 */
export interface RecipePreviewRow {
  itemId: string
  itemName: string
  skipped: boolean
  reason: string | null
  changes: RecipeFieldChange[]
}

/** Response shape shared by batchPreview / batchRecipePreview. */
export interface BatchPreviewResponse<T> {
  success: boolean
  error?: string
  rows: T[]
  appliedCount: number
  skippedCount: number
}

/** How a catalog field is filtered in the UI. */
export type FieldKind = 'numeric' | 'boolean' | 'enum' | 'text'

/**
 * One entry in the unified field catalog (from dashboardBridge.fieldCatalog).
 * Drives BOTH the dashboard stat-filter and the batch-stats field picker.
 * Mirrors CatalogEntry in DashboardBridge.kt.
 */
export interface CatalogField {
  id: string
  label: string
  category: string
  kind: FieldKind
  /** Valid values for enum fields (kind === 'enum'). */
  options?: string[]
  /** Whether the numeric batch-edit overlay may write this field
   *  (false for filter-only fields like normalized weapon damage). */
  batchEditable: boolean
}

export interface DashboardState {
  searchText: string
  typeFilter: string
  modFilter: string
  qualityFilter: string
  slotFilter: string
  statusFilter: string
  viewMode: string
  sortColumn: string
  sortAscending: boolean
  // Advanced stat filter state
  statFilter?: string
  statOperator?: string
  statValue?: string
}

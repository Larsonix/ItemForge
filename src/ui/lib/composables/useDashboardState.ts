/**
 * Dashboard state management composable.
 *
 * Manages filters, sorting, view mode, and display cap.
 * All filtering and sorting happens client-side via Vue computed properties
 * for instant responsiveness — no server roundtrip needed.
 *
 * Display cap: max 100 items rendered at once. Each table row ≈ 8 elements,
 * 100 rows × 8 = 800 elements. Vuetale handles ~1000 well. Beyond 200 rows
 * (1600 elements), client FPS drops. UX_DESIGN.md §4.7 already planned this.
 */
import { ref, computed, type Ref, type ComputedRef } from 'vue'
import type { DashboardItem, CatalogField, FieldKind } from '../types/DashboardPayload'

/**
 * View-adaptive display cap. Grid cells are lightweight (~6 elements each),
 * table rows are heavier (~12 elements each). Both caps target ~1200 total
 * Vuetale elements — the comfortable upper bound for ~4s mount/switch.
 *
 * Vuetale creates elements sequentially via V8→Kotlin bridge at ~3-5ms each.
 * 1200 elements × 3ms = ~4s. Beyond this, mount timeout or sluggish UX.
 */
// Grid view cap = the native ItemGrid's OWN slot cap. Unlike the table (Vue elements per
// row), grid items are host-pushed native slots (zero Vue elements per item), so the limit
// is the server push budget — NOT a Vue element count. The grid's `Slots` is set wholesale
// in ONE ~4MB UICommand (no append/stream primitive), and the client holds the whole array
// in RAM (virtualizing rendering only), so ~30k is the hard ceiling; 20k keeps a safe margin.
// Exported so the footer's "shown" count and the actual grid push (Dashboard.vue syncGrid)
// share ONE source of truth — the footer can never under-report again. MUST stay in lockstep
// with Kotlin DashboardBridge.NATIVE_GRID_CAP.
export const GRID_NATIVE_CAP = 20000
// The batch action bar + per-row selection indicator add elements to the
// table view, so the row cap drops from 100 to 80 to stay within the ~800-element
// comfort budget (80 rows × ~9 + chrome ≈ 720).
const TABLE_CAP = 80

/**
 * Word-based fuzzy match: split query into words, all words must appear
 * somewhere in the target. "iron chest" matches "Iron Chestplate".
 */
function fuzzyMatch(query: string, text: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(w => w.length > 0)
  const target = text.toLowerCase()
  return words.every(word => target.includes(word))
}

/** Thousand-separator formatting (Javet V8 may lack ICU for toLocaleString). */
function fmtNum(n: number): string {
  if (n < 1000) return String(n)
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** Definition for a context-dependent stat column in the table view */
export interface StatColumnDef {
  /** Sort key — used in toggleSort/sortColumn state */
  key: string
  /** Display header label */
  label: string
  /** Extract the numeric value from a DashboardItem (null if N/A) */
  getValue: (item: DashboardItem) => number | null
}

/**
 * Catalog field IDs whose values are already pre-extracted into the dashboard payload.
 * Filtering by these is INSTANT — no server value-fetch. This is purely a speed
 * optimization, NOT the source of truth for what's filterable: the full field list
 * comes dynamically from the server catalog. Maps catalog field id → payload accessor.
 */
const PAYLOAD_FAST_FIELDS: Record<string, (i: DashboardItem) => number | null> = {
  'MaxDurability': i => i.durability,
  'ItemLevel': i => i.level,
  'MaxStack': i => i.maxStack,
  'Tool.Speed': i => i.speed,
  'Armor.StatModifiers.Health': i => i.health,
  'Armor.StatModifiers.Stamina': i => i.stamina,
  'Armor.DamageResistance.Physical': i => i.defense,
  'Armor.DamageResistance.Fire': i => i.fireRes,
  '@wdmg.All': i => i.damage,
}

/** Comparison operators for the numeric advanced filter. */
export const FILTER_OPERATORS = ['>=', '>', '<=', '<', '='] as const

export interface DashboardStateResult {
  searchText: Ref<string>
  typeFilter: Ref<string>
  modFilter: Ref<string>
  qualityFilter: Ref<string>
  slotFilter: Ref<string>
  statusFilter: Ref<string>
  viewMode: Ref<'table' | 'grid'>
  sortColumn: Ref<string>
  sortAsc: Ref<boolean>

  /** Advanced stat filter state */
  statFilter: Ref<string>
  statOperator: Ref<string>
  statValue: Ref<string>

  /** The full, dynamically-discovered field catalog (drives the field picker). */
  catalog: Ref<CatalogField[]>
  setCatalog: (fields: CatalogField[]) => void
  /** The currently-selected filter field, or null when 'none'. */
  selectedField: ComputedRef<CatalogField | null>
  /** Kind of the selected field — drives the predicate and the value hint. */
  selectedFieldKind: ComputedRef<FieldKind>
  /** Placeholder hint for the value input, adapted to the selected field. */
  valuePlaceholder: ComputedRef<string>
  /** Whether a field filters instantly from the payload (no server value-fetch needed). */
  isInstantField: (fieldId: string) => boolean
  /** Server-fetched values for a non-instant field: fieldId → { itemId: value }. */
  setFieldValues: (fieldId: string, values: Record<string, number | string | boolean>) => void
  hasFieldValues: (fieldId: string) => boolean

  filteredItems: ComputedRef<DashboardItem[]>
  sortedItems: ComputedRef<DashboardItem[]>
  displayItems: ComputedRef<DashboardItem[]>
  hasMore: ComputedRef<boolean>
  footerText: ComputedRef<string>

  /** Context-dependent stat columns (1-3 based on type filter) */
  statColumns: ComputedRef<StatColumnDef[]>

  /** Unique quality values from the item set (for dropdown population) */
  qualityOptions: ComputedRef<string[]>
  /** Unique slot values from the item set (for dropdown population) */
  slotOptions: ComputedRef<string[]>

  /** Whether the advanced filter is actively narrowing results */
  statFilterActive: ComputedRef<boolean>

  toggleSort: (column: string) => void
  clearStatFilter: () => void
  restoreState: (state: { searchText?: string; typeFilter?: string; modFilter?: string; qualityFilter?: string; slotFilter?: string; statusFilter?: string; viewMode?: string; sortColumn?: string; sortAscending?: boolean; statFilter?: string; statOperator?: string; statValue?: string } | null) => void
  captureState: () => string
}

export function useDashboardState(allItems: ComputedRef<DashboardItem[]>): DashboardStateResult {
  const searchText = ref('')
  const typeFilter = ref('All')
  const modFilter = ref('All')
  const qualityFilter = ref('All')
  const slotFilter = ref('All')
  const statusFilter = ref('All')
  const viewMode = ref<'table' | 'grid'>('grid')
  const sortColumn = ref('name')
  const sortAsc = ref(true)

  // Advanced stat filter
  const statFilter = ref('none')
  const statOperator = ref('>=')
  const statValue = ref('')

  // Full server-scanned catalog + on-demand typed value caches for non-instant fields.
  const catalog = ref<CatalogField[]>([])
  const fieldValueMap = ref<Record<string, Record<string, number | string | boolean>>>({})
  function setCatalog(f: CatalogField[]) { catalog.value = f }
  function setFieldValues(fieldId: string, values: Record<string, number | string | boolean>) {
    fieldValueMap.value = { ...fieldValueMap.value, [fieldId]: values }
  }
  function hasFieldValues(fieldId: string): boolean { return !!fieldValueMap.value[fieldId] }
  function isInstantField(fieldId: string): boolean { return fieldId in PAYLOAD_FAST_FIELDS }

  const selectedField = computed<CatalogField | null>(() =>
    catalog.value.find(f => f.id === statFilter.value) ?? null)
  const selectedFieldKind = computed<FieldKind>(() => selectedField.value?.kind ?? 'numeric')

  const valuePlaceholder = computed(() => {
    const f = selectedField.value
    if (!f) return 'Value...'
    switch (f.kind) {
      case 'boolean': return 'true / false'
      case 'enum':    return f.options?.length ? `e.g. ${f.options.slice(0, 3).join(', ')}` : 'Value...'
      case 'text':    return 'contains...'
      default:        return 'Value...'
    }
  })

  /** Resolve a filter selection to a typed value extractor: instant fields read the
   *  payload; everything else reads its server-fetched value map. */
  function statGetValue(key: string): (i: DashboardItem) => number | string | boolean | null {
    const fast = PAYLOAD_FAST_FIELDS[key]
    if (fast) return fast
    return (i: DashboardItem) => fieldValueMap.value[key]?.[i.id] ?? null
  }

  // ── Filtering (client-side, instant) ─────────────────────────────────

  const filteredItems = computed(() => {
    let items = allItems.value
    if (searchText.value) {
      const q = searchText.value.trim()
      if (q) items = items.filter(i => fuzzyMatch(q, i.name) || fuzzyMatch(q, i.id))
    }
    if (typeFilter.value !== 'All')
      items = items.filter(i => i.type === typeFilter.value)
    if (modFilter.value !== 'All')
      items = items.filter(i => i.mod === modFilter.value)
    if (qualityFilter.value !== 'All')
      items = items.filter(i => i.quality === qualityFilter.value)
    if (slotFilter.value !== 'All')
      items = items.filter(i => i.slot === slotFilter.value)
    if (statusFilter.value === 'Modified')
      items = items.filter(i => i.hasOverride)
    if (statusFilter.value === 'Default')
      items = items.filter(i => !i.hasOverride)

    // Advanced stat filter — two modes:
    // 1. Stat selected, no value → show items that HAVE the stat (non-null)
    //    e.g. select "Health" → instantly shows all armor
    // 2. Stat + operator + value → comparison filter
    //    e.g. "Health >= 20" → find overpowered armor
    if (statFilter.value !== 'none') {
      // Instant fields resolve from the payload; everything else from its server-fetched
      // value map (empty until fetched → matches nothing yet). The predicate adapts to
      // the field KIND so numerics filter by range, booleans by true/false, enums by
      // equality, and free text by substring.
      const getValue = statGetValue(statFilter.value)
      const kind = selectedFieldKind.value
      const raw = statValue.value.trim()

      if (kind === 'numeric') {
        const numVal = parseFloat(raw)
        if (!isNaN(numVal)) {
          const op = statOperator.value
          items = items.filter(i => {
            const v = getValue(i)
            if (typeof v !== 'number') return false
            switch (op) {
              case '>':  return v > numVal
              case '<':  return v < numVal
              case '>=': return v >= numVal
              case '<=': return v <= numVal
              case '=':  return v === numVal
              default:   return true
            }
          })
        } else {
          // No value entered — show items that HAVE this field (non-null).
          items = items.filter(i => getValue(i) != null)
        }
      } else if (kind === 'boolean') {
        // Empty value defaults to "is true"; otherwise match the parsed boolean. Only
        // items that actually HAVE the field match — absent (null) is never "false".
        const want = raw === '' ? true : /^(true|1|yes|y|on)$/i.test(raw)
        items = items.filter(i => getValue(i) === want)
      } else {
        // enum / text
        if (raw === '') {
          items = items.filter(i => getValue(i) != null) // has-field
        } else {
          const q = raw.toLowerCase()
          if (kind === 'enum') {
            items = items.filter(i => String(getValue(i) ?? '').toLowerCase() === q)
          } else {
            items = items.filter(i => String(getValue(i) ?? '').toLowerCase().includes(q))
          }
        }
      }
    }

    return items
  })

  // ── Sorting ──────────────────────────────────────────────────────────

  // ── Stat Column Definitions (context-dependent on type filter) ──────

  const COL_DUR:     StatColumnDef = { key: 'dur',     label: 'Dur.',      getValue: i => i.durability }
  const COL_LEVEL:   StatColumnDef = { key: 'level',   label: 'Level',     getValue: i => i.level }
  const COL_HEALTH:  StatColumnDef = { key: 'health',  label: 'Health',    getValue: i => i.health }
  const COL_DEFENSE: StatColumnDef = { key: 'defense', label: 'Def. %',   getValue: i => i.defense }
  const COL_DAMAGE:  StatColumnDef = { key: 'damage',  label: 'Damage',    getValue: i => i.damage }
  const COL_SPEED:   StatColumnDef = { key: 'speed',   label: 'Speed',     getValue: i => i.speed }
  const COL_STAMINA: StatColumnDef = { key: 'stamina', label: 'Stamina',   getValue: i => i.stamina }
  const COL_FIRE:    StatColumnDef = { key: 'fireRes', label: 'Fire %',   getValue: i => i.fireRes }
  const COL_STACK:   StatColumnDef = { key: 'stack',   label: 'Stack',     getValue: i => i.maxStack }

  const statColumns = computed<StatColumnDef[]>(() => {
    switch (typeFilter.value) {
      case 'Armor':  return [COL_HEALTH, COL_DEFENSE, COL_DUR]
      case 'Weapon': return [COL_DAMAGE, COL_DUR]
      case 'Tool':   return [COL_SPEED, COL_DUR]
      default:       return [COL_DUR, COL_LEVEL]
    }
  })

  /** Resolve a sort key to a value extractor for stat columns */
  function getStatSortValue(item: DashboardItem, key: string): number | null {
    switch (key) {
      case 'dur':     return item.durability ?? null
      case 'level':   return item.level ?? null
      case 'health':  return item.health ?? null
      case 'defense': return item.defense ?? null
      case 'damage':  return item.damage ?? null
      case 'speed':   return item.speed ?? null
      case 'stamina': return item.stamina ?? null
      case 'fireRes': return item.fireRes ?? null
      case 'stack':   return item.maxStack ?? null
      default:        return null
    }
  }

  /** Known text sort columns (default ascending) */
  const TEXT_SORT_COLS = new Set(['name', 'type', 'mod'])

  const sortedItems = computed(() => {
    const sorted = [...filteredItems.value]
    const col = sortColumn.value
    sorted.sort((a, b) => {
      let va: any, vb: any
      switch (col) {
        case 'name':   va = a.name; vb = b.name; break
        case 'type':   va = a.type; vb = b.type; break
        case 'mod':    va = a.mod;  vb = b.mod;  break
        case 'status': va = a.hasOverride ? 1 : 0; vb = b.hasOverride ? 1 : 0; break
        default:
          // Dynamic stat columns (dur, level, health, defense, damage, speed)
          va = getStatSortValue(a, col) ?? -Infinity
          vb = getStatSortValue(b, col) ?? -Infinity
          break
      }
      if (typeof va === 'string') {
        // Simple comparison — avoids localeCompare which depends on ICU data
        // availability in Javet's V8. Fast and deterministic in all configs.
        const cmp = va < vb ? -1 : va > vb ? 1 : 0
        return sortAsc.value ? cmp : -cmp
      }
      return sortAsc.value ? va - vb : vb - va
    })
    return sorted
  })

  // ── Display Cap (view-adaptive) ─────────────────────────────────────

  const displayCap = computed(() => viewMode.value === 'grid' ? GRID_NATIVE_CAP : TABLE_CAP)
  const displayItems = computed(() => sortedItems.value.slice(0, displayCap.value))
  const hasMore = computed(() => sortedItems.value.length > displayCap.value)

  const footerText = computed(() => {
    const shown = displayItems.value.length
    const filtered = filteredItems.value.length
    const all = allItems.value.length
    const modified = allItems.value.filter(i => i.hasOverride).length

    // "X modified" is the key ItemForge metric — always leads.
    // "items" for the full set, "results" when filters narrow it.
    let text = `${fmtNum(modified)} modified`

    if (shown < filtered) {
      // Display cap reached — some items hidden
      const label = filtered < all ? 'results' : 'items'
      text += `  -  ${fmtNum(shown)} of ${fmtNum(filtered)} ${label}`
    } else if (filtered < all) {
      // Filters active, all fit under cap
      text += `  -  ${fmtNum(filtered)} results`
    } else {
      // No filters, everything visible
      text += `  -  ${fmtNum(all)} items`
    }

    if (hasMore.value) text += '  -  Narrow your search to see more'
    return text
  })

  // ── Stat Filter Status ─────────────────────────────────────────────

  /** Whether the stat filter is actively narrowing results (for UI indicators) */
  const statFilterActive = computed(() => statFilter.value !== 'none')

  /**
   * Unique quality values derived from the item set.
   * Standard rarities first in progression order, then any extras alphabetically.
   */
  const QUALITY_ORDER = ['Common', 'Uncommon', 'Rare', 'Epic', 'Legendary']
  const qualityOptions = computed(() => {
    const set = new Set<string>()
    for (const item of allItems.value) {
      if (item.quality) set.add(item.quality)
    }
    const ordered = QUALITY_ORDER.filter(q => set.has(q))
    const rest = [...set].filter(q => !QUALITY_ORDER.includes(q)).sort()
    return [...ordered, ...rest]
  })

  /**
   * Unique armor slot values derived from the item set.
   * Ordered head-to-toe, then any mod-added slots alphabetically.
   */
  const SLOT_ORDER = ['Head', 'Chest', 'Hands', 'Legs']
  const slotOptions = computed(() => {
    const set = new Set<string>()
    for (const item of allItems.value) {
      if (item.slot) set.add(item.slot)
    }
    const ordered = SLOT_ORDER.filter(s => set.has(s))
    const rest = [...set].filter(s => !SLOT_ORDER.includes(s)).sort()
    return [...ordered, ...rest]
  })

  // ── Actions ──────────────────────────────────────────────────────────

  function clearStatFilter() {
    statFilter.value = 'none'
    statOperator.value = '>='
    statValue.value = ''
  }

  function toggleSort(column: string) {
    if (sortColumn.value === column) {
      sortAsc.value = !sortAsc.value
    } else {
      sortColumn.value = column
      // Text columns default ascending, numeric columns default descending
      sortAsc.value = TEXT_SORT_COLS.has(column)
    }
  }

  /** Restore state from server-side preserved state (navigate-back). */
  function restoreState(state: { searchText?: string; typeFilter?: string; modFilter?: string; qualityFilter?: string; slotFilter?: string; statusFilter?: string; viewMode?: string; sortColumn?: string; sortAscending?: boolean; statFilter?: string; statOperator?: string; statValue?: string } | null) {
    if (!state) return
    searchText.value = state.searchText || ''
    typeFilter.value = state.typeFilter || 'All'
    modFilter.value = state.modFilter || 'All'
    qualityFilter.value = state.qualityFilter || 'All'
    slotFilter.value = state.slotFilter || 'All'
    statusFilter.value = state.statusFilter || 'All'
    viewMode.value = (state.viewMode === 'grid' ? 'grid' : 'table') as 'table' | 'grid'
    sortColumn.value = state.sortColumn || 'name'
    sortAsc.value = state.sortAscending !== false
    // Restore advanced filter state
    statFilter.value = state.statFilter || 'none'
    statOperator.value = state.statOperator || '>='
    statValue.value = state.statValue || ''
  }

  /** Capture current state as JSON for server-side storage. */
  function captureState(): string {
    return JSON.stringify({
      searchText: searchText.value,
      typeFilter: typeFilter.value,
      modFilter: modFilter.value,
      qualityFilter: qualityFilter.value,
      slotFilter: slotFilter.value,
      statusFilter: statusFilter.value,
      viewMode: viewMode.value,
      sortColumn: sortColumn.value,
      sortAscending: sortAsc.value,
      statFilter: statFilter.value,
      statOperator: statOperator.value,
      statValue: statValue.value,
    })
  }

  return {
    searchText, typeFilter, modFilter, qualityFilter, slotFilter, statusFilter, viewMode,
    sortColumn, sortAsc,
    statFilter, statOperator, statValue,
    filteredItems, sortedItems, displayItems, hasMore, footerText,
    statColumns, qualityOptions, slotOptions, statFilterActive,
    catalog, setCatalog, selectedField, selectedFieldKind, valuePlaceholder, isInstantField,
    setFieldValues, hasFieldValues,
    toggleSort, clearStatFilter, restoreState, captureState,
  }
}

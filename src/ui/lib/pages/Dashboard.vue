<script setup lang="ts">
/**
 * ItemForge Dashboard Page
 *
 * Opens via `/itemforge`. Balance dashboard with grid/table views, filters,
 * sorting, and item click → editor navigation.
 *
 * ## Architecture: Single Page with navigate()
 *
 * Dashboard and Editor are separate Vue components in a single Vuetale page.
 * Navigation uses PlayerUi.navigate() — the Vue app stays alive across
 * component swaps. Dashboard state (filters, sort, view mode) survives
 * naturally in the reactive store.
 *
 * ## Rendering Safety
 *
 * - All dynamic props on native elements are guaranteed non-null
 * - Text props use `|| ' '` fallback
 * - Only the ACTIVE view renders (v-if) — inactive view creates zero elements
 * - No static elements inside v-for (dynamic props prevent hoisting)
 * - No Anchor.Full on dirty elements — use { Horizontal: 0, Vertical: 0 }
 * - No reactive background colors (red X on dirty elements)
 * - All button/label styles are static module-level constants
 * - requestFlush() for instant feedback after bridge calls
 * - TextButton is a LEAF in Vuetale — never nest children inside it
 *
 * ## Element Budget (~400 grid, ~700 table)
 *
 * No pre-warm: only the active view renders. Grid default = ~400 elements
 * (~1.5s mount). Table = ~700 elements (~2.5s mount). View switch triggers
 * structural change (clear+appendInline), acceptable for deliberate action.
 *
 * UX_DESIGN.md §4, ARCHITECTURE.md §7
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useData } from '@core/composables/useData'
import { Common } from '@core/components/Common'
import { Core } from '@core/components/core/index'
import { useDashboardState, FILTER_OPERATORS, GRID_NATIVE_CAP, type StatColumnDef } from '../composables/useDashboardState'
import { useBatchState } from '../composables/useBatchState'
import BatchEditOverlay from '../components/BatchEditOverlay.vue'
import FieldPicker from '../components/FieldPicker.vue'
import type { DashboardPayload, DashboardItem, CatalogField } from '../types/DashboardPayload'

// ── Scrollbar ────────────────────────────────────────────────────────────
const SCROLLBAR = {
  Spacing: 6, Size: 6,
  Background: { TexturePath: 'Common/Scrollbar.png', Border: 3 },
  Handle: { TexturePath: 'Common/ScrollbarHandle.png', Border: 3 },
  HoveredHandle: { TexturePath: 'Common/ScrollbarHandleHovered.png', Border: 3 },
  DraggedHandle: { TexturePath: 'Common/ScrollbarHandleDragged.png', Border: 3 },
}

// ── Label Styles (static, module-level) ────────────────────────────────
const S = {
  footer:      { FontSize: 17, TextColor: '#96a9be', VerticalAlignment: 'Center' },
  empty:       { FontSize: 18, TextColor: '#667788', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' },
  gridName:    { FontSize: 13, TextColor: '#96a9be', HorizontalAlignment: 'Center' },
  gridMod:     { FontSize: 12, TextColor: '#d4a844', HorizontalAlignment: 'Center' },
  rowName:     { FontSize: 17, TextColor: '#dde6ee', VerticalAlignment: 'Center' },
  // Non-name columns are centered to line up under the centered headers (their
  // cell widths already match the header widths). Item name stays left-aligned.
  rowText:     { FontSize: 15, TextColor: '#96a9be', VerticalAlignment: 'Center', HorizontalAlignment: 'Center' },
  rowNum:      { FontSize: 15, TextColor: '#96a9be', VerticalAlignment: 'Center', HorizontalAlignment: 'Center' },
  rowMod:      { FontSize: 15, TextColor: '#d4a844', VerticalAlignment: 'Center', HorizontalAlignment: 'Center' },
  rowDim:      { FontSize: 15, TextColor: '#556677', VerticalAlignment: 'Center', HorizontalAlignment: 'Center' },
  headerText:  { FontSize: 14, TextColor: '#667788', RenderUppercase: true, VerticalAlignment: 'Center', HorizontalAlignment: 'Center' },
  filterLabel: { FontSize: 15, TextColor: '#667788', VerticalAlignment: 'Center' },
  selCount:    { FontSize: 16, TextColor: '#d4a844', VerticalAlignment: 'Center', RenderBold: true },
  toast:       { FontSize: 16, TextColor: '#dde6ee', VerticalAlignment: 'Center' },
  loading:     { FontSize: 22, TextColor: '#dde6ee', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' },
}

// ── Button Styles ──────────────────────────────────────────────────────
const BTN_TAB_ACTIVE = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#d4a844', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#d4a844', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#d4a844', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_TAB = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#96a9be', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#797b7c', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_CLOSE = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 18, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 18, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 18, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 18, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}

// Table row style — transparent overlay button that captures clicks.
// TextButton is a leaf in Vuetale (no children). Labels are siblings, not children.
// Color '#00000001' is safe: static props → never dirty → never re-sent via Set.

// Table header button style — static per state, no spread operators.
const S_HEADER_HOVERED = { FontSize: 14, TextColor: '#96a9be', RenderUppercase: true, VerticalAlignment: 'Center', HorizontalAlignment: 'Center' }
const S_HEADER_PRESSED = { FontSize: 14, TextColor: '#d4a844', RenderUppercase: true, VerticalAlignment: 'Center', HorizontalAlignment: 'Center' }

// Item column header — left-aligned to match item names below
const BTN_HEADER_ITEM = {
  Default: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 14, TextColor: '#667788', RenderUppercase: true, VerticalAlignment: 'Center' } },
  Hovered: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 14, TextColor: '#96a9be', RenderUppercase: true, VerticalAlignment: 'Center' } },
  Pressed: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 14, TextColor: '#d4a844', RenderUppercase: true, VerticalAlignment: 'Center' } },
}
const BTN_HEADER = {
  Default: { Background: { Color: '#00000001' }, LabelStyle: S.headerText },
  Hovered: { Background: { Color: '#00000001' }, LabelStyle: S_HEADER_HOVERED },
  Pressed: { Background: { Color: '#00000001' }, LabelStyle: S_HEADER_PRESSED },
}

// Grid tile click catcher — a TextButton (proven clickable leaf) placed BEHIND the
// ItemSlot/ItemIcon. The icon layers are hit-test-false so the click passes down to
// this TextButton, while the top ItemIcon renders its native item tooltip. The button
// is visually hidden behind the opaque slot (transparent style; hit-testing is by
// geometry, not visual opacity, so it still receives the click).
const GRID_CLICK = {
  Default: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Hovered: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Pressed: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
}

// ── Native ItemGrid style ─────────────────────────────────────────────────
// ROOT CAUSE of the white-bg + red-X tiles (source-verified, not guessed): the slot frame
// is the SlotBackground texture, and the path must resolve from OUR UI root. Our Vuetale
// page roots its UI at `Common/UI/Custom/` (Vuetale ships `Pages/HytaleRoot.ui` there;
// VuetaleUIPage.build appends it), so texture refs resolve from `Common/UI/Custom/Common/`.
// The vanilla `@DefaultItemGridStyle` (client `InGame/Common.ui:14`) uses
// `SlotBackground: "Pages/Inventory/Slot.png"` — a CORRECT path *for the InGame root*, but
// from our root it points to the non-existent `Common/UI/Custom/Pages/Inventory/Slot.png`
// → missing-texture placeholder (white + red-X). The icon rendered fine; only the slot bg
// was broken (exposed on no-quality items; red corner-pixels behind the inset quality bg).
//
// FIX: use the slot texture that DOES live in our root tree — `BlockSelectorSlotBackground`
// (the engine's own ItemGrid slot bg, used by EntitySpawnPage.ui), confirmed present at
// `../Assets/Common/UI/Custom/Common/BlockSelectorSlotBackground@2x.png` alongside our
// working Scrollbar/Dropdown/Container textures. Base name → client auto-resolves the @2x.
// Quality items still draw their colored bg on top; no-quality items now get this frame.
const ITEM_GRID_STYLE = {
  // SlotSpacing widened from 2 → 4 to FILL the full panel width: with a fixed SlotSize the
  // columns don't divide 1260 evenly, so the extra inter-slot spacing closes the remainder
  // (see GRID_SLOTS_PER_ROW). SlotSpacing applies to both axes (row gaps get 4px too).
  SlotSpacing: 4,
  SlotSize: 74,
  SlotIconSize: 64,
  SlotBackground: 'Common/BlockSelectorSlotBackground.png',
}

// Native grid spans the FULL panel width (Anchor{Horizontal:0}) — no margins, no centering.
// Panel inner width = page 1300 − contentPadding(Left 20 + Right 20) = 1260. The grid fills
// all of it and packs the most columns that fit at this slot size, with SlotSpacing(4) tuned
// so the columns span edge-to-edge up to the scrollbar (no dead band):
//   16 × SlotSize(74) + 15 × SlotSpacing(4) = 1244, + ~12px scrollbar gutter ≈ 1256 ≈ 1260.
// 17 columns would overflow even at zero spacing (17 × 74 = 1258 > slot field), so 16 is max.
const GRID_SLOTS_PER_ROW = 16

// ── Dropdown style ──────────────────────────────────────────────────────
const DROPDOWN_STYLE = {
  DefaultBackground: { TexturePath: 'Common/Dropdown.png', Border: 16 },
  HoveredBackground: { TexturePath: 'Common/DropdownHovered.png', Border: 16 },
  PressedBackground: { TexturePath: 'Common/DropdownPressed.png', Border: 16 },
  DefaultArrowTexturePath: 'Common/DropdownCaret.png',
  HoveredArrowTexturePath: 'Common/DropdownCaret.png',
  PressedArrowTexturePath: 'Common/DropdownPressedCaret.png',
  ArrowWidth: 16, ArrowHeight: 22,
  LabelStyle: { TextColor: '#96a9be', RenderUppercase: true, VerticalAlignment: 'Center', FontSize: 15 },
  EntryLabelStyle: { TextColor: '#b7cedd', RenderUppercase: true, VerticalAlignment: 'Center', FontSize: 15 },
  SelectedEntryLabelStyle: { TextColor: '#b7cedd', RenderUppercase: true, VerticalAlignment: 'Center', FontSize: 15, RenderBold: true },
  HorizontalPadding: 8,
  PanelBackground: { TexturePath: 'Common/DropdownBox.png', Border: 16 },
  PanelScrollbarStyle: SCROLLBAR,
  PanelWidth: 200, PanelPadding: 6, PanelAlign: 'Right', PanelOffset: 7,
  EntryHeight: 36, EntriesInViewport: 10, HorizontalEntryPadding: 8,
  HoveredEntryBackground: { Color: '#0a0f17' },
  PressedEntryBackground: { Color: '#0f1621' },
  FocusOutlineSize: 1, FocusOutlineColor: '#ffffff(0.4)',
}

// ── Server Data ──────────────────────────────────────────────────────────

const dashboardJson = useData<string>('dashboard', '{}')
const playerId = useData<string>('playerId', '')
const restoredStateJson = useData<string>('dashboardState', '')
// Field catalog is pushed by the server via setData (VuetaleIntegration.openDashboard /
// navigateBack) — robust delivery, no mount-time JS bridge call.
const fieldCatalogJson = useData<string>('fieldCatalog', '{"fields":[]}')
// Warm status — pushed by openDashboard / drainPendingOpens. When the cache isn't warm yet the
// open shows a loading overlay until the off-tick warm pushes the real payload.
const statusJson = useData<string>('dashboardStatus', '{"warm":false}')

const payload = computed<DashboardPayload | null>(() => {
  try {
    const p = JSON.parse(dashboardJson.value)
    return p?.items ? p : null
  } catch { return null }
})

// Loading overlay: shown until the first real payload arrives (null only in the rare not-warm
// cold-open case; the warm pushes data via drainPendingOpens and this flips false). indexTotal
// feeds the "Indexing N items…" text.
const warming = computed<boolean>(() => payload.value === null)
const indexTotal = computed<number>(() => {
  try { return JSON.parse(statusJson.value)?.total ?? 0 } catch { return 0 }
})

const allItems = computed<DashboardItem[]>(() => payload.value?.items ?? [])
const modOptions = computed<string[]>(() => payload.value?.modOptions ?? [])

/**
 * Type filter options — smart ordering for admin workflow.
 * Equipment first (what admins edit stats on), then consumables, then functional,
 * then materials, then everything else alphabetically. "Other" always last.
 */
const TYPE_ORDER = [
  // Equipment — primary editing targets
  'Weapon', 'Armor', 'Tool', 'Glider',
  // Consumables
  'Food', 'Potion', 'Consumable',
  // Functional items
  'Container', 'Utility', 'Trap',
  // Materials & crafting
  'Ingredient', 'Ore', 'Recipe',
]
const typeOptions = computed<string[]>(() => {
  const raw = payload.value?.typeOptions ?? []
  const prioritySet = new Set(TYPE_ORDER)
  const ordered = TYPE_ORDER.filter(t => raw.includes(t))
  const rest = raw.filter(t => t !== 'Other' && !prioritySet.has(t)).sort()
  const result = [...ordered, ...rest]
  // "Other" always last (items with no Type tag)
  if (raw.includes('Other')) result.push('Other')
  return result
})

// ── Dashboard State (composable) ─────────────────────────────────────────

const dash = useDashboardState(allItems)

// Populate the field-filter catalog from server-pushed data. `immediate` covers the
// initial mount (data is buffered before the page opens); the watch re-runs on
// navigate-back when the catalog is re-pushed.
watch(fieldCatalogJson, (json) => {
  try {
    const resp = JSON.parse(json)
    if (resp.success && Array.isArray(resp.fields)) dash.setCatalog(resp.fields as CatalogField[])
  } catch { /* leave catalog empty until valid data arrives */ }
}, { immediate: true })

// ── Batch Operations ───────────────────────────────────────────
//
// Filter → Select All model (no per-row checkboxes — the Vuetale client has no
// scroll-restore API). "Select All" adds the CURRENT filtered set to the
// selection (accumulates across searches). Batch ops open a modal overlay; the
// overlay drives the synchronous preview bridge and the fire-and-forget apply.

const batch = useBatchState()
const undoable = ref(false)
let undoTimer: ReturnType<typeof setTimeout> | null = null

const toastText = computed(() =>
  undoable.value ? `Batch applied: ${batch.undoMessage.value}` : batch.undoMessage.value
)

function onSelectAll() {
  // Select every FILTERED item (not just the display-capped subset).
  batch.selectAll(dash.filteredItems.value)
  globalThis.itemForgeBridge.requestFlush()
}
function onClearSelection() {
  batch.clearSelection()
  globalThis.itemForgeBridge.requestFlush()
}
function onOpenBatch(kind: 'stat' | 'recipe' | 'reset') {
  if (!batch.hasSelection.value) return
  batch.openOverlay(kind)
  globalThis.itemForgeBridge.requestFlush()
}
function onOverlayClose() {
  batch.closeOverlay()
  globalThis.itemForgeBridge.requestFlush()
  // The overlay v-if toggling off is a structural re-render → re-push grid + filter dropdowns.
  syncHostData()
}
function onOverlayApplied(message: string, canUndo: boolean) {
  batch.closeOverlay()
  undoable.value = canUndo
  batch.showUndo(message)
  if (undoTimer) clearTimeout(undoTimer)
  // Auto-dismiss: 10s when an Undo is offered, 3s for a non-undoable confirmation.
  undoTimer = setTimeout(() => { batch.hideUndo(); globalThis.itemForgeBridge.requestFlush(); syncHostData() }, canUndo ? 10000 : 3000)
  // The batch is done — clear the selection so the next op starts fresh.
  batch.clearSelection()
  globalThis.itemForgeBridge.requestFlush()
  // Overlay close + toast show are both structural re-renders → re-push grid + filter dropdowns.
  syncHostData()
}
function onUndo() {
  globalThis.dashboardBridge.batchUndo(playerId.value)
  if (undoTimer) clearTimeout(undoTimer)
  batch.hideUndo()
  globalThis.itemForgeBridge.requestFlush()
  // Toast removal is a structural re-render → re-push grid + filter dropdowns.
  syncHostData()
}

// ── Mount ──────────────────────────────────────────────────────────────
//
// No pre-warm: only the ACTIVE view renders via v-if. Grid default creates
// ~400 elements (~1.5s mount). Table creates ~700 elements (~2.5s mount).
// View switch triggers structural change — acceptable for deliberate action.

onMounted(() => {
  if (restoredStateJson.value) {
    try {
      const state = JSON.parse(restoredStateJson.value)
      dash.restoreState(state)
    } catch { /* ignore */ }
  }
  // (Field catalog arrives via setData + the watch above — no bridge call here.)
  globalThis.itemForgeBridge.requestFlush()
  // Initial population of the native grid (default view). The element is in the render
  // tree by the time onMounted fires; the server push is delayed so it lands after mount.
  syncGrid()
})

// Re-push ALL host-driven data whenever the filtered/sorted set changes. sortedItems is a
// fresh array on every recompute (search, type/mod/quality/slot/status filters, advanced
// stat filter, sort toggle), so this single watch covers all browsing actions.
//
// Must be syncHostData (grid + data-driven dropdowns), NOT just syncGrid: a set recompute
// triggers a structural re-render, and on heavily-modded servers that change is large enough
// to fall back from a TARGETED structural update to a full `clear("#App")` rebuild — which
// wipes EVERY host-pushed `.Entries` (the Mod filter + the Field Filter FieldPicker), leaving
// them empty with no re-push. The grid already rode this watch (so it never emptied); the
// dropdowns previously only re-pushed on view-switch/overlay/toast via the same token, so a
// filter/sort/search that rebuilt the tree blanked them. Bumping the token here re-pushes
// them too. All pushes are server-debounced and land AFTER the rebuild, so entries are
// restored. Non-grid view → syncGrid early-returns; the dropdown re-push still runs.
watch(() => dash.sortedItems.value, () => syncHostData())

// ── Search Debounce ──────────────────────────────────────────────────────

let searchTimeout: ReturnType<typeof setTimeout> | null = null

function onSearchInput(value: string) {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    dash.searchText.value = value
    globalThis.itemForgeBridge.requestFlush()
  }, 300)
}

// ── Stat Columns ─────────────────────────────────────────────────────────

function statCol(index: number): StatColumnDef | null {
  return dash.statColumns.value[index] ?? null
}

// ── Helpers ──────────────────────────────────────────────────────────────

function sortLabel(column: string, label: string): string {
  if (dash.sortColumn.value !== column) return label
  return label + (dash.sortAsc.value ? ' ▲' : ' ▼')
}

function formatStat(val: number | null | undefined): string {
  if (val == null) return '-'
  return Number.isInteger(val) ? String(val) : val.toFixed(1)
}

/** Format override status: "Modified (3)" or "—" (table Status column). */
function overrideStatus(item: DashboardItem): string {
  if (!item.hasOverride) return '-'
  return item.overrideCount > 0 ? `Modified (${item.overrideCount})` : 'Modified'
}

// ── Navigation Actions ──────────────────────────────────────────────────

function onItemClick(itemId: string) {
  const stateJson = dash.captureState()
  globalThis.dashboardBridge.navigateToEditor(playerId.value, itemId, stateJson)
}

// ── Native ItemGrid — host-populated Slots + click mapping ───────────────
// The grid view is a single static <ItemGrid id="ifgrid">. Vue NEVER binds :slots —
// Vuetale serializes the whole array into markup on the V8 thread at mount (hangs at
// ~5028 items) AND its RefArray serializer emits invalid markup (client document-parse
// crash even at 100 slots) — both confirmed dead, see the investigation doc. Instead
// Kotlin pushes native ItemGridSlots via dashboardBridge.refreshGrid →
// VuetaleUIPage.pushItemGridSlots, off the mount thread, with the client virtualizing
// rendering. We send the ids in display order and remember that exact ordering so a
// clicked SlotIndex maps straight back to the item the client is showing.

// GRID_NATIVE_CAP is imported from useDashboardState — ONE source of truth shared with the
// footer's "shown" count, so what we push and what the footer reports can never diverge. It
// must stay in lockstep with Kotlin DashboardBridge.NATIVE_GRID_CAP (which re-caps
// defensively). Search/filters reach items beyond the cap; the footer prompts it.

// The exact ordered id list last pushed to the grid (index ⇒ item id, for click mapping).
let lastGridIds: string[] = []

/**
 * Push the current filtered/sorted set into the native grid (grid view only). The server
 * push is debounced + delayed so it coalesces filter bursts and lands AFTER any structural
 * re-render (clear+appendInline wipes injected slots). Call this after every action that
 * changes the set OR could trigger a structural re-render.
 */
function syncGrid() {
  if (dash.viewMode.value !== 'grid') return
  const ids = dash.sortedItems.value.slice(0, GRID_NATIVE_CAP).map(i => i.id)
  lastGridIds = ids
  globalThis.dashboardBridge.refreshGrid(playerId.value, JSON.stringify(ids))
}

// ── Data-driven filter dropdowns (perf) ─────────────────────────────────────
// The field-filter (FieldPicker) and the mod filter push their option lists to static native
// <DropdownBox>es as DATA instead of one <DropdownEntry> per option. Both self-push on mount and
// when their option set changes; this token forces a re-push after a structural re-render
// (overlay close / toast / view switch) that can wipe host-pushed data — mirrors the grid re-push.
const dashPickerRepushToken = ref(0)

/** Re-push the native grid AND the data-driven filter dropdowns after a structural re-render. */
function syncHostData() {
  syncGrid()
  dashPickerRepushToken.value++
}

/** Mod filter entries: "All Mods" + every mod, pushed as data (avoids one element per mod). */
const modFilterEntriesJson = computed(() =>
  JSON.stringify([{ label: 'All Mods', value: 'All' }, ...modOptions.value.map(m => ({ label: m, value: m }))]))
function pushModFilter() {
  globalThis.itemForgeBridge.refreshPickerEntries(playerId.value, 'pk_modfilter', modFilterEntriesJson.value)
}
onMounted(() => { nextTick(() => pushModFilter()) })
watch(modFilterEntriesJson, () => pushModFilter())
watch(dashPickerRepushToken, () => pushModFilter())

// SlotClicking handler. Our Vuetale patch (VuetaleEventData override) surfaces the clicked
// slot index through the event value as a string; map it back through the pushed id list.
function onSlotClick(slotIndex: string) {
  const id = lastGridIds[Number(slotIndex)]
  if (id) onItemClick(id)
}

function onClose() {
  globalThis.dashboardBridge.closeDashboard(playerId.value)
}

function onSortClick(column: string) {
  dash.toggleSort(column)
  globalThis.itemForgeBridge.requestFlush()
}

function onViewSwitch(mode: 'table' | 'grid') {
  dash.viewMode.value = mode
  globalThis.itemForgeBridge.requestFlush()
  // Switching INTO grid (re)creates the <ItemGrid> element with a fresh id and no slots —
  // sortedItems is unchanged so the watch won't fire; push explicitly. The view toggle is a
  // structural re-render, so re-push the filter dropdowns too.
  syncHostData()
}

function onFilterChange() {
  globalThis.itemForgeBridge.requestFlush()
}

// ── Advanced Filter Handlers ──────────────────────────────────────────

let statValueTimeout: ReturnType<typeof setTimeout> | null = null

function onStatValueInput(value: string) {
  if (statValueTimeout) clearTimeout(statValueTimeout)
  statValueTimeout = setTimeout(() => {
    dash.statValue.value = value
    globalThis.itemForgeBridge.requestFlush()
  }, 200)
}

function onClearStatFilter() {
  dash.clearStatFilter()
  globalThis.itemForgeBridge.requestFlush()
}

// ── Comprehensive (server-scanned) field filter ───────────────────────
function onStatFieldChange(v: string) {
  dash.statFilter.value = v
  // Non-instant fields (anything not pre-extracted into the payload) need a one-time
  // server scan of their values; after that, filtering runs instantly from the cache.
  if (v !== 'none' && !dash.isInstantField(v) && !dash.hasFieldValues(v)) {
    try {
      const resp = JSON.parse(globalThis.dashboardBridge.fieldValues(v))
      if (resp.success && resp.values) dash.setFieldValues(v, resp.values)
    } catch { /* leave empty → filter matches nothing until retried */ }
  }
  onFilterChange()
}
</script>

<template>
  <Group :anchor="{ Full: 1 }">
    <Common.DecoratedContainer
      :anchor="{ Width: 1300, Height: 900 }"
      :content-padding="{ Top: 8, Left: 20, Right: 20, Bottom: 16 }"
    >
      <!-- ── Title Bar ── -->
      <template #title>
        <Common.Title text="ItemForge" />
      </template>

      <!-- ── Content ── -->
      <template #content>

        <!-- ── Toolbar: Search + Filters + View Toggle ── -->
        <Group
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 50 }"
          :padding="{ Bottom: 6 }"
        >
          <!-- Search -->
          <Core.TextField
            :model-value="dash.searchText.value"
            placeholder-text="Search items..."
            :anchor="{ Width: 250, Height: 38 }"
            @update:model-value="onSearchInput"
          />
          <Group :anchor="{ Width: 8 }" />

          <!-- Type filter -->
          <DropdownBox
            :value="dash.typeFilter.value"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 150, Height: 38 }"
            @value-changed="(v: string) => { dash.typeFilter.value = v; onFilterChange() }"
          >
            <DropdownEntry value="All" text="All Types" />
            <DropdownEntry
              v-for="t in typeOptions"
              :key="t"
              :value="t"
              :text="t"
            />
          </DropdownBox>
          <Group :anchor="{ Width: 6 }" />

          <!-- Mod filter — child-less: entries host-pushed as data (pushModFilter), not one per mod. -->
          <DropdownBox
            id="pk_modfilter"
            :value="dash.modFilter.value"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 150, Height: 38 }"
            @value-changed="(v: string) => { dash.modFilter.value = v; onFilterChange() }"
          />
          <Group :anchor="{ Width: 6 }" />

          <!-- Quality filter -->
          <DropdownBox
            :value="dash.qualityFilter.value"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 130, Height: 38 }"
            @value-changed="(v: string) => { dash.qualityFilter.value = v; onFilterChange() }"
          >
            <DropdownEntry value="All" text="All Quality" />
            <DropdownEntry
              v-for="q in dash.qualityOptions.value"
              :key="q"
              :value="q"
              :text="q"
            />
          </DropdownBox>
          <Group :anchor="{ Width: 6 }" />

          <!-- Slot filter -->
          <DropdownBox
            :value="dash.slotFilter.value"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 120, Height: 38 }"
            @value-changed="(v: string) => { dash.slotFilter.value = v; onFilterChange() }"
          >
            <DropdownEntry value="All" text="All Slots" />
            <DropdownEntry
              v-for="s in dash.slotOptions.value"
              :key="s"
              :value="s"
              :text="s"
            />
          </DropdownBox>
          <Group :anchor="{ Width: 6 }" />

          <!-- Status filter -->
          <DropdownBox
            :value="dash.statusFilter.value"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 130, Height: 38 }"
            @value-changed="(v: string) => { dash.statusFilter.value = v; onFilterChange() }"
          >
            <DropdownEntry value="All" text="All" />
            <DropdownEntry value="Modified" text="Modified" />
            <DropdownEntry value="Default" text="Default" />
          </DropdownBox>

          <Group :flex-weight="1" />

          <!-- View toggle -->
          <TextButton
            text="Table"
            :el-style="dash.viewMode.value === 'table' ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Width: 86, Height: 38 }"
            @activating="onViewSwitch('table')"
          />
          <Group :anchor="{ Width: 6 }" />
          <TextButton
            text="Grid"
            :el-style="dash.viewMode.value === 'grid' ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Width: 86, Height: 38 }"
            @activating="onViewSwitch('grid')"
          />
        </Group>

        <!-- ── Stat Filter Row (always visible) ── -->
        <!-- Permanent row — no v-if, no :visible. Both cause client crashes:
             - :visible + Anchor.Height → dirty Set for Anchor.Height → crash
             - v-if → structural change → full clear+appendInline can race with dirty Sets
             Always-visible = created once at mount, never dirty, zero crash risk.
             Costs 42px vertical space but is always discoverable (UX §8.2). -->
        <Group
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 42 }"
          :padding="{ Top: 2, Bottom: 4, Left: 6, Right: 6 }"
        >
          <Label text="Field Filter" :el-style="S.filterLabel" :anchor="{ Width: 80, Height: 36 }" />
          <FieldPicker
            :model-value="dash.statFilter.value"
            :fields="dash.catalog.value"
            picker-id="pk_dashfilter"
            :player-id="playerId"
            :repush="dashPickerRepushToken"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 230, Height: 36 }"
            :include-none="true"
            @update:model-value="onStatFieldChange"
          />
          <Group :anchor="{ Width: 8 }" />

          <DropdownBox
            :value="dash.statOperator.value"
            :el-style="DROPDOWN_STYLE"
            :anchor="{ Width: 80, Height: 36 }"
            @value-changed="(v: string) => { dash.statOperator.value = v; onFilterChange() }"
          >
            <DropdownEntry
              v-for="op in FILTER_OPERATORS"
              :key="op"
              :value="op"
              :text="op"
            />
          </DropdownBox>
          <Group :anchor="{ Width: 8 }" />

          <Core.TextField
            :model-value="dash.statValue.value"
            :placeholder-text="dash.valuePlaceholder.value"
            :anchor="{ Width: 130, Height: 36 }"
            @update:model-value="onStatValueInput"
          />
          <Group :anchor="{ Width: 12 }" />

          <TextButton
            text="Clear"
            :el-style="BTN_TAB"
            :anchor="{ Width: 80, Height: 36 }"
            @activating="onClearStatFilter"
          />
        </Group>

        <!-- ── Batch Action Bar (always rendered — no :visible/v-if crash risk) ── -->
        <!-- Filter → Select All model. Count + action buttons use dynamic text
             (prevents Vue hoisting) and reactive texture el-styles (view-toggle
             pattern, proven safe outside TopScrolling). -->
        <Group
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 44 }"
          :padding="{ Top: 2, Bottom: 4, Left: 6, Right: 6 }"
        >
          <TextButton
            :text="`Select All (${dash.filteredItems.value.length})`"
            :el-style="BTN_TAB"
            :anchor="{ Width: 180, Height: 36 }"
            @activating="onSelectAll"
          />
          <Group :anchor="{ Width: 8 }" />
          <Label :text="`Selected: ${batch.selectedCount.value}`" :el-style="S.selCount" :anchor="{ Width: 140, Height: 36 }" />
          <Group :flex-weight="1" />
          <TextButton
            :text="`Batch Stats (${batch.selectedCount.value})`"
            :el-style="batch.hasSelection.value ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Width: 180, Height: 36 }"
            @activating="onOpenBatch('stat')"
          />
          <Group :anchor="{ Width: 6 }" />
          <TextButton
            :text="`Batch Recipes (${batch.selectedCount.value})`"
            :el-style="batch.hasSelection.value ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Width: 200, Height: 36 }"
            @activating="onOpenBatch('recipe')"
          />
          <Group :anchor="{ Width: 6 }" />
          <TextButton
            :text="`Reset (${batch.selectedCount.value})`"
            :el-style="batch.hasSelection.value ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Width: 130, Height: 36 }"
            @activating="onOpenBatch('reset')"
          />
          <Group :anchor="{ Width: 6 }" />
          <TextButton
            text="Clear"
            :el-style="BTN_TAB"
            :anchor="{ Width: 80, Height: 36 }"
            @activating="onClearSelection"
          />
        </Group>

        <!-- ── Table Header (outside TopScrolling, always visible) ── -->
        <Group
          :visible="dash.viewMode.value === 'table'"
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 34 }"
          :padding="{ Left: 6, Right: 6 }"
        >
          <TextButton :text="sortLabel('name', 'Item')" :el-style="BTN_HEADER_ITEM"
            :flex-weight="1" :anchor="{ Height: 30 }" @activating="onSortClick('name')" />
          <TextButton :text="sortLabel('type', 'Type')" :el-style="BTN_HEADER"
            :anchor="{ Width: 110, Height: 30 }" @activating="onSortClick('type')" />
          <TextButton :text="sortLabel('mod', 'Mod')" :el-style="BTN_HEADER"
            :anchor="{ Width: 140, Height: 30 }" @activating="onSortClick('mod')" />
          <TextButton :text="statCol(0) ? sortLabel(statCol(0)!.key, statCol(0)!.label) : ' '"
            :el-style="BTN_HEADER" :anchor="{ Width: 80, Height: 30 }"
            @activating="statCol(0) && onSortClick(statCol(0)!.key)" />
          <TextButton :text="statCol(1) ? sortLabel(statCol(1)!.key, statCol(1)!.label) : ' '"
            :el-style="BTN_HEADER" :anchor="{ Width: 80, Height: 30 }"
            @activating="statCol(1) && onSortClick(statCol(1)!.key)" />
          <TextButton :text="statCol(2) ? sortLabel(statCol(2)!.key, statCol(2)!.label) : ' '"
            :el-style="BTN_HEADER" :anchor="{ Width: 80, Height: 30 }"
            @activating="statCol(2) && onSortClick(statCol(2)!.key)" />
          <TextButton :text="sortLabel('status', 'Status')" :el-style="BTN_HEADER"
            :anchor="{ Width: 100, Height: 30 }" @activating="onSortClick('status')" />
        </Group>

        <!-- ── Separator ── -->
        <Common.ContentSeparator :anchor="{ Horizontal: 0, Height: 1 }" />

        <!-- ── Grid View — native Creative-style ItemGrid (host-populated Slots) ──
             A single STATIC <ItemGrid id="ifgrid">. Vue never binds :slots (Vuetale
             serializes the whole array into markup at mount → hangs at ~5028 items, and its
             RefArray serializer emits invalid markup → client crash even at 100 slots — both
             confirmed dead, see docs/DASHBOARD_TOOLTIP_AND_ITEMGRID_INVESTIGATION.md).
             Instead Kotlin pushes native ItemGridSlots via dashboardBridge.refreshGrid →
             VuetaleUIPage.pushItemGridSlots, off the mount thread, with the client
             virtualizing rendering. Native per-item hover tooltips (InfoDisplay=Tooltip),
             rarity backgrounds, slot clicks, and its own virtualized scroll — like the
             Creative tab. No reactive children ⇒ browsing never structurally re-renders it
             (which would wipe injected slots); deliberate structural events re-push. -->
        <ItemGrid
          v-if="dash.viewMode.value === 'grid'"
          id="ifgrid"
          info-display="Tooltip"
          :render-item-quality-background="true"
          :slots-per-row="GRID_SLOTS_PER_ROW"
          :el-style="ITEM_GRID_STYLE"
          :show-scrollbar="true"
          :scrollbar-style="SCROLLBAR"
          :are-items-draggable="false"
          :flex-weight="1"
          :anchor="{ Horizontal: 0 }"
          @slot-clicking="onSlotClick"
        />

        <!-- ── Table View — scrollable detailed rows (stat columns, sorting) ── -->
        <Group
          v-if="dash.viewMode.value === 'table'"
          layout-mode="TopScrolling"
          :flex-weight="1"
          :anchor="{ Horizontal: 0 }"
          :scrollbar-style="SCROLLBAR"
        >
          <Group layout-mode="Top" :anchor="{ Horizontal: 0 }">
            <template v-for="item in dash.displayItems.value" :key="item.id">
              <!-- Row: ItemSlot + ItemIcon + Labels, then a transparent TextButton ON TOP
                   as the click catcher. The TextButton must be topmost (a hit-test-false
                   layer absorbs clicks rather than passing them to anything behind), and
                   transparent so the row text shows through. ItemIcon renders its real item
                   tooltip by geometry (cursor over its bounds), independent of what's on top. -->
              <Group :anchor="{ Horizontal: 0, Height: 40 }">
                <ItemSlot
                  :item-id="item.id"
                  :show-quality-background="true"
                  :show-quantity="false"
                  :anchor="{ Left: 6, Width: 36, Height: 36, Top: 2 }"
                />
                <ItemIcon
                  :item-id="item.id"
                  :hit-test-visible="false"
                  :show-item-tooltip="true"
                  :text-tooltip-show-delay="0"
                  :anchor="{ Left: 6, Width: 36, Height: 36, Top: 2 }"
                />
                <Label :hit-test-visible="false" :text="item.name || ' '" :el-style="S.rowName"
                  :anchor="{ Left: 48, Right: 596, Height: 40 }" />
                <Label :hit-test-visible="false" :text="item.type || ' '" :el-style="S.rowText"
                  :anchor="{ Right: 486, Width: 110, Height: 40 }" />
                <Label :hit-test-visible="false" :text="item.mod || ' '" :el-style="S.rowText"
                  :anchor="{ Right: 346, Width: 140, Height: 40 }" />
                <Label :hit-test-visible="false" :text="statCol(0) ? formatStat(statCol(0)!.getValue(item)) : ' '"
                  :el-style="S.rowNum" :anchor="{ Right: 266, Width: 80, Height: 40 }" />
                <Label :hit-test-visible="false" :text="statCol(1) ? formatStat(statCol(1)!.getValue(item)) : ' '"
                  :el-style="S.rowNum" :anchor="{ Right: 186, Width: 80, Height: 40 }" />
                <Label :hit-test-visible="false" :text="statCol(2) ? formatStat(statCol(2)!.getValue(item)) : ' '"
                  :el-style="S.rowNum" :anchor="{ Right: 106, Width: 80, Height: 40 }" />
                <Label :hit-test-visible="false" :text="overrideStatus(item)"
                  :el-style="item.hasOverride ? S.rowMod : S.rowDim"
                  :anchor="{ Right: 6, Width: 100, Height: 40 }" />
                <!-- Click target: row body only (Left:48 → right edge), NOT the icon at
                     Left:6-42. Keeping it off the icon lets the icon's tooltip show on hover
                     while the rest of the row opens the editor. -->
                <TextButton
                  text=" "
                  :el-style="GRID_CLICK"
                  :anchor="{ Left: 48, Right: 0, Vertical: 0 }"
                  @activating="onItemClick(item.id)"
                />
              </Group>
              <!-- Row divider — !!item.id references loop variable, prevents hoisting -->
              <Group :visible="!!item.id" :anchor="{ Horizontal: 0, Height: 1 }"
                :background="{ Color: '#1a2030' }" />
            </template>
          </Group>

          <!-- Empty state (table) -->
          <Label
            text="No items match your filters."
            :visible="dash.displayItems.value.length === 0 && !!payload"
            :el-style="S.empty"
            :anchor="{ Horizontal: 0, Height: 50 }"
          />
        </Group>

        <!-- ── Separator ── -->
        <Common.ContentSeparator :anchor="{ Horizontal: 0, Height: 1 }" />

        <!-- ── Footer ── -->
        <Group
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 48 }"
          :padding="{ Top: 6 }"
        >
          <Label
            :text="dash.footerText.value || ' '"
            :el-style="S.footer"
            :anchor="{ Height: 40 }"
          />
          <Group :flex-weight="1" />
          <TextButton
            text="Close"
            :el-style="BTN_CLOSE"
            :anchor="{ Width: 120, Height: 40 }"
            @activating="onClose"
          />
        </Group>
      </template>
    </Common.DecoratedContainer>

    <!-- ── Batch Edit Modal (structural v-if — safe for deliberate show/hide) ── -->
    <BatchEditOverlay
      v-if="batch.overlayOpen.value"
      :kind="batch.batchKind.value"
      :selected-ids="batch.selectedArray.value"
      :player-id="playerId.value"
      @close="onOverlayClose"
      @applied="onOverlayApplied"
    />

    <!-- ── Undo Toast (page-level, OUTSIDE TopScrolling; v-if auto-dismissed by JS timer) ── -->
    <Group
      v-if="batch.undoVisible.value"
      :anchor="{ Width: 540, Height: 58, Bottom: 72 }"
      :background="{ TexturePath: 'Common/ContainerFullPatch.png', Border: 20 }"
    >
      <Label :text="toastText || ' '" :el-style="S.toast" :anchor="{ Left: 22, Right: 150, Vertical: 0 }" />
      <TextButton
        v-if="undoable"
        text="Undo"
        :el-style="BTN_TAB_ACTIVE"
        :anchor="{ Right: 16, Width: 110, Height: 38 }"
        @activating="onUndo"
      />
    </Group>

    <!-- ── Loading overlay — root-level sibling, structural v-if (like the batch overlay):
         shown only until the off-tick warm pushes the payload. Never wraps the table/grid, so the
         heavy content still does its normal single-pass first render when data arrives. ── -->
    <Group
      v-if="warming"
      :anchor="{ Full: 1 }"
      :background="{ Color: '#0a0f17e6' }"
    >
      <Label
        :text="indexTotal > 0 ? `Indexing ${indexTotal} items...` : 'Indexing items...'"
        :el-style="S.loading"
        :anchor="{ Horizontal: 0, Vertical: 0 }"
      />
    </Group>
  </Group>
</template>

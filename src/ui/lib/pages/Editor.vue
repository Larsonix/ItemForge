<script setup lang="ts">
/**
 * ItemForge Editor Page
 *
 * Opens via `/itemforge edit <id>`. Supports up to four tabs:
 * - **Properties** — Component stats (weapon, tool, glider, utility, container)
 *   + equipment fields (ArmorSlot). Only shown if the item has these components.
 * - **Defense** — Armor defense stats (StatModifiers, DamageResistance, etc.)
 *   Only shown if the item has an Armor component with defense fields.
 * - **Damage** — Weapon InteractionVars (attack types, BaseDamage, Class, Variance).
 *   Only shown if the item has InteractionVars.
 * - **General** — Base item fields (durability, quality, level, stack size, etc.)
 *   Always shown.
 *
 * Tab bar appears when 2+ tabs exist. Items with only General (ingredients, misc)
 * show the content directly with no tab bar (UX_DESIGN.md §5.3).
 *
 * Layout follows Vuetale's TestPage.vue.js canonical pattern:
 * - Root Group with Full anchor
 * - DecoratedContainer with fixed dimensions + close button
 * - Content slot with Top layout, fields in TopScrolling area
 *
 * Data flow:
 * - Server pushes EditorPayload via setData("editor", json) before openPage
 * - useData("editor") provides reactive ComputedRef
 * - Bridge methods return JSON — applied via localOverride (no setData from bridge)
 *
 * ## Rendering Safety (audited 2026-05-26)
 *
 * Every dynamic prop on every native element is guaranteed non-null:
 * - Text props use `|| ' '` fallback (space string)
 * - Boolean props are always `!!expr` or boolean expressions
 * - Tooltip uses `v-bind` conditional spread (no undefined passthrough)
 * - No nullable values passed directly to native Hytale elements
 * - ALL fields from ALL tabs rendered once — tab switch toggles :visible only
 * - No v-if on elements — :visible toggle avoids structural changes
 * - No static elements inside v-for (Vue hoists → shared VNode → structural change)
 * - Button styles are static module-level objects (no per-render allocation)
 *
 * Any null/undefined prop triggers hasStructuralChanges in VueBridge.patchProp
 * → full clear+appendInline → all routing keys regenerated → stale keys → freeze.
 * NEVER pass nullable values directly to native Hytale elements.
 */
import { computed, ref, watch, onMounted, nextTick } from 'vue'
import { useData } from '@core/composables/useData'
import { Common } from '@core/components/Common'
import { Core } from '@core/components/core/index'
import FieldEditor from '../components/FieldEditor.vue'
import FieldPicker from '../components/FieldPicker.vue'
import ExtensionPanel from '../components/ExtensionPanel.vue'
import { useEditorState } from '../composables/useEditorState'
import type { EditorPayload, SaveResponse, EditorComponent } from '../types/EditorPayload'
import type { FieldDef } from '../types/FieldDefinition'
import type { CatalogField } from '../types/DashboardPayload'
import type { RecipeData, RecipeChanges, MaterialSearchResult, LocalInput, LocalBench } from '../types/RecipeData'

// ── Scrollbar ────────────────────────────────────────────────────────────
// Matches Vars.DefaultScrollbarStyle() from Common.js:46
const SCROLLBAR = {
  Spacing: 6, Size: 6,
  Background: { TexturePath: 'Common/Scrollbar.png', Border: 3 },
  Handle: { TexturePath: 'Common/ScrollbarHandle.png', Border: 3 },
  HoveredHandle: { TexturePath: 'Common/ScrollbarHandleHovered.png', Border: 3 },
  DraggedHandle: { TexturePath: 'Common/ScrollbarHandleDragged.png', Border: 3 },
}

// ── Label Styles ─────────────────────────────────────────────────────────
const S = {
  headerId:       { FontSize: 14, TextColor: '#667788' },
  headerMeta:     { FontSize: 15, TextColor: '#dde6ee' },
  headerModified: { FontSize: 15, TextColor: '#d4a844' },
  section:        { FontSize: 14, TextColor: '#d4a844' },
  status:         { FontSize: 13, TextColor: '#96a9be' },
  empty:          { FontSize: 14, TextColor: '#667788' },
  // View-only banner for tabs the player lacks permission to edit.
  // Muted amber — informative, not alarming. Static :visible (no scroll-reset risk).
  locked:         { FontSize: 13, TextColor: '#c8956c', VerticalAlignment: 'Center' },
  divider:        '#2b3542',
}

// ── Button Styles (static, avoids Common wrapper re-render issue) ────────
const BTN_PRIMARY = {
  Default: { Background: { TexturePath: 'Common/Buttons/Primary.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 17, TextColor: '#bfcdd5', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Primary_Hovered.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 17, TextColor: '#bfcdd5', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Primary_Pressed.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 17, TextColor: '#bfcdd5', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 17, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_SECONDARY = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}

// Reset button shown to a player without canReset. Every state uses
// the disabled texture/colour so it reads as inactive; clicking it surfaces the
// Permission Denied overlay (TextButton can't take :disabled — unsupported prop).
const RESET_LABEL = { FontSize: 17, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' }
const BTN_DISABLED_LOOK = {
  Default:  { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: RESET_LABEL },
  Hovered:  { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: RESET_LABEL },
  Pressed:  { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: RESET_LABEL },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: RESET_LABEL },
}

// Tab buttons — static styles. Active: gold text (matches section headers), bold.
// Inactive: subdued text, not bold. Both use secondary background (subtle).
const BTN_TAB_ACTIVE = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#d4a844', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#d4a844', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#d4a844', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_TAB = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#96a9be', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#797b7c', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}

// ── Overlay Styles & Anchors ──────────────────────────────────────────
// ALL objects in the overlay MUST be static module-level constants.
// When showUnsavedOverlay changes, Vue re-renders → inline objects like
// { Full: 1 } create new references → VueBridge sends prop updates →
// Hytale client crashes: "An element of type 'Number' cannot be converted
// to a 'System.Int32'" for Anchor.Full. Static refs = same object each
// render = VueBridge sees no change = no update sent = no crash.
const OVERLAY_BLOCKER = {
  Default: { Background: { Color: '#00000073' }, LabelStyle: { FontSize: 1, TextColor: '#00000001', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { Color: '#00000073' }, LabelStyle: { FontSize: 1, TextColor: '#00000001', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { Color: '#00000078' }, LabelStyle: { FontSize: 1, TextColor: '#00000001', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const OVERLAY_TITLE = { FontSize: 16, TextColor: '#d4a844', RenderBold: true, VerticalAlignment: 'Center' }
const OVERLAY_MSG = { FontSize: 14, TextColor: '#96a9be', VerticalAlignment: 'Center' }
// OV_FILL replaces { Full: 1 } for elements that toggle :visible.
// Vuetale sends ALL properties of dirty elements. Hytale's client crashes on
// Set Anchor.Full because it expects Int32 but receives JavaScript Number (float64).
// { Horizontal: 0, Vertical: 0 } fills both axes with float-safe properties.
const OV_FILL = { Horizontal: 0, Vertical: 0 }
// No dim backdrop — background color strings (both #RRGGBBAA and #RRGGBB(alpha))
// render as red X when re-sent via Set command on dirty elements. The dialog's
// ContainerPatch.png background is visually distinct enough without dimming.
const OV_DIALOG = { Width: 470, Height: 150 }
const OV_DIALOG_BG = { TexturePath: 'Common/ContainerFullPatch.png', Border: 20 }
const OV_DIALOG_PAD = { Left: 24, Right: 24, Top: 24, Bottom: 12 }
const OV_TITLE_A = { Horizontal: 0, Height: 22 }
const OV_SPACER_SM = { Height: 8 }
const OV_MSG_A = { Horizontal: 0, Height: 18 }
const OV_SPACER_LG = { Height: 12 }
const OV_ROW = { Horizontal: 0, Height: 36 }
const OV_SAVE_BTN = { Width: 140, Height: 36 }
const OV_GAP = { Width: 10 }
const OV_DISCARD_BTN = { Width: 110, Height: 36 }
const OV_KEEP_BTN = { Width: 145, Height: 36 }

// ── Server Data ──────────────────────────────────────────────────────────

const editorJson = useData<string>('editor', '{}')
const playerId = useData<string>('playerId', '')

/** Parse the JSON pushed by setData into a typed payload */
const serverPayload = computed<EditorPayload | null>(() => {
  try {
    const p = JSON.parse(editorJson.value)
    return p?.itemId ? p : null
  } catch { return null }
})

/**
 * Local override — set when bridge methods return updated payload.
 * Bridge cannot call setData (deadlock), so we apply the result locally.
 * Cleared when server pushes new data via setData.
 */
const localOverride = ref<EditorPayload | null>(null)
watch(editorJson, () => {
  localOverride.value = null
  // Server pushed fresh data (e.g., after reset, or an extension button action re-pushing its
  // rebuilt panel) — clear local state and recreate inputs so Core.TextField (vt-skip-update)
  // shows the new values instead of retaining stale typed text.
  editor.afterReset.value = false
  editor.savedValues.value = {}
  // Drop any on-demand-added rows — the fresh payload defines what's set vs addable anew.
  localAddedFields.value = []
  resetGeneration.value++
})

/** Effective payload: local override wins over server data */
const payload = computed(() => localOverride.value ?? serverPayload.value)

// ── Add-a-field ─────────────────────────────────────────────────────────────
// The server splits scanned fields into SET (`payload.fields`, rendered inline) and ADDABLE
// (`payload.addableFields`, NOT rendered — offered through the searchable "+ Add a stat" picker).
// Pre-rendering the addable set (dominated by every registered StatModifiers stat, which grows with
// installed mods) is what made the editor take ~20s to mount on heavy modpacks. When the admin picks
// an addable field it's appended here and mounted as ONE editable row on demand — a deliberate,
// infrequent structural change, the same accepted pattern as recipe "Add input". This keeps the
// editor's element count (and therefore its open/mount time) bounded regardless of mod count.
const localAddedFields = ref<FieldDef[]>([])

/** Inline field list = server's SET fields + any the admin added this session. */
const effectiveFields = computed<FieldDef[]>(() =>
  localAddedFields.value.length
    ? [...(payload.value?.fields ?? []), ...localAddedFields.value]
    : (payload.value?.fields ?? [])
)

/** The addable universe from the server (static per item/source — never mutated client-side). */
const serverAddableFields = computed<FieldDef[]>(() => payload.value?.addableFields ?? [])

// ── Derived State ────────────────────────────────────────────────────────

const itemName = computed(() => payload.value?.itemName ?? 'Loading...')
const itemId = computed(() => payload.value?.itemId ?? '')
const itemMod = computed(() => payload.value?.itemMod ?? 'Unknown')
const itemType = computed(() => payload.value?.itemType ?? 'Item')
/** Whether the item currently has overrides — from server payload OR from saves
 *  in this editing session. Drives the "Modified" badge in the header. */
const hasOverride = computed(() =>
  (payload.value?.hasOverride ?? false) ||
  Object.keys(editor.savedValues.value).length > 0
)

// ── Tab System ───────────────────────────────────────────────────────────
//
// Visibility-based approach: ALL fields from BOTH tabs are rendered once at
// mount. Tab switching toggles :visible on two wrapper Groups — the client
// just hides/shows existing elements (near-instant). No structural changes,
// no clear+appendInline, no routing key regeneration, no event re-registration.
//
// Previous element-replacement approach caused structural changes on every
// tab switch → clear("#App") + full appendInline (~40-90KB) + full event
// re-registration → 100-500ms client freeze per switch.
//
// IMPORTANT: No static elements inside v-for — Vue hoists them to _cache,
// sharing the same VNode across iterations → structural change on every render.

/** Active tab — which tab's content is displayed */
const activeTab = ref('General')

/** Available tabs from server payload (e.g., ["Properties", "General"]) */
const tabs = computed<string[]>(() => payload.value?.tabs ?? ['General'])

/** Whether to show the tab bar (hidden for single-tab items like ingredients) */
const showTabs = computed(() => tabs.value.length > 1)

// When tabs change (new item opened), default to the first tab.
// This ensures Properties is shown first for items that have it (UX §5.3).
watch(tabs, (newTabs) => {
  // Only reset tab if the current tab no longer exists (e.g., different item opened).
  // Preserve active tab across payload refreshes (e.g., after reset pushes fresh data).
  if (newTabs.length > 0 && !newTabs.includes(activeTab.value)) {
    activeTab.value = newTabs[0]
  }
})

// ── Lazy tab hydration (perf) ──────────────────────────────────────────────
// Only the ACTIVE tab's content is mounted at open; a tab is mounted the first
// time it becomes active and then kept mounted, so re-switches are instant
// (:visible toggle, zero structural change). This bounds the per-open element
// count — the dominant cost of editor open on heavy modpacks — to one tab's
// fields instead of all tabs at once. The active tab is ALWAYS mounted (the
// `tab === activeTab.value` clause), so this never changes WHICH tab shows; it
// only defers building the others until first visit (a deliberate, infrequent
// structural insert, handled by the targeted-structural update path). `visited`
// is cleared on item change so a new item never mounts a previous item's tabs.
const visited = ref<Set<string>>(new Set())
watch(activeTab, (t) => { if (t) visited.value = new Set(visited.value).add(t) }, { immediate: true })
watch(() => payload.value?.itemId, () => { visited.value = new Set() })

/** A base tab is mounted once it is active or was previously visited (mount-once, then :visible). */
function isTabMounted(tab: string): boolean {
  return tab === activeTab.value || visited.value.has(tab)
}

/** General tab fields (always present) */
const generalFields = computed<FieldDef[]>(() => {
  if (!payload.value?.fields) return []
  // Exclude per-stack metadata fields: they carry sourceMod and some resolve to category
  // "General" — without this guard they'd leak into the base General tab. They belong only to their
  // STACK: focus view. Mod StatModifiers fields (Hexcode) carry a componentKey + non-General
  // category, so they were never in this list anyway; this guard is per-stack-specific.
  return effectiveFields.value.filter(f =>
    f.category === 'General' && !f.sourceMod &&
    // Instance-only fields (globalCapable === false: the held stack's Quantity / Durability) appear
    // ONLY in Local ("This Item") scope; in Global scope they have no item-type meaning → hidden.
    (effectiveScope.value === 'local' || f.globalCapable !== false)
  )
})

/** Whether a field is an Armor defense stat (goes to Defense tab).
 *  Armor component fields EXCEPT "Equipment" category (ArmorSlot stays in Properties). */
function isDefenseField(f: FieldDef): boolean {
  return f.id.startsWith('Armor.') && f.category !== 'Equipment'
}

/** Properties tab fields (weapon, tool, glider, utility, container stats + ArmorSlot).
 *  Excludes InteractionVars (Damage tab) and Armor defense stats (Defense tab). */
const propertiesFields = computed<FieldDef[]>(() => {
  return effectiveFields.value.filter(f =>
    f.category !== 'General' && !f.id.startsWith('InteractionVars.') &&
    !f.id.startsWith('LocalDmg.') && !f.id.startsWith('LocalDef.') &&
    !f.id.startsWith('LocalStat.') && !isDefenseField(f)
  )
})

/** Defense tab fields (Armor StatModifiers, DamageResistance, Knockback, Regen, etc.).
 *  Local ("This Item") scope replaces the per-cause asset resistance rows with per-item resistance
 *  knobs (LocalDef.*); other defense fields stay visible but greyed (not yet per-item). */
const defenseFields = computed<FieldDef[]>(() => {
  if (effectiveScope.value !== 'local')
    return effectiveFields.value.filter(isDefenseField)
  // Local: per-item stat-bonus knobs (LocalStat.*) + per-item resistance knobs (LocalDef.*) replace
  // the asset StatModifiers/DamageResistance rows; other defense fields stay visible but greyed.
  const localStat = effectiveFields.value.filter(f => f.id.startsWith('LocalStat.'))
  const localDef = effectiveFields.value.filter(f => f.id.startsWith('LocalDef.'))
  const otherDefense = effectiveFields.value.filter(f =>
    isDefenseField(f) && !f.id.startsWith('Armor.DamageResistance.') && !f.id.startsWith('Armor.StatModifiers.'))
  return [...localStat, ...localDef, ...otherDefense]
})

/** Damage tab fields. Local ("This Item") scope shows ONE per-cause percentage knob per damage type
 *  (LocalDmg.*) — per-item damage can only scale per cause, so the per-attack BaseDamage rows are
 *  replaced. Global scope shows the normal per-attack InteractionVars fields. */
const damageFields = computed<FieldDef[]>(() => {
  if (effectiveScope.value === 'local')
    return effectiveFields.value.filter(f => f.id.startsWith('LocalDmg.'))
  return effectiveFields.value.filter(f => f.id.startsWith('InteractionVars.'))
})

// ── Category grouping (shared pattern for Properties + Damage) ──────

/**
 * Explicit category display order for the Defense tab.
 * Principle: general/universal stats first → specialized/niche last.
 * Flow: what you have → what protects you → what sustains you → what boosts you.
 * Categories not listed here appear at the end in first-seen order.
 */
const DEFENSE_CATEGORY_ORDER = [
  'Armor Stats',
  'Damage Resistance',
  'Knockback Resistance',
  'Regeneration',
  'Damage Enhancement',
  'Damage Class Enhancement',
  'Knockback Enhancement',
]

/**
 * Explicit category display order for the Damage tab.
 * "Interactions" (read-only ApplyEffect/external refs) shown first for context.
 * Attack-type categories (dynamic names) follow in BSON/asset definition order
 * (preserves the modder's intended attack sequence).
 */
const DAMAGE_CATEGORY_ORDER = [
  'Interactions',
]

/** Extract ordered unique categories from a field list, optionally sorted by priority */
function extractCategories(fields: FieldDef[], priorityOrder?: string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const f of fields) {
    if (!seen.has(f.category)) {
      seen.add(f.category)
      result.push(f.category)
    }
  }
  if (!priorityOrder || priorityOrder.length === 0) return result

  // Sort: priority-listed categories first (in priority order),
  // then remaining categories in their original first-seen order.
  const priorityIndex = new Map(priorityOrder.map((c, i) => [c, i]))
  const prioritized = result.filter(c => priorityIndex.has(c))
    .sort((a, b) => priorityIndex.get(a)! - priorityIndex.get(b)!)
  const rest = result.filter(c => !priorityIndex.has(c))
  return [...prioritized, ...rest]
}

/** Group fields by category */
function groupByCategory(fields: FieldDef[]): Record<string, FieldDef[]> {
  const groups: Record<string, FieldDef[]> = {}
  for (const f of fields) {
    if (!groups[f.category]) groups[f.category] = []
    groups[f.category].push(f)
  }
  return groups
}

const propertiesCategories = computed(() => extractCategories(propertiesFields.value))
const propertiesFieldsByCategory = computed(() => groupByCategory(propertiesFields.value))
const defenseCategories = computed(() => extractCategories(defenseFields.value, DEFENSE_CATEGORY_ORDER))
const defenseFieldsByCategory = computed(() => groupByCategory(defenseFields.value))
const damageCategories = computed(() => extractCategories(damageFields.value, DAMAGE_CATEGORY_ORDER))
const damageFieldsByCategory = computed(() => groupByCategory(damageFields.value))

// ── Mod-focused stat view ──────────────────────────────────────────────
// When an auto-detected "MOD:<name>" source is selected, the editor shows just that mod's
// registered stats (across whatever components carry them — weapon/armor), grouped by category.
// These are the SAME FieldDef objects as the base tabs (StatModifiers are base overrides), so
// every handler (getDisplayValue, onFieldChanged, save) works unchanged. Functions, not computeds,
// because they're keyed per mod inside a v-for; each reads payload.value.fields (reactive).

/** All fields attributed to a given mod, in payload order. */
function fieldsForMod(modName: string): FieldDef[] {
  return effectiveFields.value.filter(f => f.sourceMod === modName)
}
/** Ordered unique categories among a mod's fields (first-seen order). */
function modCategories(modName: string): string[] {
  return extractCategories(fieldsForMod(modName))
}
/** A mod's fields grouped by category. */
function modFieldsByCategory(modName: string): Record<string, FieldDef[]> {
  return groupByCategory(fieldsForMod(modName))
}

// ── Addable-field pickers ───────────────────────────────────────────────────
// Each tab/focus-view gets a "+ Add a stat" picker scoped to ITS addable fields, mirroring the
// inline filters above so a field lands in the same place whether it's set or added. The picker
// entry set is STATIC for the session (it reads serverAddableFields, never the reactive added list)
// — this preserves FieldPicker's documented zero-churn invariant: the DropdownEntry set only ever
// changes on a fresh server push, never while browsing or adding. Already-added fields stay listed;
// re-picking one is a guarded no-op (addField). Trading a tiny "already added" wart for not mutating
// a dropdown's children inside TopScrolling (a structural-change / scroll-reset hazard).

/** Resting/sentinel value of every add picker — selecting it is a no-op (the prompt itself). */
const ADD_NONE = 'none'

/** Map an addable FieldDef to the CatalogField shape FieldPicker consumes. */
function toCatalog(f: FieldDef): CatalogField {
  const kind = f.valueType === 'BOOLEAN' ? 'boolean'
    : f.valueType === 'STRING' ? (f.options ? 'enum' : 'text')
    : 'numeric'
  return { id: f.id, label: f.displayName, category: f.category, kind, options: f.options ?? undefined, batchEditable: true }
}

const generalAddable = computed<CatalogField[]>(() =>
  serverAddableFields.value.filter(f => f.category === 'General' && !f.sourceMod).map(toCatalog))
const propertiesAddable = computed<CatalogField[]>(() =>
  serverAddableFields.value.filter(f =>
    f.category !== 'General' && !f.id.startsWith('InteractionVars.') && !isDefenseField(f)).map(toCatalog))
const defenseAddable = computed<CatalogField[]>(() =>
  serverAddableFields.value.filter(isDefenseField).map(toCatalog))
const damageAddable = computed<CatalogField[]>(() =>
  serverAddableFields.value.filter(f => f.id.startsWith('InteractionVars.')).map(toCatalog))
/** Addable fields for a given mod focus view. */
function modAddable(modName: string): CatalogField[] {
  return serverAddableFields.value.filter(f => f.sourceMod === modName).map(toCatalog)
}

/**
 * Mount an addable field as an inline editable row on demand. Looks the field up in the server's
 * addable list (the source of truth — the picker only carries ids), guards permission + duplicates,
 * then appends it to localAddedFields. The new row appears via a deliberate structural change;
 * requestFlush pushes it immediately. The admin then types a value → pendingChanges → save, exactly
 * like any inline field (added fields are real FieldDefs with real ids, so every handler works).
 */
function addField(fieldId: string) {
  if (!fieldId || fieldId === ADD_NONE) return
  const f = serverAddableFields.value.find(x => x.id === fieldId)
  if (!f) return
  const allowed = (f.category === 'General' && !f.sourceMod) ? canEditGeneral.value : canEditStats.value
  if (!allowed) return
  if (localAddedFields.value.some(x => x.id === f.id)) return // already mounted — no-op
  localAddedFields.value = [...localAddedFields.value, f]
  globalThis.itemForgeBridge.requestFlush()
}

/**
 * Addable-field count for the active BASE tab. The "No editable properties" empty state must NOT
 * fire when a tab has no SET fields but DOES offer addable ones (the picker is right there).
 * Mirrors activeFields' tab mapping.
 */
const activeAddableCount = computed<number>(() => {
  if (activeTab.value === 'General') return generalAddable.value.length
  if (activeTab.value === 'Defense') return defenseAddable.value.length
  if (activeTab.value === 'Damage') return damageAddable.value.length
  if (activeTab.value === 'Recipe') return 0
  return propertiesAddable.value.length
})

/** Fields in the active tab (for empty state check only) */
const activeFields = computed<FieldDef[]>(() => {
  if (activeTab.value === 'General') return generalFields.value
  if (activeTab.value === 'Defense') return defenseFields.value
  if (activeTab.value === 'Damage') return damageFields.value
  if (activeTab.value === 'Recipe') return [] // Recipe tab uses its own rendering
  return propertiesFields.value
})

// ── Recipe Data ──────────────────────────────────────────────────────────

const recipeData = computed<RecipeData | null>(() => payload.value?.recipeData ?? null)
const hasRecipe = computed(() => recipeData.value !== null)
/**
 * True when this item has no recipe yet and the payload is a blank, creatable template.
 * The Recipe tab renders the normal editor form; the first save creates the recipe.
 * Constant for the payload's lifetime (a created recipe reads back as isNew=false only
 * after the editor is reopened), so v-ifs keyed on it don't toggle mid-session.
 */
const isNewRecipe = computed(() => recipeData.value?.isNew === true)

// ── Recipe Label Styles (static module-level) ────────────────────────────
const RL = {
  label: { FontSize: 14, TextColor: '#96a9be', VerticalAlignment: 'Center' },
  modified: { FontSize: 14, TextColor: '#d4a844', VerticalAlignment: 'Center' },
  subtitle: { FontSize: 12, TextColor: '#667788' },
  was: { FontSize: 12, TextColor: '#d4a844', VerticalAlignment: 'Center' },
  readOnly: { FontSize: 14, TextColor: '#556677', VerticalAlignment: 'Center' },
  newBadge: { FontSize: 12, TextColor: '#6eb86e', VerticalAlignment: 'Center' },
  searchHint: { FontSize: 13, TextColor: '#556677', VerticalAlignment: 'Center' },
  searchResultName: { FontSize: 13, TextColor: '#b7cedd', VerticalAlignment: 'Center' },
  searchResultId: { FontSize: 11, TextColor: '#556677', VerticalAlignment: 'Center' },
}
// Invisible click overlay for input name area (Dashboard ROW_STYLE pattern).
// CRITICAL: TextButton MUST have Background in ALL 4 states — missing Background
// causes "Failed to parse or resolve document for AppendInline" client crash.
const INPUT_OVERLAY = {
  Default:  { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Hovered:  { Background: { Color: '#ffffff08' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Pressed:  { Background: { Color: '#ffffff10' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Disabled: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
}
// Subtle remove button (transparent default, reddish hover)
const REMOVE_BTN = {
  Default:  { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 13, TextColor: '#667788', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered:  { Background: { Color: '#44222240' }, LabelStyle: { FontSize: 13, TextColor: '#cc6666', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed:  { Background: { Color: '#55333360' }, LabelStyle: { FontSize: 13, TextColor: '#ee8888', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 13, TextColor: '#334444', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
// Small secondary button for Cancel and + Add Input
const SMALL_BTN_R = {
  Default:  { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 12, TextColor: '#96a9be', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered:  { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 12, TextColor: '#b7cedd', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed:  { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 12, TextColor: '#dde6ee', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 12, TextColor: '#556677', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
// Search result row overlay — invisible click target with subtle hover highlight.
// Covers the full row width (icon + name + ID) for consistent hover/click.
// Same hover/pressed colors as dropdown entries for visual consistency.
const SEARCH_ROW_OVERLAY = {
  Default:  { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Hovered:  { Background: { Color: '#ffffff10' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Pressed:  { Background: { Color: '#ffffff18' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Disabled: { Background: { Color: '#00000001' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
}
const BOOL_BTN_R = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}

// ── Recipe Dropdown Style (matches FieldEditor DROPDOWN_STYLE) ────────
const DROPDOWN_R = {
  DefaultBackground: { TexturePath: 'Common/Dropdown.png', Border: 16 },
  HoveredBackground: { TexturePath: 'Common/DropdownHovered.png', Border: 16 },
  PressedBackground: { TexturePath: 'Common/DropdownPressed.png', Border: 16 },
  DefaultArrowTexturePath: 'Common/DropdownCaret.png',
  HoveredArrowTexturePath: 'Common/DropdownCaret.png',
  PressedArrowTexturePath: 'Common/DropdownPressedCaret.png',
  ArrowWidth: 13, ArrowHeight: 18,
  LabelStyle: { TextColor: '#96a9be', VerticalAlignment: 'Center', FontSize: 13 },
  EntryLabelStyle: { TextColor: '#b7cedd', VerticalAlignment: 'Center', FontSize: 13 },
  SelectedEntryLabelStyle: { TextColor: '#b7cedd', VerticalAlignment: 'Center', FontSize: 13, RenderBold: true },
  HorizontalPadding: 8,
  PanelBackground: { TexturePath: 'Common/DropdownBox.png', Border: 16 },
  PanelScrollbarStyle: SCROLLBAR,
  PanelWidth: 240, PanelPadding: 6, PanelAlign: 'Right', PanelOffset: 7,
  EntryHeight: 31, EntriesInViewport: 8, HorizontalEntryPadding: 7,
  HoveredEntryBackground: { Color: '#0a0f17' },
  PressedEntryBackground: { Color: '#0f1621' },
  FocusOutlineSize: 1, FocusOutlineColor: '#ffffff(0.4)',
}

// ── Recipe Input Management (local state, search, add/remove) ────────────

/**
 * Auto-incrementing uid for stable v-for keys on LocalInput rows.
 * Never reused — guarantees unique keys even after add/remove cycles.
 */
let nextInputUid = 0

/** Reactive local copy of recipe inputs. Supports add/remove/modify. */
const localInputs = ref<LocalInput[]>([])

/**
 * Auto-incrementing uid for stable v-for keys on LocalBench rows.
 * Never reused — guarantees unique keys even after add/remove cycles.
 */
let nextBenchUid = 0

/** Reactive local copy of recipe bench requirements. Supports add/remove/modify. */
const localBenches = ref<LocalBench[]>([])

/** Which localInput uid has the search panel open (null = none) */
const searchingInputUid = ref<number | null>(null)

/**
 * Search query display value — reactive ref, only updated on open/close.
 * NEVER updated during typing (see onSearchQueryChanged for why).
 * Used as Core.TextField :model-value for initial display (empty on open).
 */
const searchQuery = ref('')

/**
 * Search query typed text — NON-REACTIVE plain variable.
 * Updated on every keystroke without triggering Vue re-render.
 * Core.TextField manages its own display via vt-skip-update — we only
 * need this value for the debounced bridge search call.
 *
 * WHY NON-REACTIVE: Setting a reactive ref during a @value-changed handler
 * triggers a full Editor.vue re-render inside handleDataEvent's runOnV8Thread().get().
 * The response back to the client causes focus re-evaluation → cursor leaves the
 * TextField on every keystroke. Same root cause as the scroll reset issue
 * (see SCROLL_RESET_INVESTIGATION.md — "ZERO reactive changes during editing").
 */
let searchQueryText = ''

/** Current search results from bridge searchMaterials() */
const searchResults = ref<MaterialSearchResult[]>([])

/** Debounce timer for bridge search calls */
let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null

/**
 * Initialize localInputs from server data whenever recipeData changes.
 * This fires on initial load, navigate-back, and after reset (server pushes fresh data).
 */
watch(recipeData, (rd) => {
  if (!rd) { localInputs.value = []; localBenches.value = []; return }
  localInputs.value = rd.inputs.map(input => ({
    uid: nextInputUid++,
    itemId: input.itemId,
    resourceTypeId: input.resourceTypeId,
    quantity: input.quantity,
    displayName: input.displayName,
    originalIndex: input.index,
    isNew: false,
  }))
  // Seed local bench requirements. Copy the FULL categories array (defensive slice) so a
  // bench's extra panels are preserved through editing — only collapsed when the panel
  // dropdown is actively changed. originalIndex maps each row to its server snapshot for "was:".
  localBenches.value = rd.benchRequirements.map(bench => ({
    uid: nextBenchUid++,
    type: bench.type,
    benchId: bench.benchId,
    categories: [...(bench.categories ?? [])],
    requiredTierLevel: bench.requiredTierLevel,
    originalIndex: bench.index,
    isNew: false,
  }))
  // Close any open search panel on data refresh
  searchingInputUid.value = null
  searchQuery.value = ''     // Reactive — clears for next open
  searchQueryText = ''       // Sync non-reactive
  searchResults.value = []
}, { immediate: true })

// ── Input Search ──────────────────────────────────────────────────────────

function openInputSearch(uid: number) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  searchingInputUid.value = uid
  searchQuery.value = ''     // Reactive — clears TextField display on open
  searchQueryText = ''       // Sync non-reactive
  searchResults.value = []
}

function cancelInputSearch() {
  const idx = localInputs.value.findIndex(i => i.uid === searchingInputUid.value)
  // If the input is new and has no material, remove it (admin cancelled without picking)
  if (idx >= 0) {
    const li = localInputs.value[idx]
    if (li.isNew && !li.itemId && !li.resourceTypeId) {
      localInputs.value = localInputs.value.filter((_, i) => i !== idx)
    }
  }
  searchingInputUid.value = null
  searchQuery.value = ''     // Reactive — clears for next open
  searchQueryText = ''       // Sync non-reactive
  searchResults.value = []
  syncInputChanges()
}

/**
 * Debounced search — 1s delay after last keystroke.
 *
 * CRITICAL: ZERO reactive state changes during typing. Only the non-reactive
 * searchQueryText is updated. Core.TextField manages its own display via
 * vt-skip-update — we never need to push the typed value back to the client.
 *
 * searchResults.value updates ONLY after the 1s debounce fires, when the admin
 * has stopped typing and focus stability no longer matters.
 */
function onSearchQueryChanged(value: string) {
  searchQueryText = value  // NON-REACTIVE — no Vue re-render, no focus loss
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    // Sync the reactive display value BEFORE updating results.
    // When searchResults changes, v-for adds/removes elements → structural change
    // → VueBridge full app rebuild → TextField recreated with model-value from
    // searchQuery. Without this sync, the rebuilt TextField shows '' (last open value),
    // losing the typed text. Safe to set here: user stopped typing 1s ago.
    searchQuery.value = searchQueryText
    if (searchQueryText.trim().length < 2) {
      searchResults.value = []
    } else {
      try {
        const json = globalThis.itemForgeBridge.searchMaterials(searchQueryText.trim())
        searchResults.value = JSON.parse(json)
      } catch { searchResults.value = [] }
    }
  }, 1000)
}

function selectSearchResult(result: MaterialSearchResult) {
  const idx = localInputs.value.findIndex(i => i.uid === searchingInputUid.value)
  if (idx < 0) return
  const li = localInputs.value[idx]
  li.itemId = result.type === 'item' ? result.id : null
  li.resourceTypeId = result.type === 'resource' ? result.id : null
  li.displayName = result.displayName
  // Close search panel
  searchingInputUid.value = null
  searchQuery.value = ''     // Reactive — clears for next open
  searchQueryText = ''       // Sync non-reactive
  searchResults.value = []
  syncInputChanges()
}

// ── Input Add / Remove ────────────────────────────────────────────────────

function addInput() {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const newInput: LocalInput = {
    uid: nextInputUid++,
    itemId: null,
    resourceTypeId: null,
    quantity: 1,
    displayName: '',
    originalIndex: null,
    isNew: true,
  }
  localInputs.value = [...localInputs.value, newInput]
  // Immediately open search for the new input
  openInputSearch(newInput.uid)
}

function removeInput(uid: number) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  if (localInputs.value.length <= 1) return // Minimum 1 input
  if (searchingInputUid.value === uid) {
    searchingInputUid.value = null
    searchQuery.value = ''
    searchResults.value = []
  }
  localInputs.value = localInputs.value.filter(i => i.uid !== uid)
  syncInputChanges()
}

// ── Input Quantity ─────────────────────────────────────────────────────────

function onLocalInputQtyChanged(uid: number, value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const n = parseInt(value, 10)
  if (isNaN(n) || n < 1) return
  const li = localInputs.value.find(i => i.uid === uid)
  if (li) {
    li.quantity = n
    syncInputChanges()
  }
}

// ── Sync local inputs → RecipeChanges ─────────────────────────────────────

/**
 * Propagates local input state to editor.recipeChanges.
 *
 * Uses inputsFull (full replacement) when any structural change occurred
 * (add/remove/material change). Falls back to per-index inputs for
 * quantity-only changes (lighter payload, backward compat).
 */
function syncInputChanges() {
  const origInputs = recipeData.value?.inputs ?? []

  // Detect structural changes: different count OR different materials
  const hasStructural = localInputs.value.length !== origInputs.length ||
    localInputs.value.some((li, i) => {
      const orig = origInputs[i]
      if (!orig) return true
      return li.itemId !== orig.itemId || li.resourceTypeId !== orig.resourceTypeId
    })

  // Preserve non-input recipe changes (time, knowledge, benches, etc.)
  const base: RecipeChanges = { ...editor.recipeChanges.value }
  delete base.inputs
  delete base.inputsFull

  if (hasStructural) {
    // Full replacement: send complete input list
    const inputsFull = localInputs.value
      .filter(li => li.itemId || li.resourceTypeId) // Skip incomplete entries
      .map(li => ({
        itemId: li.itemId,
        resourceTypeId: li.resourceTypeId,
        quantity: li.quantity,
      }))
    editor.onRecipeChanged({ ...base, inputsFull })
  } else {
    // Per-index quantity changes only
    const inputChanges: Record<number, { quantity: number }> = {}
    let anyChange = false
    for (let i = 0; i < localInputs.value.length; i++) {
      const li = localInputs.value[i]
      const orig = origInputs[i]
      if (orig && li.quantity !== orig.quantity) {
        inputChanges[i] = { quantity: li.quantity }
        anyChange = true
      }
    }
    if (anyChange) {
      editor.onRecipeChanged({ ...base, inputs: inputChanges })
    } else {
      // No input changes — check if other recipe changes exist
      const hasOther = base.outputQuantity !== undefined || base.timeSeconds !== undefined ||
        base.knowledgeRequired !== undefined || base.requiredMemoriesLevel !== undefined ||
        (base.benches && Object.keys(base.benches).length > 0) ||
        base.benchesFull !== undefined
      if (hasOther) {
        editor.onRecipeChanged(base)
      } else {
        editor.onRecipeChanged(null as unknown as RecipeChanges)
      }
    }
  }
}

// ── Input "was:" and display helpers ──────────────────────────────────────

/** Whether a local input's material was changed from the original */
function isLocalInputMaterialModified(li: LocalInput): boolean {
  if (li.isNew) return false // New inputs don't have "modified" styling — they have "(new)"
  if (li.originalIndex == null) return false
  const orig = recipeData.value?.inputs[li.originalIndex]
  if (!orig) return false
  return li.itemId !== orig.itemId || li.resourceTypeId !== orig.resourceTypeId
}

/** Whether a local input's quantity was changed from the original */
function isLocalInputQtyModified(li: LocalInput): boolean {
  if (li.isNew) return false
  if (li.originalIndex == null) return false
  const orig = recipeData.value?.inputs[li.originalIndex]
  if (!orig) return false
  return li.quantity !== orig.quantity
}

/** Whether any input was modified (for section header gold styling) */
const hasInputChanges = computed(() => {
  const origInputs = recipeData.value?.inputs ?? []
  if (localInputs.value.length !== origInputs.length) return true
  return localInputs.value.some((li, i) => {
    const orig = origInputs[i]
    if (!orig) return true
    return li.itemId !== orig.itemId || li.resourceTypeId !== orig.resourceTypeId || li.quantity !== orig.quantity
  })
})

/**
 * Get subtitle text for a local input.
 *
 * SCROLL RESET SAFETY: This function must NOT read li.quantity — the admin types
 * in the quantity TextField, and any reactive change to subtitle text would dirty
 * the Label element, triggering TopScrolling scroll reset. Only material changes
 * (which happen on search result selection, not during typing) are shown as "was:".
 *
 * @see SCROLL_RESET_INVESTIGATION.md for the root cause analysis
 */
function getLocalInputSubtitle(li: LocalInput): string {
  if (editor.afterReset.value) return li.itemId || li.resourceTypeId || ' '
  if (li.isNew) return '(new)'
  if (li.originalIndex == null) return ' '
  const orig = recipeData.value?.originalInputs?.[li.originalIndex] ?? recipeData.value?.inputs[li.originalIndex]
  if (!orig) return li.itemId || li.resourceTypeId || ' '
  // Only show "was:" for material changes — NOT quantity (scroll reset hazard)
  const materialChanged = li.itemId !== orig.itemId || li.resourceTypeId !== orig.resourceTypeId
  if (materialChanged) return `was: ${orig.displayName}`
  return li.itemId || li.resourceTypeId || ' '
}

/** Style for input subtitle — gold for material changes, subtle for normal */
function getLocalInputSubtitleStyle(li: LocalInput): Record<string, unknown> {
  if (li.isNew) return RL.newBadge
  if (li.originalIndex == null) return RL.subtitle
  const orig = recipeData.value?.inputs[li.originalIndex]
  if (!orig) return RL.subtitle
  // Only material change triggers gold — NOT quantity (same scroll reset reason)
  if (li.itemId !== orig.itemId || li.resourceTypeId !== orig.resourceTypeId) return RL.was
  return RL.subtitle
}

// ── Recipe Display Value Helpers (non-input fields) ──────────────────────

function getDisplayTimeSeconds(): string {
  const rc = editor.recipeChanges.value
  if (rc?.timeSeconds !== undefined) return String(rc.timeSeconds)
  return String(recipeData.value?.timeSeconds ?? 0)
}

function getDisplayOutputQty(): string {
  const rc = editor.recipeChanges.value
  if (rc?.outputQuantity !== undefined) return String(rc.outputQuantity)
  return String(recipeData.value?.outputQuantity ?? 1)
}

function getDisplayKnowledge(): boolean {
  const rc = editor.recipeChanges.value
  if (rc?.knowledgeRequired !== undefined) return rc.knowledgeRequired
  return recipeData.value?.knowledgeRequired ?? false
}

function getDisplayMemoriesLevel(): string {
  const rc = editor.recipeChanges.value
  if (rc?.requiredMemoriesLevel !== undefined) return String(rc.requiredMemoriesLevel)
  return String(recipeData.value?.requiredMemoriesLevel ?? 1)
}

// Bench display values come straight off the reactive LocalBench rows (b.benchId,
// b.categories[0], b.requiredTierLevel) in the template — same as inputs read li.*.
// No getDisplay* indirection needed; localBenches IS the editing state.

/** All benches from the registry, sorted alphabetically for dropdown */
const benchOptions = computed(() => {
  const registry = recipeData.value?.benchRegistry
  if (!registry) return []
  return Object.values(registry).sort((a, b) => a.displayName.localeCompare(b.displayName))
})

/** Panel options for a specific bench id (from registry) */
function getPanelOptions(benchId: string): string[] {
  const registry = recipeData.value?.benchRegistry
  if (!registry || !benchId) return []
  return registry[benchId]?.panels || []
}

/** Format raw panel ID for display: "Workbench_Tools" → "Workbench Tools" */
function formatPanelName(panelId: string): string {
  return panelId.replace(/_/g, ' ')
}

// ── Recipe "was:" Helpers (non-input) ─────────────────────────────────────

function getTimeWasText(): string {
  if (editor.afterReset.value) return ' '
  const orig = recipeData.value?.originalTimeSeconds
  if (orig == null) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    if (orig !== recipeData.value?.timeSeconds) return 'was: ' + orig
  }
  return ' '
}

function getOutputQtyWasText(): string {
  if (editor.afterReset.value) return ' '
  const orig = recipeData.value?.originalOutputQuantity
  if (orig == null) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    if (orig !== recipeData.value?.outputQuantity) return 'was: ' + orig
  }
  return ' '
}

function getKnowledgeWasText(): string {
  if (editor.afterReset.value) return ' '
  const orig = recipeData.value?.originalKnowledgeRequired
  if (orig == null) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    if (orig !== recipeData.value?.knowledgeRequired) return 'was: ' + (orig ? 'Yes' : 'No')
  }
  return ' '
}

function getMemoriesWasText(): string {
  if (editor.afterReset.value) return ' '
  const orig = recipeData.value?.originalRequiredMemoriesLevel
  if (orig == null) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    if (orig !== recipeData.value?.requiredMemoriesLevel) return 'was: ' + orig
  }
  return ' '
}

// ── Bench "was:" helpers ──
//
// SCROLL RESET SAFETY: these compare the last-SAVED server state (benchRequirements)
// against the original (originalBenchRequirements) by originalIndex — both are STATIC
// snapshots. They never read the reactive LocalBench, so typing a tier / picking a
// panel never dirties these Labels (which would trigger a TopScrolling scroll reset).
// New/removed rows (originalIndex == null) show no "was:". Same gating as the input
// "was:" indicators: only after a save/override exists.

function getBenchTierWasText(b: LocalBench): string {
  if (editor.afterReset.value || b.originalIndex == null) return ' '
  const bench = recipeData.value?.benchRequirements[b.originalIndex]
  const orig = recipeData.value?.originalBenchRequirements?.[b.originalIndex]
  if (!orig || !bench) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    if (orig.requiredTierLevel !== bench.requiredTierLevel) return 'was: ' + orig.requiredTierLevel
  }
  return ' '
}

function getBenchIdWasText(b: LocalBench): string {
  if (editor.afterReset.value || b.originalIndex == null) return ' '
  const bench = recipeData.value?.benchRequirements[b.originalIndex]
  const orig = recipeData.value?.originalBenchRequirements?.[b.originalIndex]
  if (!orig || !bench) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    if (orig.benchId !== bench.benchId) return 'was: ' + orig.benchDisplayName
  }
  return ' '
}

function getBenchPanelWasText(b: LocalBench): string {
  if (editor.afterReset.value || b.originalIndex == null) return ' '
  const bench = recipeData.value?.benchRequirements[b.originalIndex]
  const orig = recipeData.value?.originalBenchRequirements?.[b.originalIndex]
  if (!orig || !bench) return ' '
  if (recipeData.value?.hasOverride || editor.recipeSavedThisSession.value) {
    const origPanel = orig.categories?.[0] ?? ''
    const currPanel = bench.categories?.[0] ?? ''
    if (origPanel !== currPanel) return 'was: ' + formatPanelName(origPanel || '(none)')
  }
  return ' '
}

// ── Recipe Event Handlers (bench, output, time, knowledge, memories) ─────

function onBenchIdChanged(uid: number, value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  if (!value) return
  const b = localBenches.value.find(x => x.uid === uid)
  if (!b) return
  b.benchId = value
  // Type is intrinsic to the bench id — derive it from the registry so the saved
  // BenchRequirement carries the correct BenchType (verified: Type is required).
  b.type = recipeData.value?.benchRegistry?.[value]?.type ?? b.type
  // Switching bench resets the panel to the new bench's first panel (or none).
  const firstPanel = recipeData.value?.benchRegistry?.[value]?.panels?.[0]
  b.categories = firstPanel ? [firstPanel] : []
  syncBenchChanges()
}

function onBenchPanelChanged(uid: number, value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const b = localBenches.value.find(x => x.uid === uid)
  if (!b) return
  // Single-panel UI: actively picking a panel collapses to that one. Leaving it
  // untouched preserves the bench's full categories array (seeded from the server).
  b.categories = value ? [value] : []
  syncBenchChanges()
}

function onRecipeTimeChanged(value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const n = parseFloat(value)
  if (isNaN(n) || n < 0) return
  editor.onRecipeChanged({ ...editor.recipeChanges.value, timeSeconds: n })
}

function onRecipeOutputQtyChanged(value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const n = parseInt(value, 10)
  if (isNaN(n) || n < 1) return
  editor.onRecipeChanged({ ...editor.recipeChanges.value, outputQuantity: n })
}

function onRecipeKnowledgeToggle() {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const current = getDisplayKnowledge()
  editor.onRecipeChanged({ ...editor.recipeChanges.value, knowledgeRequired: !current })
}

function onRecipeMemoriesChanged(value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const n = parseInt(value, 10)
  if (isNaN(n) || n < 1) return
  editor.onRecipeChanged({ ...editor.recipeChanges.value, requiredMemoriesLevel: n })
}

function onBenchTierChanged(uid: number, value: string) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  const n = parseInt(value, 10)
  if (isNaN(n) || n < 0) return
  const b = localBenches.value.find(x => x.uid === uid)
  if (!b) return
  b.requiredTierLevel = n
  syncBenchChanges()
}

// ── Bench Add / Remove ─────────────────────────────────────────────────────

function addBench() {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  // Default to the first discovered bench so the new row is always valid (never blank).
  const first = benchOptions.value[0]
  if (!first) return // No benches discovered — nothing to add
  const newBench: LocalBench = {
    uid: nextBenchUid++,
    type: first.type,
    benchId: first.id,
    categories: first.panels.length > 0 ? [first.panels[0]] : [],
    requiredTierLevel: 0,
    originalIndex: null,
    isNew: true,
  }
  localBenches.value = [...localBenches.value, newBench]
  syncBenchChanges()
}

function removeBench(uid: number) {
  if (!canEditRecipes.value) return // recipe tab locked (no edit permission)
  // No minimum: removing all benches is allowed and disables the recipe (uncraftable).
  localBenches.value = localBenches.value.filter(b => b.uid !== uid)
  syncBenchChanges()
}

// ── Sync local benches → RecipeChanges ─────────────────────────────────────

/**
 * Propagates local bench state to editor.recipeChanges. Mirrors syncInputChanges:
 * a structural change (count differs, or any row's bench/type differs from its
 * positional original) emits `benchesFull` (full replacement, even an empty array
 * for delete-all). Otherwise emits the lighter per-index `benches` map for
 * tier/panel-only modifies. Non-bench recipe changes are preserved via `base`.
 */
function syncBenchChanges() {
  const origBenches = recipeData.value?.benchRequirements ?? []

  const hasStructural = localBenches.value.length !== origBenches.length ||
    localBenches.value.some((b, i) => {
      const orig = origBenches[i]
      if (!orig) return true
      return b.benchId !== orig.benchId || b.type !== orig.type
    })

  // Preserve non-bench recipe changes (inputs, time, knowledge, etc.)
  const base: RecipeChanges = { ...editor.recipeChanges.value }
  delete base.benches
  delete base.benchesFull

  if (hasStructural) {
    const benchesFull = localBenches.value
      .filter(b => b.benchId && b.benchId.trim())
      .map(b => ({
        type: b.type,
        benchId: b.benchId,
        categories: b.categories,
        requiredTierLevel: b.requiredTierLevel,
      }))
    editor.onRecipeChanged({ ...base, benchesFull })
  } else {
    // Per-index modifies (tier/panel only — bench id & type unchanged at each position)
    const benchChanges: Record<number, { requiredTierLevel?: number; categories?: string[] }> = {}
    let anyChange = false
    for (let i = 0; i < localBenches.value.length; i++) {
      const b = localBenches.value[i]
      const orig = origBenches[i]
      if (!orig) continue
      const change: { requiredTierLevel?: number; categories?: string[] } = {}
      if (b.requiredTierLevel !== orig.requiredTierLevel) change.requiredTierLevel = b.requiredTierLevel
      const origCats = orig.categories ?? []
      const sameCats = b.categories.length === origCats.length &&
        b.categories.every((c, k) => c === origCats[k])
      if (!sameCats) change.categories = b.categories
      if (Object.keys(change).length > 0) { benchChanges[i] = change; anyChange = true }
    }
    if (anyChange) {
      editor.onRecipeChanged({ ...base, benches: benchChanges })
    } else {
      // No bench changes — preserve other recipe changes, or clear to null if none.
      const hasOther = base.outputQuantity !== undefined || base.timeSeconds !== undefined ||
        base.knowledgeRequired !== undefined || base.requiredMemoriesLevel !== undefined ||
        (base.inputs && Object.keys(base.inputs).length > 0) ||
        (base.inputsFull && base.inputsFull.length > 0)
      if (hasOther) editor.onRecipeChanged(base)
      else editor.onRecipeChanged(null as unknown as RecipeChanges)
    }
  }
}

// ── Layout Pre-Warm ──────────────────────────────────────────────────────
//
// On initial mount, ALL tab content Groups render as Visible:true. The client
// computes layout for every element upfront — this cost is absorbed into the
// already-slow page-open time. After the first V8 tick, inactive tabs are
// toggled invisible (tiny set commands). From then on, tab switches are instant
// because the client reuses cached layout instead of computing it from scratch.
//
// Without this: first tab switch forces the client to layout 100-300 elements
// synchronously (text measurement, positioning, scroll area recomputation),
// causing a perceptible freeze. Subsequent switches are fine (layout cached).
//
// The "flash" of all content visible is 0-1 frames — the initial AppendInline
// and the subsequent set commands arrive back-to-back in the V8 pipeline.

const layoutPreWarmed = ref(false)

onMounted(() => {
  nextTick(() => {
    layoutPreWarmed.value = true
    globalThis.itemForgeBridge.requestFlush()
  })
})

// ── Editor State (composable) ────────────────────────────────────────────

const editor = useEditorState()

// ── Reset Generation ─────────────────────────────────────────────────────
// Incremented on reset to force all FieldEditors to recreate via :key change.
// Core.TextField has vt-skip-update (prevents Set commands during editing),
// which also prevents value updates on reset. Changing the key destroys and
// recreates the element with the correct reverted value. One-time structural
// change on explicit user action — acceptable cost.
const resetGeneration = ref(0)

// ── FieldPicker re-push token (perf) ───────────────────────────────────────
// Each FieldPicker pushes its option list to a static native <DropdownBox> as DATA (not one element
// per option). A structural re-render — server payload re-push (reset / extension action), scope or
// source switch (resetGeneration), or add-field (localAddedFields) — can wipe those host-pushed
// entries, so bump this token on those signals to re-push every mounted picker. Idempotent and
// debounced host-side; a no-op for pickers not currently mounted (they re-push from their own
// onMounted when they appear). A freshly-hydrated tab's picker self-pushes on mount.
const pickerRepushToken = ref(0)
watch(
  [() => payload.value, resetGeneration, activeTab, () => localAddedFields.value.length],
  () => { pickerRepushToken.value++ }
)

// ── Unsaved Changes Overlay ───────────────────────────────────────────
const showUnsavedOverlay = ref(false)
const overlayMessage = computed(() => {
  if (!showUnsavedOverlay.value) return ' '
  const n = editor.dirtyCount.value
  return `You have ${n} unsaved change${n > 1 ? 's' : ''}.`
})

// ── Permissions ───────────────────────────────────────────────────────
// Per-tab edit permissions from the server payload, resolved at open time.
// STATIC for the editing session — they never change reactively while the
// admin types, so binding :visible / read-only to them introduces no
// scroll-reset risk (unlike pending-change state). Fail-closed default (false)
// covers the brief null-payload window and error payloads.
const canEditStats = computed(() => payload.value?.permissions?.canEditStats ?? false)
const canEditRecipes = computed(() => payload.value?.permissions?.canEditRecipes ?? false)
const canEditGeneral = computed(() => payload.value?.permissions?.canEditGeneral ?? false)
const canReset = computed(() => payload.value?.permissions?.canReset ?? false)

// ── Editor source (extension panels) ──────────────────────────────────
//
// When a mod EditorExtension contributes a panel for this item, the server sends
// editorSources = [Base Item, ...extensions] + extensionPanels. A dropdown lets the admin
// choose what to edit:
//   • "BASE"        → the normal base-item tabs (global) — DEFAULT.
//   • an extension  → that mod's declared panel (also global, per item id).
// No extension → editorSources empty → no dropdown, behaviour unchanged. Available from every
// entry point (dashboard/command/inspect) since it keys off the item id, not a physical copy.
//
// SCROLL/CRASH SAFETY: hasExtensionSources and the panels are STATIC for the session (from the
// payload), so the dropdown row + panel containers toggle visibility only on selectedSource
// (a deliberate, infrequent action) — same proven mechanism as tab switching.

/** Prefix marking an auto-detected mod stat source id (matches EditorBridge.MOD_SOURCE_PREFIX). */
const MOD_SOURCE_PREFIX = 'MOD:'
/** True for an auto-detected mod stat source ("MOD:<name>"). */
function isModSource(id: string): boolean {
  return id.startsWith(MOD_SOURCE_PREFIX)
}
/** The mod display name behind a "MOD:<name>" source id. */
function modNameOf(id: string): string {
  return id.slice(MOD_SOURCE_PREFIX.length)
}

// ── Per-stack (per-item metadata) sources ──────────────────────────────────────────
// A held item's own metadata namespaces (e.g. "SocketReforge") arrive as "STACK:<ns>" sources
// (matches StackEditContext.STACK_PREFIX). Their fields ride the same payload.fields list with
// sourceMod = the namespace, so they render through the SAME focus-view + FieldEditor path as
// auto-detected MOD: stat sources — the only differences are this prefix and that they SAVE to
// the held item (executeStackSave) rather than the base asset. Per-item only, inspect-only.
/** Prefix marking a per-stack metadata source id (matches StackEditContext.STACK_PREFIX). */
const STACK_SOURCE_PREFIX = 'STACK:'
/** True for a per-stack metadata source ("STACK:<namespace>"). */
function isStackSource(id: string): boolean {
  return id.startsWith(STACK_SOURCE_PREFIX)
}
/** The namespace behind a "STACK:<namespace>" source id (also the fields' sourceMod). */
function stackNamespaceOf(id: string): string {
  return id.slice(STACK_SOURCE_PREFIX.length)
}
/** The sourceMod value behind any focus source id (MOD: or STACK:) — used to filter its fields. */
function sourceModOf(id: string): string {
  return isStackSource(id) ? stackNamespaceOf(id) : modNameOf(id)
}

/** All selectable sources (empty when nothing extra contributes). First is Base Item. */
const editorSources = computed(() => payload.value?.editorSources ?? [])
/** Show the source dropdown when ≥1 extra source exists (API extension OR detected mod stats). */
const hasExtensionSources = computed(() => editorSources.value.length > 1)
/** API extension sources only (excludes Base Item, mod stat sources, AND per-stack sources). */
const extensionSources = computed(() =>
  editorSources.value.filter(s => s.id !== 'BASE' && !isModSource(s.id) && !isStackSource(s.id))
)
/** Auto-detected mod stat sources only ("MOD:<name>"). */
const modSources = computed(() => editorSources.value.filter(s => isModSource(s.id)))
/** Per-stack metadata sources only ("STACK:<namespace>"). */
const stackSources = computed(() => editorSources.value.filter(s => isStackSource(s.id)))
/**
 * All focus sources (auto-detected MOD: stats + per-stack STACK: metadata). Both render the SAME
 * FieldEditor-rows focus view filtered by sourceMod, so they share ONE v-for — keeping the compiled
 * renderList count down (the freeze validator's brace heuristic drifts with each extra renderList)
 * and avoiding two near-identical template blocks. Only one view is shown at a time (v-if).
 */
const focusSources = computed(() => [...modSources.value, ...stackSources.value])

/** Currently selected source. "BASE" = base item (default). */
const selectedSource = ref('BASE')
/**
 * True while an API extension panel source is selected. A "MOD:" stat source is NOT extension
 * mode — its stats are base-item overrides, so save/reset use the BASE path (see effectiveSaveSource).
 */
const isExtensionMode = computed(() =>
  selectedSource.value !== 'BASE' &&
  !isModSource(selectedSource.value) &&
  !isStackSource(selectedSource.value)
)
/** True while a per-stack metadata source is selected (its fields save to the held item). */
const isStackMode = computed(() => isStackSource(selectedSource.value))
/**
 * The source string passed to save(). API extensions route to their provider; a "STACK:" source
 * routes to the held-item write path (executeStackSave keys off the "STACK:" prefix); BASE and
 * every "MOD:" stat source save to the base item (StatModifiers are base overrides).
 */
const effectiveSaveSource = computed(() =>
  isStackMode.value ? selectedSource.value
    : isExtensionMode.value ? selectedSource.value
    : 'BASE'
)

// ── Global / Local edit-scope indicator ─────────────────────────────────────────────
// A two-state indicator beside the source dropdown that makes the edit SCOPE explicit and teaches
// the storage model by highlighting the source's legal scope and muting the impossible one:
//   • Base Item / MOD: stat → GLOBAL (asset override, all copies). LOCAL not yet available — it
//                             needs the per-item base-stat override engine.
//   • STACK: (per-item meta) → LOCAL only (the data lives on this one item; no asset slot exists
//                             for per-stack metadata, so GLOBAL is impossible — verified).
//   • API extension          → GLOBAL (per item-id). LOCAL N/A (no per-instance concept).
// In v1 each source has exactly ONE legal scope, so this is an indicator + teaching device, not a
// free choice. The buttons live in the source-selector
// row ABOVE TopScrolling and only restyle on a deliberate source change (onSourceChanged), so they
// can never dirty a scrolled field — scroll-reset safe. el-style swap only; never :disabled.
type EditScope = 'global' | 'local'

/** Whether per-item ("This Item") editing is available this session — inspect mode only (a
 *  concrete held ItemStack exists to write back to). Dashboard/command opens leave it false. */
const localScopeAvailable = computed(() => payload.value?.localScopeAvailable ?? false)

/**
 * The admin's chosen edit scope for sources that support a CHOICE (Base Item). Defaults to global.
 * Per-stack (STACK:) sources are intrinsically local and ignore this; extension/MOD: sources are
 * global-only in this build. The actually-applied scope is [effectiveScope].
 */
const editScope = ref<EditScope>('global')

/** Whether the CURRENT source can be edited per-item ("This Item"). Free tier: BASE, inspect-only. */
const canEditLocal = computed(() =>
  localScopeAvailable.value && (selectedSource.value === 'BASE' || isStackMode.value)
)
/** Whether the CURRENT source can be edited globally ("All copies"). STACK: cannot (no asset slot). */
const canEditGlobal = computed(() => !isStackMode.value)

/**
 * The scope actually in effect: per-stack metadata is always local; everything else follows the
 * admin's toggle, clamped to 'global' when local isn't available for the current source.
 */
const effectiveScope = computed<EditScope>(() => {
  if (isStackMode.value) return 'local'
  if (editScope.value === 'local' && canEditLocal.value) return 'local'
  return 'global'
})

/** Tooltip explaining the active scope / why a scope is unavailable, by source kind. */
const scopeHint = computed(() => {
  if (isStackMode.value) return "Per-item: edits only the item in your hand. 'All copies' isn't possible - this data lives on the individual item."
  if (isExtensionMode.value) return "Edits all copies of this item type."
  if (!localScopeAvailable.value) return "Editing all copies of this item type. To edit just one item, open it from your hand (crouch + right-click)."
  return effectiveScope.value === 'local'
    ? "Editing only the item in your hand. Greyed stats can't be set per-item yet - switch to 'All copies' for those."
    : "Editing all copies of this item type. Switch to 'This item' to change only the one in your hand."
})

/**
 * Switch the edit scope. Treated like a source switch: the field set and
 * which fields are editable differ by scope, so discard unsaved edits + recreate inputs (bump
 * resetGeneration). Deliberate + infrequent, and the buttons live ABOVE TopScrolling → scroll-safe.
 */
function onScopeSelected(scope: EditScope) {
  if (isStackMode.value) return                       // per-stack is always local — ignore
  if (scope === editScope.value) return
  if (scope === 'local' && !canEditLocal.value) return
  if (scope === 'global' && !canEditGlobal.value) return
  editScope.value = scope
  editor.pendingChanges.value = {}
  editor.savedValues.value = {}
  localAddedFields.value = []
  resetGeneration.value++
  globalThis.itemForgeBridge.requestFlush()
}

/** A different item opened (or navigated to) → default back to global scope. Same item re-pushes
 *  (reset / action) keep the current scope so an in-progress per-item session isn't disrupted. */
watch(itemId, () => { editScope.value = 'global' })

/** An extension's declarative component panel. */
function extensionPanelFor(srcId: string): EditorComponent[] {
  return payload.value?.extensionPanels?.[srcId] ?? []
}

/**
 * Values that override a component's server value in the panel display: saved-this-session
 * values and pending edits both win over the component's baseline (so a just-saved field keeps
 * showing the new value until the panel is re-pushed).
 */
const extensionDisplayValues = computed<Record<string, unknown>>(() => ({
  ...editor.savedValues.value,
  ...editor.pendingChanges.value,
}))

/**
 * Reset the source selection when a new item/payload arrives. If the previously selected
 * source is gone (different item, or no extensions), fall back to Base Item.
 */
watch(() => payload.value?.editorSources, (sources) => {
  const ids = (sources ?? []).map(s => s.id)
  if (!ids.includes(selectedSource.value)) selectedSource.value = 'BASE'
}, { immediate: true })

/**
 * Switch the active source. Discards unsaved edits — base field ids and extension field ids
 * are different namespaces; mixing them in one save would be wrong. Deliberate, infrequent.
 */
function onSourceChanged(value: string) {
  if (!value || value === selectedSource.value) return
  selectedSource.value = value
  // Switching source resets the scope choice to global; the admin re-picks "This item" if wanted
  // (a STACK: source forces local via effectiveScope regardless).
  editScope.value = 'global'
  editor.pendingChanges.value = {}
  editor.savedValues.value = {}
  // Added rows belong to the previous source's view — drop them so the new source starts clean.
  localAddedFields.value = []
  // Recreate inputs so Core.TextField (vt-skip-update) re-reads the new source's values.
  resetGeneration.value++
  globalThis.itemForgeBridge.requestFlush()
}

/** Record an extension field edit into pending changes (keyed by component id). */
function onExtensionFieldChanged(fieldId: string, value: unknown) {
  if (!canEditStats.value) return
  const comp = extensionPanelFor(selectedSource.value).find(c => c.id === fieldId)
  editor.onFieldChanged(fieldId, value, comp?.value)
}

/** Fire an extension button action; the server runs it and re-pushes the rebuilt panel. */
function onExtensionAction(actionId: string) {
  if (!canEditStats.value || !payload.value) return
  // The action may change the same fields the admin was editing; drop pending and let the
  // server's re-pushed panel provide fresh values.
  editor.pendingChanges.value = {}
  editor.savedValues.value = {}
  try {
    globalThis.itemForgeBridge.extensionAction(playerId.value, payload.value.itemId, selectedSource.value, actionId)
  } catch (e: any) {
    console.error('[ItemForge] extensionAction error:', e?.message)
  }
  globalThis.itemForgeBridge.requestFlush()
}

// ── Permission Denied Overlay ─────────────────────────────────────────
// Backstop for permission revoked mid-session: a save/reset the UI believed
// was allowed gets rejected server-side. Also fires when Reset is clicked
// without canReset. Same proven dialog pattern as the unsaved overlay.
const showPermissionOverlay = ref(false)
const permissionOverlayMessage = computed(() =>
  showPermissionOverlay.value ? (editor.permissionDeniedMessage.value || ' ') : ' '
)

/** Surface a permission denial reported by the composable after save/reset. */
function consumePermissionDenial(): boolean {
  if (!editor.permissionDenied.value) return false
  showPermissionOverlay.value = true
  editor.permissionDenied.value = false // consume the one-shot flag
  globalThis.itemForgeBridge.requestFlush()
  return true
}

/** Dismiss the Permission Denied overlay. */
function onPermissionOverlayOk() {
  showPermissionOverlay.value = false
  globalThis.itemForgeBridge.requestFlush()
}

/**
 * Get the display value: pending change > saved value > server value.
 * For modifier fields, pending/saved values are composite {amount, calculationType} —
 * we extract the scalar amount for the number input display.
 */
function getDisplayValue(field: FieldDef): number | boolean | string | null {
  const pending = editor.pendingChanges.value[field.id]
  if (pending !== undefined) return extractScalar(pending)
  const saved = editor.savedValues.value[field.id]
  if (saved !== undefined) return extractScalar(saved)
  // In Local ("This Item") scope, show the field's per-instance value (this stack's actual value),
  // not the item-type default. Falls through to currentValue when there's no instance value.
  if (effectiveScope.value === 'local' && field.localCapable && field.localValue != null) {
    return field.localValue
  }
  // After reset, show original values while waiting for server confirmation.
  // originalValue is set for fields that had overrides; null means currentValue IS original.
  if (editor.afterReset.value) {
    if (field.originalValue != null) return field.originalValue
    // Fields that were "not set" originally should revert to null display
    if (field.isNotSet) return null
  }
  return field.currentValue
}

/**
 * Get the effective CalculationType for modifier fields.
 * Priority: pending change > saved value > server field value.
 * Returns null for non-modifier fields.
 */
function getDisplayCalcType(field: FieldDef): string | null {
  if (!field.calculationType) return null
  const pending = editor.pendingChanges.value[field.id]
  if (pending != null && typeof pending === 'object' && 'calculationType' in (pending as object)) {
    return (pending as { calculationType: string }).calculationType
  }
  const saved = editor.savedValues.value[field.id]
  if (saved != null && typeof saved === 'object' && 'calculationType' in (saved as object)) {
    return (saved as { calculationType: string }).calculationType
  }
  return field.calculationType
}

/** Extract scalar display value from a potentially composite modifier change */
function extractScalar(value: unknown): number | boolean | string | null {
  if (value != null && typeof value === 'object' && 'amount' in (value as object)) {
    return (value as { amount: number }).amount
  }
  return value as number | boolean | string | null
}

/** Whether a field was saved during this editing session */
function isSaved(field: FieldDef): boolean {
  return field.id in editor.savedValues.value
}

/**
 * Whether a base-item field's input is locked (greyed, read-only) — combines permission locking
 * with edit-scope capability:
 *  - permission: General fields gate on canEditGeneral, everything else on canEditStats.
 *  - Local scope: a field with no per-instance applier yet (`localCapable` false) is greyed —
 *    teaching the admin which stats can't be set per-item without hiding them.
 *  - Global scope: instance-only fields (`globalCapable` false) can't be edited (also hidden).
 * Recomputed only on scope/source change (FieldEditors are recreated via resetGeneration), so it
 * never toggles reactively on a live scrolled element → scroll-reset safe.
 */
function fieldEditLocked(field: FieldDef): boolean {
  const permLocked = (field.category === 'General' && !field.sourceMod)
    ? !canEditGeneral.value
    : !canEditStats.value
  if (permLocked) return true
  if (effectiveScope.value === 'local') return !field.localCapable
  return field.globalCapable === false
}

// ── Custom Name + Lore (header, dual-scope) ────────────────────────────────
// Pinned in the header above the tabs, always editable regardless of active tab. Editable in BOTH
// scopes — global (item type → i18n broadcast) and local (this stack → ItemDisplay) — the active
// scope picks the save channel. They ride pendingChanges like any field, so onSave bundles them
// with the current scope automatically. Present only when the viewer can edit General (else null →
// the header shows a static name). The lore box lives OUTSIDE TopScrolling, so the multiline input
// can't trigger the scroll-reset hazard.

/** Editable Name field from the payload, or null (no General permission → static name shown). */
const nameField = computed<FieldDef | null>(() => payload.value?.nameField ?? null)
/** Editable Lore field (multiline) from the payload, or null (not editable). */
const loreField = computed<FieldDef | null>(() => payload.value?.loreField ?? null)

/** Scope-aware current text for a header identity field: pending edit > saved-this-session > scope value. */
function identityValue(field: FieldDef | null): string {
  if (!field) return ''
  const pending = editor.pendingChanges.value[field.id]
  if (pending !== undefined) return typeof pending === 'string' ? pending : ''
  const saved = editor.savedValues.value[field.id]
  if (saved !== undefined) return typeof saved === 'string' ? saved : ''
  const v = effectiveScope.value === 'local' ? (field.localValue ?? field.currentValue) : field.currentValue
  return typeof v === 'string' ? v : ''
}
const headerNameValue = computed(() => identityValue(nameField.value))
const headerLoreValue = computed(() => identityValue(loreField.value))
/**
 * Name shown in the title bar. Deliberately does NOT read pendingChanges — a reactive title update
 * on every keystroke would emit a Set command, and ANY Set (even outside TopScrolling) resets the
 * scroll position of the tab fields below (see SCROLL_RESET_INVESTIGATION). So it tracks only the
 * saved/server value (updates on save or scope change), while the editable field shows the live text.
 * The Name/Lore inputs themselves are scroll-safe via vt-skip-update.
 */
const headerNamePreview = computed(() => {
  const f = nameField.value
  if (f) {
    const saved = editor.savedValues.value[f.id]
    if (typeof saved === 'string' && saved) return saved
    const v = effectiveScope.value === 'local' ? (f.localValue ?? f.currentValue) : f.currentValue
    if (typeof v === 'string' && v) return v
  }
  return itemName.value
})

/** Records a header identity edit, scope-aware so reverting to the shown value clears the pending change. */
function onIdentityChanged(field: FieldDef | null, newValue: string) {
  if (!field) return
  const serverValue = effectiveScope.value === 'local' ? (field.localValue ?? field.currentValue) : field.currentValue
  editor.onFieldChanged(field.id, newValue, typeof serverValue === 'string' ? serverValue : '')
}
function onNameInput(v: string) { onIdentityChanged(nameField.value, v) }

/** Transient vt-skip-update flag for the multiline Lore box — mirrors Core.TextField's echo guard so
 *  typing doesn't reset the cursor when Vue re-sends the value on the same tick. */
const loreSkipUpdate = ref(false)
function onLoreInput(v: string) {
  loreSkipUpdate.value = true
  onIdentityChanged(loreField.value, v)
  nextTick(() => { loreSkipUpdate.value = false })
}

/**
 * `value` + `vt-skip-update` for the native MultilineTextField, bundled into ONE object so the
 * template can `v-bind` them as a spread.
 *
 * WHY A SPREAD IS LOAD-BEARING (do not inline these back as `:value` / `:vt-skip-update`):
 * The skip guard only works if `VtSkipUpdate` is already true on the element at the moment Vue
 * patches `value` (VueBridge.patchProp checks `isVtSkipUpdate(el)` before marking the element dirty).
 * With explicit props, the Vue compiler emits the optimized PROPS patch flag with
 * `dynamicProps = ["value", "vt-skip-update"]`, and Vue patches that array IN ORDER — `value` first,
 * while the skip flag is still false → element marked dirty → a Set is sent → the client re-renders
 * the field and steals focus on EVERY keystroke ("one letter at a time"). A `v-bind` spread forces
 * the FULL_PROPS patch flag, which routes to Vue's full `patchProps` — and that path special-cases
 * the prop named `value` to be patched LAST, after `vt-skip-update` is already true. This is exactly
 * the mechanism that makes Vuetale's own Core.TextField (which `mergeProps`-spreads its bindings)
 * immune to the same hazard. Verified from decompiled Vuetale (VuetalePropertySchemaDataKt.isVtSkipUpdate,
 * VueBridge.patchProp) + the compiled Editor.js patch flags.
 */
const loreNativeBindings = computed(() => ({
  value: headerLoreValue.value,
  'vt-skip-update': loreSkipUpdate.value,
}))

/** InputBox decoration matching Vuetale's text fields, for the native MultilineTextField (no Core
 *  wrapper). Texture backgrounds (not colors) → safe on a dirty element. */
const LORE_DECORATION = {
  Default: { Background: { TexturePath: 'Common/InputBox.png', Border: 16 } },
  Hovered: { Background: { TexturePath: 'Common/InputBoxHovered.png', Border: 16 } },
  Pressed: { Background: { TexturePath: 'Common/InputBoxPressed.png', Border: 16 } },
  Selected: { Background: { TexturePath: 'Common/InputBoxSelected.png', Border: 16 } },
}
const LORE_TEXT_STYLE = { TextColor: '#dde6ee', FontSize: 14, Wrap: true }

// ── Actions ──────────────────────────────────────────────────────────────

function onTabClick(tab: string) {
  activeTab.value = tab
  // Deferred flush: schedule immediate UI update instead of waiting 50ms for V8 tick.
  // The flush task runs AFTER this callback returns (game thread free). See
  // EditorBridge.requestFlush() for full safety analysis.
  globalThis.itemForgeBridge.requestFlush()
}

function onFieldChanged(fieldId: string, newValue: unknown) {
  // Base-item fields only. Extension-panel field edits route through onExtensionFieldChanged
  // (emitted by ExtensionPanel), so this handler stays focused on the codec-scanned fields.

  // Search ALL inline fields (server SET fields + on-demand added rows), not just the active tab —
  // a field could be changed, tab switched, then saved from the other tab.
  const allFields = effectiveFields.value
  if (!allFields.length) return
  const field = allFields.find(f => f.id === fieldId)
  if (!field) return
  // Permission guard: silently ignore edits to a locked tab.
  // Per-stack metadata fields (sourceMod set) ALWAYS gate on canEditStats: their
  // category is a fallback label (a single-segment key resolves to "General"), and the backend
  // executeStackSave authoritatively gates every per-stack save on canEditStats — so the UI must
  // match to avoid letting an admin type a value the save will reject. Inputs are already
  // read-only via :edit-locked; this is defense-in-depth and the backend re-checks regardless.
  // In Local ("This Item") scope the rules differ — per-item editing requires the stat
  // permission (backend executeLocalBaseSave gates canEditStats) and the field must have a
  // per-instance applier (localCapable). Otherwise keep the per-tab permission split.
  const allowed = (selectedSource.value === 'BASE' && effectiveScope.value === 'local')
    ? (canEditStats.value && !!field.localCapable)
    : (field.category === 'General' && !field.sourceMod)
      ? canEditGeneral.value
      : canEditStats.value
  if (!allowed) return
  // After reset, the effective base value is the original (reverted) value,
  // not the stale currentValue from the old payload. In Local scope the base is the field's
  // per-instance value, so reverting to it correctly clears the pending edit.
  const scalarBase = (effectiveScope.value === 'local' && field.localCapable && field.localValue != null)
    ? field.localValue
    : editor.afterReset.value
      ? (field.originalValue ?? field.currentValue)
      : field.currentValue
  // For modifier fields, the base must be composite {amount, calculationType}
  // so valuesEqual can properly detect when the admin reverts both to original.
  // When scalarBase is null (field not set on item), use null as serverValue —
  // any non-null change is automatically dirty.
  if (field.calculationType) {
    if (typeof scalarBase === 'number') {
      editor.onFieldChanged(fieldId, newValue, { amount: scalarBase, calculationType: field.calculationType })
    } else {
      editor.onFieldChanged(fieldId, newValue, null)
    }
  } else {
    editor.onFieldChanged(fieldId, newValue, scalarBase)
  }
  // NO requestFlush here — the only elements that change during typing are
  // outside TopScrolling (save button, status text). These update on the next
  // V8 tick (50ms). Calling requestFlush here would force immediate processing.
}

function onSave() {
  if (!editor.isDirty.value || !payload.value) return
  // effectiveSaveSource: API extensions route to their provider; BASE and "MOD:" stat
  // sources both save to the base item (modded StatModifiers are base overrides).
  // Local scope only applies to a BASE-source save (the held item's own fields);
  // STACK: sources already write to the held item via their source id, extension/MOD: are global.
  const saveScope: 'local' | undefined =
    (effectiveSaveSource.value === 'BASE' && effectiveScope.value === 'local') ? 'local' : undefined
  editor.save(playerId.value, payload.value.itemId, effectiveSaveSource.value, saveScope)
  // save() is synchronous — a permission denial is now visible on the composable.
  consumePermissionDenial()
}

function onReset() {
  if (!payload.value) return
  // Reset reverts the shared item asset's overrides — it has no meaning for a mod extension
  // panel (that data is the mod's), so it's a no-op while an extension source is selected.
  if (isExtensionMode.value) return
  // Fast path: if the player can't reset, show the denial overlay without a
  // server round-trip. (The server also re-checks for revoked-mid-session.)
  if (!canReset.value) {
    editor.permissionDeniedMessage.value = "You don't have permission to reset items."
    showPermissionOverlay.value = true
    globalThis.itemForgeBridge.requestFlush()
    return
  }
  editor.resetAll(playerId.value, payload.value.itemId)
  // resetAll() is synchronous — surface a server-side denial if it occurred.
  if (consumePermissionDenial()) return
  // Reset reverts every override → any rows the admin added this session no longer apply.
  localAddedFields.value = []
  resetGeneration.value++  // Force recreate FieldEditors to clear vt-skip-update text
}

function onClose() {
  if (editor.isDirty.value) {
    showUnsavedOverlay.value = true
    globalThis.itemForgeBridge.requestFlush()
    return
  }
  editor.close(playerId.value)
}

/**
 * Save & Close: save pending changes, then close if successful.
 * save() is synchronous on V8 — it clears pendingChanges on success,
 * making isDirty false immediately. If save fails, hide the overlay
 * so the admin can see the error in the status text.
 */
function onSaveAndClose() {
  if (!editor.isDirty.value || !payload.value) return
  editor.save(playerId.value, payload.value.itemId, effectiveSaveSource.value)
  // Permission revoked mid-session: dismiss this dialog and show the denial.
  if (editor.permissionDenied.value) {
    showUnsavedOverlay.value = false
    consumePermissionDenial()
    return
  }
  if (!editor.isDirty.value) {
    // Save succeeded — close
    editor.close(playerId.value)
  } else {
    // Save failed (validation) — hide overlay, let error status show
    showUnsavedOverlay.value = false
  }
}

/** Discard: close without saving. */
function onDiscard() {
  editor.close(playerId.value)
}

/** Keep Editing: dismiss the overlay, return to editor. */
function onKeepEditing() {
  showUnsavedOverlay.value = false
  globalThis.itemForgeBridge.requestFlush()
}
</script>

<template>
  <Group :anchor="{ Full: 1 }">
    <Common.DecoratedContainer
      :anchor="{ Width: 650, Height: 772 }"
      :content-padding="{ Top: 4, Left: 17, Right: 17, Bottom: 17 }"
    >
      <!-- ── Title Bar ── (live preview of the current/edited name) -->
      <template #title>
        <Common.Title :text="headerNamePreview" />
      </template>

      <!-- ── Content ── -->
      <template #content>
        <!-- Custom NAME — pinned at the very top, editable in both scopes (the active Global/Local
             scope decides where it saves). Editable Core.TextField when the viewer can edit General
             (scroll-reset-safe via the wrapper's vt-skip-update); else a static resolved-name label.
             v-if(nameField) is static per session (permission doesn't change mid-edit) → no runtime
             structural toggle. Lives outside TopScrolling. -->
        <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 34 }" :padding="{ Left: 4, Right: 4, Top: 2 }">
          <Label text="Name" :el-style="RL.label" :anchor="{ Width: 52, Height: 32 }" />
          <Group v-if="nameField" :flex-weight="1" :anchor="{ Height: 32 }">
            <Core.TextField
              :model-value="headerNameValue"
              :anchor="{ Horizontal: 0, Height: 28 }"
              @update:model-value="onNameInput"
            />
          </Group>
          <Label v-else :text="itemName" :el-style="S.headerId" :flex-weight="1" :anchor="{ Height: 32 }" />
        </Group>

        <!-- Item Header: icon + ID + metadata row.
             ItemSlot is a native Hytale element (registered in Elements.kt, confirmed
             in PropertySchemaData.kt). Props: ItemId (String), ShowQualityBackground
             (Boolean), ShowQuantity (Boolean). Quality background is automatic — the
             client renders the item's QualityIndex texture behind the icon.
             Kebab-case props → PascalCase via VueBridge.fromKebabCaseToPascalCase(). -->
        <Group
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 80 }"
          :padding="{ Left: 4, Right: 4, Top: 4, Bottom: 4 }"
        >
          <!-- Layered: ItemSlot (rarity background, behind) + ItemIcon (icon + the
               real native item tooltip, on top). ItemIcon owns ShowItemTooltip — the
               dedicated tooltip flag ItemSlot lacks; ItemSlot drives the quality
               background. ItemIcon is frontmost so its tooltip fires on hover. -->
          <Group :anchor="{ Width: 68, Height: 68 }">
            <ItemSlot
              :item-id="itemId || ' '"
              :show-quality-background="true"
              :show-quantity="false"
              :anchor="{ Horizontal: 0, Vertical: 0 }"
            />
            <ItemIcon
              :item-id="itemId || ' '"
              :show-item-tooltip="true"
              :text-tooltip-show-delay="0"
              :anchor="{ Horizontal: 0, Vertical: 0 }"
            />
          </Group>
          <Group :anchor="{ Width: 10 }" />
          <Group layout-mode="Top" :flex-weight="1" :anchor="{ Height: 68 }">
            <Label :text="itemId || ' '" :el-style="S.headerId" :anchor="{ Horizontal: 0, Height: 22 }" />
            <!-- Flex spacer pushes metadata to bottom, aligning with icon bottom edge -->
            <Group :flex-weight="1" />
            <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 22 }">
              <Label :text="itemMod" :el-style="S.headerMeta" :anchor="{ Height: 22 }" />
              <Label text=" - " :el-style="S.headerMeta" :anchor="{ Height: 22 }" />
              <Label :text="itemType" :el-style="S.headerMeta" :anchor="{ Height: 22 }" />
              <Label
                :visible="hasOverride"
                text=" - Modified"
                :el-style="S.headerModified"
                :anchor="{ Height: 22 }"
              />
            </Group>
          </Group>
        </Group>

        <!-- Custom LORE / description — editable multiline box, full width, pinned in the header
             (same scope as Name). Native MultilineTextField (no Core wrapper) with a manual
             vt-skip-update echo guard so typing doesn't reset the cursor. It lives OUTSIDE
             TopScrolling, so the multiline input can't trigger the scroll-reset hazard. v-if is
             static per session. -->
        <Group v-if="loreField" layout-mode="Top" :anchor="{ Horizontal: 0, Height: 82 }" :padding="{ Left: 4, Right: 4, Top: 2 }">
          <Label text="Lore" :el-style="RL.label" :anchor="{ Horizontal: 0, Height: 18 }" />
          <!-- value + vt-skip-update are spread via v-bind (NOT explicit :value / :vt-skip-update) on
               purpose — the spread forces Vue's FULL_PROPS patch path so `value` is patched LAST, after
               the skip flag is true, preventing a focus-stealing Set on every keystroke. See
               loreNativeBindings for the full rationale. -->
          <MultilineTextField
            :max-lines="6"
            :anchor="{ Horizontal: 0, Height: 56 }"
            :decoration="LORE_DECORATION"
            :el-style="LORE_TEXT_STYLE"
            :padding="{ Horizontal: 8, Vertical: 6 }"
            v-bind="loreNativeBindings"
            @value-changed="onLoreInput"
          />
        </Group>

        <!-- Divider -->
        <Common.ContentSeparator :anchor="{ Horizontal: 0, Height: 1 }" />

        <!-- Source + Scope row. Shown when there's a source choice (extensions/mod stats) OR when
             per-item editing is available (inspect mode). All :visible gates here are STATIC for
             the session (hasExtensionSources / localScopeAvailable never change mid-edit), so the
             Height anchors are safe — like the tab bar's showTabs. -->
        <Group
          layout-mode="Left"
          :visible="hasExtensionSources || localScopeAvailable"
          :anchor="{ Horizontal: 0, Height: 36 }"
          :padding="{ Left: 4, Right: 4, Top: 4 }"
        >
          <!-- Source selector — only when ≥1 extra source exists (extension/mod stats). Static
               :visible, so hiding it on a plain inspected item (scope-only row) is safe. -->
          <Label text="Stat Source" :el-style="RL.label" :visible="hasExtensionSources" :anchor="{ Width: 94, Height: 36 }" />
          <Group :visible="hasExtensionSources" :anchor="{ Width: 240, Height: 32 }" :padding="{ Left: 8, Right: 8 }">
            <DropdownBox
              :value="selectedSource"
              :el-style="DROPDOWN_R"
              :anchor="{ Horizontal: 0, Height: 28 }"
              @value-changed="onSourceChanged"
            >
              <DropdownEntry
                v-for="src in editorSources"
                :key="src.id"
                :value="src.id"
                :text="src.name"
              />
            </DropdownBox>
          </Group>
          <!-- Global / Local edit-scope toggle. el-style swap marks the
               active scope (the proven BTN_TAB pattern, identical to the tab bar — NEVER :disabled).
               When a scope isn't legal for the current source the button is a guarded no-op
               (onScopeSelected re-checks legality) and the tooltip explains why. Lives in this
               fixed-height row ABOVE TopScrolling, so restyling on toggle never dirties a scrolled
               field → scroll-reset safe. -->
          <Label text="Scope" :el-style="RL.label" :anchor="{ Width: 56, Height: 36 }" />
          <TextButton
            text="All copies"
            :el-style="effectiveScope === 'global' ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Vertical: 0, Width: 96, Height: 28 }"
            :tooltip-text="scopeHint"
            @activating="onScopeSelected('global')"
          />
          <Group :anchor="{ Width: 6 }" />
          <TextButton
            text="This item"
            :el-style="effectiveScope === 'local' ? BTN_TAB_ACTIVE : BTN_TAB"
            :anchor="{ Vertical: 0, Width: 96, Height: 28 }"
            :tooltip-text="scopeHint"
            @activating="onScopeSelected('local')"
          />
          <Group :flex-weight="1" />
        </Group>

        <!-- Base-item tab bar + single-tab header. Wrapped so it can hide when a provider
             source is selected. The wrapper has only a Horizontal:0 anchor (no Height), so
             toggling its :visible on selectedSource re-sends only float-safe props — the inner
             Height-anchored groups are never re-sent (they aren't dirtied by the parent). -->
        <Group
          layout-mode="Top"
          :anchor="{ Horizontal: 0 }"
          :visible="selectedSource === 'BASE'"
        >
          <!-- Tab Bar — visible when item has multiple tabs (UX_DESIGN.md §5.3).
               Uses :visible toggle, not v-if, to avoid structural changes. -->
          <Group
            layout-mode="Left"
            :visible="showTabs"
            :anchor="{ Horizontal: 0, Height: 36 }"
            :padding="{ Top: 4, Bottom: 2 }"
          >
            <!-- Flex spacers either side center the tab cluster (problem: tabs were
                 left-packed). These are direct children, NOT inside the v-for, so they
                 are not subject to the Vue v-for hoisting hazard. -->
            <Group :flex-weight="1" />
            <template v-for="(tab, index) in tabs" :key="tab">
              <!-- Gap between tab buttons. :visible uses index (dynamic per iteration,
                   prevents Vue hoisting). Hidden for first button → no leading gap. -->
              <Group :visible="index > 0" :anchor="{ Width: 4 }" />
              <TextButton
                :text="tab"
                :el-style="tab === activeTab ? BTN_TAB_ACTIVE : BTN_TAB"
                :anchor="{ Width: 120, Height: 28 }"
                @activating="onTabClick(tab)"
              />
            </template>
            <Group :flex-weight="1" />
          </Group>

          <!-- Section Header — shown when only one tab (no tab bar needed).
               Matches the appearance for simple items like ingredients. -->
          <Group
            layout-mode="Left"
            :visible="!showTabs"
            :anchor="{ Horizontal: 0, Height: 28 }"
            :padding="{ Left: 4, Top: 8 }"
          >
            <Label text="Item Properties" :el-style="S.section" :anchor="{ Height: 20 }" />
            <Group :anchor="{ Width: 8 }" />
            <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
          </Group>
        </Group>

        <!-- Scrollable Field List — ALL fields rendered once, tabs toggle :visible.
             Two wrapper Groups (General + Properties) swap visibility on tab switch.
             This is an incremental property update (2 dirty elements, ~20 set commands),
             NOT a structural change. The client hides/shows existing elements → instant. -->
        <Group
          layout-mode="TopScrolling"
          :flex-weight="1"
          :anchor="{ Horizontal: 0 }"
          :padding="{ Left: 4, Right: 4, Top: 4 }"
          :scrollbar-style="SCROLLBAR"
        >
          <!-- ── General Tab Content ── -->
          <Group
            v-if="selectedSource === 'BASE' && isTabMounted('General')"
            :visible="activeTab === 'General'"
            layout-mode="Top"
            :anchor="{ Horizontal: 0 }"
          >
            <!-- View-only banner. :visible is permission-only =
                 static for the session → never toggles reactively → scroll-safe. -->
            <Label
              :visible="!canEditGeneral"
              text="View-only - you don't have permission to edit these properties."
              :el-style="S.locked"
              :anchor="{ Horizontal: 0, Height: 22 }"
              :padding="{ Left: 4, Top: 2, Bottom: 4 }"
            />
            <!-- Addable properties live behind this picker, not as pre-rendered rows.
                 Entry set is static for the session (zero churn); picking mounts one row on demand. -->
            <Group
              v-if="generalAddable.length && canEditGeneral && effectiveScope === 'global'"
              layout-mode="Left"
              :anchor="{ Horizontal: 0, Height: 38 }"
              :padding="{ Left: 4, Right: 4, Top: 2, Bottom: 6 }"
            >
              <FieldPicker
                :model-value="ADD_NONE"
                :fields="generalAddable"
                picker-id="pk_general"
                :player-id="playerId"
                :repush="pickerRepushToken"
                :anchor="{ Height: 30, Width: 300 }"
                :el-style="DROPDOWN_R"
                :include-none="true"
                none-label="+ Add a property..."
                @update:model-value="addField"
              />
            </Group>
            <FieldEditor
              v-for="field in generalFields"
              :key="field.id + '-' + resetGeneration"
              :field="field"
              :display-value="getDisplayValue(field)"
              :display-calc-type="getDisplayCalcType(field)"
              :is-saved="isSaved(field)"
              :after-reset="editor.afterReset.value"
              :edit-locked="fieldEditLocked(field)"
              @change="onFieldChanged"
            />
          </Group>

          <!-- ── Properties Tab Content ── -->
          <Group
            v-if="selectedSource === 'BASE' && isTabMounted('Properties')"
            :visible="activeTab === 'Properties'"
            layout-mode="Top"
            :anchor="{ Horizontal: 0 }"
          >
            <Label
              :visible="!canEditStats"
              text="View-only - you don't have permission to edit item stats."
              :el-style="S.locked"
              :anchor="{ Horizontal: 0, Height: 22 }"
              :padding="{ Left: 4, Top: 2, Bottom: 4 }"
            />
            <!-- Addable properties picker (see General tab for rationale). -->
            <Group
              v-if="propertiesAddable.length && canEditStats && effectiveScope === 'global'"
              layout-mode="Left"
              :anchor="{ Horizontal: 0, Height: 38 }"
              :padding="{ Left: 4, Right: 4, Top: 2, Bottom: 6 }"
            >
              <FieldPicker
                :model-value="ADD_NONE"
                :fields="propertiesAddable"
                picker-id="pk_properties"
                :player-id="playerId"
                :repush="pickerRepushToken"
                :anchor="{ Height: 30, Width: 300 }"
                :el-style="DROPDOWN_R"
                :include-none="true"
                none-label="+ Add a property..."
                @update:model-value="addField"
              />
            </Group>
            <!-- IMPORTANT: No static elements inside v-for — Vue hoists them
                 to _cache → shared VNode → structural change every render. -->
            <template v-for="cat in propertiesCategories" :key="cat">
              <!-- Category Section Header (e.g., "Armor Stats", "Damage Resistance") -->
              <Group
                layout-mode="Left"
                :anchor="{ Horizontal: 0, Height: 28 }"
                :padding="{ Left: 0, Top: 4 }"
              >
                <Label :text="cat" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <FieldEditor
                v-for="field in (propertiesFieldsByCategory[cat] || [])"
                :key="field.id + '-' + resetGeneration"
                :field="field"
                :display-value="getDisplayValue(field)"
                :display-calc-type="getDisplayCalcType(field)"
                :is-saved="isSaved(field)"
                :after-reset="editor.afterReset.value"
                :edit-locked="fieldEditLocked(field)"
                @change="onFieldChanged"
              />
            </template>
          </Group>

          <!-- ── Defense Tab Content ── -->
          <Group
            v-if="selectedSource === 'BASE' && isTabMounted('Defense')"
            :visible="activeTab === 'Defense'"
            layout-mode="Top"
            :anchor="{ Horizontal: 0 }"
          >
            <Label
              :visible="!canEditStats"
              text="View-only - you don't have permission to edit item stats."
              :el-style="S.locked"
              :anchor="{ Horizontal: 0, Height: 22 }"
              :padding="{ Left: 4, Top: 2, Bottom: 4 }"
            />
            <!-- Addable defense stats picker — the big win on modded servers, where
                 Armor.StatModifiers spans the entire registered-stat registry. -->
            <Group
              v-if="defenseAddable.length && canEditStats && effectiveScope === 'global'"
              layout-mode="Left"
              :anchor="{ Horizontal: 0, Height: 38 }"
              :padding="{ Left: 4, Right: 4, Top: 2, Bottom: 6 }"
            >
              <FieldPicker
                :model-value="ADD_NONE"
                :fields="defenseAddable"
                picker-id="pk_defense"
                :player-id="playerId"
                :repush="pickerRepushToken"
                :anchor="{ Height: 30, Width: 300 }"
                :el-style="DROPDOWN_R"
                :include-none="true"
                none-label="+ Add a stat..."
                @update:model-value="addField"
              />
            </Group>
            <template v-for="cat in defenseCategories" :key="cat">
              <!-- Defense category header (e.g., "Armor Stats", "Damage Resistance") -->
              <Group
                layout-mode="Left"
                :anchor="{ Horizontal: 0, Height: 28 }"
                :padding="{ Left: 0, Top: 4 }"
              >
                <Label :text="cat" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <FieldEditor
                v-for="field in (defenseFieldsByCategory[cat] || [])"
                :key="field.id + '-' + resetGeneration"
                :field="field"
                :display-value="getDisplayValue(field)"
                :display-calc-type="getDisplayCalcType(field)"
                :is-saved="isSaved(field)"
                :after-reset="editor.afterReset.value"
                :edit-locked="fieldEditLocked(field)"
                @change="onFieldChanged"
              />
            </template>
          </Group>

          <!-- ── Damage Tab Content ── -->
          <Group
            v-if="selectedSource === 'BASE' && isTabMounted('Damage')"
            :visible="activeTab === 'Damage'"
            layout-mode="Top"
            :anchor="{ Horizontal: 0 }"
          >
            <Label
              :visible="!canEditStats"
              text="View-only - you don't have permission to edit item stats."
              :el-style="S.locked"
              :anchor="{ Horizontal: 0, Height: 22 }"
              :padding="{ Left: 4, Top: 2, Bottom: 4 }"
            />
            <!-- Addable damage types picker (add e.g. Fire damage to a Physical-only weapon). -->
            <Group
              v-if="damageAddable.length && canEditStats && effectiveScope === 'global'"
              layout-mode="Left"
              :anchor="{ Horizontal: 0, Height: 38 }"
              :padding="{ Left: 4, Right: 4, Top: 2, Bottom: 6 }"
            >
              <FieldPicker
                :model-value="ADD_NONE"
                :fields="damageAddable"
                picker-id="pk_damage"
                :player-id="playerId"
                :repush="pickerRepushToken"
                :anchor="{ Height: 30, Width: 300 }"
                :el-style="DROPDOWN_R"
                :include-none="true"
                none-label="+ Add a damage type..."
                @update:model-value="addField"
              />
            </Group>
            <template v-for="cat in damageCategories" :key="cat">
              <!-- Attack type header (e.g., "Swing Left Damage", "Groundslam Damage") -->
              <Group
                layout-mode="Left"
                :anchor="{ Horizontal: 0, Height: 28 }"
                :padding="{ Left: 0, Top: 4 }"
              >
                <Label :text="cat" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <FieldEditor
                v-for="field in (damageFieldsByCategory[cat] || [])"
                :key="field.id + '-' + resetGeneration"
                :field="field"
                :display-value="getDisplayValue(field)"
                :display-calc-type="getDisplayCalcType(field)"
                :is-saved="isSaved(field)"
                :after-reset="editor.afterReset.value"
                :edit-locked="fieldEditLocked(field)"
                @change="onFieldChanged"
              />
            </template>
          </Group>

          <!-- ── Recipe Tab Content ── -->
          <Group
            v-if="selectedSource === 'BASE' && isTabMounted('Recipe')"
            :visible="activeTab === 'Recipe'"
            layout-mode="Top"
            :anchor="{ Horizontal: 0 }"
          >
            <!-- Has Recipe content -->
            <Group :visible="hasRecipe" layout-mode="Top" :anchor="{ Horizontal: 0 }">

              <!-- View-only banner — permission-only :visible, scroll-safe. -->
              <Label
                :visible="!canEditRecipes"
                text="View-only - you don't have permission to edit recipes."
                :el-style="S.locked"
                :anchor="{ Horizontal: 0, Height: 22 }"
                :padding="{ Left: 4, Top: 2, Bottom: 4 }"
              />

              <!-- New-recipe hint — this item has no recipe yet; the form below creates one.
                   v-if (not :visible) because of Anchor.Height; isNewRecipe is constant for
                   the payload lifetime so this never toggles mid-session. -->
              <Label
                v-if="isNewRecipe && canEditRecipes"
                text="No recipe yet - add at least one input (and a bench to make it craftable), then Save to create one."
                :el-style="RL.subtitle"
                :anchor="{ Horizontal: 0, Height: 36 }"
                :padding="{ Left: 4, Top: 2, Bottom: 4 }"
              />

              <!-- ── Inputs Section ── -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 0, Top: 4 }">
                <Label text="Inputs" :el-style="hasInputChanges ? RL.modified : S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <!-- Input rows — always rendered, no v-if inside v-for.
                   Name area uses :visible toggle between Label (view) and TextField (search).
                   Anchors use Horizontal/Vertical only (no Height/Width) — safe for :visible.
                   Search results panel is a shared sibling OUTSIDE the v-for. -->
              <template v-for="li in localInputs" :key="'ri-' + li.uid">
                <!-- Hide other rows when searching — results appear right below the active row.
                     Outer Group has no Anchor.Height (auto-sizes from Top layout) — safe for :visible. -->
                <Group :visible="searchingInputUid === null || searchingInputUid === li.uid" layout-mode="Top" :anchor="{ Horizontal: 0 }">
                  <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 50 }" :padding="{ Left: 4, Right: 4, Top: 3, Bottom: 3 }">
                    <!-- Item icon — fits inside 44px content area (50 - 3 - 3).
                         Resource inputs (resourceTypeId, no itemId) are wildcard categories
                         with no icon → render an empty slot area instead of the native grey
                         placeholder square. Single v-if on a leaf — safe inside v-for. -->
                    <Group layout-mode="Left" :anchor="{ Width: 44, Height: 44 }">
                      <ItemSlot
                        v-if="li.itemId"
                        :item-id="li.itemId"
                        :show-quality-background="false"
                        :show-quantity="false"
                        :anchor="{ Width: 44, Height: 44 }"
                      />
                    </Group>
                    <!-- Vertical separator between icon and name area.
                         Outer: 9px wide (4px gap + 1px line + 4px gap), stretches to row height.
                         Inner: fills remaining space (1px × ~32px) with divider color.
                         Both reference li.uid to prevent Vue compiler hoisting (static-in-v-for constraint). -->
                    <Group :anchor="{ Width: 9, Vertical: 0 }" :padding="{ Left: 4, Right: 4, Top: 6, Bottom: 6 }" :visible="li.uid >= 0">
                      <Group :anchor="{ Horizontal: 0, Vertical: 0 }" :background="{ Color: '#2b3542' }" :visible="li.uid >= 0" />
                    </Group>
                    <!-- Name area: two-line cell (name + subtitle) in view mode, search TextField in search mode.
                         No layout-mode on outer = absolute positioning. View/search groups overlay in same space.
                         :visible toggled groups use Horizontal/Vertical only (no Height/Width — safe). -->
                    <Group :flex-weight="1" :anchor="{ Height: 44 }">
                      <!-- View mode: stacked name + subtitle via Top layout.
                           Parent Group toggles :visible — safe with Horizontal/Vertical anchor.
                           Children (Labels) don't toggle :visible, so Height in their anchors is safe. -->
                      <Group :visible="searchingInputUid !== li.uid" layout-mode="Top" :anchor="{ Horizontal: 0, Vertical: 0 }">
                        <Label
                          :text="li.displayName || (li.isNew ? 'Select item...' : 'Unknown')"
                          :el-style="isLocalInputMaterialModified(li) ? RL.modified : RL.label"
                          :anchor="{ Horizontal: 0, Height: 24 }"
                        />
                        <Label
                          :text="getLocalInputSubtitle(li)"
                          :el-style="getLocalInputSubtitleStyle(li)"
                          :anchor="{ Horizontal: 0, Height: 20 }"
                        />
                      </Group>
                      <!-- Click overlay for name area (view mode) -->
                      <TextButton
                        :visible="searchingInputUid !== li.uid"
                        text=" "
                        :el-style="INPUT_OVERLAY"
                        :anchor="{ Horizontal: 0, Vertical: 0 }"
                        @activating="openInputSearch(li.uid)"
                      />
                      <!-- Search mode -->
                      <Group :visible="searchingInputUid === li.uid" :anchor="{ Horizontal: 0 }">
                        <Core.TextField
                          :model-value="searchQuery"
                          placeholder-text="Search items..."
                          :anchor="{ Horizontal: 0, Height: 28 }"
                          @update:model-value="onSearchQueryChanged"
                        />
                      </Group>
                    </Group>
                    <!-- Quantity input -->
                    <Group :anchor="{ Width: 56, Height: 32 }" :padding="{ Left: 4, Right: 4 }">
                      <Core.TextField
                        :model-value="String(li.quantity)"
                        :is-read-only="!canEditRecipes"
                        :anchor="{ Horizontal: 0, Height: 28 }"
                        @update:model-value="(v: string) => onLocalInputQtyChanged(li.uid, v)"
                      />
                    </Group>
                    <!-- Remove button — subtle, reddish hover -->
                    <TextButton
                      text="x"
                      :el-style="REMOVE_BTN"
                      :anchor="{ Width: 24, Height: 24 }"
                      @activating="removeInput(li.uid)"
                    />
                  </Group>
                </Group>
              </template>

              <!-- Search results panel — OUTSIDE v-for, shared instance.
                   v-if on deliberate click (safe structural change).
                   Outer wrapper provides 4px top gap; inner Group has DropdownBox
                   background (same texture as bench/panel dropdowns — proven pattern).
                   Background textures safe here: v-if = always fresh render. -->
              <Group v-if="searchingInputUid !== null" layout-mode="Top" :anchor="{ Horizontal: 0 }" :padding="{ Top: 4 }">
                <Group
                  layout-mode="Top"
                  :anchor="{ Horizontal: 0 }"
                  :background="{ TexturePath: 'Common/DropdownBox.png', Border: 16 }"
                  :padding="{ Left: 6, Right: 6, Top: 6, Bottom: 6 }"
                >
                  <!-- Results (up to 8) — icon + name + ID with full-row click overlay.
                       Outer Group has no layout-mode (absolute positioning):
                       content Group + TextButton overlay occupy the same space.
                       Overlay covers the entire row width for consistent hover/click. -->
                  <template v-for="(result, ridx) in searchResults" :key="'sr-' + ridx">
                    <Group :anchor="{ Horizontal: 0, Height: 28 }">
                      <!-- Content layer -->
                      <Group layout-mode="Left" :anchor="{ Horizontal: 0, Vertical: 0 }" :padding="{ Left: 4, Right: 4 }">
                        <!-- Slot area: 24px icon + 8px gap baked into the 32px container.
                             Resources are wildcard categories with no item icon → render an
                             empty slot area (no ItemSlot) so the native empty-slot grey
                             placeholder square never appears. The 8px gap gives the name
                             breathing room. Single v-if on a leaf is safe inside v-for —
                             only complex content (TextField/nested v-for/multiple v-if) crashes. -->
                        <Group layout-mode="Left" :anchor="{ Width: 32, Height: 24 }">
                          <ItemSlot
                            v-if="result.type === 'item'"
                            :item-id="result.id"
                            :show-quality-background="false"
                            :show-quantity="false"
                            :anchor="{ Width: 24, Height: 24 }"
                          />
                        </Group>
                        <Label
                          :text="result.displayName"
                          :el-style="RL.searchResultName"
                          :flex-weight="1"
                          :anchor="{ Height: 28 }"
                        />
                        <Label
                          :text="result.id"
                          :el-style="RL.searchResultId"
                          :anchor="{ Width: 160, Height: 28 }"
                        />
                      </Group>
                      <!-- Click/hover overlay — full row width -->
                      <TextButton
                        text=" "
                        :el-style="SEARCH_ROW_OVERLAY"
                        :anchor="{ Horizontal: 0, Vertical: 0 }"
                        @activating="selectSearchResult(result)"
                      />
                    </Group>
                  </template>
                  <!-- Cancel button at bottom of results -->
                  <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }">
                    <TextButton
                      text="Cancel"
                      :el-style="SMALL_BTN_R"
                      :anchor="{ Width: 64, Height: 24 }"
                      @activating="cancelInputSearch"
                    />
                  </Group>
                </Group>
              </Group>

              <!-- Create gate hint: a recipe needs ≥1 input (codec-enforced; save is rejected
                   without one). Mirrors the empty-bench note — v-if toggles only on deliberate
                   add/remove (safe structural change). Shown only while creating. -->
              <Label
                v-if="isNewRecipe && localInputs.length === 0"
                text="Add at least one input to create this recipe."
                :el-style="RL.subtitle"
                :anchor="{ Horizontal: 0, Height: 24 }"
                :padding="{ Left: 4, Top: 2 }"
              />

              <!-- Add Input button -->
              <Group :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 4, Top: 4 }">
                <TextButton
                  text="+ Add Input"
                  :el-style="SMALL_BTN_R"
                  :anchor="{ Width: 100, Height: 24 }"
                  @activating="addInput"
                />
              </Group>

              <!-- ── Output Section ── -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 0, Top: 8 }">
                <Label text="Output" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <!-- OutputQuantity (editable) — standard field row -->
              <Group
                :visible="recipeData?.outputPattern === 'quantity'"
                layout-mode="Left"
                :anchor="{ Horizontal: 0, Height: 36 }"
                :padding="{ Left: 4, Right: 4 }"
              >
                <Label text="Output Quantity" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                  <Core.TextField
                    :model-value="getDisplayOutputQty()"
                    :is-read-only="!canEditRecipes"
                    :anchor="{ Horizontal: 0, Height: 28 }"
                    @update:model-value="onRecipeOutputQtyChanged"
                  />
                </Group>
                <Group :anchor="{ Width: 90, Height: 36 }">
                  <Label :text="getOutputQtyWasText()" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                </Group>
              </Group>

              <!-- Output array (read-only) — consistent 14px rows -->
              <Group
                :visible="recipeData?.outputPattern === 'array' || recipeData?.outputPattern === 'primary'"
                layout-mode="Top"
                :anchor="{ Horizontal: 0 }"
              >
                <template v-for="(output, oidx) in (recipeData?.outputs || [])" :key="'ro-' + oidx">
                  <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 4, Right: 4 }">
                    <Label :text="output.displayName" :el-style="RL.readOnly" :anchor="{ Width: 200, Height: 28 }" />
                    <Label :text="'x ' + output.quantity" :el-style="RL.label" :anchor="{ Height: 28 }" :padding="{ Left: 8 }" />
                  </Group>
                </template>
              </Group>

              <!-- ── Crafting Section ── -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 0, Top: 8 }">
                <Label text="Crafting" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <!-- Time (seconds) — standard field row -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 36 }" :padding="{ Left: 4, Right: 4 }">
                <Label text="Time (seconds)" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                  <Core.TextField
                    :model-value="getDisplayTimeSeconds()"
                    :is-read-only="!canEditRecipes"
                    :anchor="{ Horizontal: 0, Height: 28 }"
                    @update:model-value="onRecipeTimeChanged"
                  />
                </Group>
                <Group :anchor="{ Width: 90, Height: 36 }">
                  <Label :text="getTimeWasText()" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                </Group>
              </Group>

              <!-- Knowledge Required — boolean toggle (FieldEditor pattern) -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 36 }" :padding="{ Left: 4, Right: 4 }">
                <Label text="Knowledge Required" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                  <TextButton
                    :text="getDisplayKnowledge() ? 'Yes' : 'No'"
                    :el-style="BOOL_BTN_R"
                    :anchor="{ Width: 80, Height: 28 }"
                    @activating="onRecipeKnowledgeToggle"
                  />
                </Group>
                <Group :anchor="{ Width: 90, Height: 36 }">
                  <Label :text="getKnowledgeWasText()" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                </Group>
              </Group>

              <!-- Memories Level — standard field row -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 36 }" :padding="{ Left: 4, Right: 4 }">
                <Label text="Memories Level" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                  <Core.TextField
                    :model-value="getDisplayMemoriesLevel()"
                    :is-read-only="!canEditRecipes"
                    :anchor="{ Horizontal: 0, Height: 28 }"
                    @update:model-value="onRecipeMemoriesChanged"
                  />
                </Group>
                <Group :anchor="{ Width: 90, Height: 36 }">
                  <Label :text="getMemoriesWasText()" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                </Group>
              </Group>

              <!-- ── Bench Requirements Section ──
                   Always rendered (no length gate) so a recipe with zero benches can still
                   gain one via "+ Add Bench". Driven by the reactive localBenches array (uid
                   keyed) to support add/remove/modify, mirroring the recipe-inputs pattern. -->
              <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 0, Top: 8 }">
                <Label text="Bench Requirements" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
              </Group>

              <!-- Each bench gets Bench dropdown (+ Remove) + Panel dropdown + Tier field -->
              <template v-for="(b, bidx) in localBenches" :key="'rb-' + b.uid + '-' + resetGeneration">
                <!-- Spacer between multiple bench requirements (dynamic index → no Vue hoisting) -->
                <Group v-if="bidx > 0" :anchor="{ Horizontal: 0, Height: 8 }" />

                <!-- Bench dropdown — from BenchRegistry — plus a per-row Remove button -->
                <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 36 }" :padding="{ Left: 4, Right: 4 }">
                  <Label text="Bench" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                  <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                    <DropdownBox
                      :value="b.benchId"
                      :el-style="DROPDOWN_R"
                      :anchor="{ Horizontal: 0, Height: 28 }"
                      @value-changed="(v: string) => onBenchIdChanged(b.uid, v)"
                    >
                      <DropdownEntry
                        v-for="opt in benchOptions"
                        :key="opt.id"
                        :value="opt.id"
                        :text="opt.displayName"
                      />
                    </DropdownBox>
                  </Group>
                  <Group :anchor="{ Width: 90, Height: 36 }">
                    <Label :text="getBenchIdWasText(b)" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                  </Group>
                  <!-- Remove this bench requirement — subtle, reddish hover (leaf TextButton) -->
                  <TextButton
                    text="x"
                    :el-style="REMOVE_BTN"
                    :anchor="{ Width: 24, Height: 24 }"
                    @activating="removeBench(b.uid)"
                  />
                </Group>

                <!-- Panel dropdown — filtered by selected bench. v-if because
                     Anchor.Height on :visible toggle crashes. Only toggles on
                     bench change (Processing has no panels) — deliberate, infrequent. -->
                <Group
                  v-if="getPanelOptions(b.benchId).length > 0"
                  layout-mode="Left"
                  :anchor="{ Horizontal: 0, Height: 36 }"
                  :padding="{ Left: 4, Right: 4 }"
                >
                  <Label text="Panel" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                  <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                    <DropdownBox
                      :value="b.categories[0] ?? ''"
                      :el-style="DROPDOWN_R"
                      :anchor="{ Horizontal: 0, Height: 28 }"
                      @value-changed="(v: string) => onBenchPanelChanged(b.uid, v)"
                    >
                      <DropdownEntry
                        v-for="p in getPanelOptions(b.benchId)"
                        :key="p"
                        :value="p"
                        :text="formatPanelName(p)"
                      />
                    </DropdownBox>
                  </Group>
                  <Group :anchor="{ Width: 90, Height: 36 }">
                    <Label :text="getBenchPanelWasText(b)" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                  </Group>
                </Group>

                <!-- Tier — standard field row -->
                <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 36 }" :padding="{ Left: 4, Right: 4 }">
                  <Label text="Tier" :el-style="RL.label" :anchor="{ Width: 200, Height: 36 }" />
                  <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
                    <Core.TextField
                      :model-value="String(b.requiredTierLevel)"
                      :is-read-only="!canEditRecipes"
                      :anchor="{ Horizontal: 0, Height: 28 }"
                      @update:model-value="(v: string) => onBenchTierChanged(b.uid, v)"
                    />
                  </Group>
                  <Group :anchor="{ Width: 90, Height: 36 }">
                    <Label :text="getBenchTierWasText(b)" :el-style="RL.was" :anchor="{ Horizontal: 0, Vertical: 0 }" />
                  </Group>
                </Group>
              </template>

              <!-- Empty-state note — recipe with no bench is uncraftable. v-if (Anchor.Height
                   present → :visible toggle would crash); deliberate/infrequent state. -->
              <Label
                v-if="localBenches.length === 0"
                text="No bench set - this recipe is uncraftable until a bench is added."
                :el-style="RL.subtitle"
                :anchor="{ Horizontal: 0, Height: 24 }"
                :padding="{ Left: 4, Top: 2 }"
              />

              <!-- Add Bench button -->
              <Group :anchor="{ Horizontal: 0, Height: 28 }" :padding="{ Left: 4, Top: 4 }">
                <TextButton
                  text="+ Add Bench"
                  :el-style="SMALL_BTN_R"
                  :anchor="{ Width: 110, Height: 24 }"
                  @activating="addBench"
                />
              </Group>

            </Group>

            <!-- (No dead "no recipe" state here: buildRecipeData now returns a blank creatable
                 template instead of null, so whenever the Recipe tab exists, hasRecipe is true
                 and the editable form above is shown — for both existing and brand-new recipes.) -->
          </Group>

          <!-- Empty state — always present, toggled via :visible -->
          <Label
            text="No editable properties."
            :visible="selectedSource === 'BASE' && activeFields.length === 0 && activeAddableCount === 0 && activeTab !== 'Recipe' && !!payload"
            :el-style="S.empty"
            :anchor="{ Horizontal: 0, Height: 28 }"
          />

          <!-- ── Extension panels ──
               One panel per managing extension, shown when that source is selected in the
               dropdown. ExtensionPanel renders the mod's declared components with our themed
               widgets. The container uses a Horizontal:0 anchor (no Height) so toggling its
               :visible on selectedSource is safe; pre-warmed on mount like the base tabs. The
               inner :key includes resetGeneration so a server re-push (after a button action)
               recreates the inputs with fresh values. -->
          <template v-for="src in extensionSources" :key="'ext-' + src.id">
            <Group
              :visible="!layoutPreWarmed || selectedSource === src.id"
              layout-mode="Top"
              :anchor="{ Horizontal: 0 }"
            >
              <Label
                :visible="!canEditStats"
                text="View-only - you don't have permission to edit this item."
                :el-style="S.locked"
                :anchor="{ Horizontal: 0, Height: 22 }"
                :padding="{ Left: 4, Top: 2, Bottom: 4 }"
              />
              <ExtensionPanel
                :key="src.id + '-' + resetGeneration"
                :components="extensionPanelFor(src.id)"
                :edit-locked="!canEditStats"
                :pending="extensionDisplayValues"
                @change="onExtensionFieldChanged"
                @action="onExtensionAction"
              />
            </Group>
          </template>

          <!-- ── Focus views: auto-detected mod stats (MOD:) + per-stack metadata (STACK:) ──
               One focused view per MOD: source (a mod's registered EntityStatTypes on this item,
               e.g. "Hexcode" — base-item StatModifiers overrides) OR per STACK: source (a per-item
               metadata namespace on the held item, e.g. "SocketReforge" — saved to the held stack).
               Both render the SAME base FieldEditor rows filtered by sourceMod (sourceModOf strips
               either prefix); every handler works unchanged, and save routing differs purely by the
               source id (effectiveSaveSource → MOD: to the base asset, STACK: to the held item).
               MOUNT-EXCLUSIVE v-if (not :visible): only the selected source's fields are mounted, so
               editing one never dirties a duplicate copy inside TopScrolling (the zero-dirty-during-
               editing scroll-reset invariant — a second mounted copy of a field would have a false
               vt-skip-update flag and be marked dirty on the first keystroke). Switching source is a
               deliberate, infrequent action (onSourceChanged clears pending + bumps resetGeneration),
               so the mount/unmount structural change is acceptable. ONE v-for over BOTH lists
               (focusSources) keeps the compiled renderList count down — the freeze validator's brace
               heuristic drifts with each extra renderList. -->
          <template v-for="src in focusSources" :key="'focus-' + src.id">
            <Group
              v-if="selectedSource === src.id"
              layout-mode="Top"
              :anchor="{ Horizontal: 0 }"
            >
              <Label
                :visible="!canEditStats"
                text="View-only - you don't have permission to edit item stats."
                :el-style="S.locked"
                :anchor="{ Horizontal: 0, Height: 22 }"
                :padding="{ Left: 4, Top: 2, Bottom: 4 }"
              />
              <!-- Addable picker for this mod's stats (MOD: sources only; STACK: metadata
                   has no addable universe). Props reference `src` → never hoisted to _cache. -->
              <Group
                v-if="isModSource(src.id) && modAddable(sourceModOf(src.id)).length && canEditStats"
                layout-mode="Left"
                :anchor="{ Horizontal: 0, Height: 38 }"
                :padding="{ Left: 4, Right: 4, Top: 2, Bottom: 6 }"
              >
                <FieldPicker
                  :model-value="ADD_NONE"
                  :fields="modAddable(sourceModOf(src.id))"
                  :picker-id="'pk_mod_' + sourceModOf(src.id).replace(/[^a-zA-Z0-9]/g, '_')"
                  :player-id="playerId"
                  :repush="pickerRepushToken"
                  :anchor="{ Height: 30, Width: 300 }"
                  :el-style="DROPDOWN_R"
                  :include-none="true"
                  none-label="+ Add a stat..."
                  @update:model-value="addField"
                />
              </Group>
              <!-- IMPORTANT: No static elements inside v-for — Vue hoists them to _cache
                   (build validator enforces this). Mirrors the Properties tab structure. -->
              <template v-for="cat in modCategories(sourceModOf(src.id))" :key="src.id + '-' + cat">
                <Group
                  layout-mode="Left"
                  :anchor="{ Horizontal: 0, Height: 28 }"
                  :padding="{ Left: 0, Top: 4 }"
                >
                  <Label :text="cat" :el-style="S.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
                  <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: S.divider }" />
                </Group>

                <FieldEditor
                  v-for="field in (modFieldsByCategory(sourceModOf(src.id))[cat] || [])"
                  :key="field.id + '-' + resetGeneration"
                  :field="field"
                  :display-value="getDisplayValue(field)"
                  :display-calc-type="getDisplayCalcType(field)"
                  :is-saved="isSaved(field)"
                  :after-reset="editor.afterReset.value"
                  :edit-locked="!canEditStats"
                  @change="onFieldChanged"
                />
              </template>
            </Group>
          </template>
        </Group>

        <!-- Divider -->
        <Common.ContentSeparator :anchor="{ Horizontal: 0, Height: 1 }" />

        <!-- Action Bar -->
        <Group
          layout-mode="Left"
          :anchor="{ Horizontal: 0, Height: 50 }"
          :padding="{ Top: 8, Bottom: 8 }"
        >
          <TextButton
            text="Save"
            :el-style="BTN_PRIMARY"
            :anchor="{ Width: 120, Height: 36 }"
            :padding="{ Horizontal: 24 }"
            @activating="onSave"
          />
          <Group :anchor="{ Width: 8 }" />
          <TextButton
            text="Reset"
            :el-style="canReset ? BTN_SECONDARY : BTN_DISABLED_LOOK"
            :anchor="{ Width: 120, Height: 36 }"
            :padding="{ Horizontal: 24 }"
            @activating="onReset"
          />
          <Group :anchor="{ Width: 8 }" />
          <TextButton
            text="Back"
            :el-style="BTN_SECONDARY"
            :anchor="{ Width: 100, Height: 36 }"
            :padding="{ Horizontal: 24 }"
            @activating="onClose"
          />
          <!-- Flex spacer — pushes status text to right edge (UX S5.8) -->
          <Group :flex-weight="1" />
          <Label
            :text="editor.statusText.value || ' '"
            :el-style="S.status"
            :anchor="{ Height: 36 }"
          />
        </Group>
      </template>
    </Common.DecoratedContainer>

    <!-- ── Unsaved Changes Overlay ────────────────────────────────────
         Always present, toggled via :visible. No structural changes.
         TextButton blocker captures clicks and dims the background.
         Clicking outside the dialog dismisses the overlay (Keep Editing).
         Pattern: Vampirism SkillTree confirmation dialog (production-proven). -->
    <!-- Overlay wrapper — ALWAYS present, NEVER dirty. Uses Full:1 (safe
         because this element never toggles :visible or any reactive prop).
         Children toggle :visible individually with float-safe anchors. -->
    <Group :anchor="{ Full: 1 }">
      <!-- Invisible click blocker (captures clicks outside dialog = Keep Editing) -->
      <TextButton
        :visible="showUnsavedOverlay"
        text=" "
        :el-style="OVERLAY_BLOCKER"
        :anchor="OV_FILL"
        @activating="onKeepEditing"
      />
      <!-- Centering wrapper (CenterMiddle positions dialog in viewport center) -->
      <Group :visible="showUnsavedOverlay" :anchor="OV_FILL" layout-mode="CenterMiddle">
        <!-- Dialog panel -->
        <Group
          layout-mode="Top"
          :anchor="OV_DIALOG"
          :background="OV_DIALOG_BG"
          :padding="OV_DIALOG_PAD"
        >
          <Label
            text="Unsaved Changes"
            :el-style="OVERLAY_TITLE"
            :anchor="OV_TITLE_A"
          />
          <Group :anchor="OV_SPACER_SM" />
          <Label
            :text="overlayMessage"
            :el-style="OVERLAY_MSG"
            :anchor="OV_MSG_A"
          />
          <Group :anchor="OV_SPACER_LG" />
          <Group
            layout-mode="Left"
            :anchor="OV_ROW"
          >
            <TextButton
              text="Save & Close"
              :el-style="BTN_PRIMARY"
              :anchor="OV_SAVE_BTN"
              @activating="onSaveAndClose"
            />
            <Group :anchor="OV_GAP" />
            <TextButton
              text="Discard"
              :el-style="BTN_SECONDARY"
              :anchor="OV_DISCARD_BTN"
              @activating="onDiscard"
            />
            <Group :anchor="OV_GAP" />
            <TextButton
              text="Keep Editing"
              :el-style="BTN_SECONDARY"
              :anchor="OV_KEEP_BTN"
              @activating="onKeepEditing"
            />
          </Group>
        </Group>
      </Group>

      <!-- ── Permission Denied Overlay ───────────────────────────────────
           Backstop for permission revoked mid-session (a save/reset the UI
           believed was allowed) and for clicking a disabled Reset. Same
           never-dirty wrapper + :visible pattern as the unsaved overlay. -->
      <TextButton
        :visible="showPermissionOverlay"
        text=" "
        :el-style="OVERLAY_BLOCKER"
        :anchor="OV_FILL"
        @activating="onPermissionOverlayOk"
      />
      <Group :visible="showPermissionOverlay" :anchor="OV_FILL" layout-mode="CenterMiddle">
        <Group
          layout-mode="Top"
          :anchor="OV_DIALOG"
          :background="OV_DIALOG_BG"
          :padding="OV_DIALOG_PAD"
        >
          <Label
            text="Permission Denied"
            :el-style="OVERLAY_TITLE"
            :anchor="OV_TITLE_A"
          />
          <Group :anchor="OV_SPACER_SM" />
          <Label
            :text="permissionOverlayMessage"
            :el-style="OVERLAY_MSG"
            :anchor="OV_MSG_A"
          />
          <Group :anchor="OV_SPACER_LG" />
          <Group layout-mode="Left" :anchor="OV_ROW">
            <TextButton
              text="OK"
              :el-style="BTN_PRIMARY"
              :anchor="OV_SAVE_BTN"
              @activating="onPermissionOverlayOk"
            />
          </Group>
        </Group>
      </Group>
    </Group>
  </Group>
</template>

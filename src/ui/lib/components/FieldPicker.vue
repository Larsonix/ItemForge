<script setup lang="ts">
/**
 * FieldPicker — shared grouped + searchable field selector.
 *
 * ONE control reused by BOTH the dashboard stat-filter and the batch-stats overlay.
 * Renders a native DropdownBox with the client's built-in search box
 * (ShowSearchInput) and category section-headers (disabled DropdownEntry rows), so a
 * field universe of hundreds stays navigable: browse by category, or type to filter
 * across everything at once.
 *
 * ## Rendering safety
 * DropdownBox + ShowSearchInput / NoItemsText / Disabled are all in DropdownBox's
 * registered prop allow-list (Vuetale Elements.kt:62/66), so — unlike the TextButton
 * hazard — they cannot trigger the "Failed to parse or resolve document" crash. Worst
 * case they degrade to a plain grouped dropdown. Section headers are non-selectable
 * (Disabled) AND guarded in the change handler, so a header can never become a value.
 * The entry set only changes when the catalog changes (a deliberate, infrequent push),
 * never during browsing.
 */
import { computed, onMounted, nextTick, watch } from 'vue'
import type { CatalogField } from '../types/DashboardPayload'

const props = defineProps<{
  /** Currently selected field id (or 'none'). */
  modelValue: string
  /** The catalog to choose from (already scoped/filtered by the parent). */
  fields: CatalogField[]
  anchor: object
  elStyle: object
  /** Prepend a "— None —" entry (dashboard filter uses it; batch overlay does not). */
  includeNone?: boolean
  noneLabel?: string
  /** Stable, dot-free element id — the host pushes Entries to THIS id (data-driven, not children). */
  pickerId: string
  /** Player UUID — required for the host push (UI is per-player). */
  playerId: string
  /** Bump to force a re-push after a structural re-render that would have wiped the pushed entries. */
  repush?: number
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

/**
 * Display order for category groups — gameplay-meaningful groups first, everything
 * else alphabetical. Mirrors the editor's tab ordering so the mental model matches.
 */
const CATEGORY_ORDER = [
  'General', 'Weapon Damage', 'Weapon Stats',
  'Armor Stats', 'Damage Resistance', 'Damage Enhancement', 'Damage Class Enhancement',
  'Knockback Resistance', 'Knockback Enhancement', 'Regeneration',
  'Tool Properties', 'Flight Physics', 'Container', 'Utility Effects',
  'Portal Key', 'Equipment',
]
function catRank(cat: string): number {
  const i = CATEGORY_ORDER.indexOf(cat)
  return i === -1 ? CATEGORY_ORDER.length : i
}

/** Case-insensitive compare — avoids localeCompare (ICU may be absent in Javet's V8). */
function cmp(a: string, b: string): number {
  const x = a.toLowerCase(), y = b.toLowerCase()
  return x < y ? -1 : x > y ? 1 : 0
}

interface Group { category: string; fields: CatalogField[] }

const groups = computed<Group[]>(() => {
  const byCat = new Map<string, CatalogField[]>()
  for (const f of props.fields) {
    const arr = byCat.get(f.category)
    if (arr) arr.push(f)
    else byCat.set(f.category, [f])
  }
  const out: Group[] = []
  for (const [category, fields] of byCat) {
    fields.sort((a, b) => cmp(a.label, b.label))
    out.push({ category, fields })
  }
  out.sort((a, b) => {
    const r = catRank(a.category) - catRank(b.category)
    return r !== 0 ? r : cmp(a.category, b.category)
  })
  return out
})

function onChanged(v: string) {
  // Disabled headers shouldn't be selectable, but guard regardless: a "__hdr_" value
  // is never a real field, so swallow it and leave the current selection intact.
  if (typeof v !== 'string' || v.startsWith('__hdr_')) return
  emit('update:modelValue', v)
}

/**
 * Style for the in-panel search box. REQUIRED whenever ShowSearchInput is true — the
 * client throws "Search input enabled in dropdown box but SearchInputStyle not provided"
 * otherwise.
 *
 * NOTE: the client's own Common.ui uses a raw color Background + icon textures, but in
 * Vuetale a color-string background on a dynamically-rendered (dirty) element renders as
 * a red-X missing-texture placeholder (documented hazard). So we use a texture-PATCH
 * background — the same 'Common/Dropdown.png' the dropdown body uses (known to render) —
 * and skip the optional search/clear icon textures (a second red-X source).
 */
const SEARCH_INPUT_STYLE = {
  Background: { TexturePath: 'Common/Dropdown.png', Border: 16 },
  Anchor: { Height: 32 },
  Padding: { Left: 10, Right: 10 },
  PlaceholderText: 'Search fields...',
  PlaceholderStyle: { TextColor: '#7e93a8', FontSize: 14 },
  Style: { TextColor: '#dde6ee', FontSize: 14 },
}

/**
 * The parent's dropdown style plus the required SearchInputStyle. Merged here so the
 * search style lives with the only component that enables search — other dropdowns
 * (which don't set ShowSearchInput) are unaffected. A fresh object per render is safe:
 * VueBridge dirty-detection compares rendered strings, not references.
 */
const pickerStyle = computed(() => ({ ...props.elStyle, SearchInputStyle: SEARCH_INPUT_STYLE }))

/**
 * Flat entry list in display order, pushed to the native DropdownBox as DATA (one Set command)
 * instead of one <DropdownEntry> element per option. The old child-element approach built one
 * V8 native element per option — the dominant editor/dashboard open cost on heavy modpacks. The
 * native dropdown renders + virtualizes the rows itself from this data. Category dividers are
 * pushed as guarded `__hdr_` entries (the engine entry data has no "disabled" flag) labelled
 * "── Category ──"; onChanged swallows them so a divider can never become a value.
 */
interface PickerEntry { label: string; value: string; tooltip?: string }
const entries = computed<PickerEntry[]>(() => {
  const out: PickerEntry[] = []
  if (props.includeNone) out.push({ label: props.noneLabel || '- None -', value: 'none' })
  for (const g of groups.value) {
    out.push({ label: `-- ${g.category} --`, value: `__hdr_${g.category}` })
    for (const f of g.fields) out.push({ label: f.label, value: f.id })
  }
  return out
})
/** Stringified once; the watch fires only when the entry set actually changes (no-op pushes avoided). */
const entriesJson = computed(() => JSON.stringify(entries.value))

/**
 * Push the entry data to the host-resolved DropdownBox (by [pickerId]). Safe if the element isn't
 * in the render tree yet — the host returns false and we re-push from onMounted / the repush token.
 */
function pushEntries() {
  if (!props.pickerId) return
  globalThis.itemForgeBridge.refreshPickerEntries(props.playerId ?? '', props.pickerId, entriesJson.value)
}

// Push after first mount (DropdownBox is now in the tree), whenever the entry set changes, and
// whenever the parent bumps `repush` (a structural re-render wiped the pushed entries).
onMounted(() => { nextTick(() => pushEntries()) })
watch(entriesJson, () => pushEntries())
watch(() => props.repush, () => pushEntries())
</script>

<template>
  <!-- Child-less: options are host-pushed as a data array (see pushEntries) — NOT one
       <DropdownEntry> element per option. The @value-changed binding lives on the box itself
       (independent of children), so selection still fires. `:id` becomes the host customId. -->
  <DropdownBox
    :id="pickerId"
    :value="modelValue"
    :el-style="pickerStyle"
    :anchor="anchor"
    :show-search-input="true"
    no-items-text="No matching fields"
    @value-changed="onChanged"
  />
</template>

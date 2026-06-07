<script setup lang="ts">
/**
 * Batch Edit Overlay.
 *
 * A modal rendered on top of the dashboard (mounted via v-if from Dashboard.vue —
 * structural show/hide, the safe pattern for a deliberate, infrequent action).
 * Three mutually-exclusive modes:
 *   - 'stat'   : pick a field + operation + value, live-preview, apply to the selection
 *   - 'recipe' : scale recipe inputs or craft time across the selection's recipes
 *   - 'reset'  : confirm removal of ALL overrides for the selection (no preview, no undo)
 *
 * ## Rendering safety (same rules as Dashboard.vue)
 * - Modal appears/disappears via the PARENT's v-if → children are Appended/Removed,
 *   never :visible-toggled, so Anchor sub-props are safe (set once at creation).
 * - Dropdowns use string @value-changed (the only codec-safe event binding).
 * - Value entry uses Core.TextField (vt-skip-update → no dirty churn while typing).
 * - The preview list is the overlay's OWN scroll region; rebuilding it on a new
 *   preview is a deliberate structural change, isolated from the dashboard table.
 * - All button/label styles are static module constants. No reactive bg colors.
 * - Bridge preview methods are synchronous (pure reads); apply is fire-and-forget.
 */
import { ref, computed, watch, onMounted } from 'vue'
import { Common } from '@core/components/Common'
import { Core } from '@core/components/core/index'
import FieldPicker from './FieldPicker.vue'
import type { BatchPreviewRow, RecipePreviewRow, CatalogField } from '../types/DashboardPayload'

/** Cap on rendered preview rows — apply still covers the full selection. Keeps
 *  the table+overlay element count under the ~1000 mount-timeout ceiling. */
const PREVIEW_DISPLAY_CAP = 50

const props = defineProps<{
  kind: 'stat' | 'recipe' | 'reset'
  selectedIds: string[]
  playerId: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  /** Fired after a batch is dispatched. `undoable` controls whether the toast offers Undo. */
  (e: 'applied', message: string, undoable: boolean): void
}>()

// Batchable stat fields are discovered dynamically from the selection via the unified
// fieldCatalog bridge (every field present across the chosen items), then filtered to
// the batch-editable numeric ones — grouped + searchable via the shared FieldPicker.
const fields = ref<CatalogField[]>([])

const OPERATIONS: { id: string; label: string }[] = [
  { id: 'SCALE', label: 'Scale by %' },
  { id: 'SET', label: 'Set to' },
  { id: 'ADD', label: 'Add' },
  { id: 'SUBTRACT', label: 'Subtract' },
]

const RECIPE_TARGETS: { id: string; label: string }[] = [
  { id: 'INPUTS', label: 'Input quantities' },
  { id: 'TIME', label: 'Craft time' },
]

// ── State ──
const fieldId = ref('')
const operation = ref('SCALE')
const recipeTarget = ref('INPUTS')
// Reactive value — drives summaryText + the TextField's initial model-value. Synced
// from valueText ONLY after the debounce fires (see runPreview), never during typing.
const valueStr = ref('')
// Typed value — NON-REACTIVE plain variable. onValueInput writes here so typing
// causes no Vue re-render / Set command / focus reset. Core.TextField manages its
// own display (vt-skip-update), so we never need to push this back to the client.
// Same root cause/strategy as the recipe-input search focus fix.
let valueText = ''

const statRows = ref<BatchPreviewRow[]>([])
const recipeRows = ref<RecipePreviewRow[]>([])
const appliedCount = ref(0)
const skippedCount = ref(0)
const previewError = ref('')

const idsJson = computed(() => JSON.stringify(props.selectedIds))
const count = computed(() => props.selectedIds.length)

const title = computed(() => {
  switch (props.kind) {
    case 'stat': return `Batch Edit - ${count.value} items`
    case 'recipe': return `Batch Recipe - ${count.value} items`
    case 'reset': return `Reset ${count.value} items`
  }
  return 'Batch'
})

// Reset is a plain confirm (warning + buttons, no preview list), so it gets a
// compact dialog. stat/recipe need room for the live preview list. `kind` is fixed
// for the overlay's lifetime → this is evaluated once at mount, never goes dirty,
// so the static-anchor rule (no dirty Anchor sub-props) holds.
const dialogHeight = computed(() => (props.kind === 'reset' ? 240 : 600))

/** SCALE uses a percent ("%") suffix; others use a raw value. */
const valuePlaceholder = computed(() => {
  if (props.kind === 'recipe') return 'Percent, e.g. 80'
  return operation.value === 'SCALE' ? 'Percent, e.g. 80' : 'Value, e.g. 20'
})

const summaryText = computed(() => {
  if (previewError.value) return previewError.value
  if (valueStr.value.trim() === '') return 'Enter a value to preview.'
  return `${appliedCount.value} will change - ${skippedCount.value} skipped`
})

// ── Preview (debounced; bridge preview methods are synchronous) ──
// 1s debounce: runPreview() ends in requestFlush(), and ANY Set command pushed to
// the client resets focus on the active text field (same client behaviour as the
// TopScrolling scroll-reset). A short debounce fires mid-typing and steals focus
// before the value is fully entered; 1s lets the admin finish typing first.
let previewTimer: ReturnType<typeof setTimeout> | null = null

function schedulePreview() {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, 1000)
}

function runPreview() {
  previewError.value = ''
  if (props.kind === 'reset') return
  // Sync the reactive value BEFORE touching the preview rows. Updating statRows can
  // trigger a structural rebuild that recreates the TextField from valueStr — without
  // this sync first, the rebuilt field would show the stale value. Safe here: the user
  // stopped typing 1s ago, so focus stability no longer matters.
  valueStr.value = valueText
  if (valueStr.value.trim() === '') { statRows.value = []; recipeRows.value = []; appliedCount.value = 0; skippedCount.value = 0; return }
  try {
    const bridge = globalThis.dashboardBridge
    if (props.kind === 'stat') {
      const resp = JSON.parse(bridge.batchPreview(props.playerId, idsJson.value, fieldId.value, operation.value, valueStr.value))
      if (!resp.success) { previewError.value = resp.error || 'Preview failed'; statRows.value = []; return }
      statRows.value = resp.rows || []
      appliedCount.value = resp.appliedCount || 0
      skippedCount.value = resp.skippedCount || 0
    } else {
      const resp = JSON.parse(bridge.batchRecipePreview(props.playerId, idsJson.value, valueStr.value, recipeTarget.value))
      if (!resp.success) { previewError.value = resp.error || 'Preview failed'; recipeRows.value = []; return }
      recipeRows.value = resp.rows || []
      appliedCount.value = resp.appliedCount || 0
      skippedCount.value = resp.skippedCount || 0
    }
  } catch (e) {
    previewError.value = 'Preview failed'
  }
  globalThis.itemForgeBridge.requestFlush()
}

// Re-preview when a dropdown changes (uses the live typed value, not the debounced one).
watch([fieldId, operation, recipeTarget], () => { if (valueText.trim() !== '') runPreview() })

function onValueInput(v: string) {
  valueText = v   // NON-REACTIVE — no Vue re-render, no focus loss (even on the first char)
  schedulePreview()
}

// ── Apply ──
function onApply() {
  const bridge = globalThis.dashboardBridge
  if (props.kind === 'reset') {
    bridge.batchReset(props.playerId, idsJson.value)
    emit('applied', `Reset ${count.value} items`, false)
    return
  }
  if (valueText.trim() === '') return
  if (props.kind === 'stat') {
    if (appliedCount.value === 0) return
    bridge.batchApply(props.playerId, idsJson.value, fieldId.value, operation.value, valueText)
    const f = fields.value.find(x => x.id === fieldId.value)
    emit('applied', `${f?.label ?? fieldId.value} - ${appliedCount.value} items`, true)
  } else {
    if (appliedCount.value === 0) return
    bridge.batchRecipeScale(props.playerId, idsJson.value, valueText, recipeTarget.value)
    emit('applied', `Recipe scale - ${appliedCount.value} items`, true)
  }
}

function onCancel() { emit('close') }

// Capped views — render at most PREVIEW_DISPLAY_CAP rows; apply covers the full set.
const displayStatRows = computed(() => statRows.value.slice(0, PREVIEW_DISPLAY_CAP))
const displayRecipeRows = computed(() => recipeRows.value.slice(0, PREVIEW_DISPLAY_CAP))
const statOverflow = computed(() => Math.max(0, statRows.value.length - PREVIEW_DISPLAY_CAP))
const recipeOverflow = computed(() => Math.max(0, recipeRows.value.length - PREVIEW_DISPLAY_CAP))

/** Fetch the field catalog for the selection and keep the batch-editable ones (stat mode). */
function loadFields() {
  try {
    const resp = JSON.parse(globalThis.dashboardBridge.fieldCatalog(idsJson.value))
    if (resp.success && Array.isArray(resp.fields)) {
      // The batch engine writes numeric fields only — drop filter-only entries
      // (e.g. normalized weapon damage) and any non-numeric fields.
      const editable = (resp.fields as CatalogField[]).filter(f => f.batchEditable)
      fields.value = editable
      if (editable.length === 0) {
        previewError.value = 'No editable numeric fields in this selection'
      } else if (!editable.find(f => f.id === fieldId.value)) {
        fieldId.value = editable[0].id
      }
    } else {
      fields.value = []
      previewError.value = resp.error || 'No editable numeric fields in this selection'
    }
  } catch (e) {
    previewError.value = 'Could not load fields'
  }
}

onMounted(() => {
  if (props.kind === 'stat') loadFields()
  globalThis.itemForgeBridge.requestFlush()
})

// ── Styles (static module constants) ──
const SCROLLBAR = {
  Spacing: 6, Size: 6,
  Background: { TexturePath: 'Common/Scrollbar.png', Border: 3 },
  Handle: { TexturePath: 'Common/ScrollbarHandle.png', Border: 3 },
  HoveredHandle: { TexturePath: 'Common/ScrollbarHandleHovered.png', Border: 3 },
  DraggedHandle: { TexturePath: 'Common/ScrollbarHandleDragged.png', Border: 3 },
}
const BACKDROP = {
  Default: { Background: { Color: '#0a0e14cc' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Hovered: { Background: { Color: '#0a0e14cc' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
  Pressed: { Background: { Color: '#0a0e14cc' }, LabelStyle: { FontSize: 1, TextColor: '#00000001' } },
}
const DROPDOWN_STYLE = {
  DefaultBackground: { TexturePath: 'Common/Dropdown.png', Border: 16 },
  HoveredBackground: { TexturePath: 'Common/DropdownHovered.png', Border: 16 },
  PressedBackground: { TexturePath: 'Common/DropdownPressed.png', Border: 16 },
  DefaultArrowTexturePath: 'Common/DropdownCaret.png',
  HoveredArrowTexturePath: 'Common/DropdownCaret.png',
  PressedArrowTexturePath: 'Common/DropdownPressedCaret.png',
  ArrowWidth: 16, ArrowHeight: 22,
  LabelStyle: { TextColor: '#b7cedd', VerticalAlignment: 'Center', FontSize: 15 },
  EntryLabelStyle: { TextColor: '#b7cedd', VerticalAlignment: 'Center', FontSize: 15 },
  SelectedEntryLabelStyle: { TextColor: '#d4a844', VerticalAlignment: 'Center', FontSize: 15, RenderBold: true },
  HorizontalPadding: 8,
  PanelBackground: { TexturePath: 'Common/DropdownBox.png', Border: 16 },
  PanelScrollbarStyle: SCROLLBAR,
  PanelWidth: 280, PanelPadding: 6, PanelAlign: 'Left', PanelOffset: 7,
  EntryHeight: 36, EntriesInViewport: 8, HorizontalEntryPadding: 8,
  HoveredEntryBackground: { Color: '#0a0f17' },
  PressedEntryBackground: { Color: '#0f1621' },
  FocusOutlineSize: 1, FocusOutlineColor: '#ffffff(0.4)',
}
const BTN_PRIMARY = {
  Default: { Background: { TexturePath: 'Common/Buttons/Primary.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#1a1208', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Primary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#1a1208', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Primary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#1a1208', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_SECONDARY = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#bdcbd3', RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_DANGER = {
  Default: { Background: { TexturePath: 'Common/Buttons/Primary.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#3a0e0e', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Primary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#3a0e0e', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Primary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 17, TextColor: '#3a0e0e', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const L = {
  field:     { FontSize: 15, TextColor: '#667788', VerticalAlignment: 'Center' },
  summary:   { FontSize: 16, TextColor: '#96a9be', VerticalAlignment: 'Center' },
  rowName:   { FontSize: 15, TextColor: '#dde6ee', VerticalAlignment: 'Center' },
  rowChange: { FontSize: 15, TextColor: '#7fd18b', VerticalAlignment: 'Center', HorizontalAlignment: 'End' },
  rowSkip:   { FontSize: 14, TextColor: '#556677', VerticalAlignment: 'Center', HorizontalAlignment: 'End' },
  resetWarn: { FontSize: 17, TextColor: '#d49a44', HorizontalAlignment: 'Center', VerticalAlignment: 'Center' },
}

function fmt(v: number | null): string {
  if (v == null) return '-'
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}
/** One-line change summary for a recipe row (joins its input/time changes). */
function recipeChangeText(row: RecipePreviewRow): string {
  if (row.skipped) return row.reason || 'skipped'
  return row.changes.map(c => `${fmt(c.oldValue)} -> ${fmt(c.newValue)}`).join(', ')
}
</script>

<template>
  <Group :anchor="{ Horizontal: 0, Vertical: 0 }">
    <!-- Dim backdrop — click to dismiss. Static color, never dirty. -->
    <TextButton text=" " :el-style="BACKDROP" :anchor="{ Horizontal: 0, Vertical: 0 }" @activating="onCancel" />

    <!-- Centered dialog — Width/Height with no edge anchors centers in the parent
         (same as the dashboard's DecoratedContainer). 'HorizontalAlignment' is NOT
         a valid Anchor key (it's a LabelStyle key) — using it would crash the client. -->
    <Group :anchor="{ Width: 720, Height: dialogHeight }">
      <Common.DecoratedContainer
        :anchor="{ Horizontal: 0, Vertical: 0 }"
        :content-padding="{ Top: 8, Left: 20, Right: 20, Bottom: 16 }"
      >
        <template #title>
          <Common.Title :text="title" />
        </template>

        <template #content>
          <!-- ── Controls ── -->
          <Group
            v-if="kind === 'stat'"
            layout-mode="Left"
            :anchor="{ Horizontal: 0, Height: 46 }"
            :padding="{ Bottom: 6 }"
          >
            <Label text="Field" :el-style="L.field" :anchor="{ Width: 50, Height: 38 }" />
            <FieldPicker
              :model-value="fieldId"
              :fields="fields"
              picker-id="pk_batchfield"
              :player-id="playerId"
              :el-style="DROPDOWN_STYLE"
              :anchor="{ Width: 240, Height: 38 }"
              @update:model-value="(v: string) => { fieldId = v }"
            />
            <Group :anchor="{ Width: 10 }" />
            <DropdownBox
              :value="operation"
              :el-style="DROPDOWN_STYLE"
              :anchor="{ Width: 150, Height: 38 }"
              @value-changed="(v: string) => { operation = v }"
            >
              <DropdownEntry v-for="o in OPERATIONS" :key="o.id" :value="o.id" :text="o.label" />
            </DropdownBox>
            <Group :anchor="{ Width: 10 }" />
            <Core.TextField
              :model-value="valueStr"
              :placeholder-text="valuePlaceholder"
              :anchor="{ Width: 150, Height: 38 }"
              @update:model-value="onValueInput"
            />
          </Group>

          <Group
            v-if="kind === 'recipe'"
            layout-mode="Left"
            :anchor="{ Horizontal: 0, Height: 46 }"
            :padding="{ Bottom: 6 }"
          >
            <Label text="Scale" :el-style="L.field" :anchor="{ Width: 50, Height: 38 }" />
            <DropdownBox
              :value="recipeTarget"
              :el-style="DROPDOWN_STYLE"
              :anchor="{ Width: 240, Height: 38 }"
              @value-changed="(v: string) => { recipeTarget = v }"
            >
              <DropdownEntry v-for="t in RECIPE_TARGETS" :key="t.id" :value="t.id" :text="t.label" />
            </DropdownBox>
            <Group :anchor="{ Width: 10 }" />
            <Core.TextField
              :model-value="valueStr"
              :placeholder-text="valuePlaceholder"
              :anchor="{ Width: 150, Height: 38 }"
              @update:model-value="onValueInput"
            />
          </Group>

          <!-- ── Reset confirmation (no preview) ── -->
          <Group v-if="kind === 'reset'" :flex-weight="1" :anchor="{ Horizontal: 0 }">
            <Label
              :text="`Remove ALL overrides from ${count} selected items? This reverts them to their original values and cannot be undone.`"
              :el-style="L.resetWarn"
              :anchor="{ Horizontal: 0, Vertical: 0 }"
            />
          </Group>

          <!-- ── Summary ── -->
          <Group v-if="kind !== 'reset'" layout-mode="Left" :anchor="{ Horizontal: 0, Height: 28 }">
            <Label :text="summaryText || ' '" :el-style="L.summary" :anchor="{ Horizontal: 0, Height: 24 }" />
          </Group>

          <Common.ContentSeparator v-if="kind !== 'reset'" :anchor="{ Horizontal: 0, Height: 1 }" />

          <!-- ── Preview list ── -->
          <Group
            v-if="kind !== 'reset'"
            layout-mode="TopScrolling"
            :flex-weight="1"
            :anchor="{ Horizontal: 0 }"
            :scrollbar-style="SCROLLBAR"
          >
            <!-- Stat preview rows (display-capped; apply covers all) -->
            <Group v-if="kind === 'stat'" layout-mode="Top" :anchor="{ Horizontal: 0 }">
              <template v-for="row in displayStatRows" :key="row.itemId">
                <Group :anchor="{ Horizontal: 0, Height: 32 }">
                  <Label :text="row.itemName || ' '" :el-style="L.rowName" :anchor="{ Left: 4, Right: 260, Height: 32 }" />
                  <Label
                    :text="row.skipped ? (row.reason || 'skipped') : (fmt(row.oldValue) + '  ->  ' + fmt(row.newValue))"
                    :el-style="row.skipped ? L.rowSkip : L.rowChange"
                    :anchor="{ Right: 8, Width: 240, Height: 32 }"
                  />
                </Group>
                <Group :visible="!!row.itemId" :anchor="{ Horizontal: 0, Height: 1 }" :background="{ Color: '#1a2030' }" />
              </template>
              <Label v-if="statOverflow > 0" :text="`... and ${statOverflow} more (all will be applied)`"
                :el-style="L.rowSkip" :anchor="{ Horizontal: 0, Height: 30 }" />
            </Group>

            <!-- Recipe preview rows (display-capped; apply covers all) -->
            <Group v-if="kind === 'recipe'" layout-mode="Top" :anchor="{ Horizontal: 0 }">
              <template v-for="row in displayRecipeRows" :key="row.itemId">
                <Group :anchor="{ Horizontal: 0, Height: 32 }">
                  <Label :text="row.itemName || ' '" :el-style="L.rowName" :anchor="{ Left: 4, Right: 300, Height: 32 }" />
                  <Label
                    :text="recipeChangeText(row)"
                    :el-style="row.skipped ? L.rowSkip : L.rowChange"
                    :anchor="{ Right: 8, Width: 280, Height: 32 }"
                  />
                </Group>
                <Group :visible="!!row.itemId" :anchor="{ Horizontal: 0, Height: 1 }" :background="{ Color: '#1a2030' }" />
              </template>
              <Label v-if="recipeOverflow > 0" :text="`... and ${recipeOverflow} more (all will be applied)`"
                :el-style="L.rowSkip" :anchor="{ Horizontal: 0, Height: 30 }" />
            </Group>
          </Group>

          <Common.ContentSeparator :anchor="{ Horizontal: 0, Height: 1 }" />

          <!-- ── Buttons ── -->
          <Group layout-mode="Left" :anchor="{ Horizontal: 0, Height: 52 }" :padding="{ Top: 8 }">
            <Group :flex-weight="1" />
            <TextButton text="Cancel" :el-style="BTN_SECONDARY" :anchor="{ Width: 130, Height: 40 }" @activating="onCancel" />
            <Group :anchor="{ Width: 10 }" />
            <TextButton
              v-if="kind === 'reset'"
              text="Reset All"
              :el-style="BTN_DANGER"
              :anchor="{ Width: 160, Height: 40 }"
              @activating="onApply"
            />
            <TextButton
              v-if="kind !== 'reset'"
              text="Apply"
              :el-style="BTN_PRIMARY"
              :anchor="{ Width: 160, Height: 40 }"
              @activating="onApply"
            />
          </Group>
        </template>
      </Common.DecoratedContainer>
    </Group>
  </Group>
</template>

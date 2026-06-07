<script setup lang="ts">
/**
 * Renders a mod-built editor panel — a list of declarative [EditorComponent]s —
 * using ItemForge's own themed widgets, so a mod chooses *what* (fields, dropdowns, toggles,
 * buttons, sections) while the look stays consistent with the vanilla editor.
 *
 * Rendering safety (mirrors Editor.vue's proven patterns):
 * - Text/number/integer fields use Core.TextField (vt-skip-update → no dirtying while typing).
 * - Dropdowns use native DropdownBox @value-changed (string value → StringCodec-safe).
 * - Buttons/toggles are native TextButton with Background in all 4 states (required, else the
 *   client rejects the AppendInline). All el-styles are static module-level constants.
 * - Field/button labels never change style reactively (no scroll-reset hazard).
 * - The panel renders once; the parent toggles its container's :visible by selected source and
 *   bumps a :key (resetGeneration) on a server re-push so values refresh cleanly.
 */
import { Core } from '@core/components/core/index'
import type { EditorComponent } from '../types/EditorPayload'

const props = defineProps<{
  components: EditorComponent[]
  /** When true, inputs render read-only (e.g. the viewer lacks edit permission). */
  editLocked: boolean
  /** Field id → pending (unsaved) value, owned by the parent's editor state. */
  pending: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'change', fieldId: string, value: unknown): void
  (e: 'action', actionId: string): void
}>()

// ── Themed styles (match Editor.vue) ─────────────────────────────────────
const ST = {
  label:   { FontSize: 14, TextColor: '#96a9be', VerticalAlignment: 'Center' },
  section: { FontSize: 14, TextColor: '#d4a844' },
  note:    { FontSize: 12, TextColor: '#667788', VerticalAlignment: 'Center' },
  divider: '#2b3542',
}
const BOOL_BTN = {
  Default:  { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered:  { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed:  { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_SECONDARY = {
  Default:  { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 15, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered:  { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 15, TextColor: '#dde6ee', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed:  { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 15, TextColor: '#dde6ee', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 15, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const BTN_PRIMARY = {
  Default:  { Background: { TexturePath: 'Common/Buttons/Primary.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 15, TextColor: '#bfcdd5', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered:  { Background: { TexturePath: 'Common/Buttons/Primary_Hovered.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 15, TextColor: '#bfcdd5', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed:  { Background: { TexturePath: 'Common/Buttons/Primary_Pressed.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 15, TextColor: '#bfcdd5', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', VerticalBorder: 12, HorizontalBorder: 80 }, LabelStyle: { FontSize: 15, TextColor: '#797b7c', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}
const DROPDOWN = {
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
  PanelWidth: 240, PanelPadding: 6, PanelAlign: 'Right', PanelOffset: 7,
  EntryHeight: 31, EntriesInViewport: 8, HorizontalEntryPadding: 7,
  HoveredEntryBackground: { Color: '#0a0f17' },
  PressedEntryBackground: { Color: '#0f1621' },
  FocusOutlineSize: 1, FocusOutlineColor: '#ffffff(0.4)',
}

// ── Value helpers ─────────────────────────────────────────────────────────

/** Current display value: pending (unsaved) edit wins over the component's server value. */
function displayValue(c: EditorComponent): unknown {
  if (c.id != null && props.pending[c.id] !== undefined) return props.pending[c.id]
  return c.value
}
function displayString(c: EditorComponent): string {
  const v = displayValue(c)
  return v == null ? '' : String(v)
}
function displayBool(c: EditorComponent): boolean {
  return displayValue(c) === true
}

// ── Input handlers ──────────────────────────────────────────────────────────
function onText(c: EditorComponent, v: string) {
  if (props.editLocked || c.id == null) return
  emit('change', c.id, v)
}
function onNumber(c: EditorComponent, v: string) {
  if (props.editLocked || c.id == null) return
  const n = parseFloat(v)
  if (isNaN(n)) return
  emit('change', c.id, n)
}
function onInteger(c: EditorComponent, v: string) {
  if (props.editLocked || c.id == null) return
  const n = parseInt(v, 10)
  if (isNaN(n)) return
  emit('change', c.id, n)
}
function onToggle(c: EditorComponent) {
  if (props.editLocked || c.id == null) return
  emit('change', c.id, !displayBool(c))
}
function onDropdown(c: EditorComponent, v: string) {
  if (props.editLocked || c.id == null) return
  emit('change', c.id, v)
}
function onButton(c: EditorComponent) {
  if (props.editLocked || c.id == null) return
  emit('action', c.id)
}
</script>

<template>
  <Group layout-mode="Top" :anchor="{ Horizontal: 0 }">
    <template v-for="(c, idx) in components" :key="(c.id || 'c') + '-' + idx">
      <!-- Section header -->
      <Group
        v-if="c.kind === 'section'"
        layout-mode="Left"
        :anchor="{ Horizontal: 0, Height: 28 }"
        :padding="{ Left: 0, Top: 4 }"
      >
        <Label :text="c.label || ' '" :el-style="ST.section" :anchor="{ Height: 20 }" :padding="{ Right: 8 }" />
        <Group :flex-weight="1" :anchor="{ Height: 1 }" :background="{ Color: ST.divider }" />
      </Group>

      <!-- Read-only note / label -->
      <Label
        v-else-if="c.kind === 'label'"
        :text="c.label || ' '"
        :el-style="ST.note"
        :anchor="{ Horizontal: 0, Height: 20 }"
        :padding="{ Left: 4, Top: 2, Bottom: 2 }"
      />

      <!-- Spacer -->
      <Group v-else-if="c.kind === 'spacer'" :anchor="{ Horizontal: 0, Height: 8 }" />

      <!-- Button (action) -->
      <Group
        v-else-if="c.kind === 'button'"
        layout-mode="Left"
        :anchor="{ Horizontal: 0, Height: 42 }"
        :padding="{ Left: 4, Top: 6, Bottom: 2 }"
      >
        <TextButton
          :text="c.label || ' '"
          :el-style="c.style === 'primary' ? BTN_PRIMARY : BTN_SECONDARY"
          :anchor="{ Width: 180, Height: 32 }"
          @activating="onButton(c)"
        />
      </Group>

      <!-- Field row: label + input -->
      <Group
        v-else
        layout-mode="Left"
        :anchor="{ Horizontal: 0, Height: 36 }"
        :padding="{ Left: 4, Right: 4 }"
      >
        <Label :text="c.label || ' '" :el-style="ST.label" :anchor="{ Width: 200, Height: 36 }" />
        <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">
          <!-- Dropdown -->
          <DropdownBox
            v-if="c.inputType === 'dropdown'"
            :value="displayString(c)"
            :el-style="DROPDOWN"
            :anchor="{ Horizontal: 0, Height: 28 }"
            @value-changed="(v: string) => onDropdown(c, v)"
          >
            <DropdownEntry
              v-for="opt in (c.options || [])"
              :key="opt"
              :value="opt"
              :text="opt"
            />
          </DropdownBox>

          <!-- Toggle -->
          <TextButton
            v-else-if="c.inputType === 'toggle'"
            :text="displayBool(c) ? 'Yes' : 'No'"
            :el-style="BOOL_BTN"
            :anchor="{ Width: 80, Height: 28 }"
            @activating="onToggle(c)"
          />

          <!-- Integer -->
          <Core.TextField
            v-else-if="c.inputType === 'integer'"
            :model-value="displayString(c)"
            :is-read-only="editLocked || !!c.readOnly"
            :anchor="{ Horizontal: 0, Height: 28 }"
            @update:model-value="(v: string) => onInteger(c, v)"
          />

          <!-- Number -->
          <Core.TextField
            v-else-if="c.inputType === 'number'"
            :model-value="displayString(c)"
            :is-read-only="editLocked || !!c.readOnly"
            :anchor="{ Horizontal: 0, Height: 28 }"
            @update:model-value="(v: string) => onNumber(c, v)"
          />

          <!-- Text (default) -->
          <Core.TextField
            v-else
            :model-value="displayString(c)"
            :is-read-only="editLocked || !!c.readOnly"
            :anchor="{ Horizontal: 0, Height: 28 }"
            @update:model-value="(v: string) => onText(c, v)"
          />
        </Group>
      </Group>
    </template>
  </Group>
</template>

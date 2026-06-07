<script setup lang="ts">
/**
 * Universal field editor component.
 *
 * Input strategy (VUETALE_API_VERIFIED.md §1.2 — StringCodec constraint):
 *
 * VuetaleEventData.CODEC uses StringCodec for @Value. Only string-valued
 * elements work with @value-changed. NumberField (number) and CheckBox
 * (boolean) crash the codec. Safe patterns:
 *
 * - DOUBLE/INTEGER → Core.TextField v-model (string value, Vue-internal event)
 * - BOOLEAN → SmallSecondaryTextButton toggle (button @activating, no @Value)
 * - STRING with options → native DropdownBox @value-changed (string value, OK)
 * - STRING without options → Common.TextField @value-changed (string value, OK)
 * - CalculationType → native DropdownBox @value-changed (string value, OK)
 *
 * ## Modifier Fields
 *
 * Stat modifier entries (field.calculationType !== null) show two inputs:
 * [Label: 200px] [Amount: flex] [CalcType: 140px] [was: 90px]
 *
 * Both inputs emit composite change values {amount, calculationType} so the
 * Kotlin save flow can reconstruct the BSON modifier array. Non-modifier
 * fields emit simple scalar values as before.
 *
 * ## Non-Modifier Fields
 *
 * [Label: 200px] [Input: flex] [was: 90px]
 *
 * ## Visual Feedback Strategy (Scroll-Safe)
 *
 * NO reactive visual changes during typing — this prevents dirty elements
 * inside TopScrolling which cause scroll reset on every keystroke.
 *
 * Visual feedback is deferred to Save:
 * - `isSaved` prop → gold label + "was: X" for fields saved this session
 * - `isModified` (from server state) → gold label + "was: X" for previously saved fields
 * - During typing: no gold, no "was:", no visual change inside scroll area
 *
 * See SCROLL_RESET_INVESTIGATION.md for full root cause analysis.
 */
import { computed } from 'vue'
import { Common } from '@core/components/Common'
import { Core } from '@core/components/core/index'
import type { FieldDef } from '../types/FieldDefinition'

const props = defineProps<{
  field: FieldDef
  displayValue: number | boolean | string | null
  /** Effective CalculationType for modifier fields. null for non-modifier fields. */
  displayCalcType: string | null
  /** Whether this field was saved during the current editing session. */
  isSaved: boolean
  afterReset: boolean
  /**
   * Whether editing is locked by permission. When true, inputs
   * render read-only regardless of the field's own readOnly flag. Static for
   * the session — never toggles reactively, so it introduces no scroll-reset risk.
   */
  editLocked?: boolean
}>()

const emit = defineEmits<{
  change: [fieldId: string, newValue: unknown]
}>()

// ── Computed ─────────────────────────────────────────────────────────────

const isModified = computed(() => !props.afterReset && props.field.state === 'MODIFIED')

/** Whether to show the "saved" visual state (gold label).
 *  Suppressed during afterReset (fields are reverting to defaults). */
const isSaved = computed(() => !props.afterReset && props.isSaved)

/**
 * Label style — gold for modified/saved fields, dim for not-set, normal otherwise.
 * Computed to avoid inline ternary precedence issues in the template.
 */
const labelStyle = computed(() => {
  // Locked by permission (whole-tab view-only) OR per-item scope greying → dim, so the admin can
  // see at a glance which fields can't be edited in this scope. NOT triggered by intrinsic
  // field.readOnly, so normal global editing is unchanged. editLocked is static for the session
  // (recreated via resetGeneration on a scope/source switch) → never toggles on a scrolled element.
  if (props.editLocked) return LABEL_STYLE_LOCKED
  if (isModified.value || isSaved.value) return LABEL_STYLE_MODIFIED
  if (props.field.isNotSet) return LABEL_STYLE_NOT_SET
  return LABEL_STYLE
})

/** Whether this is a stat modifier entry (has CalculationType) */
const isModifier = computed(() => props.field.calculationType !== null)

/**
 * Effective CalculationType for the dropdown display.
 * Falls back through: displayCalcType prop → field.calculationType → first option.
 * Never null/undefined — dropdown always has a selected value (Vuetale safety).
 */
const currentCalcType = computed(() =>
  props.displayCalcType ?? props.field.calculationType ?? calcTypeOptions.value[0]
)

/**
 * Dropdown options per modifier system (from decompiled source):
 * - StaticModifier (StatModifiers, DamageEnhancement, DamageClassEnhancement): Additive, Multiplicative
 * - ResistanceModifier (DamageResistance): Flat, Percent
 *
 * Source: StaticModifier.java:107-119, ResistanceModifier.java:80-83
 */
const RESISTANCE_CALC_TYPES = ['Flat', 'Percent']
const STATIC_CALC_TYPES = ['Additive', 'Multiplicative']

const calcTypeOptions = computed(() => {
  const ct = props.field.calculationType
  if (ct === 'Flat' || ct === 'Percent') return RESISTANCE_CALC_TYPES
  return STATIC_CALC_TYPES
})

/**
 * "was: X" indicator text.
 *
 * Shows what the value was before the current state:
 * - isSaved: shows field.currentValue (the server value before this editing session)
 * - isModified (from server): shows field.originalValue (the vanilla value)
 * - Neither: no indicator
 *
 * isSaved takes priority — it's more recent and more relevant feedback.
 */
const wasText = computed(() => {
  // After reset, suppress "was:" — values already show the reverted originals
  if (props.afterReset) return null
  // Saved this session — show what it was before
  if (isSaved.value) {
    if (props.field.currentValue != null) return `was: ${props.field.currentValue}`
    if (props.field.isNotSet) return 'was: Not set'
  }
  // Previously modified on server — show vanilla value
  if (isModified.value && props.field.originalValue != null)
    return `was: ${props.field.originalValue}`
  return null
})

const isDisabled = computed(() => props.field.readOnly || !!props.editLocked)

/** String display for Core.TextField — numbers shown as text */
const displayString = computed(() => String(props.displayValue ?? ''))

/** Boolean toggle button text — shows "Not Set" for null unset fields */
const boolText = computed(() => {
  if (props.field.isNotSet && props.displayValue == null) return 'Not Set'
  return props.displayValue === true ? 'Yes' : 'No'
})

// ── Event Handlers ───────────────────────────────────────────────────────

/**
 * Core.TextField v-model emits string — parse to number.
 * For modifier fields, emits composite {amount, calculationType}.
 * For simple fields, emits scalar number.
 */
function onNumberInput(value: string) {
  const n = props.field.valueType === 'INTEGER' ? parseInt(value, 10) : parseFloat(value)
  if (isNaN(n)) return

  if (isModifier.value) {
    emit('change', props.field.id, { amount: n, calculationType: currentCalcType.value })
  } else {
    emit('change', props.field.id, n)
  }
}

/**
 * CalculationType dropdown changed — emits composite with new calcType.
 * Uses the current display amount (from prop) + the new calcType.
 */
function onCalcTypeChanged(value: string) {
  const amount = typeof props.displayValue === 'number' ? props.displayValue : 0
  emit('change', props.field.id, { amount, calculationType: value })
}

/** Toggle boolean via button @activating — proven safe pattern */
function onBoolToggle() {
  emit('change', props.field.id, !(props.displayValue === true))
}

function onDropdownChanged(value: string) {
  emit('change', props.field.id, value)
}

function onTextChanged(value: string) {
  emit('change', props.field.id, value)
}

// ── Styles ───────────────────────────────────────────────────────────────

/**
 * Label styles — module-level statics for VueBridge safety.
 *
 * Modified fields use gold text (UX S5.5: "visual distinction for modified fields").
 * We use label color instead of Background.Color because:
 * - PatchStyle.Color is ValueCodec.STRING — parsed client-side
 * - ColorParseUtil only supports #RGB/#RRGGBB/#RGBA/#RRGGBBAA/rgb()/rgba()
 * - The #rrggbb(alpha) notation is NOT in ColorParseUtil (only used by
 *   FocusOutlineColor which has a separate client-side parser)
 * - A "transparent" fallback color risks rendering as solid black on all
 *   unmodified fields if the client ignores alpha for Background.Color
 *
 * Gold label + gold "was: X" text = clear, safe, proven indicator.
 */
const LABEL_STYLE = { FontSize: 14, TextColor: '#96a9be', VerticalAlignment: 'Center' }
const LABEL_STYLE_MODIFIED = { FontSize: 14, TextColor: '#d4a844', VerticalAlignment: 'Center' }
const LABEL_STYLE_NOT_SET = { FontSize: 14, TextColor: '#556677', VerticalAlignment: 'Center' }
/** Dim label style for a locked field (can't be edited in this scope). */
const LABEL_STYLE_LOCKED = { FontSize: 14, TextColor: '#566273', VerticalAlignment: 'Center' }
/** Dim input text style for a locked text/number field — the field keeps its box but the value
 *  renders muted (no disabled input texture exists), so it reads as greyed-out yet still a control. */
const LOCKED_INPUT_STYLE = { TextColor: '#566273' }
const SMALL_LABEL_STYLE = { FontSize: 12, TextColor: '#667788' }
const MODIFIED_LABEL_STYLE = { FontSize: 12, TextColor: '#d4a844', VerticalAlignment: 'Center' }
const NOT_SET_HINT_STYLE = { FontSize: 11, TextColor: '#556677' }

/**
 * Static button style for boolean toggles — uses native TextButton instead of
 * Common.SmallSecondaryTextButton. Common wrappers recreate Style objects on
 * every Vue re-render, causing VueBridge false-positive dirty detection →
 * full 7500-line UI re-renders that crash the client.
 */
const BOOL_BUTTON_STYLE = {
  Default: { Background: { TexturePath: 'Common/Buttons/Secondary.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Hovered: { Background: { TexturePath: 'Common/Buttons/Secondary_Hovered.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Pressed: { Background: { TexturePath: 'Common/Buttons/Secondary_Pressed.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
  Disabled: { Background: { TexturePath: 'Common/Buttons/Disabled.png', Border: 12 }, LabelStyle: { FontSize: 14, TextColor: '#bdcbd3', RenderBold: true, RenderUppercase: true, HorizontalAlignment: 'Center', VerticalAlignment: 'Center' } },
}

const SCROLLBAR = {
  Spacing: 6, Size: 6,
  Background: { TexturePath: 'Common/Scrollbar.png', Border: 3 },
  Handle: { TexturePath: 'Common/ScrollbarHandle.png', Border: 3 },
  HoveredHandle: { TexturePath: 'Common/ScrollbarHandleHovered.png', Border: 3 },
  DraggedHandle: { TexturePath: 'Common/ScrollbarHandleDragged.png', Border: 3 },
}

/** Static dropdown style — used for both enum string fields and CalculationType */
const DROPDOWN_STYLE = {
  DefaultBackground: { TexturePath: 'Common/Dropdown.png', Border: 16 },
  HoveredBackground: { TexturePath: 'Common/DropdownHovered.png', Border: 16 },
  PressedBackground: { TexturePath: 'Common/DropdownPressed.png', Border: 16 },
  DefaultArrowTexturePath: 'Common/DropdownCaret.png',
  HoveredArrowTexturePath: 'Common/DropdownCaret.png',
  PressedArrowTexturePath: 'Common/DropdownPressedCaret.png',
  ArrowWidth: 13, ArrowHeight: 18,
  LabelStyle: { TextColor: '#96a9be', RenderUppercase: true, VerticalAlignment: 'Center', FontSize: 13 },
  EntryLabelStyle: { TextColor: '#b7cedd', RenderUppercase: true, VerticalAlignment: 'Center', FontSize: 13 },
  SelectedEntryLabelStyle: { TextColor: '#b7cedd', RenderUppercase: true, VerticalAlignment: 'Center', FontSize: 13, RenderBold: true },
  HorizontalPadding: 8,
  PanelBackground: { TexturePath: 'Common/DropdownBox.png', Border: 16 },
  PanelScrollbarStyle: SCROLLBAR,
  PanelWidth: 200, PanelPadding: 6, PanelAlign: 'Right', PanelOffset: 7,
  EntryHeight: 31, EntriesInViewport: 10, HorizontalEntryPadding: 7,
  HoveredEntryBackground: { Color: '#0a0f17' },
  PressedEntryBackground: { Color: '#0f1621' },
  FocusOutlineSize: 1, FocusOutlineColor: '#ffffff(0.4)',
}

/** Locked variant of the dropdown style — dim selected-value label so a non-editable dropdown
 *  (e.g. Quality in "This Item" scope) reads as greyed. Static object built once at module load. */
const DROPDOWN_STYLE_LOCKED = {
  ...DROPDOWN_STYLE,
  LabelStyle: { ...DROPDOWN_STYLE.LabelStyle, TextColor: '#566273' },
}
</script>

<template>
  <Group layout-mode="Top" :anchor="{ Horizontal: 0 }">
    <!-- Field Row: Label | Input | [CalcType] | "was: X" -->
    <Group
      layout-mode="Left"
      :anchor="{ Horizontal: 0, Height: 36 }"
      :padding="{ Left: 4, Right: 4 }"
    >
      <!-- Label — tooltip via v-bind spread to avoid passing undefined.
           VueBridge.patchProp sets hasStructuralChanges=true for ANY null/undefined
           prop, forcing a full clear+appendInline of the entire UI tree. Passing
           tooltip-text=undefined on every render causes full rebuilds per keystroke. -->
      <Label
        :text="field.displayName"
        :el-style="labelStyle"
        :anchor="{ Width: 200, Height: 36 }"
        v-bind="field.tooltip ? { 'tooltip-text': field.tooltip } : {}"
      />

      <!-- Amount / Value Input -->
      <Group :flex-weight="1" :anchor="{ Height: 32 }" :padding="{ Left: 8, Right: 8 }">

        <!-- DOUBLE / INTEGER → Core.TextField. When locked it keeps its box but renders the value
             muted via el-style (no disabled input texture exists) so it reads as greyed yet still a
             control. Conditional v-bind avoids passing an undefined prop (which would force a full
             UI rebuild). editLocked is static per mount (recreated via resetGeneration on a
             scope/source switch) → no mid-edit toggle. -->
        <Core.TextField
          v-if="field.valueType === 'DOUBLE' || field.valueType === 'INTEGER'"
          :model-value="displayString"
          :is-read-only="isDisabled"
          :anchor="{ Horizontal: 0, Height: 28 }"
          v-bind="editLocked ? { 'el-style': LOCKED_INPUT_STYLE } : {}"
          @update:model-value="onNumberInput"
        />

        <!-- BOOLEAN → native TextButton toggle (Yes/No) with static style -->
        <TextButton
          v-else-if="field.valueType === 'BOOLEAN'"
          :text="boolText"
          :disabled="isDisabled"
          :el-style="BOOL_BUTTON_STYLE"
          :anchor="{ Width: 80, Height: 28 }"
          :padding="{ Horizontal: 16 }"
          @activating="onBoolToggle"
        />

        <!-- STRING with options → native DropdownBox (string value, codec OK) -->
        <DropdownBox
          v-else-if="field.valueType === 'STRING' && field.options"
          :value="typeof displayValue === 'string' ? displayValue : ''"
          :el-style="editLocked ? DROPDOWN_STYLE_LOCKED : DROPDOWN_STYLE"
          :anchor="{ Horizontal: 0, Height: 28 }"
          @value-changed="onDropdownChanged"
        >
          <DropdownEntry
            v-for="opt in field.options"
            :key="opt"
            :value="opt"
            :text="opt"
          />
        </DropdownBox>

        <!-- STRING without options → Common.TextField (string value, codec OK) -->
        <Common.TextField
          v-else-if="field.valueType === 'STRING'"
          :value="typeof displayValue === 'string' ? displayValue : ''"
          :is-read-only="isDisabled"
          :anchor="{ Horizontal: 0, Height: 28 }"
          v-bind="editLocked ? { 'el-style': LOCKED_INPUT_STYLE } : {}"
          @value-changed="onTextChanged"
        />
      </Group>

      <!-- CalculationType Dropdown — only for stat modifier fields. v-if (not :visible): isModifier
           is STATIC per field (field.calculationType never changes for a given row, and the v-for key
           includes field.id, so a different field is a different instance). It therefore NEVER toggles
           at runtime → no structural change after mount. v-if skips building this DropdownBox + its
           entries entirely on every non-modifier field (~4 elements each) — a meaningful cut to total
           mount time on big items. When absent, the flex amount input expands to fill,
           identical to the old hidden case. -->
      <Group v-if="isModifier" :anchor="{ Width: 140, Height: 32 }" :padding="{ Left: 4 }">
        <DropdownBox
          :value="currentCalcType"
          :el-style="editLocked ? DROPDOWN_STYLE_LOCKED : DROPDOWN_STYLE"
          :anchor="{ Horizontal: 0, Height: 28 }"
          @value-changed="onCalcTypeChanged"
        >
          <DropdownEntry
            v-for="opt in calcTypeOptions"
            :key="opt"
            :value="opt"
            :text="opt"
          />
        </DropdownBox>
      </Group>

      <!-- "was: X" indicator — always present, text set to space when hidden.
           Uses text content only (no :visible toggle) to minimize dirty props.
           Anchor uses Horizontal+Vertical (not Full) — Full crashes on Set commands
           (Int32 vs Number, see memory: Anchor.Full bug). -->
      <Group :anchor="{ Width: 90, Height: 36 }">
        <Label
          :text="wasText || ' '"
          :el-style="MODIFIED_LABEL_STYLE"
          :anchor="{ Horizontal: 0, Vertical: 0 }"
        />
      </Group>
    </Group>

    <!-- Effect description — v-if (not :visible): field.effectDescription is a static payload
         property (computed server-side from the value at scan time; it is NOT recomputed as the
         admin types), so this never toggles at runtime → safe to skip building the Label entirely
         when there's no description (element trim). -->
    <Label
      v-if="!!field.effectDescription"
      :text="field.effectDescription || ' '"
      :el-style="SMALL_LABEL_STYLE"
      :anchor="{ Horizontal: 0, Height: 18 }"
      :padding="{ Left: 212 }"
    />

    <!-- "Not set" hint — server state only, never toggles during editing.
         Using displayValue here caused :visible to toggle on first keystroke
         → dirty element inside TopScrolling → scroll reset + focus loss.
         field.isNotSet is from the server payload — stable during editing,
         clears naturally when server pushes fresh data after save. -->
    <Label
      text="Not set"
      :visible="field.isNotSet && !isSaved"
      :el-style="NOT_SET_HINT_STYLE"
      :anchor="{ Horizontal: 0, Height: 16 }"
      :padding="{ Left: 212 }"
    />
  </Group>
</template>

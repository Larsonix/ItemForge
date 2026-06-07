/**
 * Editor state management composable.
 *
 * Manages pending changes, dirty tracking, and status messages for the editor.
 * All state is local to the Vue component — only the save/reset actions
 * communicate with the server via the globalThis bridge.
 *
 * ARCHITECTURE.md §3.2 (composables/useEditorState.ts)
 * UX_DESIGN.md §12.2.1 (Reactive Editor Data Flow)
 */
import { ref, computed, type Ref, type ComputedRef } from 'vue'
import type { SaveResponse } from '../types/EditorPayload'
import type { RecipeChanges } from '../types/RecipeData'

export interface EditorState {
  /** Field ID → new value. Only contains fields the admin has changed but NOT yet saved. */
  pendingChanges: Ref<Record<string, unknown>>
  /** Field ID → saved value. Values that were saved but not yet in the server payload. */
  savedValues: Ref<Record<string, unknown>>
  /** Whether any unsaved changes are pending (fields OR recipe). */
  isDirty: ComputedRef<boolean>
  /** Number of pending changes (recipe counts as 1 unit). */
  dirtyCount: ComputedRef<number>
  /** Status text: "2 unsaved changes", "Saved!", "Save failed: ..." */
  statusText: ComputedRef<string>
  /** Whether a save is in progress. */
  saving: Ref<boolean>
  /** Whether a reset was just performed — suppresses visual indicators until server confirms. */
  afterReset: Ref<boolean>
  /**
   * Set true when the most recent save/reset was rejected for lack of permission.
   * The page reads this synchronously after save()/resetAll() returns
   * and shows the Permission Denied overlay, then clears the flag.
   */
  permissionDenied: Ref<boolean>
  /** Human-readable reason for the last permission denial (shown in the overlay). */
  permissionDeniedMessage: Ref<string>
  /** Pending recipe changes (null = no changes). */
  recipeChanges: Ref<RecipeChanges | null>
  /** Whether recipe was saved during this editing session (for "was:" indicators). */
  recipeSavedThisSession: Ref<boolean>
  /** Whether recipe has pending changes. */
  hasRecipeChanges: ComputedRef<boolean>
  /** Record a field value change from the UI. */
  onFieldChanged: (fieldId: string, newValue: unknown, serverValue: unknown) => void
  /** Record a recipe change from the UI. */
  onRecipeChanged: (changes: RecipeChanges) => void
  /**
   * Save all pending changes to the server.
   * @param source Stat source for per-stack provider edits. Omit or "BASE"
   *        for normal asset editing; a provider id routes the save to that provider.
   * @param scope Edit scope. "local" routes a BASE-source save to the
   *        held item (per-item edit); omit/"global" for the normal asset override.
   */
  save: (playerId: string, itemId: string, source?: string, scope?: 'global' | 'local') => void
  /** Reset all overrides for the item. Async revert on server. */
  resetAll: (playerId: string, itemId: string) => void
  /** Close the editor page. */
  close: (playerId: string) => void
}

/**
 * Creates and returns the editor state management.
 *
 * Usage in Editor.vue:
 * ```ts
 * const editor = useEditorState()
 * // editor.onFieldChanged('MaxDurability', 200, 100)
 * // editor.save(playerId, itemId)
 * ```
 */
export function useEditorState(): EditorState {
  const pendingChanges = ref<Record<string, unknown>>({})
  const savedValues = ref<Record<string, unknown>>({})
  const saving = ref(false)
  const afterReset = ref(false)
  const permissionDenied = ref(false)
  const permissionDeniedMessage = ref('')
  const statusMessage = ref('')
  let statusTimeout: ReturnType<typeof setTimeout> | null = null

  // Recipe state
  const recipeChanges = ref<RecipeChanges | null>(null)
  const recipeSavedThisSession = ref(false)

  const hasRecipeChanges = computed(() => {
    const rc = recipeChanges.value
    if (!rc) return false
    if (rc.outputQuantity !== undefined || rc.timeSeconds !== undefined ||
        rc.knowledgeRequired !== undefined || rc.requiredMemoriesLevel !== undefined) return true
    if (rc.inputs && Object.keys(rc.inputs).length > 0) return true
    if (rc.inputsFull && rc.inputsFull.length > 0) return true
    if (rc.benches && Object.keys(rc.benches).length > 0) return true
    // benchesFull is dirty whenever present — even as an empty array (delete-all benches).
    // syncBenchChanges only sets it on a real structural diff, so presence == change.
    if (rc.benchesFull !== undefined) return true
    return false
  })

  const isDirty = computed(() => Object.keys(pendingChanges.value).length > 0 || hasRecipeChanges.value)
  const dirtyCount = computed(() => {
    let count = Object.keys(pendingChanges.value).length
    if (hasRecipeChanges.value) count++ // Recipe counts as 1 change unit
    return count
  })

  // Dirty count omitted during editing — reactive updates outside TopScrolling
  // may also cause scroll reset (unverified). Counter shows in close overlay instead.
  const statusText = computed(() => {
    if (saving.value) return 'Saving...'
    if (statusMessage.value) return statusMessage.value
    return ''
  })

  function setTemporaryStatus(msg: string, durationMs = 3000) {
    statusMessage.value = msg
    if (statusTimeout) clearTimeout(statusTimeout)
    statusTimeout = setTimeout(() => { statusMessage.value = '' }, durationMs)
  }

  /**
   * Records a field value change.
   *
   * If the new value equals the server's current value, the change is removed
   * from pendingChanges (the admin reverted their edit).
   */
  function onFieldChanged(fieldId: string, newValue: unknown, serverValue: unknown) {
    // Compare with type coercion for numeric values (JS may send 100.0 vs 100)
    if (valuesEqual(newValue, serverValue)) {
      const copy = { ...pendingChanges.value }
      delete copy[fieldId]
      pendingChanges.value = copy
    } else {
      pendingChanges.value = { ...pendingChanges.value, [fieldId]: newValue }
    }
  }

  /** Records recipe changes from the UI. */
  function onRecipeChanged(changes: RecipeChanges) {
    recipeChanges.value = changes
  }

  /**
   * Saves all pending changes via the bridge.
   *
   * The bridge validates synchronously on V8 (0-2ms with field cache) and
   * dispatches apply+sync to a worker thread. We update local state immediately
   * for instant feedback — the next V8 tick (50ms) sends the UI update to client.
   *
   * Note: saving.value is NOT toggled here. The bridge call is synchronous
   * and fast — setting saving=true then false in the same synchronous callback
   * would never be visible (Vue batches) and just adds a wasted dirty element.
   */
  function save(playerId: string, itemId: string, source?: string, scope?: 'global' | 'local'): void {
    if (!isDirty.value) return

    try {
      // Build payload: { source?, scope?, fields: {...}, recipe: {...} }
      // Backend detects this format and processes each section independently.
      // A non-BASE `source` routes the save to a StatProvider (per-stack edit);
      // `scope: "local"` routes a BASE-source save to the held item (per-item edit);
      // recipe changes never coexist with a provider source or local scope.
      const payload: Record<string, unknown> = {}
      if (source && source !== 'BASE') {
        payload.source = source
      }
      if (scope === 'local') {
        payload.scope = 'local'
      }
      const hasFieldChanges = Object.keys(pendingChanges.value).length > 0
      if (hasFieldChanges) {
        payload.fields = pendingChanges.value
      }
      if (hasRecipeChanges.value) {
        payload.recipe = recipeChanges.value
      }

      const changesJson = JSON.stringify(payload)
      const resultJson = globalThis.itemForgeBridge.save(playerId, itemId, changesJson)

      const result: SaveResponse = JSON.parse(resultJson)
      if (result.permissionDenied) {
        // Permission was revoked while editing. Keep pending changes intact
        // (nothing was saved) and let the page show the denial overlay.
        permissionDeniedMessage.value = result.error || "You don't have permission to make this change."
        permissionDenied.value = true
        globalThis.itemForgeBridge.requestFlush()
        return
      }
      if (result.success) {
        // Move pending field changes to savedValues
        if (hasFieldChanges) {
          savedValues.value = { ...savedValues.value, ...pendingChanges.value }
          pendingChanges.value = {}
        }
        // Clear recipe changes and mark as saved this session
        if (hasRecipeChanges.value) {
          recipeChanges.value = null
          recipeSavedThisSession.value = true
        }
        setTemporaryStatus('Saved!')
      } else {
        setTemporaryStatus(`Save failed: ${result.error || 'Unknown error'}`, 5000)
      }
    } catch (e: any) {
      setTemporaryStatus(`Save failed: ${e?.message || 'Bridge error'}`, 5000)
    }

    // Schedule a deferred UI flush. requestFlush() submits a task to the V8
    // executor that runs AFTER this callback returns (game thread free). It
    // flushes Vue microtasks and fires onDirty → sendUpdateAsync. This
    // eliminates the 50ms tick delay for visual feedback ("Saved!" appears
    // in ~1ms instead of ~50-100ms). Safe because the flush runs outside
    // runOnV8Thread — see EditorBridge.requestFlush() for full analysis.
    globalThis.itemForgeBridge.requestFlush()
  }

  /**
   * Resets all overrides for the item via the bridge.
   *
   * The bridge dispatches the actual revert async (loadAssets on worker thread).
   * We clear pendingChanges and show a status message immediately. The admin
   * can reopen the editor to see the reverted values.
   *
   * Returns void — the bridge response is a status indicator ({success: true}),
   * NOT an EditorPayload. Previous code set it as localOverride, which corrupted
   * the payload, destroyed all fields (structural rebuild), and caused stale
   * routing keys → frozen UI on subsequent clicks.
   */
  function resetAll(playerId: string, itemId: string): void {
    try {
      const resultJson = globalThis.itemForgeBridge.reset(playerId, itemId)
      // reset() returns a status object: { success, permissionDenied?, error? }.
      // On denial, nothing was reverted — preserve all local state and surface
      // the overlay instead of falsely showing "Reset to default!".
      const result = JSON.parse(resultJson) as { success?: boolean; permissionDenied?: boolean; error?: string }
      if (result.permissionDenied) {
        permissionDeniedMessage.value = result.error || "You don't have permission to reset items."
        permissionDenied.value = true
        globalThis.itemForgeBridge.requestFlush()
        return
      }
      pendingChanges.value = {}
      savedValues.value = {}
      recipeChanges.value = null
      recipeSavedThisSession.value = false
      afterReset.value = true
      setTemporaryStatus('Reset to default!')
    } catch (e: any) {
      setTemporaryStatus(`Reset failed: ${e?.message || 'Bridge error'}`, 5000)
    }
    // Deferred flush — see save() comment for rationale.
    globalThis.itemForgeBridge.requestFlush()
  }

  /**
   * Closes the editor page via the bridge.
   * Cancels any pending status timer first to avoid V8 events on a dead app.
   */
  function close(playerId: string) {
    if (statusTimeout) {
      clearTimeout(statusTimeout)
      statusTimeout = null
    }
    try {
      globalThis.itemForgeBridge.closePage(playerId)
    } catch (e: any) {
      console.error('[ItemForge] closePage error:', e?.message)
    }
  }

  return {
    pendingChanges,
    savedValues,
    isDirty,
    dirtyCount,
    statusText,
    saving,
    afterReset,
    permissionDenied,
    permissionDeniedMessage,
    recipeChanges,
    recipeSavedThisSession,
    hasRecipeChanges,
    onFieldChanged,
    onRecipeChanged,
    save,
    resetAll,
    close,
  }
}

/**
 * Compares two values for equality, handling numeric type coercion
 * and composite modifier values.
 *
 * Simple scalars: JS bridge may return 100.0 for an integer — should equal 100.
 * Composite modifiers: `{amount: 17, calculationType: "Additive"}` — both
 * fields must match for equality. Used for dirty detection when the admin
 * changes amount and/or calcType on a stat modifier field.
 */
function valuesEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a === 'number' && typeof b === 'number') return a === b
  // Composite modifier values: {amount, calculationType}
  if (isModifierValue(a) && isModifierValue(b)) {
    return a.amount === b.amount && a.calculationType === b.calculationType
  }
  // Composite modifier vs an empty baseline (a "not set" modifier slot's value is null).
  // A modifier with no real amount (null/0/NaN) is equivalent to "not set" — a modifier
  // does nothing without an amount. This prevents phantom calc-type echoes on empty stat
  // slots (which re-fire on re-render) from being miscounted as unsaved changes; a genuine
  // edit always carries a non-zero amount and is still detected. (Symmetric in a/b.)
  if (isModifierValue(a) && b == null) return isEmptyAmount(a.amount)
  if (isModifierValue(b) && a == null) return isEmptyAmount(b.amount)
  return false
}

/** A modifier amount that represents "no real value" (a modifier is inert without an amount). */
function isEmptyAmount(amount: unknown): boolean {
  return amount == null || amount === 0 || (typeof amount === 'number' && Number.isNaN(amount))
}

/** Type guard for composite modifier change values */
function isModifierValue(v: unknown): v is { amount: number; calculationType: string } {
  return v != null && typeof v === 'object' && 'amount' in (v as object)
}

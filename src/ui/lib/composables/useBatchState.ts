/**
 * Batch operations state.
 *
 * Owns the multi-item SELECTION plus the batch overlay / undo-toast UI flags.
 * Selection uses the "Filter → Select All" model: there are no per-row checkboxes
 * (the Vuetale client has no scroll-restore API, and any per-row selection paint
 * resets a TopScrolling list to the top — see PHASE6_SPEC). Instead the admin
 * filters/searches, hits "Select All (N)", and selection ACCUMULATES across
 * searches. Individual deselect happens in the overlay's review list.
 *
 * Lives at the page level so selection survives navigate() to the editor and back
 * (the Vue reactive store is never destroyed across navigations).
 *
 * Reactivity note: a `ref<Set>` does NOT track `.add()/.delete()`. Every mutation
 * reassigns a fresh Set so computed properties (count, isSelected via selectedIds)
 * re-evaluate deterministically.
 */
import { ref, computed, type Ref, type ComputedRef } from 'vue'
import type { DashboardItem } from '../types/DashboardPayload'

/** A batch is EITHER a stat op OR a recipe op — never both (clean single-path undo). */
export type BatchKind = 'stat' | 'recipe'

export interface BatchStateResult {
  selectedIds: Ref<Set<string>>
  selectedCount: ComputedRef<number>
  selectedArray: ComputedRef<string[]>
  hasSelection: ComputedRef<boolean>
  isSelected: (id: string) => boolean

  selectAll: (items: DashboardItem[]) => void
  addItem: (id: string) => void
  removeItem: (id: string) => void
  toggleItem: (id: string) => void
  clearSelection: () => void

  // ── Overlay ──
  overlayOpen: Ref<boolean>
  batchKind: Ref<BatchKind>
  openOverlay: (kind: BatchKind) => void
  closeOverlay: () => void

  // ── Undo toast ──
  undoVisible: Ref<boolean>
  undoMessage: Ref<string>
  showUndo: (message: string) => void
  hideUndo: () => void
}

export function useBatchState(): BatchStateResult {
  const selectedIds = ref<Set<string>>(new Set())

  /** Reassigns a fresh Set so Vue reactivity fires (ref<Set> ignores in-place mutation). */
  function mutate(fn: (s: Set<string>) => void) {
    const next = new Set(selectedIds.value)
    fn(next)
    selectedIds.value = next
  }

  const selectedCount = computed(() => selectedIds.value.size)
  const selectedArray = computed(() => Array.from(selectedIds.value))
  const hasSelection = computed(() => selectedIds.value.size > 0)
  const isSelected = (id: string): boolean => selectedIds.value.has(id)

  /** Adds every given item to the selection (accumulates — does not replace). */
  function selectAll(items: DashboardItem[]) {
    if (items.length === 0) return
    mutate(s => { for (const it of items) s.add(it.id) })
  }
  function addItem(id: string) { mutate(s => { s.add(id) }) }
  function removeItem(id: string) { if (selectedIds.value.has(id)) mutate(s => { s.delete(id) }) }
  function toggleItem(id: string) { mutate(s => { if (s.has(id)) s.delete(id); else s.add(id) }) }
  function clearSelection() { if (selectedIds.value.size > 0) selectedIds.value = new Set() }

  const overlayOpen = ref(false)
  const batchKind = ref<BatchKind>('stat')
  function openOverlay(kind: BatchKind) { batchKind.value = kind; overlayOpen.value = true }
  function closeOverlay() { overlayOpen.value = false }

  const undoVisible = ref(false)
  const undoMessage = ref('')
  function showUndo(message: string) { undoMessage.value = message; undoVisible.value = true }
  function hideUndo() { undoVisible.value = false }

  return {
    selectedIds, selectedCount, selectedArray, hasSelection, isSelected,
    selectAll, addItem, removeItem, toggleItem, clearSelection,
    overlayOpen, batchKind, openOverlay, closeOverlay,
    undoVisible, undoMessage, showUndo, hideUndo,
  }
}

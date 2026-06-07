/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/**
 * ItemForge bridge exposed by the Kotlin server via V8 globalThis.
 * Methods run on the V8 thread — return JSON strings, don't call setData().
 */
declare interface ItemForgeBridge {
  save(playerId: string, itemId: string, changesJson: string): string
  reset(playerId: string, itemId: string): string
  resetField(playerId: string, itemId: string, fieldId: string): string
  closePage(playerId: string): void
  searchMaterials(query: string): string
  requestFlush(): void
  /** Fire a mod extension panel button action (Phase 8.2). Server runs it + re-pushes the panel. */
  extensionAction(playerId: string, itemId: string, extensionId: string, actionId: string): string
  /**
   * Populate a large dropdown's option list as a single data array (perf — avoids one native
   * element per option). `entriesJson` = JSON `[{label, value, tooltip?}]` in display order.
   * Debounced per `(playerId|customId)`; safe if the target element isn't mounted yet.
   */
  refreshPickerEntries(playerId: string, customId: string, entriesJson: string): string
}

declare var itemForgeBridge: ItemForgeBridge

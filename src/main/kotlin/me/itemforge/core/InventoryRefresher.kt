package me.itemforge.core

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.inventory.InventoryComponent
import com.hypixel.hytale.server.core.inventory.ItemStack
import com.hypixel.hytale.server.core.universe.Universe

/**
 * Refreshes existing ItemStacks in all online players' inventories when an
 * Item asset's MaxDurability changes.
 *
 * ## Why This Is Needed
 *
 * ItemStack stores `maxDurability` as instance state (ItemStack.java:63), baked
 * from `item.getMaxDurability()` at creation time. When ItemForge modifies the
 * Item asset's MaxDurability, existing ItemStacks keep their old value. This
 * class finds and updates them.
 *
 * ## What Does NOT Need Refreshing
 *
 * - **Stat modifiers** (Armor.Health, Weapon.Damage) — read live from the Item
 *   asset every tick by StatModifiersManager. EntityStatsModule auto-schedules
 *   recalculation on LoadedAssetsEvent.
 * - **General fields** (Quality, Consumable, DropOnDeath, FuelQuality, ItemLevel,
 *   DurabilityLossOnHit/Death) — read live from Item asset at use time.
 * - **MaxStack** — checked from Item asset when stacking, not stored on ItemStack.
 *
 * ## Durability Clamping Rules
 *
 * | Before        | Change     | After       | Rationale                           |
 * |---------------|------------|-------------|-------------------------------------|
 * | 50/100        | Max → 200  | 50/200      | Item can take more hits             |
 * | 50/100        | Max → 30   | 30/30       | Clamp: can't exceed new max         |
 * | 0/0 (unbreak) | Max → 100  | 100/100     | Was unbreakable, now has durability  |
 * | 50/100        | Max → 0    | 0/0         | Now unbreakable                     |
 * | 100/100       | Max → 200  | 100/200     | Absolute durability preserved       |
 *
 * ## Threading
 *
 * Dispatches to each world's thread via `world.execute {}`.
 * `Store.getComponent()` has a thread assertion — calling from ForkJoinPool crashes.
 * `ItemContainer.replaceAll()` uses internal write locks and auto-sends client updates.
 *
 * ## Multi-World Support
 *
 * Iterates ALL worlds via `Universe.get().getWorlds()`. Each world's player
 * inventory refresh runs on that world's thread. Safe for multi-world servers.
 *
 * ## Evidence
 *
 * - ItemStack.java:63 — `protected double maxDurability` (stored field)
 * - ItemStack.java:87-91 — 5-arg constructor with explicit durability/maxDurability
 * - ItemContainer.java:209-237 — `replaceAll()` atomic batch replace + auto sendUpdate
 * - EntityStatsModule.java:140 — `Universe.get().getWorlds()` iteration pattern
 * - SayCommand.java:42 — `world.getPlayerRefs()` iteration pattern
 * - TrailOfOrbis GearUtils — iterates all 5 inventory sections per player
 */
class InventoryRefresher(
    private val logger: HytaleLogger? = null
) {

    /**
     * Refreshes all ItemStacks matching [itemId] across all online players
     * in all worlds.
     *
     * Must be called AFTER the Item asset has been updated (via loadAssets or
     * direct field decode). Reads the new MaxDurability from the live Item asset.
     *
     * Safe to call from any thread — dispatches to each world's thread internally.
     *
     * @param itemId The item ID that was modified
     */
    fun refreshItemStacks(itemId: String) {
        refreshItemStacks(setOf(itemId))
    }

    /**
     * Batch variant — refreshes ItemStacks for multiple item IDs in a single pass.
     * More efficient for batch operations (performReload).
     *
     * @param itemIds Set of item IDs that were modified
     */
    fun refreshItemStacks(itemIds: Set<String>) {
        if (itemIds.isEmpty()) return

        // Pre-fetch the new MaxDurability for each item from the (already updated) asset map.
        val newMaxByItem = mutableMapOf<String, Double>()
        for (id in itemIds) {
            val item = Item.getAssetMap().getAsset(id) ?: continue
            newMaxByItem[id] = item.maxDurability
        }
        if (newMaxByItem.isEmpty()) return

        // Iterate ALL worlds — handles multi-world servers.
        // Pattern from EntityStatsModule.java:140 and SayCommand.java:42.
        try {
            Universe.get().worlds.values.forEach { world ->
                world.execute {
                    try {
                        refreshWorldPlayers(world, newMaxByItem)
                    } catch (e: Exception) {
                        logger?.atWarning()?.withCause(e)?.log(
                            "InventoryRefresher: Failed in world '%s'", world
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger?.atWarning()?.withCause(e)?.log(
                "InventoryRefresher: Failed to iterate worlds for %d item(s)", itemIds.size
            )
        }
    }

    /**
     * Refreshes all players in a single world. Runs on that world's thread.
     */
    private fun refreshWorldPlayers(
        world: com.hypixel.hytale.server.core.universe.world.World,
        newMaxByItem: Map<String, Double>
    ) {
        val store = world.entityStore.store
        var playersChecked = 0
        var stacksUpdated = 0

        // Iterate all player refs in this world (SayCommand.java:42 pattern)
        for (playerRef in world.playerRefs) {
            val ref = playerRef.reference ?: continue
            if (!ref.isValid) continue

            playersChecked++
            stacksUpdated += refreshPlayerInventory(ref, store, newMaxByItem)
        }

        if (stacksUpdated > 0) {
            logger?.atInfo()?.log(
                "InventoryRefresher: Updated %d ItemStack(s) across %d player(s)",
                stacksUpdated, playersChecked
            )
        }
    }

    /**
     * Refreshes all inventory sections for a single player.
     *
     * Checks all 5 sections: Hotbar, Armor, Storage, Utility, Backpack.
     * Uses the specific component types directly (not section IDs) because
     * `getComponentTypeById(-1)` for Hotbar may be missing from the switch.
     *
     * @return Number of ItemStacks updated
     */
    @Suppress("UNCHECKED_CAST")
    private fun refreshPlayerInventory(
        ref: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        store: com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        newMaxByItem: Map<String, Double>
    ): Int {
        var updated = 0

        // Iterate all inventory section types directly
        // (InventoryComponent.EVERYTHING order: armor, hotbar, utility, storage, backpack)
        val sectionTypes = arrayOf(
            InventoryComponent.Hotbar.getComponentType(),
            InventoryComponent.Armor.getComponentType(),
            InventoryComponent.Storage.getComponentType(),
            InventoryComponent.Utility.getComponentType(),
            InventoryComponent.Backpack.getComponentType(),
        )

        for (componentType in sectionTypes) {
            val section = store.getComponent(ref, componentType) ?: continue
            val container = section.inventory ?: continue
            updated += refreshContainer(container, newMaxByItem)
        }

        return updated
    }

    /**
     * Refreshes ItemStacks in a single inventory container.
     *
     * Uses [com.hypixel.hytale.server.core.inventory.container.ItemContainer.replaceAll]
     * for atomic batch replacement with automatic client sync (sends inventory update packet).
     *
     * @return Number of ItemStacks updated
     */
    private fun refreshContainer(
        container: com.hypixel.hytale.server.core.inventory.container.ItemContainer,
        newMaxByItem: Map<String, Double>
    ): Int {
        var updated = 0

        container.replaceAll { _, existing ->
            if (existing == null || existing.isEmpty) return@replaceAll existing

            val newMax = newMaxByItem[existing.itemId] ?: return@replaceAll existing

            // Skip if maxDurability already matches (no update needed — avoids
            // unnecessary inventory packets for items unaffected by the change)
            if (existing.maxDurability == newMax) return@replaceAll existing

            // Build replacement with updated maxDurability
            val newDurability = computeNewDurability(
                existing.durability, existing.maxDurability, newMax
            )
            val replacement = ItemStack(
                existing.itemId,
                existing.quantity,
                newDurability,
                newMax,
                existing.metadata
            )

            // Preserve overrideDroppedItemAnimation flag (5-arg constructor doesn't set it,
            // defaults to false. Must copy explicitly if the original had it set.)
            if (existing.overrideDroppedItemAnimation) {
                replacement.setOverrideDroppedItemAnimation(true)
            }

            updated++
            replacement
        }

        return updated
    }

    /**
     * Computes the new durability value after a MaxDurability change.
     *
     * Rules:
     * - Unbreakable → breakable (oldMax ≤ 0, newMax > 0): full condition (newMax)
     * - Breakable → unbreakable (newMax ≤ 0): 0 (durability system disabled)
     * - Normal: clamp to newMax (preserve absolute durability, cap at new maximum)
     */
    private fun computeNewDurability(
        currentDurability: Double,
        oldMaxDurability: Double,
        newMaxDurability: Double
    ): Double {
        return when {
            // Was unbreakable, now has durability → start at full condition
            oldMaxDurability <= 0.0 && newMaxDurability > 0.0 -> newMaxDurability
            // Now unbreakable → durability system disabled
            newMaxDurability <= 0.0 -> 0.0
            // Normal: clamp current durability to new maximum
            else -> minOf(currentDurability, newMaxDurability)
        }
    }
}

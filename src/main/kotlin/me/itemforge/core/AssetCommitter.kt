package me.itemforge.core

import com.hypixel.hytale.protocol.ItemBase
import com.hypixel.hytale.protocol.UpdateType
import com.hypixel.hytale.protocol.packets.assets.UpdateItems
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.universe.Universe

/**
 * Commits modified items and recipes to the engine for live sync.
 *
 * Wraps `AssetStore.loadAssets()` — the single call that handles:
 * 1. Replacing the asset in the asset map
 * 2. Firing `LoadedAssetsEvent` (CraftingPlugin rebuilds recipe registries)
 * 3. Generating `UpdateItems`/`UpdateRecipes` packets via `ItemPacketGenerator`
 * 4. Broadcasting to ALL connected players via `Universe.broadcastPacketNoCache()`
 * 5. Invalidating cached Init packets (new players get fresh data)
 *
 * ## Feedback Loop Guard
 *
 * `loadAssets()` fires `LoadedAssetsEvent` synchronously. Our `AssetLoadListener`
 * handles that event to re-apply overrides. Without a guard, this creates an
 * infinite loop:
 *
 * ```
 * commitItem() → loadAssets() → LoadedAssetsEvent → AssetLoadListener
 *   → applyOverride() → commitItem() → loadAssets() → ... (11+ cycles)
 * ```
 *
 * The [flushing] flag breaks this cycle. When true, `AssetLoadListener` skips
 * re-application because it knows the event came from our own commit.
 *
 * **Evidence**: ToO `ItemSyncCoordinator.java:481-491` discovered this the hard
 * way (11+ cycles, 1903 client warnings). Their `currentlyFlushing` flag is the
 * exact same pattern.
 *
 * ## Evidence
 *
 * - `AssetStore.java:420` (loadAssets is public)
 * - `HytaleAssetStore.java:93-122` (handleRemoveOrUpdate broadcasts automatically)
 * - `ItemPacketGenerator.java:37-48` (generates UpdateItems with AddOrUpdate)
 * - SimpleEnchantments, LifeCrops, CraftingPlugin, FunctionalTargetDummy all use loadAssets()
 * - Probe 1.4: loaded 1, failed 0, value persisted, restoration synced
 */
class AssetCommitter {

    /**
     * Feedback loop guard. When true, AssetLoadListener should skip override
     * re-application because the LoadedAssetsEvent came from our own commit.
     *
     * Volatile for visibility across the server thread and event dispatch.
     * Not AtomicBoolean because loadAssets fires synchronously on the same
     * thread — no concurrent access.
     */
    @Volatile
    var flushing: Boolean = false
        private set

    /**
     * Commits a single modified item to the engine.
     *
     * After this call, all connected players will see the updated item stats
     * in their tooltips, equipment, and inventory.
     *
     * @param item The modified Item object
     */
    fun commitItem(item: Item) {
        if (flushing) return // Prevent feedback loop

        flushing = true
        try {
            Item.getAssetStore().loadAssets(PACK_KEY, listOf(item))
        } finally {
            flushing = false
        }

        // Corrected weapon tooltip re-broadcast (see broadcastWeaponTooltipCorrection KDoc)
        broadcastWeaponTooltipCorrection(listOf(item))
    }

    /**
     * Commits multiple modified items in a single loadAssets call.
     *
     * More efficient than calling [commitItem] in a loop — generates one
     * UpdateItems packet containing all changes instead of N separate packets.
     * The engine batches them into a single broadcast.
     *
     * @param items The modified Item objects
     */
    fun commitItems(items: List<Item>) {
        if (items.isEmpty()) return
        if (flushing) return

        flushing = true
        try {
            Item.getAssetStore().loadAssets(PACK_KEY, items)
        } finally {
            flushing = false
        }

        // Corrected weapon tooltip re-broadcast (see broadcastWeaponTooltipCorrection KDoc)
        broadcastWeaponTooltipCorrection(items)
    }

    /**
     * Commits a single modified recipe to the engine.
     *
     * After this call, crafting benches will reflect the updated recipe
     * (inputs, quantities, time, etc.) for all connected players.
     *
     * @param recipe The modified CraftingRecipe object
     */
    fun commitRecipe(recipe: CraftingRecipe) {
        if (flushing) return

        flushing = true
        try {
            CraftingRecipe.getAssetStore().loadAssets(PACK_KEY, listOf(recipe))
        } finally {
            flushing = false
        }
    }

    /**
     * Commits multiple modified recipes in a single loadAssets call.
     *
     * @param recipes The modified CraftingRecipe objects
     */
    fun commitRecipes(recipes: List<CraftingRecipe>) {
        if (recipes.isEmpty()) return
        if (flushing) return

        flushing = true
        try {
            CraftingRecipe.getAssetStore().loadAssets(PACK_KEY, recipes)
        } finally {
            flushing = false
        }
    }

    /**
     * Removes a recipe from the engine and broadcasts the removal to all players.
     *
     * Used to delete a recipe ItemForge created (an item that had no recipe of its own — see
     * [me.itemforge.core.RecipeOverrideEngine.deleteRecipe]). `removeAssets` drops the asset
     * from the map and emits an `UpdateRecipes` Remove packet so crafting menus update live.
     *
     * Scoped to [PACK_KEY] (`all = false`) — the same pack created recipes are committed under
     * — so this never touches recipes loaded from other packs. The [flushing] guard mirrors
     * [commitRecipe]: removal fires `RemovedAssetsEvent`, and the guard keeps any listener that
     * reacts by re-committing from looping back into us.
     *
     * @param recipeId The recipe ID to remove
     */
    fun removeRecipe(recipeId: String) {
        if (flushing) return

        flushing = true
        try {
            CraftingRecipe.getAssetStore().removeAssets(
                PACK_KEY,
                false,
                setOf(recipeId),
                com.hypixel.hytale.assetstore.AssetUpdateQuery.DEFAULT
            )
        } finally {
            flushing = false
        }
    }

    // ── Weapon Tooltip Correction ──────────────────────────────────────

    /**
     * Sends a corrected UpdateItems packet for weapon items after loadAssets.
     *
     * **Why this is needed:**
     *
     * `DamageBreakdown` is a `transient` field on `ItemWeapon` — not part of the
     * codec, not encoded/decoded. It's computed by `ItemModule.computeWeaponData()`
     * which runs on `LoadedAssetsEvent`. But `AssetStore.loadAssets()` broadcasts
     * the `UpdateItems` packet (line 859) **before** firing `LoadedAssetsEvent`
     * (line 870). So the first broadcast has null `DamageBreakdown` → client
     * tooltip shows no "Damage Data" section.
     *
     * After `loadAssets()` returns, events have fired synchronously and
     * `computeWeaponData` has populated `DamageBreakdown` + invalidated the
     * packet cache. This method sends a second corrected packet where
     * `item.toPacket()` now includes the weapon tooltip data.
     *
     * The double-packet is imperceptible to clients (processed sequentially,
     * second overwrites first within the same network tick).
     *
     * @param items The items that were just committed via loadAssets
     */
    fun broadcastWeaponTooltipCorrection(items: List<Item>) {
        val weapons = items.filter { it.weapon != null }
        if (weapons.isEmpty()) return

        val packet = UpdateItems()
        packet.type = UpdateType.AddOrUpdate
        val itemMap = HashMap<String, ItemBase>()
        for (item in weapons) {
            itemMap[item.id] = item.toPacket()
        }
        packet.items = itemMap
        Universe.get().broadcastPacketNoCache(packet)
    }

    companion object {
        /**
         * Pack key used for all ItemForge commits.
         *
         * The pack key identifies the source of the loaded assets in the asset map.
         * Using a unique key lets other systems distinguish ItemForge-loaded assets
         * from mod-loaded ones.
         */
        const val PACK_KEY = "ItemForge:overrides"
    }
}

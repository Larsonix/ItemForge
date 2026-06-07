package me.itemforge.entry

import com.google.gson.JsonObject
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent
import com.hypixel.hytale.assetstore.map.DefaultAssetMap
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.config.ConfigManager
import me.itemforge.core.AssetCommitter
import me.itemforge.core.OriginalsCache
import me.itemforge.core.OverrideEngine
import me.itemforge.metadata.MaterialIndex
import me.itemforge.metadata.ModSourceTracker
import me.itemforge.metadata.TagCache
import org.bson.BsonDocument

/**
 * Handles LoadedAssetsEvent<Item> — applies saved overrides and rebuilds
 * metadata caches when items are loaded or reloaded.
 *
 * ## Registration (in plugin setup())
 *
 * ```kotlin
 * eventRegistry.register(
 *     LoadedAssetsEvent::class.java,
 *     Item::class.java,
 *     assetLoadListener::onItemsLoaded
 * )
 * ```
 *
 * Evidence: CraftingPlugin.java:141-144 uses this exact pattern.
 *
 * ## Call Sequence
 *
 * This is called by the Hytale engine when:
 * 1. Server starts — all items loaded from mod JSON files (isInitial = true)
 * 2. A mod reloads — items from that mod are re-loaded (isInitial = false)
 * 3. ItemForge commits — loadAssets() fires LoadedAssetsEvent (isInitial = false)
 *
 * For case 3, the [AssetCommitter.flushing] guard prevents re-application.
 *
 * ## Override Application Order
 *
 * ```
 * 1. Skip if committer.flushing (our own commit — don't re-apply)
 * 2. Rebuild metadata (ModSourceTracker, TagCache) for loaded items
 * 3. For each loaded item with a saved override:
 *    a. Capture original values (OriginalsCache) — BEFORE override
 *    b. Apply override (OverrideEngine)
 * 4. Batch commit all overridden items
 * ```
 */
class AssetLoadListener(
    private val configManager: ConfigManager,
    private val overrideEngine: OverrideEngine,
    private val originalsCache: OriginalsCache,
    private val committer: AssetCommitter,
    private val modSourceTracker: ModSourceTracker,
    private val tagCache: TagCache,
    private val logger: HytaleLogger? = null,
    /** Nullable — set after dashboard bridge is created (startup order). */
    var dashboardBridge: me.itemforge.vuetale.DashboardBridge? = null,
    /** Nullable — set after material index is created. Invalidated on asset reload. */
    var materialIndex: MaterialIndex? = null
) {

    /**
     * Handles LoadedAssetsEvent for Item assets.
     *
     * This is the Consumer<LoadedAssetsEvent> registered with the event system.
     * The event provides the newly loaded items via [LoadedAssetsEvent.getLoadedAssets].
     *
     * @param event The loaded assets event containing newly loaded/reloaded items
     */
    fun onItemsLoaded(event: LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>>) {
        // Skip if this is our own commit (feedback loop guard)
        if (committer.flushing) return

        val loadedItems = event.loadedAssets
        if (loadedItems.isEmpty()) return

        var overridesApplied = 0
        val modifiedItems = mutableListOf<Item>()

        for ((itemId, item) in loadedItems) {
            // Rebuild metadata for this item
            val packKey = try {
                event.assetMap.getAssetPack(itemId)
            } catch (_: Exception) { null }
            modSourceTracker.track(itemId, packKey)
            tagCache.index(itemId, item)

            // Apply saved override if one exists
            if (configManager.hasItemOverride(itemId)) {
                val overrideJson = configManager.itemOverrides.getOverride(itemId) ?: continue

                // Capture originals BEFORE applying override
                originalsCache.capture(itemId, item)

                // Convert Gson JsonObject to BSON for the override engine
                val overrideBson = jsonObjectToBson(overrideJson) ?: continue

                if (overrideEngine.applyWithoutSync(itemId, overrideBson)) {
                    // Re-fetch from asset map — Path B may have replaced the object
                    Item.getAssetMap().getAsset(itemId)?.let { modifiedItems.add(it) }
                    overridesApplied++
                }
            }
        }

        // Batch commit all overridden items
        if (modifiedItems.isNotEmpty()) {
            committer.commitItems(modifiedItems)
        }

        if (overridesApplied > 0) {
            logger?.atInfo()?.log("Applied %d saved override(s) on asset load", overridesApplied)
        }

        // Invalidate caches — item stats/names may have changed from asset reload
        dashboardBridge?.invalidateCache()
        materialIndex?.invalidate()
    }

    // ── Conversion ──────────────────────────────────────────────────────

    /**
     * Converts a Gson JsonObject to a MongoDB BsonDocument.
     *
     * Uses BsonDocument.parse() — confirmed available in Hytale runtime
     * (used by TrailOfOrbis GearStashRepository, MapStashRepository).
     */
    private fun jsonObjectToBson(json: JsonObject): BsonDocument? {
        return try {
            BsonDocument.parse(json.toString())
        } catch (e: Exception) {
            logger?.atWarning()?.log("Failed to convert override JSON to BSON: %s", e.message)
            null
        }
    }
}

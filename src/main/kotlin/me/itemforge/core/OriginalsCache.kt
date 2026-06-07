package me.itemforge.core

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import org.bson.BsonDocument

/**
 * Caches pre-override BSON snapshots of items for revert operations.
 *
 * When an override is first applied to an item, the OriginalsCache captures
 * the item's current state as a full BSON document (via `BuilderCodec.encode()`).
 * This snapshot is used to:
 *
 * - **Revert**: Restore the item to its original mod/vanilla values
 * - **Editor "was:" indicators**: Show the original value next to the current override
 * - **Conflict detection**: Compare admin's baseline against current server state
 *
 * ## Not Persisted (CONFIG_VERSIONING.md §6.1)
 *
 * Originals are rebuilt each startup from the live assets BEFORE overrides are applied.
 * Persisting them would risk staleness — if a mod updates item values, the stale
 * persisted originals would be wrong. The authoritative source is always the mod's
 * asset file, re-read at startup.
 *
 * ## Build Sequence (in AssetLoadListener)
 *
 * ```
 * 1. LoadedAssetsEvent fires → items loaded from mod JSON
 * 2. For each item with a saved override:
 *    a. capture(itemId, item)  ← BEFORE applying override
 *    b. overrideEngine.apply(itemId, overrideBson)
 * 3. Originals now reflect the mod's values, item has the override applied
 * ```
 *
 * ## Thread Safety
 *
 * Accessed from the server thread only (during LoadedAssetsEvent, editor open,
 * and revert operations). No concurrent access expected — no locking needed.
 *
 * @param itemCodec The Item BuilderCodec (from CodecScanner.itemCodec)
 */
class OriginalsCache(
    private val itemCodec: BuilderCodec<Item>
) {

    /** Item ID → pre-override BSON snapshot. */
    private val cache = HashMap<String, BsonDocument>()

    /**
     * Captures the current state of an item as a BSON snapshot.
     *
     * Must be called BEFORE overrides are applied. If a snapshot already
     * exists for this item, it is NOT overwritten — the first capture
     * (the true original) is the most valuable.
     *
     * Uses `BuilderCodec.encode()` for a complete snapshot including
     * versioned fields and parent chain (handles version headers internally,
     * as documented in SDK_FINDINGS.md §3.8).
     *
     * @param itemId The item's asset ID
     * @param item The live Item object (pre-override state)
     */
    fun capture(itemId: String, item: Item) {
        if (cache.containsKey(itemId)) return // Already captured — don't overwrite

        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        // BuilderCodec.encode() produces a complete BSON document.
        // It handles version headers and parent chain internally (SDK_FINDINGS.md §3.8).
        // encode() skips null fields (§3.6), so the snapshot is sparse — this is correct,
        // null means "not set" in the codec system.
        val snapshot = itemCodec.encode(item, extraInfo)

        cache[itemId] = snapshot
    }

    /**
     * Returns the pre-override BSON snapshot for the given item.
     * null if no snapshot exists (item was never overridden, or cache was cleared).
     *
     * The returned document is a reference — callers should NOT mutate it.
     * If mutation is needed, clone first.
     */
    fun get(itemId: String): BsonDocument? = cache[itemId]

    /**
     * Removes the snapshot for the given item.
     * Called after a successful revert — the item is back to its original state,
     * no snapshot needed.
     */
    fun remove(itemId: String) {
        cache.remove(itemId)
    }

    /** Whether a snapshot exists for the given item. */
    fun has(itemId: String): Boolean = cache.containsKey(itemId)

    /** Number of items with cached snapshots. */
    fun size(): Int = cache.size

    /**
     * Clears all cached snapshots.
     * Called during reload when all overrides need to be re-applied from scratch.
     */
    fun clear() {
        cache.clear()
    }
}

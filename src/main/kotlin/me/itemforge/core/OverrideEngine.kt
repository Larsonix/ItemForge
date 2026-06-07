package me.itemforge.core

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.builder.BuilderField
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.scanner.CodecScanner
import me.itemforge.util.BsonHelper
import org.bson.BsonDocument
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Applies and reverts item overrides using the Hytale codec pipeline.
 *
 * ## Two Modification Paths
 *
 * **Path A — Direct field decode** (top-level primitives only):
 * Uses `BuilderField.decode()` directly on the existing Item object.
 * Fast — only modifies the fields that changed, no re-decode overhead.
 * Covers: MaxDurability, ItemLevel, MaxStack, Quality, FuelQuality,
 * DropOnDeath, Consumable, and other top-level primitive/string fields.
 *
 * **Path B — Full BSON re-decode** (components and interactions):
 * Encodes the full item to BSON, merges override values, then decodes a
 * fresh Item via `assetStore.decode()`. Preserves all non-overridden fields.
 * Covers: Armor, Weapon, Tool, Glider, Utility, Container (component fields),
 * InteractionVars, Interactions (interaction chain fields).
 *
 * Path B is used for ALL component fields because `BuilderField.decode()` on a
 * compound field creates a NEW sub-object from the partial BSON, wiping
 * non-overridden sub-fields to defaults. Path B's merge-then-decode avoids this.
 *
 * ## Why Two Paths?
 *
 * InteractionVars uses `ContainedAssetCodec` which requires `AssetExtraInfo`
 * (not the default `ExtraInfo`). `codec.decode()` with default ExtraInfo throws
 * `CodecException` wrapping `UnsupportedOperationException` (Probe 1.5 confirmed).
 * Only `assetStore.decode()` provides the correct ExtraInfo context.
 *
 * ## Reflection Budget
 *
 * **Zero reflection.** `Item.invalidatePacketCache()` (public, Item.java:597)
 * replaces the previous `cachedPacket` field reflection. The entire engine now
 * operates through the codec's public API.
 *
 * @param codecScanner For field lookup and codec access
 * @param originalsCache For capturing pre-override state
 * @param committer For syncing modified items to all players
 * @param logger For error reporting
 */
class OverrideEngine(
    private val codecScanner: CodecScanner,
    private val originalsCache: OriginalsCache,
    private val committer: AssetCommitter,
    private val logger: HytaleLogger? = null
) {

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Applies an override to an item, captures originals if first override,
     * and syncs to all players.
     *
     * Routes to Path A or Path B based on what's being changed.
     *
     * @param itemId The item's asset ID
     * @param overrideBson JsonObject-compatible BSON with PascalCase codec keys
     * @return true if the override was applied successfully
     */
    fun applyAndSync(itemId: String, overrideBson: BsonDocument): Boolean {
        val item = Item.getAssetMap().getAsset(itemId)
        if (item == null) {
            logger?.atWarning()?.log("OverrideEngine: Item '$itemId' not found in asset map")
            return false
        }

        // Capture originals before first override
        if (!originalsCache.has(itemId)) {
            originalsCache.capture(itemId, item)
        }

        return try {
            if (canUsePathA(overrideBson)) {
                // Path A: all override keys are top-level primitives/strings.
                // Safe to decode individual fields in-place — no component wipe risk.
                applyViaDirectDecode(itemId, item, overrideBson)
                val modifiedItem = Item.getAssetMap().getAsset(itemId) ?: item
                committer.commitItem(modifiedItem)
            } else {
                // Path B: at least one component/interaction key. Full re-decode
                // merges override into complete BSON, so non-overridden sub-fields
                // are preserved. Uses committer.commitItem() internally for sync
                // + flushing guard (prevents LoadedAssetsEvent feedback loop).
                applyViaFullRedecode(itemId, overrideBson)
            }

            true
        } catch (e: Exception) {
            logger?.atSevere()?.log("OverrideEngine: Failed to apply override for '$itemId': ${e.message}")
            false
        }
    }

    /**
     * Applies an override, deferring sync where possible.
     *
     * For Path A (simple fields): mutates the item in-place without calling loadAssets().
     * The caller is responsible for batching modified items into a single commitItems() call.
     *
     * For Path B (interaction chain fields): calls loadAssets() internally — unavoidable
     * because decode() creates a new Item object that must be inserted into the asset map,
     * and loadAssets() is the only public API for that. Each Path B item syncs individually.
     *
     * @param itemId The item's asset ID
     * @param overrideBson Override BSON document
     * @return true if applied successfully
     */
    fun applyWithoutSync(itemId: String, overrideBson: BsonDocument): Boolean {
        val item = Item.getAssetMap().getAsset(itemId)
        if (item == null) {
            logger?.atWarning()?.log("OverrideEngine: Item '$itemId' not found in asset map")
            return false
        }

        if (!originalsCache.has(itemId)) {
            originalsCache.capture(itemId, item)
        }

        return try {
            if (canUsePathA(overrideBson)) {
                applyViaDirectDecode(itemId, item, overrideBson)
            } else {
                // Path B calls loadAssets() internally — unavoidable for component fields.
                // guardedCommit=true: the flushing guard prevents re-entrant event handling
                // (loadAssets fires LoadedAssetsEvent → AssetLoadListener would re-apply
                // the same override → infinite recursion without the guard).
                applyViaFullRedecode(itemId, overrideBson, guardedCommit = true)
            }
            true
        } catch (e: Exception) {
            logger?.atSevere()?.log("OverrideEngine: Failed to apply override for '$itemId': ${e.message}")
            false
        }
    }

    /**
     * Reverts an item to its original pre-override values.
     *
     * Uses the BSON snapshot from OriginalsCache to restore via full re-decode
     * (safe for all field types including interaction chains).
     *
     * @param itemId The item's asset ID
     * @return true if reverted successfully
     */
    fun revertItem(itemId: String): Boolean {
        val originalBson = originalsCache.get(itemId)
        if (originalBson == null) {
            logger?.atWarning()?.log("OverrideEngine: No original snapshot for '$itemId' — nothing to revert")
            return false
        }

        return try {
            // Decode the original BSON DIRECTLY into a fresh Item — no merge.
            //
            // applyViaFullRedecode uses encode→deepMerge→decode, which fails for revert:
            // deepMerge only adds/replaces keys, it never removes them. Fields that
            // the override ADDED (were "Not set" on the original) survive the merge
            // because the original BSON is sparse (encode() skips null fields per §3.6).
            //
            // Direct decode from the original snapshot restores the exact original state,
            // including the ABSENCE of fields that were "Not set".
            //
            // guardedCommit not needed: override already removed from config by reset()
            // before this async dispatch. AssetLoadListener finds no override → no feedback loop.
            val packKey = try {
                Item.getAssetMap().getAssetPack(itemId) ?: AssetCommitter.PACK_KEY
            } catch (_: Exception) {
                AssetCommitter.PACK_KEY
            }

            val freshItem = Item.getAssetStore().decode(packKey, itemId, originalBson)
            Item.getAssetStore().loadAssets(AssetCommitter.PACK_KEY, listOf(freshItem))

            // Broadcast tooltip correction — DamageBreakdown is transient and computed
            // after LoadedAssetsEvent. The first UpdateItems packet may have stale data.
            committer.broadcastWeaponTooltipCorrection(listOf(freshItem))

            originalsCache.remove(itemId)
            true
        } catch (e: Exception) {
            logger?.atSevere()?.log("OverrideEngine: Failed to revert '$itemId': ${e.message}")
            false
        }
    }

    /**
     * Reverts many items to their originals in a SINGLE engine sync.
     *
     * [revertItem] fires one `loadAssets()` per item — for a batch reset of dozens
     * of items that is N broadcasts and N event passes. This method decodes every
     * original snapshot first, then commits all fresh items through
     * [AssetCommitter.commitItems] (one `loadAssets`, one tooltip-correction pass).
     *
     * **Precondition:** the caller must have already removed each item's override
     * from config (e.g. `ItemOverrideStore.removeOverride`) BEFORE calling this.
     * That is what makes the commit safe — `AssetLoadListener` fires on the
     * resulting `LoadedAssetsEvent` but finds no override to re-apply, so there is
     * no feedback loop (same contract as [revertItem]'s guardedCommit=false path).
     *
     * Items without an originals snapshot are skipped silently — there is nothing
     * live to restore (their override, if any, was config-only and is already gone).
     *
     * @param itemIds The items to revert
     * @return true if every snapshot that existed was decoded and committed without error
     */
    fun batchRevertItems(itemIds: List<String>): Boolean {
        val freshItems = mutableListOf<Item>()
        val revertedIds = mutableListOf<String>()
        var failed = 0

        for (itemId in itemIds) {
            val originalBson = originalsCache.get(itemId) ?: continue // no live override to revert

            try {
                val packKey = try {
                    Item.getAssetMap().getAssetPack(itemId) ?: AssetCommitter.PACK_KEY
                } catch (_: Exception) {
                    AssetCommitter.PACK_KEY
                }

                freshItems.add(Item.getAssetStore().decode(packKey, itemId, originalBson))
                revertedIds.add(itemId)
            } catch (e: Exception) {
                logger?.atSevere()?.log(
                    "OverrideEngine: Failed to decode original for batch revert of '$itemId': ${e.message}"
                )
                failed++
            }
        }

        if (freshItems.isNotEmpty()) {
            // Single sync through AssetCommitter (loadAssets + weapon-tooltip correction).
            committer.commitItems(freshItems)
            revertedIds.forEach { originalsCache.remove(it) }
        }

        if (failed > 0) {
            logger?.atWarning()?.log("OverrideEngine: batch revert: $failed item(s) failed to decode")
        }

        return failed == 0
    }

    /**
     * Applies many Path-B overrides in a SINGLE engine sync.
     *
     * Each item is re-decoded (encode current → deep-merge override → assetStore.decode),
     * then ALL fresh items are committed through one [AssetCommitter.commitItems] (one
     * UpdateItems broadcast) and all child interaction assets are synced in one
     * RootInteraction load. This is the apply-side twin of [batchRevertItems] — it turns
     * an N-item weapon-damage batch from N broadcasts into ~2.
     *
     * Captures each item's original snapshot (on first override) so the change is
     * revertible. The flushing guard in [AssetCommitter.commitItems] prevents the
     * LoadedAssetsEvent feedback loop (overrides ARE in config at this point).
     *
     * @param overrides itemId → fully-built override BSON (e.g. the InteractionVars clone)
     * @return the itemIds that were applied successfully
     */
    fun batchApplyOverrides(overrides: Map<String, BsonDocument>): List<String> {
        val freshItems = mutableListOf<Item>()
        val interactionItems = mutableListOf<Item>()
        val applied = mutableListOf<String>()

        for ((itemId, overrideBson) in overrides) {
            val item = Item.getAssetMap().getAsset(itemId) ?: continue
            if (!originalsCache.has(itemId)) originalsCache.capture(itemId, item)
            try {
                val extraInfo = ExtraInfo.THREAD_LOCAL.get()
                val fullBson = codecScanner.itemCodec.encode(item, extraInfo)
                BsonHelper.deepMerge(fullBson, overrideBson)
                val freshItem = Item.getAssetStore().decode(packKeyFor(itemId), itemId, fullBson)
                freshItems.add(freshItem)
                if (overrideBson.containsKey("InteractionVars")) interactionItems.add(freshItem)
                applied.add(itemId)
            } catch (e: Throwable) {
                // Throwable, not Exception: a deeply-recursive contained-asset chain can
                // StackOverflowError during encode/decode — skip that item, keep the batch.
                logger?.atSevere()?.log("OverrideEngine: batch apply failed for '$itemId': ${e.message}")
            }
        }

        if (freshItems.isNotEmpty()) {
            committer.commitItems(freshItems)
            if (interactionItems.isNotEmpty()) syncChildInteractionAssetsBatch(interactionItems)
        }
        return applied
    }

    /**
     * Restores many items to a per-item PRE-BATCH override snapshot, in a single sync.
     *
     * For each item: decode the ORIGINAL snapshot, and — if [snapshots] holds a non-null
     * override for it — deep-merge that override back in. This re-creates the exact state
     * that existed before a batch, which a single number cannot (a multi-attack weapon's
     * per-attack values can't be reconstructed from one sum). Used for weapon-damage undo.
     *
     * snapshot == null → the item had NO override before the batch, so it reverts fully to
     * original (and its originals snapshot is dropped). snapshot != null → original + the
     * prior override is restored (originals kept, since an override still exists).
     *
     * @param snapshots itemId → pre-batch override BSON (null = was unmodified before)
     * @return the itemIds that were restored successfully
     */
    fun batchRestoreSnapshots(snapshots: Map<String, BsonDocument?>): List<String> {
        val freshItems = mutableListOf<Item>()
        val interactionItems = mutableListOf<Item>()
        val restored = mutableListOf<String>()

        for ((itemId, snapshot) in snapshots) {
            val originalBson = originalsCache.get(itemId) ?: continue // nothing live to restore
            try {
                val merged = if (snapshot != null) {
                    originalBson.clone().also { BsonHelper.deepMerge(it, snapshot) }
                } else {
                    originalBson
                }
                val freshItem = Item.getAssetStore().decode(packKeyFor(itemId), itemId, merged)
                freshItems.add(freshItem)
                if (merged.containsKey("InteractionVars")) interactionItems.add(freshItem)
                if (snapshot == null) originalsCache.remove(itemId) // back to vanilla — no override left
                restored.add(itemId)
            } catch (e: Throwable) {
                // Throwable, not Exception — guard against StackOverflowError on deep chains.
                logger?.atSevere()?.log("OverrideEngine: batch restore failed for '$itemId': ${e.message}")
            }
        }

        if (freshItems.isNotEmpty()) {
            committer.commitItems(freshItems)
            if (interactionItems.isNotEmpty()) syncChildInteractionAssetsBatch(interactionItems)
        }
        return restored
    }

    /** Resolves an item's asset pack key, falling back to the plugin pack. */
    private fun packKeyFor(itemId: String): String = try {
        Item.getAssetMap().getAssetPack(itemId) ?: AssetCommitter.PACK_KEY
    } catch (_: Exception) {
        AssetCommitter.PACK_KEY
    }

    /**
     * Batched variant of [syncChildInteractionAssets] — collects the child RootInteraction
     * assets from EVERY given item and broadcasts them in one RootInteraction load, instead
     * of one broadcast per item.
     */
    private fun syncChildInteractionAssetsBatch(items: List<Item>) {
        try {
            val rootInteractionMap = com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetMap()
            val rootInteractionStore = com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetStore()
            val childAssets = LinkedHashSet<com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction>()
            for (item in items) {
                for ((_, assetId) in item.getInteractionVars()) rootInteractionMap.getAsset(assetId)?.let { childAssets.add(it) }
                for ((_, assetId) in item.getInteractions()) rootInteractionMap.getAsset(assetId)?.let { childAssets.add(it) }
            }
            if (childAssets.isNotEmpty()) {
                rootInteractionStore.loadAssets(AssetCommitter.PACK_KEY, childAssets.toList())
                logger?.atInfo()?.log("OverrideEngine: synced %d child RootInteraction assets for %d items", childAssets.size, items.size)
            }
        } catch (e: Exception) {
            logger?.atWarning()?.log("OverrideEngine: batch child interaction sync failed: ${e.message}")
        }
    }

    // ── Path A: Direct Field Decode ─────────────────────────────────────

    /**
     * Modifies specific fields on an existing Item using BuilderField.decode().
     *
     * Fast path — only touches the fields in the override BSON, leaves everything
     * else untouched. The codec's decode() reads the BSON value and writes it
     * directly to the Item object via the field's setter.
     *
     * Catches ThrowingValidationResults exceptions for individual fields —
     * if one field fails validation, the others are still applied.
     * (SDK_FINDINGS.md §3.5: default ExtraInfo uses ThrowingValidationResults)
     */
    private fun applyViaDirectDecode(itemId: String, item: Item, overrideBson: BsonDocument) {
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        val version = extraInfo.version
        var applied = 0
        var failed = 0

        for ((jsonKey, fieldList) in codecScanner.itemCodec.entries) {
            if (!overrideBson.containsKey(jsonKey)) continue

            val field = selectField(fieldList, version) ?: continue

            try {
                // Create a single-key BSON doc for this field
                // (decode reads from the doc using the field's KeyedCodec key)
                val fieldDoc = BsonDocument()
                fieldDoc.put(jsonKey, overrideBson[jsonKey])

                @Suppress("UNCHECKED_CAST")
                (field as BuilderField<Any, Any>).decode(fieldDoc, item as Any, extraInfo)
                applied++
            } catch (e: Exception) {
                // ThrowingValidationResults or other codec error
                logger?.atWarning()?.log(
                    "OverrideEngine: Field '$jsonKey' on '$itemId' failed to decode: ${e.message}"
                )
                failed++
            }
        }

        // Clear cached packet so toPacket() regenerates with modified values.
        // Public API — no reflection needed (Item.java:597).
        item.invalidatePacketCache()

        if (failed > 0) {
            logger?.atWarning()?.log("OverrideEngine: Path A on '$itemId': $applied applied, $failed failed")
        }
    }

    // ── Path B: Full BSON Re-decode ─────────────────────────────────────

    /**
     * Rebuilds an entire Item from merged BSON via assetStore.decode().
     *
     * This is the only way to modify InteractionVars/Interactions because
     * their codecs (ContainedAssetCodec, CHILD_ASSET_CODEC) require
     * AssetExtraInfo — which only assetStore.decode() provides.
     *
     * Process:
     * 1. Encode the current item to a full BSON document
     * 2. Deep-merge override values into the full BSON
     * 3. Decode a fresh Item from the merged BSON via assetStore.decode()
     * 4. Insert the fresh item via loadAssets() (only public API for asset map insertion)
     *
     * **This method calls loadAssets() directly** — the only way to insert the decoded
     * item into the asset map. Callers should NOT also call commitItem() for Path B items,
     * as that would trigger a redundant second loadAssets(). The loadAssets() call here
     * handles sync (UpdateItems broadcast) automatically.
     */
    /**
     * @param guardedCommit When true, uses committer.commitItem() which sets the
     *   flushing guard — prevents AssetLoadListener feedback loop when a saved
     *   override is still in config. When false, calls loadAssets() directly —
     *   safe when the override has already been removed from config (revert path),
     *   because AssetLoadListener finds nothing to re-apply.
     *
     *   Save path: guardedCommit=true (override exists → feedback loop risk)
     *   Revert path: guardedCommit=false (override removed → no feedback loop)
     *   Startup path (applyWithoutSync): guardedCommit=false (AssetLoadListener
     *     is already running — commitItem's flushing guard would skip the insert)
     */
    private fun applyViaFullRedecode(
        itemId: String,
        overrideBson: BsonDocument,
        guardedCommit: Boolean = true
    ) {
        val item = Item.getAssetMap().getAsset(itemId)
            ?: throw IllegalStateException("Item '$itemId' not found in asset map")

        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        // Step 1: Encode the CURRENT item to full BSON
        val fullBson = codecScanner.itemCodec.encode(item, extraInfo)

        // Step 2: Deep-merge override values
        BsonHelper.deepMerge(fullBson, overrideBson)

        // Step 3: Decode a fresh Item from merged BSON via AssetStore.decode()
        // This method creates AssetExtraInfo internally (line 666 in AssetStore.java),
        // which is required by ContainedAssetCodec for InteractionVars.
        val packKey = try {
            Item.getAssetMap().getAssetPack(itemId) ?: AssetCommitter.PACK_KEY
        } catch (_: Exception) {
            AssetCommitter.PACK_KEY
        }

        val freshItem = Item.getAssetStore().decode(packKey, itemId, fullBson)

        // Step 4: Insert fresh item into asset map + broadcast to all players.
        if (guardedCommit) {
            // Save path: override exists in config → LoadedAssetsEvent would trigger
            // AssetLoadListener to re-apply it → infinite recursion. The committer's
            // flushing guard prevents this.
            committer.commitItem(freshItem)
        } else {
            // Revert/startup path: override already removed from config (or we're
            // inside AssetLoadListener already). Direct loadAssets is safe — the
            // event handler finds no override to re-apply (revert) or is already
            // running (startup). This is the original pattern that was
            // verified working.
            Item.getAssetStore().loadAssets(AssetCommitter.PACK_KEY, listOf(freshItem))

            // Corrected weapon tooltip re-broadcast — same timing issue applies
            // to revert/startup: loadAssets broadcasts before computeWeaponData runs.
            committer.broadcastWeaponTooltipCorrection(listOf(freshItem))
        }

        // Step 6: Sync child RootInteraction assets to clients.
        //
        // CHILD_ASSET_CODEC creates new RootInteraction child assets (with *-prefixed
        // IDs) during assetStore.decode(). These are registered on the SERVER's
        // RootInteraction asset map, but NOT broadcast to clients. Item.toPacket()
        // converts the child asset IDs to integer indices, but clients don't have
        // the assets at those indices.
        //
        // Fix: call loadAssets() on the RootInteraction store with the new child
        // assets. RootInteractionPacketGenerator generates an update packet that
        // the client processes, adding the new entries to its indexed map.
        // On reconnect this happens via InitRootInteractions, but during live
        // sessions we must trigger it explicitly.
        //
        // Only needed when InteractionVars are modified (child assets recreated).
        // For non-interaction overrides (armor, tools), the child assets are
        // unchanged and already synced from the initial load.
        if (overrideBson.containsKey("InteractionVars")) {
            syncChildInteractionAssets(freshItem)
        }
    }

    /**
     * Broadcasts new RootInteraction child assets created during Path B re-decode.
     *
     * When CHILD_ASSET_CODEC processes InteractionVars during decode, it creates
     * new RootInteraction assets with `*`-prefixed IDs and registers them in the
     * server's RootInteraction asset map. But [HytaleAssetStore.handleRemoveOrUpdate]
     * only broadcasts for the asset store that [loadAssets] was called on (Item store).
     * The RootInteraction store never gets a loadAssets call, so clients never
     * receive the new child assets.
     *
     * This method collects the child assets from the fresh item's interactionVars
     * (and interactions) maps and loads them into the RootInteraction store,
     * triggering [RootInteractionPacketGenerator.generateUpdatePacket] → broadcast.
     */
    private fun syncChildInteractionAssets(freshItem: Item) {
        try {
            val rootInteractionMap = com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetMap()
            val rootInteractionStore = com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction.getAssetStore()
            val childAssets = mutableListOf<com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction>()

            // Collect child assets from interactionVars (weapon damage, etc.)
            for ((_, assetId) in freshItem.getInteractionVars()) {
                val asset = rootInteractionMap.getAsset(assetId)
                if (asset != null) {
                    childAssets.add(asset)
                }
            }

            // Collect child assets from interactions (attack type → RootInteraction)
            for ((_, assetId) in freshItem.getInteractions()) {
                val asset = rootInteractionMap.getAsset(assetId)
                if (asset != null && asset !in childAssets) {
                    childAssets.add(asset)
                }
            }

            if (childAssets.isNotEmpty()) {
                rootInteractionStore.loadAssets(AssetCommitter.PACK_KEY, childAssets)
                logger?.atInfo()?.log(
                    "OverrideEngine: Synced %d child RootInteraction assets for '%s'",
                    childAssets.size, freshItem.id
                )
            }
        } catch (e: Exception) {
            logger?.atWarning()?.log(
                "OverrideEngine: Failed to sync child interaction assets: ${e.message}"
            )
        }
    }

    // ── Routing ───────────────────────────────────────────────────────────

    /**
     * Determines whether ALL override keys are safe for Path A (direct field decode).
     *
     * Path A is ONLY safe for top-level fields whose child codec is PrimitiveCodec
     * (or whose BSON value is a simple string). For component fields (Armor, Weapon,
     * Tool, etc.), BuilderField.decode() creates a NEW sub-object from the partial
     * override BSON, **wiping all non-overridden sub-fields to defaults**.
     *
     * InteractionVars/Interactions always require Path B (ContainedAssetCodec
     * needs AssetExtraInfo).
     *
     * Any override containing even ONE non-primitive key must use Path B, which
     * does full encode → merge → decode and preserves all existing values.
     */
    private fun canUsePathA(overrideBson: BsonDocument): Boolean {
        if (overrideBson.isEmpty()) return false

        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        val version = extraInfo.version

        for (key in overrideBson.keys) {
            // InteractionVars/Interactions always need Path B
            if (key == "InteractionVars" || key == "Interactions") return false

            // Quality requires Path B: the codec stores a string qualityId, but
            // processConfig() (Item.java:1178-1179) resolves it to an integer
            // qualityIndex used in the network packet (toPacket line 710).
            // processConfig only runs via afterDecode — Path A's in-place decode
            // skips it, leaving qualityIndex stale until server restart.
            if (key == "Quality") return false

            // Look up the field in the codec
            val fieldList = codecScanner.itemCodec.entries[key] ?: return false
            val field = selectField(fieldList, version) ?: return false

            // Only primitives and strings are safe for direct decode
            val childCodec = field.codec.childCodec
            if (childCodec !is com.hypixel.hytale.codec.PrimitiveCodec) {
                // Check if it's a string value (StringCodec is not PrimitiveCodec)
                val bsonValue = overrideBson[key]
                if (bsonValue == null || bsonValue.bsonType != org.bson.BsonType.STRING) {
                    return false // Component or complex field — needs Path B
                }
            }
        }

        return true
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Version-aware field selection. Replicates BuilderCodec.findField()
     * (which is protected — SDK_FINDINGS.md §3.3).
     *
     * Returns the first field in the list that supports the given version.
     * With default ExtraInfo (version = MAX_VALUE), this naturally selects
     * current non-deprecated field definitions.
     */
    private fun selectField(
        fields: List<BuilderField<Item, *>>,
        version: Int
    ): BuilderField<Item, *>? {
        for (field in fields) {
            if (field.supportsVersion(version)) return field
        }
        return null
    }

}

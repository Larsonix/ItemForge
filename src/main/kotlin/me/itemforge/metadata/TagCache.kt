package me.itemforge.metadata

import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.assetstore.AssetRegistry

/**
 * Caches item tag data for dashboard filtering.
 *
 * Scans `item.data.getRawTags()` for all items, building a catalog of tag keys
 * and their possible values. Provides tag-based item queries via the engine's
 * native `AssetRegistry.getTagIndex()` + `AssetMap.getKeysForTag()`.
 *
 * ## Tag Structure (from AssetExtraInfo.Data)
 *
 * `getRawTags()` returns a `Map<String, String[]>` that includes EXPANDED entries:
 * ```
 * "Type" → ["Armor"]           ← real tag (key with values — we want this)
 * "Armor" → []                 ← promoted value (empty array — skip)
 * "Type=Armor" → []            ← composite (contains "=" — skip)
 * ```
 *
 * We filter to keep only entries with non-empty value arrays and no "=" in the key.
 *
 * ## Runtime Evidence (Probe 1.6)
 *
 * - 5,312 items with tags, 211 without
 * - 6 tag keys: BranchType, Family, Material, Spreadable, SubType, Type
 * - 46 Type values: Armor (1,747), Rock (1,532), Weapon (574), Furniture (445), ...
 * - Tag not-found sentinel: `Integer.MIN_VALUE` (-2147483648)
 * - `getKeysForTag()` returns correct item sets (445 for Type=Furniture)
 *
 * ## Lifecycle
 *
 * Call [scan] once during plugin start.
 * Call [index] from AssetLoadListener when items are loaded/reloaded at runtime.
 */
class TagCache {

    /** Tag key → set of possible values (e.g., "Type" → {"Armor", "Weapon", ...}). */
    private val tagValues = HashMap<String, LinkedHashSet<String>>()

    /**
     * Scans all items and builds the tag catalog.
     * Call once during plugin start.
     */
    fun scan() {
        tagValues.clear()

        for (item in Item.getAssetMap().assetMap.values) {
            indexItem(item)
        }
    }

    /**
     * Indexes a single item's tags. Called from AssetLoadListener
     * when items are loaded/reloaded at runtime.
     */
    fun index(itemId: String, item: Item) {
        indexItem(item)
    }

    /**
     * Returns all known values for a tag key.
     * E.g., `getValuesForKey("Type")` → {"Armor", "Weapon", "Tool", ...}
     * Used to populate the Type filter dropdown in the dashboard.
     */
    fun getValuesForKey(key: String): Set<String> {
        return tagValues[key] ?: emptySet()
    }

    /**
     * Returns all known tag keys.
     * E.g., {"BranchType", "Family", "Material", "Spreadable", "SubType", "Type"}
     */
    fun getAllTagKeys(): Set<String> = tagValues.keys

    /**
     * Returns all item IDs that have the given tag.
     *
     * Uses the engine's native tag query API — O(1) lookup via integer tag index.
     * Returns empty set if the tag doesn't exist (sentinel = Integer.MIN_VALUE).
     *
     * @param key Tag key (e.g., "Type")
     * @param value Tag value (e.g., "Armor")
     * @return Set of item IDs matching the tag
     */
    fun getItemsWithTag(key: String, value: String): Set<String> {
        val tagIndex = try {
            AssetRegistry.getTagIndex("$key=$value")
        } catch (_: Exception) {
            return emptySet()
        }

        // Integer.MIN_VALUE = tag not found (confirmed by Probe 1.6)
        if (tagIndex == Int.MIN_VALUE) return emptySet()

        return try {
            Item.getAssetMap().getKeysForTag(tagIndex)
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Returns the primary "Type" tag for an item, or null if untagged.
     * Convenience method for the dashboard's type column.
     */
    fun getItemType(item: Item): String? {
        val rawTags = try {
            item.data?.rawTags
        } catch (_: Exception) { null }

        val types = rawTags?.get("Type")
        return if (types != null && types.isNotEmpty()) types[0] else null
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Indexes one item's tags into the catalog.
     *
     * Filters rawTags to keep only real key→values entries:
     * - Skip keys containing "=" (expanded composites like "Type=Armor")
     * - Skip entries with empty value arrays (promoted values like "Armor" → [])
     */
    private fun indexItem(item: Item) {
        val rawTags = try {
            item.data?.rawTags
        } catch (_: Exception) { null }

        if (rawTags == null) return

        for ((key, values) in rawTags) {
            // Filter expanded entries (SDK_FINDINGS.md §9, Probe 1.6)
            if (key.contains("=")) continue
            if (values.isEmpty()) continue

            val valueSet = tagValues.getOrPut(key) { LinkedHashSet() }
            for (v in values) {
                valueSet.add(v)
            }
        }
    }
}

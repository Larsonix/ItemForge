package me.itemforge.metadata

import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.asset.AssetModule

/**
 * Tracks which mod/asset pack each item came from.
 *
 * Builds an item→mod mapping during [scan] and provides O(1) lookup for
 * the dashboard's "Source Mod" column and mod filter dropdown.
 *
 * ## Resolution Chain
 *
 * ```
 * DefaultAssetMap.getAssetPack(itemId) → pack key (e.g., "LadyPaladra:TheArmory")
 * AssetModule.get().getAssetPack(packKey) → AssetPack object
 * AssetPack.getManifest().getName() → display name (e.g., "TheArmory")
 * Fallback: packKey.substringAfter(":") → mod name from pack key format
 * ```
 *
 * ## Runtime Evidence (Probe 1.6)
 *
 * 11 mods detected on test server:
 * Hytale (3,551), TheArmory (840), Hexcode_PatcherOverrides (625),
 * TrailOfOrbis (398), Hexcode (41), pillows-n-plushies (31),
 * ModernStorage (25), BattleGauntlets (9), +3 more.
 *
 * All resolved via manifest name. Fallback (substringAfter) is the safety net
 * for mods without proper manifests.
 *
 * ## Lifecycle
 *
 * Call [scan] once during plugin start (after assets are loaded).
 * Call [track] from AssetLoadListener when new items are loaded at runtime.
 */
class ModSourceTracker {

    /** Item ID → resolved mod display name. */
    private val itemModMap = HashMap<String, String>()

    /** All unique mod names seen, in insertion order (for dropdown stability). */
    private val knownMods = LinkedHashSet<String>()

    /**
     * Scans all items in the asset map and builds the item→mod mapping.
     * Call once during plugin start.
     */
    fun scan() {
        val assetMap = Item.getAssetMap()
        val assetModule = try { AssetModule.get() } catch (_: Exception) { null }

        for (itemId in assetMap.assetMap.keys) {
            val packKey = try {
                assetMap.getAssetPack(itemId)
            } catch (_: Exception) { null }

            // If the item is registered under ItemForge's own pack key (e.g., from a
            // previous session that wasn't reverted), the real mod source is lost.
            // Store "Unknown" rather than "overrides" — and don't overwrite if already tracked.
            if (isItemForgePack(packKey)) {
                if (!itemModMap.containsKey(itemId)) {
                    itemModMap[itemId] = UNKNOWN
                    knownMods.add(UNKNOWN)
                }
                continue
            }

            val modName = resolveModName(packKey, assetModule)
            itemModMap[itemId] = modName
            knownMods.add(modName)
        }
    }

    /**
     * Tracks a single item's mod source. Called from AssetLoadListener
     * when items are loaded/reloaded at runtime.
     *
     * Ignores ItemForge's own pack key to preserve the real mod source.
     * Path B's applyViaFullRedecode() calls loadAssets(PACK_KEY) directly
     * without the committer's flushing guard, so AssetLoadListener may
     * receive a LoadedAssetsEvent with "ItemForge:overrides" as the pack key.
     *
     * @param itemId The item's asset ID
     * @param packKey The pack key from the LoadedAssetsEvent (nullable)
     */
    fun track(itemId: String, packKey: String?) {
        // Don't overwrite a real mod source with ItemForge's commit pack key
        if (isItemForgePack(packKey) && itemModMap.containsKey(itemId)) return

        val assetModule = try { AssetModule.get() } catch (_: Exception) { null }
        val modName = resolveModName(packKey, assetModule)
        itemModMap[itemId] = modName
        knownMods.add(modName)
    }

    /**
     * Returns the mod display name for the given item.
     * O(1) HashMap lookup. Returns "Unknown" if the item wasn't tracked.
     */
    fun getModName(itemId: String): String {
        return itemModMap[itemId] ?: UNKNOWN
    }

    /**
     * Returns all known mod names, in the order they were first seen.
     * Used to populate the mod filter dropdown in the dashboard.
     */
    fun getAllModNames(): Set<String> = knownMods

    /**
     * Returns the number of items tracked from the given mod.
     */
    fun getItemCount(modName: String): Int {
        return itemModMap.values.count { it == modName }
    }

    /**
     * Resolves an asset pack key to its human-readable mod display name.
     *
     * Public, generic counterpart to the item-keyed [getModName]: callers that hold
     * a pack key for a non-item asset (e.g. an [com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType])
     * can attribute it to its mod with the SAME resolution chain (manifest name → pack-key
     * suffix). Returns [UNKNOWN] for a null/unresolvable pack key. Vanilla assets resolve
     * to "Hytale".
     */
    fun resolvePackToModName(packKey: String?): String {
        val assetModule = try { AssetModule.get() } catch (_: Exception) { null }
        return resolveModName(packKey, assetModule)
    }

    /**
     * Resolves a pack key to a human-readable mod name.
     *
     * Tries manifest resolution first (AssetPack → manifest → name).
     * Falls back to extracting the mod name from the pack key format "Author:ModName".
     */
    private fun resolveModName(packKey: String?, assetModule: AssetModule?): String {
        if (packKey == null) return UNKNOWN

        // Tier 1: Manifest resolution
        if (assetModule != null) {
            try {
                val pack = assetModule.getAssetPack(packKey)
                if (pack != null) {
                    val manifest = pack.manifest
                    if (manifest != null) {
                        val name = manifest.name
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            } catch (_: Exception) {
                // Fall through to string parsing
            }
        }

        // Tier 2: Extract from pack key format "Author:ModName"
        // substringAfter gives the mod name; substringBefore would give the author
        return packKey.substringAfter(":", packKey)
    }

    /**
     * Returns true if the pack key belongs to ItemForge's own loadAssets() commits.
     * These should never be treated as a real mod source.
     */
    private fun isItemForgePack(packKey: String?): Boolean {
        return packKey != null && packKey.startsWith(ITEMFORGE_PACK_PREFIX)
    }

    companion object {
        private const val UNKNOWN = "Unknown"

        /** Prefix of pack keys used by ItemForge for committed overrides. */
        private const val ITEMFORGE_PACK_PREFIX = "ItemForge:"
    }
}

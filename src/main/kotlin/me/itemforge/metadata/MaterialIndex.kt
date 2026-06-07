package me.itemforge.metadata

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.scanner.CodecScanner
import me.itemforge.util.ValueFormatter
import org.bson.BsonType

/**
 * Searchable index of all items and resource types for the recipe input picker.
 *
 * ## Why This Exists
 *
 * The recipe input editor needs to let admins search and select from ~5,500+ items
 * and ~90 resource types. A Vuetale DropdownBox with that many entries would exceed
 * the element budget (~500 comfortable, ~800 limit). Instead, the editor uses a
 * bridge-powered search: admin types a query → JS calls [search] via the bridge →
 * Kotlin fuzzy-matches server-side → returns top N results as JSON.
 *
 * ## Search Algorithm
 *
 * Fuzzy word-based matching (same as dashboard): split query into lowercase words,
 * ALL words must appear in the entry's search text (displayName + " " + id, lowercased).
 * Results sorted by relevance: exact display name prefix first, then shorter names.
 *
 * ## Lifecycle
 *
 * Built lazily on first [search] call. Invalidated via [invalidate] when assets reload
 * (AssetLoadListener). Thread-safe: immutable list replaced atomically via @Volatile.
 *
 * ## Performance
 *
 * - Build: ~200ms (dominated by ItemNameResolver I18n calls, cached after first run)
 * - Search: ~1-3ms (O(n) scan of ~5,600 pre-computed strings)
 *
 * @param codecScanner For encoding recipes to extract ResourceTypeId values from BSON
 * @param logger Optional logger for diagnostics
 */
class MaterialIndex(
    private val codecScanner: CodecScanner,
    private val logger: HytaleLogger? = null
) {

    /**
     * A single searchable material entry.
     *
     * Serialized to JSON by Gson and sent to the UI via the bridge's searchMaterials().
     * [searchText] is @Transient — excluded from JSON output (used only server-side).
     *
     * @property id The asset ID (item ID or ResourceTypeId)
     * @property displayName Human-readable name for the UI
     * @property type "item" for specific items, "resource" for ResourceTypeId wildcards
     * @property searchText Pre-computed lowercase search text: "$displayName $id"
     */
    data class MaterialEntry(
        val id: String,
        val displayName: String,
        val type: String,
        @Transient val searchText: String = ""
    )

    /** Immutable entry list. Replaced atomically on rebuild. */
    @Volatile
    private var entries: List<MaterialEntry> = emptyList()

    /** Whether the index has been built at least once. */
    @Volatile
    private var built = false

    // ── Build ─────────────────────────────────────────────────────────────

    /**
     * Builds the index from current game assets.
     *
     * Collects all items via [Item.getAssetMap] and all unique ResourceTypeId values
     * from [CraftingRecipe.getAssetMap] (via BSON encoding). Safe to call multiple
     * times (rebuilds from scratch). Called lazily on first search.
     */
    fun build() {
        val result = mutableListOf<MaterialEntry>()

        // ── Items ────────────────────────────────────────────────────────
        val itemMap = Item.getAssetMap()
        for (item in itemMap.assetMap.values) {
            val id = item.id ?: continue
            val name = ItemNameResolver.resolve(item)
            result.add(MaterialEntry(
                id = id,
                displayName = name,
                type = "item",
                searchText = "${name.lowercase()} ${id.lowercase()}"
            ))
        }

        // ── Resource types from recipes ──────────────────────────────────
        // Encode each recipe to BSON and extract unique ResourceTypeId values
        // from Input arrays. Same pattern as RecipeScanner.extractInputs().
        val resourceTypes = mutableSetOf<String>()
        val recipeCodec = codecScanner.recipeCodec
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        val recipeMap = CraftingRecipe.getAssetMap()

        for (recipe in recipeMap.assetMap.values) {
            try {
                val bson = recipeCodec.encode(recipe, extraInfo)
                val inputVal = bson["Input"] ?: continue
                if (inputVal.bsonType != BsonType.ARRAY) continue
                for (element in inputVal.asArray()) {
                    if (element.bsonType != BsonType.DOCUMENT) continue
                    val doc = element.asDocument()
                    doc["ResourceTypeId"]?.let {
                        if (it.bsonType == BsonType.STRING) resourceTypes.add(it.asString().value)
                    }
                }
            } catch (_: Exception) {
                // Skip recipes that fail to encode — non-critical
            }
        }

        for (rtId in resourceTypes.sorted()) {
            val name = ValueFormatter.formatFieldName(rtId)
            result.add(MaterialEntry(
                id = rtId,
                displayName = "$name (Resource)",
                type = "resource",
                searchText = "${name.lowercase()} resource ${rtId.lowercase()}"
            ))
        }

        // Sort: items first (alphabetical by displayName), then resources
        result.sortWith(compareBy({ it.type != "item" }, { it.displayName.lowercase() }))

        entries = result
        built = true

        logger?.atInfo()?.log(
            "MaterialIndex: indexed %d items + %d resource types = %d entries",
            result.count { it.type == "item" },
            resourceTypes.size,
            result.size
        )
    }

    /**
     * Marks the index as stale. Next [search] call will rebuild from current assets.
     *
     * Called from [AssetLoadListener] when game assets reload (mod install, `/itemforge reload`).
     */
    fun invalidate() {
        built = false
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Fuzzy word-based search across all materials.
     *
     * Split [query] into lowercase words. An entry matches if ALL words appear in its
     * [MaterialEntry.searchText]. Results sorted by relevance:
     * 1. Display name starts with the full query (exact prefix)
     * 2. Shorter display names first (more specific matches)
     *
     * Runs on V8 thread via bridge. Pure computation — no world thread access.
     *
     * @param query Admin's search text (e.g., "iron ingot" or "wood")
     * @param limit Maximum results to return (default 8)
     * @return Matching entries, sorted by relevance
     */
    fun search(query: String, limit: Int = 8): List<MaterialEntry> {
        if (!built) build()

        val words = query.lowercase().trim().split("\\s+".toRegex())
        if (words.isEmpty() || words.all { it.isBlank() }) return emptyList()

        val queryLower = query.lowercase().trim()

        return entries.asSequence()
            .filter { entry -> words.all { word -> entry.searchText.contains(word) } }
            .sortedWith(compareBy(
                // Exact display name prefix match first
                { !it.displayName.lowercase().startsWith(queryLower) },
                // Items before resource types
                { it.type != "item" },
                // Shorter names = more specific matches
                { it.displayName.length }
            ))
            .take(limit)
            .toList()
    }
}

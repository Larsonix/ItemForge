package me.itemforge.metadata

import com.hypixel.hytale.protocol.BenchType
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Discovers all crafting benches and their panels at startup.
 *
 * ## Discovery Strategy
 *
 * Instead of scanning all 5,500+ items for BlockType.Bench configs, we scan the
 * CraftingRecipe asset map (~1,600 entries). Every recipe declares its bench
 * requirements, which tells us:
 * - Which bench IDs exist (e.g., "Workbench", "Weapon_Bench", "Armor_Bench")
 * - What type each bench is (Crafting, Processing, DiagramCrafting, StructuralCrafting)
 * - Which panel categories each bench uses (e.g., "Weapon_Sword", "Armor_Head")
 *
 * This is O(recipes) instead of O(items) — fast and complete because every bench
 * referenced by any recipe is automatically discovered, including mod benches.
 *
 * ## Usage
 *
 * Called once during plugin start (after assets are loaded). The resulting map
 * is included in the editor payload so the Vue UI can build bench dropdowns
 * for the Recipe tab.
 *
 * @param logger For logging discovery results
 */
class BenchRegistry(private val logger: HytaleLogger? = null) {

    /** benchId → BenchInfo. Ordered by insertion (alphabetical after sorting). */
    private val benches = LinkedHashMap<String, BenchInfo>()

    /**
     * Scans all recipes for bench references and builds the registry.
     *
     * Safe to call multiple times (clears and rebuilds). Called on startup
     * and on `/itemforge reload`.
     */
    fun init() {
        benches.clear()

        // Intermediate: collect unique bench data from all recipes
        val benchData = LinkedHashMap<String, MutableBenchData>()

        val recipeMap = CraftingRecipe.getAssetMap()
        for (recipe in recipeMap.assetMap.values) {
            val benchReqs = recipe.benchRequirement ?: continue
            for (req in benchReqs) {
                val id = req.id
                if (id.isBlank()) continue

                val data = benchData.getOrPut(id) {
                    MutableBenchData(
                        type = req.type.name,
                        panels = mutableSetOf(),
                        maxTier = 0
                    )
                }

                // Collect categories (panels) from this recipe
                val categories = req.categories
                if (categories != null) {
                    for (cat in categories) {
                        if (cat.isNotBlank()) {
                            data.panels.add(cat)
                        }
                    }
                }

                // Track max required tier level observed
                if (req.requiredTierLevel > data.maxTier) {
                    data.maxTier = req.requiredTierLevel
                }
            }
        }

        // Convert to immutable BenchInfo, sorted by ID for consistent UI ordering
        for (id in benchData.keys.sorted()) {
            val data = benchData[id]!!
            benches[id] = BenchInfo(
                id = id,
                type = data.type,
                displayName = formatBenchName(id),
                panels = data.panels.sorted(),
                maxTier = data.maxTier
            )
        }

        logger?.atInfo()?.log(
            "BenchRegistry: discovered %d bench(es) from %d recipe(s)",
            benches.size, recipeMap.assetMap.size
        )
    }

    // ── Queries ──────────────────────────────────────────────────────────

    /** Returns all benches as a map for serialization into the editor payload. */
    fun toPayload(): Map<String, BenchInfo> = benches.toMap()

    /** Returns bench info for a specific bench ID. */
    fun getBenchInfo(benchId: String): BenchInfo? = benches[benchId]

    /** Returns all bench IDs of a given type. */
    fun getBenchesForType(type: String): List<BenchInfo> =
        benches.values.filter { it.type == type }

    /** Returns panel/category IDs for a specific bench. */
    fun getPanelsForBench(benchId: String): List<String> =
        benches[benchId]?.panels ?: emptyList()

    /** Total number of discovered benches. */
    fun size(): Int = benches.size

    // ── Display Name Formatting ─────────────────────────────────────────

    /**
     * Converts a bench ID to a human-readable display name.
     *
     * Examples:
     * - "Workbench" → "Workbench"
     * - "Weapon_Bench" → "Weapon Bench"
     * - "Cookingbench" → "Cooking Bench"
     * - "Alchemybench" → "Alchemy Bench"
     * - "Salvagebench" → "Salvage Bench"
     * - "Farmingbench" → "Farming Bench"
     * - "Loombench" → "Loom Bench"
     * - "Arcanebench" → "Arcane Bench"
     * - "Furniture_Bench" → "Furniture Bench"
     * - "Fieldcraft" → "Fieldcraft"
     * - "Armory" → "Armory"
     */
    private fun formatBenchName(id: String): String {
        // Handle "Xbench" pattern (Cookingbench, Alchemybench, etc.)
        val benchSuffix = id.lowercase().indexOf("bench")
        if (benchSuffix > 0 && !id.contains('_')) {
            val prefix = id.substring(0, benchSuffix)
            return "${prefix.replaceFirstChar { it.uppercase() }} Bench"
        }

        // Handle underscore-separated (Weapon_Bench, Armor_Bench, Furniture_Bench)
        if (id.contains('_')) {
            return id.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }

        // Already clean (Workbench, Armory, Fieldcraft, Furnace, etc.)
        return id
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private class MutableBenchData(
        val type: String,
        val panels: MutableSet<String>,
        var maxTier: Int
    )
}

/**
 * Immutable bench info for the editor payload.
 *
 * Serialized to JSON and sent to the Vue UI as part of [RecipeData.benchRegistry].
 * The UI uses this to populate bench-related dropdowns in the Recipe tab.
 */
data class BenchInfo(
    /** Bench ID (e.g., "Workbench", "Weapon_Bench"). */
    val id: String,
    /** Bench type: "Crafting", "Processing", "DiagramCrafting", "StructuralCrafting". */
    val type: String,
    /** Human-readable display name (e.g., "Weapon Bench"). */
    val displayName: String,
    /** Panel/category IDs available on this bench. Empty for Processing benches. */
    val panels: List<String>,
    /** Maximum tier level observed in recipes (0 = base only). */
    val maxTier: Int
)

package me.itemforge.core

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import org.bson.BsonDocument

/**
 * Caches pre-override BSON snapshots of [CraftingRecipe] objects for revert.
 *
 * Same architecture as [OriginalsCache] but typed to CraftingRecipe.
 * Captures the full recipe state via codec encode before the first override
 * is applied, so we can restore the original recipe when the admin resets.
 *
 * ## Lifecycle
 *
 * - **Capture**: Called by [RecipeOverrideEngine.applyAndSync] before first override.
 *   Also called during startup for saved recipe overrides.
 * - **Retrieve**: Called by [RecipeScanner.scan] for "was:" indicators,
 *   and by [RecipeOverrideEngine.revertRecipe] for restore.
 * - **Remove**: Called by [RecipeOverrideEngine.revertRecipe] after successful restore.
 * - **Clear**: Called on `/itemforge reload` to invalidate stale snapshots.
 *
 * @param recipeCodec The CraftingRecipe BuilderCodec for BSON encoding
 */
class RecipeOriginalsCache(
    private val recipeCodec: BuilderCodec<CraftingRecipe>
) {

    /** recipeId → pre-override BSON snapshot. */
    private val cache = HashMap<String, BsonDocument>()

    /**
     * Captures the current recipe state as a BSON snapshot.
     *
     * No-op if already captured — the FIRST snapshot is always the original.
     * Must be called from a thread with [ExtraInfo.THREAD_LOCAL] available.
     */
    fun capture(recipeId: String, recipe: CraftingRecipe) {
        if (cache.containsKey(recipeId)) return
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        cache[recipeId] = recipeCodec.encode(recipe, extraInfo)
    }

    /** Returns the original BSON snapshot, or null if never captured. */
    fun get(recipeId: String): BsonDocument? = cache[recipeId]

    /** Whether a snapshot exists for this recipe. */
    fun has(recipeId: String): Boolean = cache.containsKey(recipeId)

    /** Removes the snapshot after a successful revert. */
    fun remove(recipeId: String) { cache.remove(recipeId) }

    /** Number of cached snapshots. */
    fun size(): Int = cache.size

    /** Clears all snapshots (used on reload). */
    fun clear() { cache.clear() }
}

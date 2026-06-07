package me.itemforge.core

import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import me.itemforge.scanner.CodecScanner
import org.bson.BsonDocument
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Applies and reverts recipe overrides via the CraftingRecipe codec pipeline.
 *
 * ## Design: Full Replacement, Not Merge
 *
 * Unlike [OverrideEngine] which uses deepMerge for items, recipe overrides use
 * **full BSON replacement**. The override document IS the complete recipe state.
 *
 * Why: Recipe inputs are arrays. Per-index patching breaks when a mod reorders
 * its recipe inputs between updates. Full replacement is the only safe strategy.
 * (See RecipeOverrideStore.kt KDoc for rationale.)
 *
 * ## Always Path B (Full Re-decode)
 *
 * No Path A routing needed — recipes are small objects with array fields.
 * Direct `BuilderField.decode()` on arrays creates NEW arrays from partial BSON,
 * which is exactly what we want (full replacement).
 *
 * ## No Weapon Tooltip Correction
 *
 * Unlike item modifications, recipe commits don't have transient computed fields.
 * The `UpdateRecipes` packet sent by `loadAssets()` is complete and accurate.
 *
 * @param codecScanner For access to the recipeCodec
 * @param originalsCache For capturing pre-override snapshots
 * @param committer For committing modified recipes to all players
 * @param logger For error reporting
 */
class RecipeOverrideEngine(
    private val codecScanner: CodecScanner,
    private val originalsCache: RecipeOriginalsCache,
    private val committer: AssetCommitter,
    private val logger: HytaleLogger? = null
) {

    /**
     * Applies a recipe override and syncs to all connected players.
     *
     * 1. Captures the original recipe if first override
     * 2. Decodes a fresh CraftingRecipe from the override BSON
     * 3. Commits via [AssetCommitter.commitRecipe]
     *
     * @param recipeId The recipe ID (e.g., "Armor_Iron_Chest_Recipe_Generated_0")
     * @param overrideBson Complete recipe BSON document (full replacement)
     * @return true if successfully applied
     */
    fun applyAndSync(recipeId: String, overrideBson: BsonDocument): Boolean {
        // A null asset is NOT an error here: it means this is a CREATE — the item never had
        // a recipe and we're registering a brand-new one (the crafting system's source of
        // truth is this asset map, so loadAssets-ing a fresh recipe makes the item craftable).
        // Only capture an "original" when a real recipe exists to revert to; a created recipe
        // has none (reset deletes it instead — see EditorBridge.reset / deleteRecipe).
        val existing = CraftingRecipe.getAssetMap().getAsset(recipeId)
        if (existing != null && !originalsCache.has(recipeId)) {
            originalsCache.capture(recipeId, existing)
        }

        return try {
            val packKey = try {
                CraftingRecipe.getAssetMap().getAssetPack(recipeId) ?: AssetCommitter.PACK_KEY
            } catch (_: Exception) {
                AssetCommitter.PACK_KEY
            }

            // Decode a fresh CraftingRecipe from the override BSON
            val freshRecipe = CraftingRecipe.getAssetStore().decode(packKey, recipeId, overrideBson)

            // Commit: loadAssets → UpdateRecipes packet → all players see new recipe
            committer.commitRecipe(freshRecipe)

            logger?.atInfo()?.log("RecipeOverrideEngine: Applied override for '%s'", recipeId)
            true
        } catch (e: Exception) {
            logger?.atSevere()?.withCause(e)?.log(
                "RecipeOverrideEngine: Failed to apply override for '%s'", recipeId
            )
            false
        }
    }

    /**
     * Reverts a recipe to its original pre-override state.
     *
     * Uses the BSON snapshot captured by [RecipeOriginalsCache] before the first override.
     * After successful revert, removes the snapshot from the cache.
     *
     * @param recipeId The recipe ID to revert
     * @return true if successfully reverted
     */
    fun revertRecipe(recipeId: String): Boolean {
        val originalBson = originalsCache.get(recipeId)
        if (originalBson == null) {
            logger?.atWarning()?.log("RecipeOverrideEngine: No original snapshot for '%s' — nothing to revert", recipeId)
            return false
        }

        return try {
            val packKey = try {
                CraftingRecipe.getAssetMap().getAssetPack(recipeId) ?: AssetCommitter.PACK_KEY
            } catch (_: Exception) {
                AssetCommitter.PACK_KEY
            }

            // Decode the original recipe from the pre-override snapshot
            val freshRecipe = CraftingRecipe.getAssetStore().decode(packKey, recipeId, originalBson)

            // Commit original recipe back
            committer.commitRecipe(freshRecipe)

            // Remove the snapshot — recipe is back to original
            originalsCache.remove(recipeId)

            logger?.atInfo()?.log("RecipeOverrideEngine: Reverted '%s' to original", recipeId)
            true
        } catch (e: Exception) {
            logger?.atSevere()?.withCause(e)?.log(
                "RecipeOverrideEngine: Failed to revert '%s'", recipeId
            )
            false
        }
    }

    /**
     * Deletes a recipe that ItemForge CREATED (the item had no recipe of its own).
     *
     * Unlike [revertRecipe], there is no original to restore — the recipe only ever existed
     * because we registered it. Removing it from the asset map broadcasts an `UpdateRecipes`
     * Remove packet so all players' crafting menus drop it immediately. Also clears any
     * captured snapshot (defensive — a created recipe re-edited after creation may have one).
     *
     * The persistent override is removed by the caller ([EditorBridge.reset]); this only
     * unregisters the live asset. Idempotent: a no-op if the recipe isn't in the map.
     *
     * @param recipeId The created recipe's ID
     * @return true if the removal was dispatched without error
     */
    fun deleteRecipe(recipeId: String): Boolean {
        return try {
            committer.removeRecipe(recipeId)
            originalsCache.remove(recipeId)
            logger?.atInfo()?.log("RecipeOverrideEngine: Deleted created recipe '%s'", recipeId)
            true
        } catch (e: Exception) {
            logger?.atSevere()?.withCause(e)?.log(
                "RecipeOverrideEngine: Failed to delete created recipe '%s'", recipeId
            )
            false
        }
    }
}

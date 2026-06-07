package me.itemforge.core

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import me.itemforge.scanner.CodecScanner
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Batch recipe modification engine — scales input quantities or crafting time
 * across many recipes at once, with a dry-run preview.
 *
 * ## Threading contract (CRITICAL — see [executeSaveRecipe] in EditorBridge)
 *
 * Both public methods do **codec encode only** and MUST be called on the V8 /
 * game thread, where [ExtraInfo.THREAD_LOCAL] holds the real codec version.
 * `encode()` is version-gated: on a wrong-version thread `supportsVersion()`
 * silently selects the wrong fields → corrupt or empty BSON (a silent data bug,
 * not a crash). This engine therefore NEVER decodes or commits — it only reads
 * (encode) and computes scaled values.
 *
 * The caller (DashboardBridge) follows the proven single-save split:
 *   1. (V8) call [scaleToBson] to get the full scaled recipe BSON
 *   2. (V8) persist it to `RecipeOverrideStore` as the override (full replacement)
 *   3. (async) hand the BSON to [RecipeOverrideEngine.applyAndSync] — which
 *      captures the original (for undo), decodes, and commits off-thread.
 *
 * Undo reuses [RecipeOverrideEngine.revertRecipe] (backed by RecipeOriginalsCache):
 * the bridge only needs to remember the list of modified recipe IDs — no parallel
 * snapshot buffer is required.
 *
 * @param codecScanner For access to the recipeCodec
 * @param logger For error reporting
 */
class BatchRecipeEngine(
    private val codecScanner: CodecScanner,
    private val logger: HytaleLogger? = null
) {

    /**
     * Dry-run preview of a scale operation across recipes. No mutation, no commit.
     *
     * Must run on the V8 / game thread (encode is version-gated). Computes the
     * exact same values [scaleToBson] would write, so the preview never drifts
     * from the applied result (red-team requirement).
     *
     * @param recipeIds The recipes to preview
     * @param scalePercent Scale factor as a percentage (80.0 = ×0.8)
     * @param target [ScaleTarget.INPUTS] or [ScaleTarget.TIME]
     * @return One preview row per recipe (including skipped recipes with reasons)
     */
    fun previewScale(
        recipeIds: List<String>,
        scalePercent: Double,
        target: ScaleTarget
    ): List<RecipeScalePreview> {
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        return recipeIds.map { recipeId ->
            val recipe = CraftingRecipe.getAssetMap().getAsset(recipeId)
                ?: return@map RecipeScalePreview(recipeId, target, emptyList(), skipped = true, reason = "Recipe not found")

            try {
                val bson = codecScanner.recipeCodec.encode(recipe, extraInfo)
                val changes = computeChanges(bson, scalePercent, target)
                if (changes.isEmpty()) {
                    RecipeScalePreview(recipeId, target, emptyList(), skipped = true, reason = skipReason(target))
                } else {
                    RecipeScalePreview(recipeId, target, changes, skipped = false, reason = null)
                }
            } catch (e: Exception) {
                logger?.atWarning()?.log("BatchRecipeEngine: preview failed for '$recipeId': ${e.message}")
                RecipeScalePreview(recipeId, target, emptyList(), skipped = true, reason = "Preview failed")
            }
        }
    }

    /**
     * Encodes a recipe to its full BSON and applies the scale in-place, returning
     * the complete document to persist as a full-replacement override.
     *
     * Must run on the V8 / game thread. Returns null if the recipe is missing or
     * the target field is absent/empty (nothing to scale — caller skips it).
     */
    fun scaleToBson(
        recipeId: String,
        scalePercent: Double,
        target: ScaleTarget
    ): BsonDocument? {
        val recipe = CraftingRecipe.getAssetMap().getAsset(recipeId) ?: return null
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        return try {
            val bson = codecScanner.recipeCodec.encode(recipe, extraInfo)
            val applied = applyScale(bson, scalePercent, target)
            if (applied) bson else null
        } catch (e: Exception) {
            logger?.atSevere()?.withCause(e)?.log("BatchRecipeEngine: scaleToBson failed for '$recipeId'")
            null
        }
    }

    // ── Scaling core (single source of truth for preview + apply) ────────────

    /**
     * Computes the per-field changes for a scale op without mutating the BSON.
     * Used by [previewScale]. Mirrors [applyScale] exactly.
     */
    private fun computeChanges(
        bson: BsonDocument,
        scalePercent: Double,
        target: ScaleTarget
    ): List<RecipeFieldChange> {
        return when (target) {
            ScaleTarget.INPUTS -> {
                val inputArray = bson.getArray("Input") ?: return emptyList()
                inputArray.mapNotNull { input ->
                    if (!input.isDocument) return@mapNotNull null
                    val doc = input.asDocument()
                    val qty = doc.getInt32("Quantity")?.value ?: return@mapNotNull null
                    RecipeFieldChange(
                        label = materialLabel(doc),
                        oldValue = qty.toDouble(),
                        newValue = scaleQuantity(qty, scalePercent).toDouble()
                    )
                }
            }

            ScaleTarget.TIME -> {
                val time = bson.getDouble("TimeSeconds")?.value ?: return emptyList()
                listOf(
                    RecipeFieldChange(
                        label = "Craft Time (s)",
                        oldValue = time,
                        newValue = scaleTime(time, scalePercent)
                    )
                )
            }
        }
    }

    /**
     * Applies the scale to the BSON in-place. Returns true if anything changed.
     * Used by [scaleToBson]. Mirrors [computeChanges] exactly.
     */
    private fun applyScale(
        bson: BsonDocument,
        scalePercent: Double,
        target: ScaleTarget
    ): Boolean {
        return when (target) {
            ScaleTarget.INPUTS -> {
                val inputArray = bson.getArray("Input") ?: return false
                var changed = false
                for (input in inputArray) {
                    if (!input.isDocument) continue
                    val doc = input.asDocument()
                    val qty = doc.getInt32("Quantity")?.value ?: continue
                    doc.put("Quantity", BsonInt32(scaleQuantity(qty, scalePercent)))
                    changed = true
                }
                changed
            }

            ScaleTarget.TIME -> {
                val time = bson.getDouble("TimeSeconds")?.value ?: return false
                bson.put("TimeSeconds", BsonDouble(scaleTime(time, scalePercent)))
                true
            }
        }
    }

    /** Quantities are integers ≥ 1 (a recipe can never need 0 of an ingredient). */
    private fun scaleQuantity(qty: Int, scalePercent: Double): Int =
        Math.max(1, Math.round(qty * scalePercent / 100.0).toInt())

    /** Craft time floored at 0.1s so a recipe never becomes instant/free. */
    private fun scaleTime(time: Double, scalePercent: Double): Double =
        Math.max(0.1, time * scalePercent / 100.0)

    private fun materialLabel(inputDoc: BsonDocument): String =
        inputDoc.getString("ItemId")?.value
            ?: inputDoc.getString("ResourceTypeId")?.value
            ?: "Unknown"

    private fun skipReason(target: ScaleTarget): String = when (target) {
        ScaleTarget.INPUTS -> "Recipe has no inputs to scale"
        ScaleTarget.TIME -> "Recipe has no crafting time"
    }
}

/** What a batch recipe scale targets. */
enum class ScaleTarget {
    /** Scale every input's Quantity. */
    INPUTS,

    /** Scale TimeSeconds. */
    TIME
}

/**
 * Dry-run preview of a scale op on one recipe.
 *
 * [changes] is one entry per input (INPUTS) or a single entry (TIME).
 * Skipped recipes carry a [reason] and an empty [changes] list.
 */
data class RecipeScalePreview(
    val recipeId: String,
    val target: ScaleTarget,
    val changes: List<RecipeFieldChange>,
    val skipped: Boolean,
    val reason: String? = null
)

/** A single before→after value within a recipe scale preview. */
data class RecipeFieldChange(
    val label: String,
    val oldValue: Double,
    val newValue: Double
)

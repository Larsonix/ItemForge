package me.itemforge.vuetale.editor

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.ItemForgePlugin

/**
 * Owns the recipe-save seam: building + validating the recipe BSON ([prepare], pure) and persisting
 * + applying it ([commit], async on the shared [EditorRuntime.asyncExecutor]).
 *
 * [prepare] is side-effect-free so the bridge can validate the recipe ahead of persisting any field
 * overrides (atomic save). [commit] uses the same single-thread executor as every other editor save,
 * so a recipe apply is serialized with field saves/resets — no contention, no out-of-order apply.
 */
class RecipeSaveHandler(
    private val plugin: ItemForgePlugin,
    private val runtime: EditorRuntime
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()

    /**
     * Result of [prepare]: either an [error] message (reject the save) or a validated [bson] ready
     * for [commit]. [isCreate] is true when no recipe asset existed and this save will register a
     * brand-new one.
     */
    class RecipeSavePrep(
        val error: String?,
        val recipeId: String? = null,
        val bson: org.bson.BsonDocument? = null,
        val isCreate: Boolean = false
    )

    /**
     * Builds and validates the complete recipe BSON for a save — WITHOUT side effects.
     *
     * Encodes the live recipe as the base if one exists; otherwise synthesizes a blank
     * template whose output is bound to this item (the create-from-scratch path). The UI
     * changes are merged in, then the result is validated against the codec's hard
     * constraint that a recipe have at least one input ([Validators.nonEmptyArray]).
     *
     * Returns a [RecipeSavePrep] carrying an error (reject) or the ready BSON. Pure so
     * the caller can validate ahead of persisting any field overrides (atomic save).
     */
    @Suppress("UNCHECKED_CAST")
    fun prepare(itemId: String, item: Item, recipeChanges: Map<String, Any>): RecipeSavePrep {
        val recipeId = CraftingRecipe.generateIdFromItemRecipe(item, 0)
        val existing = CraftingRecipe.getAssetMap().getAsset(recipeId)

        // Base BSON: encode the live recipe (edit) or synthesize a blank template (create).
        val recipeBson = if (existing != null) {
            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            plugin.codecScanner.recipeCodec.encode(existing, extraInfo)
        } else {
            buildBlankRecipeBson(itemId)
        }

        applyRecipeChangesToBson(recipeBson, recipeChanges)

        // The CraftingRecipe codec REQUIRES a non-empty Input array (Validators.nonEmptyArray).
        // Reject before persisting so we never store an override that fails to decode — which
        // would also fail silently on every restart. Covers create (no inputs added yet) AND
        // edit (admin removed every input).
        val inputVal = recipeBson["Input"]
        if (inputVal == null || !inputVal.isArray || inputVal.asArray().isEmpty()) {
            return RecipeSavePrep(error = "A recipe needs at least one input.")
        }

        return RecipeSavePrep(error = null, recipeId = recipeId, bson = recipeBson, isCreate = existing == null)
    }

    /**
     * Persists a validated recipe override and dispatches the async apply/commit off the V8
     * thread. For a create ([RecipeSavePrep.isCreate]) the commit registers a brand-new
     * CraftingRecipe asset; for an edit it replaces the existing one. Caller must have run
     * [prepare] and confirmed `error == null`.
     */
    fun commit(playerId: String, itemId: String, prep: RecipeSavePrep) {
        val recipeId = prep.recipeId!!
        val recipeBson = prep.bson!!
        val isCreate = prep.isCreate

        // Persist complete recipe BSON to RecipeOverrideStore (debounced write, no locks).
        val overrideJson = com.google.gson.JsonParser.parseString(recipeBson.toJson()).asJsonObject
        plugin.configManager.recipeOverrides.saveOverride(recipeId, overrideJson)

        // Dispatch async apply + commit (off V8 thread).
        java.util.concurrent.CompletableFuture.runAsync({
            try {
                val success = plugin.recipeOverrideEngine.applyAndSync(recipeId, recipeBson)
                if (!success) {
                    logger.atWarning().log("EditorBridge: async recipe save failed for '%s'", recipeId)
                    return@runAsync
                }
                plugin.dashboardBridge.invalidateCache()
                plugin.auditLogger.logRecipeEdit(playerId, recipeId, itemId)
                logger.atInfo().log("Recipe %s for '%s' by %s", if (isCreate) "created" else "saved", recipeId, playerId)
            } catch (e: Exception) {
                logger.atSevere().withCause(e).log("EditorBridge: async recipe save failed for '%s'", recipeId)
            }
        }, runtime.asyncExecutor)
    }

    /**
     * Synthesizes a blank, creatable recipe BSON whose output is 1 of [itemId].
     *
     * Deliberately sets PrimaryOutput + OutputQuantity but NO `Output` array. That gives the
     * "quantity" output pattern (RecipeScanner.detectOutputPattern treats a non-empty Output
     * array as the read-only "array" pattern), so the editor shows the EDITABLE Output Quantity
     * field. On save+decode, CraftingRecipe.processConfig rebuilds `outputs` from PrimaryOutput
     * (with the admin's quantity, which applyRecipeChangesToBson syncs into PrimaryOutput.Quantity),
     * and the runtime crafts from that. Input and BenchRequirement start empty — the admin adds
     * ≥1 input (codec-required) and optionally a bench before the first save creates the recipe.
     */
    private fun buildBlankRecipeBson(itemId: String): org.bson.BsonDocument {
        val doc = org.bson.BsonDocument()
        doc.put("Input", org.bson.BsonArray()) // empty — save is rejected until ≥1 input added

        val primary = org.bson.BsonDocument()
        primary.put("ItemId", org.bson.BsonString(itemId))
        primary.put("Quantity", org.bson.BsonInt32(1))
        doc.put("PrimaryOutput", primary)

        doc.put("OutputQuantity", org.bson.BsonInt32(1))
        doc.put("BenchRequirement", org.bson.BsonArray()) // no bench → uncraftable until added
        doc.put("TimeSeconds", org.bson.BsonDouble(0.0))
        doc.put("KnowledgeRequired", org.bson.BsonBoolean(false))
        doc.put("RequiredMemoriesLevel", org.bson.BsonInt32(1))
        return doc
    }

    /**
     * Merges partial recipe changes from the UI into a complete recipe BSON document.
     *
     * Modifies the BSON in-place. Handles:
     * - Input quantity/material changes (by index)
     * - Scalar fields (timeSeconds, knowledgeRequired, requiredMemoriesLevel, outputQuantity)
     * - Bench tier level changes (by index)
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyRecipeChangesToBson(bson: org.bson.BsonDocument, changes: Map<String, Any>) {
        // Scalar fields
        (changes["timeSeconds"] as? Number)?.let {
            bson.put("TimeSeconds", org.bson.BsonDouble(it.toDouble()))
        }
        (changes["knowledgeRequired"] as? Boolean)?.let {
            bson.put("KnowledgeRequired", org.bson.BsonBoolean(it))
        }
        (changes["requiredMemoriesLevel"] as? Number)?.let {
            bson.put("RequiredMemoriesLevel", org.bson.BsonInt32(it.toInt()))
        }
        (changes["outputQuantity"] as? Number)?.let {
            val qty = it.toInt().coerceAtLeast(1)
            // OutputQuantity is a legacy/seed field — keep it in sync, but the RUNTIME crafted
            // amount is read from Output[].Quantity (CraftingManager.getOutputItemStack:
            // materialQuantity.getQuantity() * craftQty), NOT from OutputQuantity. Writing
            // OutputQuantity alone is a no-op for the produced count. So for a single-output
            // ("quantity"-pattern) recipe — the only shape whose OutputQuantity field the UI
            // exposes — push the value into PrimaryOutput.Quantity and Output[0].Quantity too.
            bson.put("OutputQuantity", org.bson.BsonInt32(qty))
            (bson["PrimaryOutput"]?.takeIf { v -> v.isDocument })?.asDocument()
                ?.put("Quantity", org.bson.BsonInt32(qty))
            val outArr = bson["Output"]?.takeIf { v -> v.isArray }?.asArray()
            if (outArr != null && outArr.size == 1 && outArr[0].isDocument) {
                outArr[0].asDocument().put("Quantity", org.bson.BsonInt32(qty))
            }
        }

        // Full input replacement (add/remove/material changes).
        // Takes precedence over per-index inputs — they are mutually exclusive.
        @Suppress("UNCHECKED_CAST")
        val inputsFull = changes["inputsFull"] as? List<Map<String, Any>>
        if (inputsFull != null) {
            val newArray = org.bson.BsonArray()
            for (input in inputsFull) {
                val doc = org.bson.BsonDocument()
                val itemId = input["itemId"] as? String
                val resourceTypeId = input["resourceTypeId"] as? String
                // Mutually exclusive: set one, skip the other
                when {
                    itemId != null && itemId.isNotBlank() ->
                        doc.put("ItemId", org.bson.BsonString(itemId))
                    resourceTypeId != null && resourceTypeId.isNotBlank() ->
                        doc.put("ResourceTypeId", org.bson.BsonString(resourceTypeId))
                    else -> continue // Skip incomplete entries (no material selected)
                }
                val qty = (input["quantity"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
                doc.put("Quantity", org.bson.BsonInt32(qty))
                newArray.add(doc)
            }
            if (newArray.isNotEmpty()) {
                bson.put("Input", newArray)
            }
        } else {
            // Per-index input changes (quantity-only, backward compat):
            // { "0": { "quantity": 6, "itemId": "..." }, "1": { ... } }
            val inputChanges = changes["inputs"] as? Map<String, Map<String, Any>>
            if (inputChanges != null) {
                // operator-get + isArray: getArray(key) throws when the key is absent,
                // so the old `!= null` guard never actually triggered.
                val inputVal = bson["Input"]
                val inputArray = if (inputVal != null && inputVal.isArray) inputVal.asArray() else null
                if (inputArray != null) {
                    for ((indexStr, fieldChanges) in inputChanges) {
                        val index = indexStr.toIntOrNull() ?: continue
                        if (index < 0 || index >= inputArray.size) continue
                        val inputDoc = inputArray[index]?.asDocument() ?: continue

                        (fieldChanges["quantity"] as? Number)?.let {
                            inputDoc.put("Quantity", org.bson.BsonInt32(it.toInt()))
                        }
                        // Material ID change: set one, clear the other
                        if (fieldChanges.containsKey("itemId")) {
                            val newId = fieldChanges["itemId"] as? String
                            if (newId != null && newId.isNotBlank()) {
                                inputDoc.put("ItemId", org.bson.BsonString(newId))
                                inputDoc.remove("ResourceTypeId")
                            }
                        }
                        if (fieldChanges.containsKey("resourceTypeId")) {
                            val newId = fieldChanges["resourceTypeId"] as? String
                            if (newId != null && newId.isNotBlank()) {
                                inputDoc.put("ResourceTypeId", org.bson.BsonString(newId))
                                inputDoc.remove("ItemId")
                            }
                        }
                    }
                }
            }
        }

        // Full bench-requirement replacement (add/remove/structural).
        // Takes precedence over the per-index `benches` path — mutually exclusive,
        // exactly like inputsFull/inputs above.
        //
        // Verified against CraftingRecipe.CODEC (v0.5.3): a BenchRequirement needs
        // Type (BenchType enum, required) + Id (String, required); Categories and
        // RequiredTierLevel are optional. The BenchRequirement array has NO non-empty
        // validator, so an empty array is legal — it removes all bench requirements,
        // making the recipe uncraftable (CraftingManager.isValidBenchForRecipe).
        @Suppress("UNCHECKED_CAST")
        val benchesFull = changes["benchesFull"] as? List<Map<String, Any>>
        if (benchesFull != null) {
            val newArray = org.bson.BsonArray()
            for (bench in benchesFull) {
                val benchId = (bench["benchId"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                val doc = org.bson.BsonDocument()

                // Type: prefer the value the UI sent; fall back to the registry's type
                // for this bench id; final fallback to "Crafting" (the codec default).
                val type = (bench["type"] as? String)?.takeIf { it.isNotBlank() }
                    ?: plugin.benchRegistry.getBenchInfo(benchId)?.type
                    ?: "Crafting"
                doc.put("Type", org.bson.BsonString(type))
                doc.put("Id", org.bson.BsonString(benchId))

                // Categories (panels) — optional. Omit the key entirely when empty so the
                // BSON matches the game's own "no categories" shape (null, not []).
                val cats = bench["categories"] as? List<*>
                if (cats != null) {
                    val catArray = org.bson.BsonArray()
                    for (cat in cats) {
                        if (cat is String && cat.isNotBlank()) catArray.add(org.bson.BsonString(cat))
                    }
                    if (catArray.isNotEmpty()) doc.put("Categories", catArray)
                }

                val tier = (bench["requiredTierLevel"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
                doc.put("RequiredTierLevel", org.bson.BsonInt32(tier))

                newArray.add(doc)
            }
            // Always write — including an empty array (delete-all → uncraftable recipe).
            bson.put("BenchRequirement", newArray)
            return
        }

        // Bench requirement changes: { "0": { "requiredTierLevel": 1 } }
        val benchChanges = changes["benches"] as? Map<String, Map<String, Any>>
        if (benchChanges != null) {
            val benchVal = bson["BenchRequirement"]
            val benchArray = if (benchVal != null && benchVal.isArray) benchVal.asArray() else null
            if (benchArray != null) {
                for ((indexStr, fieldChanges) in benchChanges) {
                    val index = indexStr.toIntOrNull() ?: continue
                    if (index < 0 || index >= benchArray.size) continue
                    val benchDoc = benchArray[index]?.asDocument() ?: continue

                    (fieldChanges["requiredTierLevel"] as? Number)?.let {
                        benchDoc.put("RequiredTierLevel", org.bson.BsonInt32(it.toInt()))
                    }
                    // Bench ID change — also updates Type from BenchRegistry
                    (fieldChanges["benchId"] as? String)?.let { newBenchId ->
                        benchDoc.put("Id", org.bson.BsonString(newBenchId))
                        val benchInfo = plugin.benchRegistry.getBenchInfo(newBenchId)
                        if (benchInfo != null) {
                            benchDoc.put("Type", org.bson.BsonString(benchInfo.type))
                        }
                    }
                    // Categories (panel) change
                    if (fieldChanges.containsKey("categories")) {
                        @Suppress("UNCHECKED_CAST")
                        val newCats = fieldChanges["categories"] as? List<*>
                        if (newCats != null && newCats.isNotEmpty()) {
                            val catArray = org.bson.BsonArray()
                            for (cat in newCats) {
                                if (cat is String) catArray.add(org.bson.BsonString(cat))
                            }
                            benchDoc.put("Categories", catArray)
                        } else {
                            benchDoc.remove("Categories")
                        }
                    }
                }
            }
        }
    }
}

package me.itemforge.scanner

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import me.itemforge.metadata.ItemNameResolver
import me.itemforge.util.ValueFormatter
import me.itemforge.vuetale.RecipeBenchData
import me.itemforge.vuetale.RecipeData
import me.itemforge.vuetale.RecipeInputData
import me.itemforge.vuetale.RecipeOutputData
import org.bson.BsonDocument
import org.bson.BsonType

/**
 * Extracts structured recipe data from a live [CraftingRecipe] via BSON encoding.
 *
 * ## Pattern
 *
 * Same as [CodecScanner]: encode the object to BSON via its codec, then navigate
 * the resulting document to extract typed values. This ensures we read the ACTUAL
 * runtime values (after inheritance, afterDecode processing, etc.).
 *
 * ## BSON Field Names (from CraftingRecipe.CODEC)
 *
 * | Field | Codec Key | BSON Type |
 * |-------|-----------|-----------|
 * | Input | "Input" | Array of MaterialQuantity documents |
 * | Output | "Output" | Array of MaterialQuantity documents |
 * | PrimaryOutput | "PrimaryOutput" | MaterialQuantity document |
 * | OutputQuantity | "OutputQuantity" | Int32 |
 * | BenchRequirement | "BenchRequirement" | Array of BenchRequirement documents |
 * | TimeSeconds | "TimeSeconds" | Double |
 * | KnowledgeRequired | "KnowledgeRequired" | Boolean |
 * | RequiredMemoriesLevel | "RequiredMemoriesLevel" | Int32 |
 *
 * ## MaterialQuantity BSON Keys
 *
 * | Field | Key | Notes |
 * |-------|-----|-------|
 * | itemId | "ItemId" | Nullable. Specific item reference. |
 * | resourceTypeId | "ResourceTypeId" | Nullable. Wildcard resource match. |
 * | tag | "ItemTag" | Nullable. Tag-based matching. |
 * | quantity | "Quantity" | Int32, always >= 1. |
 *
 * ## BenchRequirement BSON Keys
 *
 * | Field | Key | Notes |
 * |-------|-----|-------|
 * | type | "Type" | String from BenchType enum. |
 * | id | "Id" | Bench ID string. |
 * | categories | "Categories" | Nullable string array. |
 * | requiredTierLevel | "RequiredTierLevel" | Int32, default 0. |
 *
 * @param codecScanner For access to the cached recipeCodec
 */
class RecipeScanner(private val codecScanner: CodecScanner) {

    /**
     * Scans a recipe and returns structured data for the editor UI.
     *
     * @param recipe The live CraftingRecipe from the asset map
     * @param hasOverride Whether this recipe has an active ItemForge override
     * @param originalBson Pre-override BSON snapshot (null if never overridden).
     *                     Used to compute "was:" indicators in the UI.
     * @return Structured recipe data for the editor payload
     */
    fun scan(
        recipe: CraftingRecipe,
        hasOverride: Boolean,
        originalBson: BsonDocument?
    ): RecipeData {
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        val bson = codecScanner.recipeCodec.encode(recipe, extraInfo)

        return RecipeData(
            recipeId = recipe.id ?: "",
            inputs = extractInputs(bson),
            outputPattern = detectOutputPattern(bson),
            outputQuantity = extractInt(bson, "OutputQuantity"),
            outputs = extractOutputs(bson),
            timeSeconds = extractDouble(bson, "TimeSeconds") ?: 0.0,
            knowledgeRequired = extractBool(bson, "KnowledgeRequired") ?: false,
            requiredMemoriesLevel = extractInt(bson, "RequiredMemoriesLevel") ?: 1,
            benchRequirements = extractBenchRequirements(bson),
            hasOverride = hasOverride,
            // Original values for "was:" indicators
            originalInputs = if (hasOverride && originalBson != null) extractInputs(originalBson) else null,
            originalOutputQuantity = if (hasOverride && originalBson != null) extractInt(originalBson, "OutputQuantity") else null,
            originalTimeSeconds = if (hasOverride && originalBson != null) extractDouble(originalBson, "TimeSeconds") else null,
            originalKnowledgeRequired = if (hasOverride && originalBson != null) extractBool(originalBson, "KnowledgeRequired") else null,
            originalRequiredMemoriesLevel = if (hasOverride && originalBson != null) extractInt(originalBson, "RequiredMemoriesLevel") else null,
            originalBenchRequirements = if (hasOverride && originalBson != null) extractBenchRequirements(originalBson) else null,
            // benchRegistry is attached later by EditorBridge
            benchRegistry = emptyMap()
        )
    }

    // ── Input Extraction ────────────────────────────────────────────────

    /**
     * Extracts input materials from the "Input" BSON array.
     *
     * Each entry is a MaterialQuantity document with optional ItemId/ResourceTypeId/ItemTag
     * and required Quantity. Display names are resolved via [ItemNameResolver] for items
     * or formatted from the ResourceTypeId for resource types.
     */
    private fun extractInputs(bson: BsonDocument?): List<RecipeInputData> {
        if (bson == null) return emptyList()
        val inputVal = bson["Input"] ?: return emptyList()
        if (inputVal.bsonType != BsonType.ARRAY) return emptyList()

        val inputs = mutableListOf<RecipeInputData>()
        val arr = inputVal.asArray()

        for ((index, element) in arr.withIndex()) {
            if (element.bsonType != BsonType.DOCUMENT) continue
            val doc = element.asDocument()

            // BsonDocument.getString() throws if key absent — use safe get+type check
            val itemId = doc["ItemId"]?.let { if (it.bsonType == BsonType.STRING) it.asString().value else null }
            val resourceTypeId = doc["ResourceTypeId"]?.let { if (it.bsonType == BsonType.STRING) it.asString().value else null }
            val itemTag = doc["ItemTag"]?.let { if (it.bsonType == BsonType.STRING) it.asString().value else null }
            val quantity = doc["Quantity"]?.let { if (it.bsonType == BsonType.INT32) it.asInt32().value else 1 } ?: 1

            // Resolve a human-readable display name
            val displayName = when {
                itemId != null -> ItemNameResolver.resolve(itemId)
                resourceTypeId != null -> ValueFormatter.formatFieldName(resourceTypeId)
                itemTag != null -> "Tag: $itemTag"
                else -> "Unknown"
            }

            inputs.add(RecipeInputData(
                index = index,
                itemId = itemId,
                resourceTypeId = resourceTypeId,
                quantity = quantity,
                displayName = displayName
            ))
        }

        return inputs
    }

    // ── Output Extraction ───────────────────────────────────────────────

    /**
     * Detects which output pattern this recipe uses.
     *
     * Three patterns (from RECIPE_RESEARCH.md §4.2):
     * - "quantity" (695 recipes): OutputQuantity field present, produces N of the parent item
     * - "array" (62 recipes): Output array with multiple different items
     * - "primary" (1 recipe): PrimaryOutput field (rare, co-exists with Output)
     *
     * Checked in order of specificity: Output array > OutputQuantity > none.
     */
    private fun detectOutputPattern(bson: BsonDocument): String {
        val outputArr = bson["Output"]
        if (outputArr != null && outputArr.bsonType == BsonType.ARRAY && outputArr.asArray().isNotEmpty()) {
            return "array"
        }
        if (bson.containsKey("OutputQuantity")) {
            return "quantity"
        }
        // Items without explicit output produce 1 of themselves (default)
        return "quantity"
    }

    /**
     * Extracts output items from the "Output" BSON array (read-only in v1).
     *
     * Only populated for "array" pattern recipes (salvage, byproduct).
     * For "quantity" pattern, the output is the parent item itself.
     */
    private fun extractOutputs(bson: BsonDocument?): List<RecipeOutputData> {
        if (bson == null) return emptyList()
        val outputVal = bson["Output"] ?: return emptyList()
        if (outputVal.bsonType != BsonType.ARRAY) return emptyList()

        val outputs = mutableListOf<RecipeOutputData>()
        for (element in outputVal.asArray()) {
            if (element.bsonType != BsonType.DOCUMENT) continue
            val doc = element.asDocument()

            val itemId = doc.optString("ItemId") ?: continue
            val quantity = doc.optInt("Quantity") ?: 1

            outputs.add(RecipeOutputData(
                itemId = itemId,
                quantity = quantity,
                displayName = ItemNameResolver.resolve(itemId)
            ))
        }

        return outputs
    }

    // ── Bench Requirement Extraction ─────────────────────────────────────

    /**
     * Extracts bench requirements from the "BenchRequirement" BSON array.
     *
     * Each entry specifies a bench type, ID, optional categories (panels),
     * and optional tier level. Display names resolved via formatting.
     */
    private fun extractBenchRequirements(bson: BsonDocument?): List<RecipeBenchData> {
        if (bson == null) return emptyList()
        val benchVal = bson["BenchRequirement"] ?: return emptyList()
        if (benchVal.bsonType != BsonType.ARRAY) return emptyList()

        val benches = mutableListOf<RecipeBenchData>()
        for ((index, element) in benchVal.asArray().withIndex()) {
            if (element.bsonType != BsonType.DOCUMENT) continue
            val doc = element.asDocument()

            val type = doc.optString("Type") ?: "Crafting"
            val benchId = doc.optString("Id") ?: continue
            val tierLevel = doc.optInt("RequiredTierLevel") ?: 0

            // Categories is an OPTIONAL string array — absent on many recipes
            // (e.g. ingredients like Ingredient_Bolt_Silk). optArray returns null
            // when the key is missing instead of throwing.
            val categories = doc.optArray("Categories")?.mapNotNull { cat ->
                if (cat.bsonType == BsonType.STRING) cat.asString().value else null
            }

            benches.add(RecipeBenchData(
                index = index,
                type = type,
                benchId = benchId,
                benchDisplayName = ValueFormatter.formatFieldName(benchId),
                categories = categories,
                requiredTierLevel = tierLevel
            ))
        }

        return benches
    }

    // ── Null-safe BSON accessors ─────────────────────────────────────────
    //
    // org.bson's single-arg getString(key)/getInt32(key)/getArray(key) THROW
    // (BsonInvalidOperationException via throwIfKeyAbsent) when the key is absent —
    // a `?.` on them is a false safety net. Item recipes are NOT uniform (ingredients
    // omit "Categories", some recipes omit "BenchRequirement"/"Output"), so optional
    // keys MUST be read with the operator-get form, which returns null when absent.

    private fun BsonDocument.optString(key: String): String? =
        this[key]?.takeIf { it.bsonType == BsonType.STRING }?.asString()?.value

    private fun BsonDocument.optInt(key: String): Int? =
        this[key]?.let {
            when (it.bsonType) {
                BsonType.INT32 -> it.asInt32().value
                BsonType.DOUBLE -> it.asDouble().value.toInt()
                else -> null
            }
        }

    private fun BsonDocument.optArray(key: String): org.bson.BsonArray? =
        this[key]?.takeIf { it.bsonType == BsonType.ARRAY }?.asArray()

    // ── Scalar Extraction Helpers ────────────────────────────────────────

    private fun extractDouble(bson: BsonDocument?, key: String): Double? {
        if (bson == null) return null
        val v = bson[key] ?: return null
        return when (v.bsonType) {
            BsonType.DOUBLE -> v.asDouble().value
            BsonType.INT32 -> v.asInt32().value.toDouble()
            else -> null
        }
    }

    private fun extractInt(bson: BsonDocument?, key: String): Int? {
        if (bson == null) return null
        val v = bson[key] ?: return null
        return when (v.bsonType) {
            BsonType.INT32 -> v.asInt32().value
            BsonType.DOUBLE -> v.asDouble().value.toInt()
            else -> null
        }
    }

    private fun extractBool(bson: BsonDocument?, key: String): Boolean? {
        if (bson == null) return null
        val v = bson[key] ?: return null
        return if (v.bsonType == BsonType.BOOLEAN) v.asBoolean().value else null
    }
}

package me.itemforge.vuetale.editor

import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.scanner.CodecScanner
import me.itemforge.scanner.FieldDefinition
import me.itemforge.util.BsonHelper
import me.itemforge.util.InteractionVarsBson
import org.bson.BsonDocument
import org.bson.BsonValue

/**
 * Builds the item-override BSON document from a validated changes map.
 *
 * Pure BSON shaping — no plugin, no executors, no threading concerns of its own. The one
 * non-pure dependency (the codec, used for the InteractionVars clone-and-modify branch) is passed
 * in as a parameter, mirroring [InteractionVarsBson.buildOverride] which this delegates to. Safe on
 * any thread that holds a valid ExtraInfo (V8 or batch worker), exactly like its caller.
 */
object OverrideBsonBuilder {

    /**
     * Builds a BSON override document from the changes map.
     *
     * For each field change, converts the value to the correct BSON type and
     * builds the nested BSON structure using [BsonHelper.buildNestedBson].
     * All changes are merged into a single document via [BsonHelper.deepMerge].
     *
     * For modifier fields (calculationType != null), the change value is a
     * composite `{amount, calculationType}` from the UI. We reconstruct the
     * BSON modifier array `[{Amount: value, CalculationType: type}]` that the
     * codec expects. Without this reconstruction, Path B's deepMerge would
     * replace the modifier array with a flat double, corrupting the item.
     *
     * Component fields like "Armor.StatModifiers.Health" produce nested BSON:
     * `{ "Armor": { "StatModifiers": { "Health": [{Amount: 25, CalculationType: "Additive"}] } } }`
     *
     * ## InteractionVars special handling
     *
     * InteractionVars fields use a clone-and-modify approach instead of
     * [BsonHelper.buildNestedBson], because the Interactions ARRAY sits between
     * the var name and DamageCalculator in the BSON path. [BsonHelper.deepMerge]
     * replaces arrays wholesale, so a minimal Interactions array would wipe
     * DamageEffects, Next chain, EntityStatsOnHit, etc. Instead, we clone the
     * COMPLETE var entry from the current item's BSON, modify specific values
     * in-place, and store the full clone as the override.
     *
     * @param itemId Used to look up the current item for InteractionVars cloning
     * @param codecScanner Source of the item codec for the InteractionVars clone-and-modify branch
     */
    fun build(
        itemId: String,
        fieldMap: Map<String, FieldDefinition>,
        changes: Map<String, Any>,
        codecScanner: CodecScanner
    ): BsonDocument {
        val result = BsonDocument()

        // Accumulate InteractionVars changes separately — they need clone-and-modify
        // Key: varName (e.g., "Swing_Left_Damage"), Value: subField → newValue
        val ivChanges = mutableMapOf<String, MutableMap<String, Any>>()

        for ((fieldId, newValue) in changes) {
            val field = fieldMap[fieldId] ?: continue

            // InteractionVars fields: buildNestedBson can't handle the Interactions
            // array. Accumulate and process after the main loop.
            if (field.componentKey == "InteractionVars" && field.requiresPathB) {
                val parts = fieldId.split(".", limit = 4)
                if (parts.size >= 3) {
                    val varName = parts[1]
                    // subFieldKey encodes the rest: "BaseDamage.Physical", "Class", etc.
                    val subFieldKey = parts.drop(2).joinToString(".")
                    ivChanges.getOrPut(varName) { mutableMapOf() }[subFieldKey] = newValue
                }
                continue
            }

            val bsonValue: BsonValue = if (field.calculationType != null && newValue is Map<*, *>) {
                // Modifier field: reconstruct the array BSON from composite value.
                // UI sends {amount: 25.0, calculationType: "Additive"}.
                val amount = (newValue["amount"] as? Number)?.toDouble()
                    ?: (field.currentValue as? Number)?.toDouble()
                    ?: continue
                val calcType = newValue["calculationType"] as? String
                    ?: field.calculationType
                BsonHelper.buildModifierArray(amount, calcType)
            } else if (field.calculationType != null && newValue is Number) {
                // Modifier field with just a scalar amount (calcType unchanged).
                // Reconstruct the array with the original calculationType.
                BsonHelper.buildModifierArray(newValue.toDouble(), field.calculationType)
            } else {
                // Simple field: direct BSON conversion
                BsonHelper.kotlinToBson(newValue, field.valueType)
            }

            val nested = BsonHelper.buildNestedBson(fieldId, bsonValue)
            BsonHelper.deepMerge(result, nested)
        }

        // Build InteractionVars override BSON via clone-and-modify
        if (ivChanges.isNotEmpty()) {
            val ivOverride = buildIvOverride(itemId, ivChanges, codecScanner)
            if (ivOverride != null) {
                BsonHelper.deepMerge(result, ivOverride)
            }
        }

        return result
    }

    /**
     * Builds InteractionVars override BSON using the clone-and-modify strategy.
     *
     * For each modified var (attack type):
     * 1. Encode the current item to full BSON (read-only, thread-safe on V8)
     * 2. Extract the var entry document (complete with all 14+ interaction keys)
     * 3. Navigate into Interactions[0].DamageCalculator and apply changes
     * 4. Store the modified-but-complete var entry in the override
     *
     * The override BSON contains the FULL var entry for each modified attack.
     * When [BsonHelper.deepMerge] processes this at the OverrideEngine level:
     * - InteractionVars → DOCUMENT → recurse
     * - VarName → DOCUMENT → recurse (replaces entire var entry)
     * - All data within the var entry is preserved because we cloned the original
     *
     * ## Thread safety
     *
     * This runs on the V8 thread. Safe because:
     * - `Item.getAssetMap()` is ConcurrentHashMap (thread-safe reads)
     * - `codec.encode()` is read-only (no mutations)
     * - We modify only the freshly-encoded BSON copy, not the live item
     *
     * @param itemId The item being saved
     * @param ivChanges varName → {subFieldKey → newValue} accumulated changes
     * @return BSON document `{InteractionVars: {var1: ..., var2: ...}}` or null if encoding fails
     */
    private fun buildIvOverride(
        itemId: String,
        ivChanges: Map<String, Map<String, Any>>,
        codecScanner: CodecScanner
    ): BsonDocument? {
        // Clone-and-modify is shared with the batch engine via InteractionVarsBson so the
        // single-item and batch weapon-damage write paths can never drift apart.
        val item = Item.getAssetMap().getAsset(itemId) ?: return null
        return InteractionVarsBson.buildOverride(codecScanner, item, ivChanges)
    }
}

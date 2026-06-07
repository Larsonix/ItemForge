package me.itemforge.vuetale.editor

import me.itemforge.scanner.FieldDefinition
import me.itemforge.util.BsonHelper
import org.bson.BsonDocument
import org.bson.BsonType

/**
 * Resolves a field's original (pre-override) value from a BSON snapshot of the item.
 *
 * Pure BSON navigation — no threading, no plugin access, no executors. Callers fetch the snapshot
 * (e.g. `plugin.originalsCache.get(itemId)`) and pass the resulting [BsonDocument] in; this object
 * only reads it. Used by the payload assembler (for "was: X" indicators) and the editor bridge's
 * asset-save audit path (for the old value in the change record).
 */
object BsonOriginalsLookup {

    /**
     * Looks up the original (pre-override) value for a field from the BSON snapshot.
     *
     * Uses [BsonHelper.getNestedValue] to navigate the dot-separated field path in the original BSON
     * document, then converts to a Kotlin value.
     *
     * For modifier array entries (field.calculationType != null), the BSON at the field path is an
     * array `[{Amount: 17.0, CalculationType: "Additive"}]`, not a scalar. We extract the Amount from
     * the first modifier using [BsonHelper.extractModifierAmount] so the "was: X" indicator shows
     * "was: 17".
     *
     * Returns null if:
     * - No original snapshot exists (item was never overridden)
     * - The field path doesn't exist in the original BSON
     * - The value is a compound type (not directly displayable)
     */
    fun forField(
        field: FieldDefinition,
        originalBson: BsonDocument?
    ): Any? {
        if (originalBson == null) return null

        // InteractionVars fields: logical field IDs don't match the literal BSON
        // path (Interactions array sits between var name and DamageCalculator).
        // Navigate the BSON manually instead of using dot-path getNestedValue.
        if (field.componentKey == "InteractionVars" && field.requiresPathB) {
            return forInteractionVar(field.id, originalBson)
        }

        val bsonValue = BsonHelper.getNestedValue(originalBson, field.id) ?: return null

        // Modifier arrays: extract Amount from first modifier for "was: X" display
        if (field.calculationType != null && bsonValue.bsonType == BsonType.ARRAY) {
            return BsonHelper.extractModifierAmount(bsonValue.asArray())
        }

        return BsonHelper.bsonToKotlin(bsonValue)
    }

    /**
     * Looks up the original value for a field by ID from an already-fetched OriginalsCache snapshot.
     * Used for audit logging (old value in the change record). The caller passes the snapshot
     * (`plugin.originalsCache.get(itemId)`); a null snapshot yields null.
     */
    fun byId(originalBson: BsonDocument?, fieldId: String): Any? {
        if (originalBson == null) return null

        // InteractionVars: logical field IDs don't match BSON path
        if (fieldId.startsWith("InteractionVars.")) {
            return forInteractionVar(fieldId, originalBson)
        }

        val bsonValue = BsonHelper.getNestedValue(originalBson, fieldId) ?: return null
        return BsonHelper.bsonToKotlin(bsonValue)
    }

    /**
     * Navigates the original BSON snapshot to find an InteractionVars field's value.
     *
     * Field ID format: `InteractionVars.{VarName}.{FieldType}[.{SubField}]`
     * Actual BSON path: `InteractionVars.{VarName}.Interactions[0].DamageCalculator.{FieldType}[.{SubField}]`
     *
     * The `Interactions` array and `DamageCalculator` level are navigated explicitly because
     * [BsonHelper.getNestedValue] cannot handle array indices.
     */
    private fun forInteractionVar(
        fieldId: String,
        bson: BsonDocument
    ): Any? {
        val segments = fieldId.split(".")
        // segments: ["InteractionVars", varName, fieldType, subField?]
        if (segments.size < 3) return null

        val varName = segments[1]
        val ivDoc = bson["InteractionVars"]
        if (ivDoc == null || ivDoc.bsonType != BsonType.DOCUMENT) return null
        val varEntry = ivDoc.asDocument()[varName]
        if (varEntry == null || varEntry.bsonType != BsonType.DOCUMENT) return null
        val interactions = varEntry.asDocument()["Interactions"]
        if (interactions == null || interactions.bsonType != BsonType.ARRAY) return null
        val arr = interactions.asArray()
        if (arr.isEmpty()) return null
        val firstInteraction = arr[0]
        if (firstInteraction.bsonType != BsonType.DOCUMENT) return null
        val damageCalc = firstInteraction.asDocument()["DamageCalculator"]
        if (damageCalc == null || damageCalc.bsonType != BsonType.DOCUMENT) return null
        val calcDoc = damageCalc.asDocument()

        return when (segments[2]) {
            "BaseDamage" -> {
                if (segments.size < 4) return null
                val baseDamage = calcDoc["BaseDamage"]
                if (baseDamage == null || baseDamage.bsonType != BsonType.DOCUMENT) return null
                val value = baseDamage.asDocument()[segments[3]] ?: return null
                BsonHelper.bsonToKotlin(value)
            }
            "Class" -> {
                val value = calcDoc["Class"] ?: return null
                BsonHelper.bsonToKotlin(value)
            }
            "RandomPercentageModifier" -> {
                val value = calcDoc["RandomPercentageModifier"] ?: return null
                BsonHelper.bsonToKotlin(value)
            }
            else -> null
        }
    }
}

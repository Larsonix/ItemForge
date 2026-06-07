package me.itemforge.util

import me.itemforge.scanner.ValueType
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.BsonType
import org.bson.BsonValue

/**
 * BSON manipulation utilities used throughout the core engine.
 *
 * The Hytale codec system uses MongoDB BSON internally. This helper bridges
 * between BSON values and the Kotlin types used in [FieldDefinition] and
 * override JSON processing.
 *
 * All functions are pure — no side effects, no SDK dependencies beyond BSON.
 *
 * ## Consumers
 *
 * - **CodecScanner**: [bsonToValueType] + [bsonToKotlin] when building FieldDefinitions
 * - **OverrideEngine**: [deepMerge] for Path B BSON merging, [kotlinToBson] for override construction
 * - **SaveFlowHandler**: [buildNestedBson] to convert dot-path field changes to nested BSON
 * - **EditorBridge**: [bsonToKotlin] for serializing values to the UI
 */
object BsonHelper {

    // ── BSON → Kotlin conversions ────────────────────────────────────────

    /**
     * Maps a BSON value's type to a [ValueType] for the field definition.
     *
     * Only returns a ValueType for leaf/editable types. Returns null for
     * DOCUMENT, ARRAY, and other compound types — those are recursed into
     * by CodecScanner, not represented as single FieldDefinitions.
     *
     * Observed at runtime (Probe 1.2):
     * - DOUBLE: MaxDurability, FuelQuality, Scale, speed values, damage values
     * - INT32: ItemLevel, MaxStack, Container.Capacity
     * - BOOLEAN: Consumable, DropOnDeath, UsePlayerAnimations, etc.
     * - STRING: Quality, SoundEventId, GlobalFilter, etc.
     */
    fun bsonToValueType(value: BsonValue): ValueType? {
        return when (value.bsonType) {
            BsonType.DOUBLE -> ValueType.DOUBLE
            BsonType.INT32 -> ValueType.INTEGER
            BsonType.INT64 -> ValueType.INTEGER // Treat Long as Integer for UI purposes
            BsonType.BOOLEAN -> ValueType.BOOLEAN
            BsonType.STRING -> ValueType.STRING
            else -> null // DOCUMENT, ARRAY, NULL, etc. — not directly editable
        }
    }

    /**
     * Converts a BSON value to its Kotlin equivalent for [FieldDefinition.currentValue].
     *
     * Returns null for compound types (DOCUMENT, ARRAY) and BsonNull.
     * The caller decides what to do with null — typically skip the field
     * or mark it as not directly editable.
     *
     * INT64 is converted to Long. If callers need Int, they should check
     * the ValueType and cast accordingly.
     */
    fun bsonToKotlin(value: BsonValue): Any? {
        return when (value.bsonType) {
            BsonType.DOUBLE -> cleanDouble(value.asDouble().value)
            BsonType.INT32 -> value.asInt32().value
            BsonType.INT64 -> value.asInt64().value
            BsonType.BOOLEAN -> value.asBoolean().value
            BsonType.STRING -> value.asString().value
            else -> null
        }
    }

    /**
     * Cleans up float-to-double precision artifacts.
     *
     * Hytale stores most numeric fields as Java `float` (32-bit). BSON only has
     * `Double` (64-bit). The widening conversion introduces noise:
     * `0.05f` → `0.05000000074505806d`, `0.12f` → `0.11999999731779099d`.
     *
     * Rounding to 6 decimal places recovers the original float values without
     * losing meaningful precision (game values rarely exceed 2 decimal places).
     */
    private fun cleanDouble(raw: Double): Double {
        // Fast path: integers don't need cleaning
        if (raw == Math.floor(raw) && raw < 1e15) return raw
        return Math.round(raw * 1_000_000.0) / 1_000_000.0
    }

    // ── Kotlin → BSON conversions ────────────────────────────────────────

    /**
     * Converts a Kotlin value to the BSON type expected by the codec for decode().
     *
     * The [type] parameter determines which BSON wrapper to use. This is critical
     * because the codec's KeyedCodec.getOrNull() decodes based on BSON type —
     * passing BsonInt32 for a DOUBLE field would cause a decode failure.
     *
     * Handles cross-type coercion for common edge cases:
     * - Int value for DOUBLE type → converts to Double
     * - Double value for INTEGER type → rounds to Int
     * - Number value for any numeric type → converts appropriately
     *
     * @throws IllegalArgumentException if the value cannot be converted to the target type
     */
    fun kotlinToBson(value: Any, type: ValueType): BsonValue {
        return when (type) {
            ValueType.DOUBLE -> {
                val d = when (value) {
                    is Double -> value
                    is Float -> value.toDouble()
                    is Int -> value.toDouble()
                    is Long -> value.toDouble()
                    is Number -> value.toDouble()
                    else -> throw IllegalArgumentException(
                        "Cannot convert ${value::class.simpleName} to DOUBLE"
                    )
                }
                BsonDouble(d)
            }
            ValueType.INTEGER -> {
                val i = when (value) {
                    is Int -> value
                    is Long -> value.toInt()
                    is Double -> value.toInt()
                    is Float -> value.toInt()
                    is Number -> value.toInt()
                    else -> throw IllegalArgumentException(
                        "Cannot convert ${value::class.simpleName} to INTEGER"
                    )
                }
                BsonInt32(i)
            }
            ValueType.BOOLEAN -> {
                val b = when (value) {
                    is Boolean -> value
                    else -> throw IllegalArgumentException(
                        "Cannot convert ${value::class.simpleName} to BOOLEAN"
                    )
                }
                BsonBoolean(b)
            }
            ValueType.STRING -> {
                val s = when (value) {
                    is String -> value
                    else -> value.toString() // Permissive — anything can be a string
                }
                BsonString(s)
            }
        }
    }

    // ── BSON document operations ─────────────────────────────────────────

    /**
     * Deep-merges [source] into [target], modifying [target] in place.
     *
     * Merge rules:
     * - If both target and source have a DOCUMENT for the same key → recurse
     * - Otherwise → source value replaces target value (including arrays)
     *
     * Arrays are NOT element-merged — they're replaced wholesale. This is
     * correct for our use case: recipe input overrides provide the complete
     * input list, not individual element patches.
     *
     * Used by OverrideEngine Path B to merge override BSON into the full
     * item BSON before re-decode via assetStore.decode().
     */
    fun deepMerge(target: BsonDocument, source: BsonDocument) {
        for ((key, sourceValue) in source) {
            val targetValue = target[key]

            if (sourceValue.bsonType == BsonType.DOCUMENT &&
                targetValue != null &&
                targetValue.bsonType == BsonType.DOCUMENT
            ) {
                // Both are documents — recurse
                deepMerge(targetValue.asDocument(), sourceValue.asDocument())
            } else {
                // Replace (covers: new key, type change, array replacement, primitives)
                target.put(key, sourceValue)
            }
        }
    }

    /**
     * Builds a nested BSON document from a dot-separated field path and a leaf value.
     *
     * Example: `buildNestedBson("Armor.StatModifiers.Health", BsonDouble(25.0))`
     * produces:
     * ```
     * { "Armor": { "StatModifiers": { "Health": BsonDouble(25.0) } } }
     * ```
     *
     * Used by SaveFlowHandler to convert individual field changes (identified by
     * FieldDefinition.id) into the nested BSON structure that OverrideEngine expects.
     *
     * For top-level fields (no dots in path), returns a document with a single key.
     */
    fun buildNestedBson(path: String, value: BsonValue): BsonDocument {
        val segments = path.split(".")
        if (segments.isEmpty()) throw IllegalArgumentException("Path cannot be empty")

        // Build from inside out: start with the leaf, wrap in parent documents
        var current: BsonValue = value
        for (i in segments.lastIndex downTo 0) {
            val doc = BsonDocument()
            doc.put(segments[i], current)
            current = doc
        }

        return current.asDocument()
    }

    /**
     * Extracts a nested BSON value by following a dot-separated path.
     *
     * Example: `getNestedValue(doc, "Armor.StatModifiers.Health")`
     * navigates `doc["Armor"]["StatModifiers"]["Health"]`.
     *
     * Returns null if any segment along the path is missing or not a document.
     * This is expected — not all items have all component fields.
     */
    fun getNestedValue(document: BsonDocument, path: String): BsonValue? {
        val segments = path.split(".")
        var current: BsonValue = document

        for (i in 0 until segments.lastIndex) {
            if (current.bsonType != BsonType.DOCUMENT) return null
            current = current.asDocument()[segments[i]] ?: return null
        }

        if (current.bsonType != BsonType.DOCUMENT) return null
        return current.asDocument()[segments.last()]
    }

    // ── Modifier array construction ──────────────────────────────────────

    /**
     * Builds a BSON modifier array from an amount and calculation type.
     *
     * Stat modifiers in Hytale are encoded as single-element arrays:
     * `[{"Amount": 17.0, "CalculationType": "Additive"}]`
     *
     * This format is used by StatModifiers, DamageResistance, DamageEnhancement,
     * DamageClassEnhancement, KnockbackResistances, and KnockbackEnhancements.
     *
     * The returned BsonArray can be placed directly at the map entry key
     * (e.g., "Health") inside the component BSON structure.
     *
     * @param amount The modifier amount (e.g., 17.0 for "+17 Health")
     * @param calculationType "Additive" or "Multiplicative"
     * @return BsonArray containing a single modifier document
     */
    fun buildModifierArray(amount: Double, calculationType: String): BsonArray {
        val modifier = BsonDocument()
        modifier.put("Amount", BsonDouble(amount))
        modifier.put("CalculationType", BsonString(calculationType))
        return BsonArray(listOf(modifier))
    }

    /**
     * Extracts the Amount from a BSON modifier array.
     *
     * Inverse of [buildModifierArray] — reads the first modifier's Amount
     * from the array. Used by [EditorBridge.lookupOriginalValue] to extract
     * the original amount for "was: X" indicators on modifier fields.
     *
     * @param array The modifier array (e.g., `[{Amount: 17.0, CalculationType: "Additive"}]`)
     * @return The Amount as a Kotlin value, or null if the array is empty/malformed
     */
    fun extractModifierAmount(array: BsonArray): Any? {
        if (array.isEmpty()) return null
        val first = array[0]
        if (first.bsonType != BsonType.DOCUMENT) return null
        val amount = first.asDocument()["Amount"] ?: return null
        return bsonToKotlin(amount)
    }
}

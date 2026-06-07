package me.itemforge.util

import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.scanner.CodecScanner
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonString
import org.bson.BsonType
import org.bson.BsonValue

/**
 * Shared clone-and-modify builder for InteractionVars (weapon damage) overrides.
 *
 * InteractionVars cannot use [BsonHelper.buildNestedBson]: the `Interactions` ARRAY
 * sits between the var name and `DamageCalculator`, and [BsonHelper.deepMerge] replaces
 * arrays wholesale — a minimal Interactions array would wipe `DamageEffects`, the `Next`
 * chain, `EntityStatsOnHit`, etc. So we clone the COMPLETE var entry from the item's
 * current BSON, modify specific `DamageCalculator` values in-place, and store the full
 * clone as the override.
 *
 * Used by BOTH the single-item editor ([me.itemforge.vuetale.EditorBridge]) and the
 * batch engine ([me.itemforge.core.BatchEngine]) so the two write paths can never drift.
 *
 * Pure BSON — no threading concerns. `encode()` is read-only; only the fresh encoded
 * copy is mutated. Safe on any thread that holds a valid [ExtraInfo] (V8 or batch worker).
 */
object InteractionVarsBson {

    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()

    /** Pseudo damage-type meaning "every damage type" — used for the "All Damage" field. */
    const val ALL_TYPES = "All"

    /** Result of [buildDamageOverride]: the override plus aggregate before/after totals. */
    data class DamageOverrideResult(
        val override: BsonDocument,
        /** Sum of the affected values BEFORE the change (across every attack var). */
        val oldSum: Double,
        /** Sum of the affected values AFTER the change. */
        val newSum: Double,
        /** How many attack vars were actually changed. */
        val varsAffected: Int
    )

    /** Union of `BaseDamage` keys across ALL attack vars (the damage types this weapon deals). */
    fun allDamageTypes(codecScanner: CodecScanner, item: Item): Set<String> {
        val vars = encodeVars(codecScanner, item) ?: return emptySet()
        val types = LinkedHashSet<String>()
        for (varName in vars.keys) {
            val baseDamage = baseDamageOf(vars[varName]) ?: continue
            types.addAll(baseDamage.keys)
        }
        return types
    }

    /**
     * Sum of [dmgType] BaseDamage across EVERY attack var (so multi-attack weapons report
     * their full output, not just the first swing). [dmgType] == [ALL_TYPES] sums every
     * type on every attack. Returns null if no attack has any matching damage.
     */
    fun sumBaseDamage(codecScanner: CodecScanner, item: Item, dmgType: String): Double? {
        val vars = encodeVars(codecScanner, item) ?: return null
        var sum = 0.0
        var found = false
        val all = dmgType == ALL_TYPES
        for (varName in vars.keys) {
            val baseDamage = baseDamageOf(vars[varName]) ?: continue
            if (all) {
                for (t in baseDamage.keys) bsonToDouble(baseDamage[t])?.let { sum += it; found = true }
            } else {
                bsonToDouble(baseDamage[dmgType])?.let { sum += it; found = true }
            }
        }
        return if (found) sum else null
    }

    /**
     * Builds a weapon-damage override applying [transform] to [dmgType] on EVERY attack var.
     *
     * - Specific type: each attack's `BaseDamage[dmgType]` is transformed. Attacks lacking
     *   that type are skipped UNLESS [createIfMissing] (SET) — then the type is added.
     * - [ALL_TYPES]: every existing damage type on every attack is transformed (never creates).
     *
     * Returns the full clone override (covering only the changed attack vars) plus the
     * aggregate old/new sums, or null if no attack was changed. The clone-and-modify keeps
     * each attack's DamageEffects / Next chain / EntityStatsOnHit intact.
     */
    fun buildDamageOverride(
        codecScanner: CodecScanner,
        item: Item,
        dmgType: String,
        createIfMissing: Boolean,
        transform: (Double) -> Double
    ): DamageOverrideResult? {
        val currentVars = encodeVars(codecScanner, item) ?: return null
        val overrideVars = BsonDocument()
        var oldSum = 0.0
        var newSum = 0.0
        var affected = 0
        val all = dmgType == ALL_TYPES

        for (varName in currentVars.keys.toList()) {
            val varEntryVal = currentVars[varName]
            val damageCalc = damageCalculatorOf(varEntryVal) ?: continue
            val varEntry = varEntryVal!!.asDocument()
            val baseDamage = damageCalc["BaseDamage"]?.takeIf { it.bsonType == BsonType.DOCUMENT }?.asDocument()
            var changed = false

            if (all) {
                if (baseDamage == null) continue
                for (type in baseDamage.keys.toList()) {
                    val existing = bsonToDouble(baseDamage[type]) ?: continue
                    val nv = transform(existing)
                    baseDamage.put(type, BsonDouble(nv))
                    oldSum += existing; newSum += nv; changed = true
                }
            } else {
                val existing = baseDamage?.let { bsonToDouble(it[dmgType]) }
                val nv: Double? = when {
                    existing != null -> transform(existing)
                    createIfMissing -> transform(0.0)
                    else -> null
                }
                if (nv != null) {
                    applyDamageCalculatorChange(damageCalc, "BaseDamage.$dmgType", nv)
                    oldSum += existing ?: 0.0; newSum += nv; changed = true
                }
            }

            if (changed) {
                overrideVars.put(varName, varEntry)
                affected++
            }
        }

        if (affected == 0) return null
        return DamageOverrideResult(
            BsonDocument().apply { put("InteractionVars", overrideVars) },
            oldSum, newSum, affected
        )
    }

    /**
     * Builds `{ InteractionVars: { <var>: <full clone with modified DamageCalculator> } }`.
     *
     * [ivChanges] maps varName → { subFieldKey → newValue }, where subFieldKey is one of
     * `"BaseDamage.<Type>"`, `"Class"`, or `"RandomPercentageModifier"`. Returns null if
     * nothing could be built (item not a weapon, vars missing, etc.).
     */
    fun buildOverride(
        codecScanner: CodecScanner,
        item: Item,
        ivChanges: Map<String, Map<String, Any>>
    ): BsonDocument? {
        val currentVars = encodeVars(codecScanner, item) ?: return null
        val overrideVars = BsonDocument()

        for ((varName, subChanges) in ivChanges) {
            val varEntryVal = currentVars[varName]
            if (varEntryVal == null || varEntryVal.bsonType != BsonType.DOCUMENT) {
                logger.atWarning().log("InteractionVarsBson: var '%s' not found in encoded BSON", varName)
                continue
            }
            // The encoded BSON is a fresh copy — safe to modify in-place. damageCalc is a
            // live sub-document of varEntry, so mutating it mutates the clone we store.
            val varEntry = varEntryVal.asDocument()
            val damageCalc = damageCalculatorOf(varEntryVal) ?: continue
            for ((subFieldKey, newValue) in subChanges) {
                applyDamageCalculatorChange(damageCalc, subFieldKey, newValue)
            }
            overrideVars.put(varName, varEntry)
        }

        if (overrideVars.isEmpty()) return null
        return BsonDocument().apply { put("InteractionVars", overrideVars) }
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Returns the item's `InteractionVars` document, or null. Read-only.
     *
     * Uses a TARGETED single-field encode of `InteractionVars` ONLY — a full-item encode
     * (`itemCodec.encode`) walks every field including the recursive `Interactions` /
     * `InteractionConfig` contained-asset chains, which can StackOverflow on items with deep
     * chains. Running that across the whole item set crashed dashboard open. The single-field
     * encode gives exactly what every caller here needs without touching those fields.
     *
     * Catches [Throwable] (not just Exception) so a pathological item is skipped rather than
     * propagating a StackOverflowError up the world thread.
     */
    private fun encodeVars(codecScanner: CodecScanner, item: Item): BsonDocument? {
        return try {
            val ivFieldList = codecScanner.itemCodec.entries["InteractionVars"] ?: return null
            val ivField = ivFieldList.last()
            val doc = BsonDocument()
            val extraInfo = ExtraInfo.THREAD_LOCAL.get()
            @Suppress("UNCHECKED_CAST")
            (ivField as com.hypixel.hytale.codec.builder.BuilderField<Any, Any>).encode(doc, item, extraInfo)
            val ivVal = doc["InteractionVars"] ?: return null
            if (ivVal.bsonType != BsonType.DOCUMENT) null else ivVal.asDocument()
        } catch (_: Throwable) {
            null
        }
    }

    /** Navigates var entry → Interactions[0] → DamageCalculator. */
    private fun damageCalculatorOf(varEntryVal: BsonValue?): BsonDocument? {
        if (varEntryVal == null || varEntryVal.bsonType != BsonType.DOCUMENT) return null
        val interactions = varEntryVal.asDocument()["Interactions"] ?: return null
        if (interactions.bsonType != BsonType.ARRAY) return null
        val arr = interactions.asArray()
        if (arr.isEmpty()) return null
        val first = arr[0]
        if (first.bsonType != BsonType.DOCUMENT) return null
        val dcVal = first.asDocument()["DamageCalculator"] ?: return null
        if (dcVal.bsonType != BsonType.DOCUMENT) return null
        return dcVal.asDocument()
    }

    /** A var entry's `DamageCalculator.BaseDamage` document, or null. */
    private fun baseDamageOf(varEntryVal: BsonValue?): BsonDocument? {
        val dc = damageCalculatorOf(varEntryVal) ?: return null
        val bd = dc["BaseDamage"] ?: return null
        return if (bd.bsonType == BsonType.DOCUMENT) bd.asDocument() else null
    }

    /** Applies one in-place change to a DamageCalculator document. */
    private fun applyDamageCalculatorChange(damageCalc: BsonDocument, subFieldKey: String, newValue: Any) {
        when {
            subFieldKey.startsWith("BaseDamage.") -> {
                val dmgType = subFieldKey.removePrefix("BaseDamage.")
                val baseDamage = damageCalc["BaseDamage"]?.let {
                    if (it.bsonType == BsonType.DOCUMENT) it.asDocument() else null
                } ?: BsonDocument().also { damageCalc.put("BaseDamage", it) }
                baseDamage.put(dmgType, BsonDouble((newValue as Number).toDouble()))
            }
            subFieldKey == "Class" -> damageCalc.put("Class", BsonString(newValue as String))
            subFieldKey == "RandomPercentageModifier" ->
                damageCalc.put("RandomPercentageModifier", BsonDouble((newValue as Number).toDouble()))
            else -> logger.atWarning().log("InteractionVarsBson: unknown sub-field '%s'", subFieldKey)
        }
    }

    private fun bsonToDouble(v: BsonValue?): Double? = when {
        v == null -> null
        v.isDouble -> v.asDouble().value
        v.isInt32 -> v.asInt32().value.toDouble()
        v.isInt64 -> v.asInt64().value.toDouble()
        else -> null
    }
}

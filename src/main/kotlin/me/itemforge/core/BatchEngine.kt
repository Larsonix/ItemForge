package me.itemforge.core

import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.metadata.ItemNameResolver
import me.itemforge.scanner.CodecScanner
import me.itemforge.scanner.FieldDefinition
import me.itemforge.scanner.ValueType
import me.itemforge.util.BsonHelper
import me.itemforge.util.InteractionVarsBson
import org.bson.BsonDocument
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Batch stat modification engine — apply the same operation to a field across
 * multiple items simultaneously.
 *
 * ## Operations
 *
 * | Operation | Effect | Example |
 * |-----------|--------|---------|
 * | SCALE     | value * (percent / 100) | Scale Health 80% → 25 becomes 20 |
 * | SET       | value = target | Set MaxDurability to 100 on all selected |
 * | ADD       | value + amount | Add 10 to all Health modifiers |
 * | SUBTRACT  | value - amount | Subtract 5 from all damage values |
 *
 * ## Workflow
 *
 * 1. Admin selects items in dashboard table view
 * 2. Opens batch edit overlay → picks field, operation, value
 * 3. [preview] shows a dry run with before/after values for each item
 * 4. Admin reviews and confirms → [apply] executes the changes
 * 5. Toast appears with "Batch applied — [Undo]" (10s timeout)
 * 6. If admin clicks undo → [revert] restores pre-batch values
 *
 * @param codecScanner For scanning item fields to find target values
 * @param overrideEngine For applying individual item overrides
 * @param committer For batch-committing all changes in a single loadAssets call
 * @param logger For error reporting
 */
class BatchEngine(
    private val codecScanner: CodecScanner,
    private val overrideEngine: OverrideEngine,
    private val committer: AssetCommitter,
    private val logger: HytaleLogger? = null
) {

    /**
     * Previews a batch operation without applying changes.
     *
     * Scans the target field on each item, computes what the new value would be,
     * and returns a preview row for each item (including skipped items with reasons).
     *
     * @param itemIds The selected item IDs
     * @param fieldId The dot-separated field ID to modify (e.g., "Armor.StatModifiers.Health")
     * @param operation The batch operation to apply
     * @param operand The operation value (scale percent, set value, add/subtract amount)
     * @return List of preview rows for the batch edit overlay
     */
    fun preview(
        itemIds: List<String>,
        fieldId: String,
        operation: BatchOperation,
        operand: Double
    ): List<BatchPreviewRow> {
        return itemIds.map { itemId ->
            previewItem(itemId, fieldId, operation, operand)
        }
    }

    /**
     * Applies a batch operation to all items.
     *
     * For efficiency, applies overrides without immediate sync, then commits
     * all modified items in a single loadAssets call.
     *
     * @param itemIds The selected item IDs
     * @param fieldId The dot-separated field ID to modify
     * @param operation The batch operation to apply
     * @param operand The operation value
     * @return Batch result with per-item outcomes
     */
    fun apply(
        itemIds: List<String>,
        fieldId: String,
        operation: BatchOperation,
        operand: Double
    ): BatchApplyResult {
        // Weapon damage spans every attack var (Path B) — handled as one batched re-decode.
        if (fieldId.startsWith(WDMG_PREFIX)) {
            return applyWeaponDamageBatch(itemIds, fieldId, operation, operand)
        }

        val results = mutableListOf<BatchItemResult>()
        val modifiedItems = mutableListOf<Item>()

        for (itemId in itemIds) {
            val result = applyToItem(itemId, fieldId, operation, operand)
            results.add(result)

            // Path B items (weapon damage) self-sync inside applyWithoutSync — exclude
            // them from the batched commit to avoid a redundant second loadAssets.
            if (result.success && !result.pathB) {
                Item.getAssetMap().getAsset(itemId)?.let { modifiedItems.add(it) }
            }
        }

        // Batch commit — single loadAssets call for all modified items
        if (modifiedItems.isNotEmpty()) {
            committer.commitItems(modifiedItems)
        }

        val applied = results.count { it.success }
        val skipped = results.count { !it.success }
        logger?.atInfo()?.log("Batch $operation on '$fieldId': $applied applied, $skipped skipped")

        // Build the undo buffer from the pre-batch values captured per item.
        // Only successful items with a known old value are revertible — skipped
        // items were never touched, so they don't belong in the buffer. A buffer
        // is only produced when there is at least one revertible change, so the
        // bridge can decide whether to offer an undo toast at all (no-op batches
        // must not show "Undo" — red-team boundary test).
        val preValues = results
            .filter { it.success && it.oldValue != null }
            .associate { it.itemId to it.oldValue!! }
        val undoBuffer = if (preValues.isNotEmpty()) {
            BatchUndoBuffer(
                itemIds = preValues.keys.toList(),
                targetField = fieldId,
                preValues = preValues,
                timestamp = java.time.Instant.now()
            )
        } else {
            null
        }

        return BatchApplyResult(BatchResult(results), undoBuffer)
    }

    /**
     * Reverts a batch operation using captured pre-batch values.
     *
     * Returns a per-item result whose [BatchItemResult.overrideBson] is the override to
     * RE-PERSIST for that item; the caller decides remove-vs-save based on whether the
     * item had a prior override (fresh items are removed entirely). Weapon-damage reverts
     * use the clone-and-modify Path B (self-syncing) and are flagged [BatchItemResult.pathB]
     * so they are excluded from the batched commit.
     *
     * @param undoBuffer The undo buffer captured during [apply]
     */
    fun revert(undoBuffer: BatchUndoBuffer): List<BatchItemResult> {
        val results = mutableListOf<BatchItemResult>()
        val modifiedItems = mutableListOf<Item>()
        val field = undoBuffer.targetField

        for ((itemId, preValue) in undoBuffer.preValues) {
            val item = Item.getAssetMap().getAsset(itemId)
            if (item == null) {
                results.add(BatchItemResult(itemId, false, "Item not found"))
                continue
            }

            // Only numeric (Path-A) fields reach here — weapon-damage undo is snapshot-based
            // (DashboardBridge.batchUndo → OverrideEngine.batchRestoreSnapshots).
            val overrideBson = BsonHelper.buildNestedBson(
                field, BsonHelper.kotlinToBson(preValue, ValueType.DOUBLE)
            )
            val ok = overrideEngine.applyWithoutSync(itemId, overrideBson)
            val result = BatchItemResult(
                itemId = itemId,
                success = ok,
                error = if (!ok) "Override application failed" else null,
                oldValue = preValue,
                newValue = preValue,
                overrideBson = if (ok) overrideBson else null
            )

            results.add(result)
            // Path B reverts self-sync; only batch the Path A items into one commit.
            if (result.success && !result.pathB) {
                Item.getAssetMap().getAsset(itemId)?.let { modifiedItems.add(it) }
            }
        }

        if (modifiedItems.isNotEmpty()) {
            committer.commitItems(modifiedItems)
        }

        val failed = results.count { !it.success }
        if (failed > 0) {
            logger?.atWarning()?.log("Batch undo: $failed item(s) failed to revert")
        }

        return results
    }

    // ── Per-Item Operations ─────────────────────────────────────────────

    private fun previewItem(
        itemId: String,
        fieldId: String,
        operation: BatchOperation,
        operand: Double
    ): BatchPreviewRow {
        val item = Item.getAssetMap().getAsset(itemId)
        if (item == null) {
            return BatchPreviewRow(
                itemId = itemId,
                itemName = ItemNameResolver.resolve(itemId),
                oldValue = null,
                newValue = null,
                skipped = true,
                reason = "Item not found"
            )
        }

        // Normalized weapon damage ("@wdmg.<Type>") is not a codec dot-path — resolve it
        // through the item's primary interaction instead of the field scan.
        if (fieldId.startsWith(WDMG_PREFIX)) {
            return previewWeaponDamage(item, itemId, fieldId, operation, operand)
        }

        val fieldDef = findField(item, fieldId)
        if (fieldDef == null) {
            return BatchPreviewRow(
                itemId = itemId,
                itemName = ItemNameResolver.resolve(item),
                oldValue = null,
                newValue = null,
                skipped = true,
                reason = "Field '$fieldId' not present on this item"
            )
        }

        val oldValue = fieldDef.currentValue as? Number
        if (oldValue == null) {
            return BatchPreviewRow(
                itemId = itemId,
                itemName = ItemNameResolver.resolve(item),
                oldValue = null,
                newValue = null,
                skipped = true,
                reason = "Field value is not numeric"
            )
        }

        val newValue = computeNewValue(oldValue.toDouble(), operation, operand)

        return BatchPreviewRow(
            itemId = itemId,
            itemName = ItemNameResolver.resolve(item),
            oldValue = oldValue.toDouble(),
            newValue = newValue,
            skipped = false,
            reason = null
        )
    }

    private fun applyToItem(
        itemId: String,
        fieldId: String,
        operation: BatchOperation,
        operand: Double
    ): BatchItemResult {
        val item = Item.getAssetMap().getAsset(itemId)
            ?: return BatchItemResult(itemId, false, "Item not found")

        val fieldDef = findField(item, fieldId)
            ?: return BatchItemResult(itemId, false, "Field not present")

        val oldValue = fieldDef.currentValue as? Number
            ?: return BatchItemResult(itemId, false, "Value is not numeric")

        val newValue = computeNewValue(oldValue.toDouble(), operation, operand)

        // Build override BSON for this field
        val bsonValue = BsonHelper.kotlinToBson(newValue, fieldDef.valueType)
        val overrideBson = BsonHelper.buildNestedBson(fieldId, bsonValue)

        val success = overrideEngine.applyWithoutSync(itemId, overrideBson)

        return BatchItemResult(
            itemId = itemId,
            success = success,
            error = if (!success) "Override application failed" else null,
            oldValue = oldValue.toDouble(),
            newValue = newValue,
            // Returned so the bridge can persist the same override it applied in-memory
            // (deep-merged into ItemOverrideStore). Only set on success.
            overrideBson = if (success) overrideBson else null
        )
    }

    // ── Weapon Damage (normalized "@wdmg.<Type>" / "@wdmg.All") ─────────────
    //
    // Weapon damage lives in InteractionVars under per-item var names; the catalog exposes
    // it uniformly. A "@wdmg.<Type>" op applies to that type on EVERY attack var; "@wdmg.All"
    // applies to every damage type on every attack. Reads report the SUM across all attacks.
    // Writes are Path B (full re-decode) and the whole selection is committed in ONE batched
    // sync via OverrideEngine.batchApplyOverrides (no per-item broadcast).

    /** Operation as a per-attack value transform. */
    private fun opTransform(operation: BatchOperation, operand: Double): (Double) -> Double = when (operation) {
        BatchOperation.SCALE -> { v -> v * (operand / 100.0) }
        BatchOperation.SET -> { _ -> operand }
        BatchOperation.ADD -> { v -> v + operand }
        BatchOperation.SUBTRACT -> { v -> v - operand }
    }

    /** SET on a specific (non-All) type may CREATE it on attacks that lack it. */
    private fun createsMissing(operation: BatchOperation, dmgType: String): Boolean =
        operation == BatchOperation.SET && dmgType != InteractionVarsBson.ALL_TYPES

    private fun previewWeaponDamage(
        item: Item,
        itemId: String,
        fieldId: String,
        operation: BatchOperation,
        operand: Double
    ): BatchPreviewRow {
        val name = ItemNameResolver.resolve(item)
        val dmgType = fieldId.removePrefix(WDMG_PREFIX)
        val r = InteractionVarsBson.buildDamageOverride(
            codecScanner, item, dmgType, createsMissing(operation, dmgType), opTransform(operation, operand)
        ) ?: return BatchPreviewRow(itemId, name, null, null, true, "No ${dmgTypeLabel(dmgType)} damage on this weapon")
        return BatchPreviewRow(itemId, name, r.oldSum, r.newSum, false, null)
    }

    /**
     * Applies a weapon-damage op across the whole selection in ONE batched Path-B sync.
     * Builds every item's full-clone override first, then commits them together.
     */
    private fun applyWeaponDamageBatch(
        itemIds: List<String>,
        fieldId: String,
        operation: BatchOperation,
        operand: Double
    ): BatchApplyResult {
        val dmgType = fieldId.removePrefix(WDMG_PREFIX)
        val createIfMissing = createsMissing(operation, dmgType)
        val transform = opTransform(operation, operand)

        val overrides = LinkedHashMap<String, BsonDocument>()
        val sums = HashMap<String, Pair<Double, Double>>()   // itemId -> (oldSum, newSum)
        val skipped = mutableListOf<BatchItemResult>()

        for (itemId in itemIds) {
            val item = Item.getAssetMap().getAsset(itemId)
            if (item == null) {
                skipped.add(BatchItemResult(itemId, false, "Item not found")); continue
            }
            val r = InteractionVarsBson.buildDamageOverride(codecScanner, item, dmgType, createIfMissing, transform)
            if (r == null) {
                skipped.add(BatchItemResult(itemId, false, "No ${dmgTypeLabel(dmgType)} damage on this weapon")); continue
            }
            overrides[itemId] = r.override
            sums[itemId] = r.oldSum to r.newSum
        }

        // ONE batched re-decode + commit for the whole selection (not N broadcasts).
        val applied = overrideEngine.batchApplyOverrides(overrides).toSet()

        val results = mutableListOf<BatchItemResult>()
        for ((itemId, override) in overrides) {
            val ok = itemId in applied
            val (oldSum, newSum) = sums.getValue(itemId)
            results.add(BatchItemResult(
                itemId = itemId,
                success = ok,
                error = if (!ok) "Override application failed" else null,
                oldValue = oldSum,
                newValue = newSum,
                overrideBson = if (ok) override else null,
                pathB = true
            ))
        }
        results.addAll(skipped)

        // Weapon-damage undo is snapshot-based (per-attack values can't be rebuilt from a
        // sum), so no Double undo buffer is produced here — the bridge captures snapshots.
        return BatchApplyResult(BatchResult(results), null)
    }

    private fun dmgTypeLabel(dmgType: String): String =
        if (dmgType == InteractionVarsBson.ALL_TYPES) "any" else dmgType

    private fun findField(item: Item, fieldId: String): FieldDefinition? {
        val fields = codecScanner.scan(item)
        return fields.find { it.id == fieldId }
    }

    private fun computeNewValue(
        oldValue: Double,
        operation: BatchOperation,
        operand: Double
    ): Double {
        return when (operation) {
            BatchOperation.SCALE -> oldValue * (operand / 100.0)
            BatchOperation.SET -> operand
            BatchOperation.ADD -> oldValue + operand
            BatchOperation.SUBTRACT -> oldValue - operand
        }
    }

    companion object {
        /** Catalog id prefix for normalized weapon damage ("@wdmg.Physical", "@wdmg.Fire", …). */
        const val WDMG_PREFIX = "@wdmg."
    }
}

/**
 * Batch operations that can be applied to a numeric field.
 */
enum class BatchOperation {
    /** Multiply by (operand / 100). E.g., 80% scales 25 → 20. */
    SCALE,

    /** Set to the exact operand value. */
    SET,

    /** Add operand to current value. */
    ADD,

    /** Subtract operand from current value. */
    SUBTRACT
}

/**
 * Result of a batch operation across all items.
 */
data class BatchResult(val items: List<BatchItemResult>) {
    val successCount: Int get() = items.count { it.success }
    val failCount: Int get() = items.count { !it.success }
}

/**
 * Outcome of [BatchEngine.apply]: the per-item result plus the undo buffer
 * captured from pre-batch values.
 *
 * [undoBuffer] is null when no item was actually changed (every item skipped) —
 * the bridge uses this to suppress the undo toast for no-op batches.
 */
data class BatchApplyResult(
    val result: BatchResult,
    val undoBuffer: BatchUndoBuffer?
)

/**
 * Result of a batch operation on a single item.
 */
data class BatchItemResult(
    val itemId: String,
    val success: Boolean,
    val error: String? = null,
    val oldValue: Double? = null,
    val newValue: Double? = null,
    /** The override BSON that was applied — used by the bridge to persist to config. */
    val overrideBson: org.bson.BsonDocument? = null,
    /** True for InteractionVars (weapon damage) writes: they re-decode via Path B and
     *  self-sync inside applyWithoutSync, so the caller must NOT re-commit them. */
    val pathB: Boolean = false
)

/**
 * Preview row for the batch edit overlay — shows what would change.
 */
data class BatchPreviewRow(
    val itemId: String,
    val itemName: String,
    val oldValue: Double?,
    val newValue: Double?,
    val skipped: Boolean,
    val reason: String? = null
)

/**
 * Captured pre-batch values for undo. Created during [BatchEngine.apply].
 */
data class BatchUndoBuffer(
    val itemIds: List<String>,
    val targetField: String,
    val preValues: Map<String, Double>,
    val timestamp: java.time.Instant
)

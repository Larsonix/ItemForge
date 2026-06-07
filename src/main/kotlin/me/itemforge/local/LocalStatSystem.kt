package me.itemforge.local

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.tick.EntityTickingSystem
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.inventory.InventoryComponent
import com.hypixel.hytale.server.core.inventory.ItemStack
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * ItemForge's always-on per-item ENTITY-STAT applier (attribute tier).
 *
 * The engine grants gear stats (max health, stamina, mana, …) from the item ASSET's `StatModifiers`,
 * never from per-stack metadata. So a per-item ("This Item") stat bonus only takes effect if ItemForge
 * applies it itself — the same `StatModifyingSystem` pattern the engine's own `StatModifiersManager`
 * uses. This is that system.
 *
 * ## How it works (verified from the 0.5.3 decompile)
 * Each tick, for every entity that has an [EntityStatMap], it reads the EXTRA per-item stat bonuses
 * stored on the entity's WORN armor pieces (`ItemForge.stat.<statId>`), sums them per stat, and
 * maintains a single ItemForge modifier per stat via [EntityStatMap.putModifier] /
 * [EntityStatMap.removeModifier] — a `StaticModifier(MAX, ADDITIVE, amount)` (raises that stat's max,
 * exactly like vanilla armor health). It implements [EntityStatsSystems.StatModifyingSystem] so its
 * writes are ordered before the engine's change-detection that tick.
 *
 * ## Idempotent + stateless
 * It reconciles across EVERY stat the entity has: a stat with no per-item bonus gets our modifier
 * REMOVED (so bonuses vanish the moment gear is taken off — no per-entity tracking needed), and a
 * stat whose desired value already equals what we wrote is skipped (no redundant network update —
 * the same guard the engine's `addItemStatModifiers` uses). On lowering/removing a max bonus, current
 * is clamped down to the new max.
 *
 * ## Threading & safety
 * Runs on the world/store tick thread (NOT V8). `isParallel = false` (stat writes must be serial,
 * matching the engine's stat systems). The whole body is exception-wrapped and fails OPEN. Per-tick
 * cost on a typical entity: read ~4 armor stacks' metadata (cheap, non-cloning) + a handful of
 * modifier lookups; gated to entities that actually have a stat map.
 *
 * NOTE: movement speed is intentionally NOT here — it is not an EntityStatType (it's driven by
 * EntityEffect HorizontalSpeedMultiplier via EffectControllerComponent), so the stat map can't carry it.
 */
class LocalStatSystem : EntityTickingSystem<EntityStore>(), EntityStatsSystems.StatModifyingSystem {
    private val logger = HytaleLogger.forEnclosingClass()

    /** Only tick entities that actually have a stat map (ComponentType is itself a Query). */
    override fun getQuery(): Query<EntityStore> = EntityStatMap.getComponentType()

    /** Stat writes must be serial (matches the engine's own stat-modifying systems). */
    override fun isParallel(archetypeChunkSize: Int, taskCount: Int): Boolean = false

    override fun tick(
        dt: Float,
        index: Int,
        chunk: ArchetypeChunk<EntityStore>,
        store: Store<EntityStore>,
        commandBuffer: CommandBuffer<EntityStore>
    ) {
        try {
            val statMap = chunk.getComponent(index, EntityStatMap.getComponentType()) ?: return
            val ref = chunk.getReferenceTo(index)

            // Sum per-item stat bonuses across the entity's WORN armor pieces (read once).
            val totals = HashMap<String, Double>()
            val armor = commandBuffer
                .getComponent(ref, InventoryComponent.Armor.getComponentType())?.inventory
            if (armor != null) {
                val cap = armor.capacity
                var slot = 0
                while (slot < cap) {
                    val piece = armor.getItemStack(slot.toShort())
                    if (piece != null && !piece.isEmpty) accumulateStatBonuses(piece, totals)
                    slot++
                }
            }

            // Reconcile our modifier across EVERY stat the entity has. Stats absent from `totals`
            // (desired 0) get our modifier removed → bonuses clear the instant gear comes off, with
            // no per-entity state to track.
            val n = statMap.size()
            var i = 0
            while (i < n) {
                if (statMap.get(i) != null) {
                    val statType = EntityStatType.getAssetMap().getAsset(i)
                    if (statType != null) reconcile(statMap, i, totals[statType.id] ?: 0.0)
                }
                i++
            }
        } catch (e: Throwable) {
            logger.atWarning().withCause(e)
                .log("LocalStatSystem: per-item stat apply failed — leaving stats unchanged")
        }
    }

    /** Adds a stack's `ItemForge.stat.<statId>` bonuses into [totals]. */
    private fun accumulateStatBonuses(stack: ItemStack, totals: HashMap<String, Double>) {
        val forge = stack.getFromMetadataOrNull(LocalScopeFields.METADATA_KEY, Codec.BSON_DOCUMENT) ?: return
        val stat = forge.get(LocalScopeFields.STAT_SUBKEY)?.takeIf { it.isDocument }?.asDocument() ?: return
        for ((statId, v) in stat) {
            if (v.isNumber) totals[statId] = (totals[statId] ?: 0.0) + v.asNumber().doubleValue()
        }
    }

    /** Idempotently set/clear ItemForge's single modifier for one stat to the desired flat bonus. */
    private fun reconcile(statMap: EntityStatMap, index: Int, desired: Double) {
        val existing = statMap.getModifier(index, KEY)
        if (kotlin.math.abs(desired) < 1e-9) {
            if (existing != null) {
                statMap.removeModifier(index, KEY)
                clampCurrentToMax(statMap, index)
            }
            return
        }
        val mod = StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, desired.toFloat())
        if (existing !is StaticModifier || existing != mod) {
            statMap.putModifier(index, KEY, mod)
            clampCurrentToMax(statMap, index)
        }
    }

    /** After a max change, keep current ≤ max (e.g. unequipping +max gear shouldn't leave current above max). */
    private fun clampCurrentToMax(statMap: EntityStatMap, index: Int) {
        val v = statMap.get(index) ?: return
        if (v.get() > v.getMax()) statMap.setStatValue(index, v.getMax())
    }

    private companion object {
        /** Stable per-stat key for ItemForge's modifier (one per stat index). */
        const val KEY = "ItemForge:local"
    }
}

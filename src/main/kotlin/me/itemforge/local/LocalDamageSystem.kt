package me.itemforge.local

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.SystemGroup
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.inventory.InventoryComponent
import com.hypixel.hytale.server.core.inventory.ItemStack
import com.hypixel.hytale.server.core.modules.entity.damage.Damage
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * ItemForge's always-on per-item WEAPON DAMAGE applier (combat tier).
 *
 * The Hytale engine reads a weapon's damage from the item ASSET, never from per-stack metadata, so a
 * per-item damage override only "bites" if ItemForge applies it itself at hit time — exactly the
 * pattern SocketReforge uses. This is that system.
 *
 * ## How it works (all verified from the 0.5.3 decompile)
 * Every melee/projectile hit dispatches one [Damage] event per [DamageCause] via
 * `commandBuffer.invoke(targetRef, damage)`. The engine runs every registered [DamageEventSystem]
 * against the target; we register in the FILTER group (alongside the engine's own
 * `ArmorDamageReduction`). In [handle] the chunk entity is the TARGET; the attacker is
 * `damage.getSource()` (an [Damage.EntitySource]; bows use the subclass `ProjectileSource` whose
 * `getRef()` is the shooter). We read the attacker's HELD weapon, look up its per-item multiplier for
 * this hit's damage cause (stored under the `ItemForge.dmg` metadata doc), and scale
 * `damage.getAmount()`. Multiplicative scaling composes cleanly across a weapon's multiple damage
 * causes and across light/charged/angled variants (the engine only exposes the cause here, so a
 * per-item edit intentionally scales ALL of the weapon's attacks of that cause).
 *
 * ## Threading & safety
 * Damage event systems run on the world thread during the ECS tick (NOT V8 — no Vuetale rules apply).
 * The whole body is exception-wrapped and fails OPEN (an error never changes or blocks the hit). The
 * per-hit cost is one held-item read + one metadata `get` + one cause lookup + arithmetic — cheap;
 * SocketReforge does far more per hit in production. If a prior system already cancelled the hit,
 * `handleInternal` skips us automatically (`shouldProcessEvent`).
 */
class LocalDamageSystem : DamageEventSystem() {
    private val logger = HytaleLogger.forEnclosingClass()

    /** Run in the filter group, alongside vanilla armor/wielding reduction (verified placement). */
    override fun getGroup(): SystemGroup<EntityStore>? = DamageModule.get().filterDamageGroup

    /** Match any damaged entity; we gate on the attacker (source) inside [handle]. */
    override fun getQuery(): Query<EntityStore> = Query.any<EntityStore>()

    override fun handle(
        index: Int,
        chunk: ArchetypeChunk<EntityStore>,
        store: Store<EntityStore>,
        commandBuffer: CommandBuffer<EntityStore>,
        damage: Damage
    ) {
        try {
            val cause = DamageCause.getAssetMap().getAsset(damage.damageCauseIndex) ?: return
            val causeId = cause.id

            // ── Attacker side: per-item WEAPON DAMAGE multiplier ──────────────────────────────
            // Only entity-sourced hits carry an attacker (melee = EntitySource, bows = ProjectileSource
            // whose getRef() is the shooter). Environmental/DoT damage has no held weapon → skipped.
            (damage.source as? Damage.EntitySource)?.let { src ->
                val weapon = InventoryComponent.getItemInHand(store, src.ref)
                if (weapon != null && !weapon.isEmpty) {
                    val mult = readForgeNumber(weapon, LocalScopeFields.DMG_SUBKEY, causeId)
                    if (mult != null && mult != 1.0) {
                        damage.amount = (damage.amount * mult).toFloat()
                    }
                }
            }

            // ── Target side: per-item ARMOR RESISTANCE ────────────────────────────────────────
            // The chunk entity is the TARGET (the Damage event is invoked on it). Sum the extra
            // per-item resistance across all worn armor pieces for this cause, then reduce the hit
            // (same place the engine's own ArmorDamageReduction runs — the filter group).
            val target = chunk.getReferenceTo(index)
            val armorContainer = commandBuffer
                .getComponent(target, InventoryComponent.Armor.getComponentType())?.inventory
            if (armorContainer != null) {
                var totalResist = 0.0
                val cap = armorContainer.capacity
                var slot = 0
                while (slot < cap) {
                    val piece = armorContainer.getItemStack(slot.toShort())
                    if (piece != null && !piece.isEmpty) {
                        readForgeNumber(piece, LocalScopeFields.DEF_SUBKEY, causeId)?.let { totalResist += it }
                    }
                    slot++
                }
                if (totalResist != 0.0) {
                    // resist 0.25 → take 75%; negative → take more; clamp so damage never goes < 0.
                    val factor = (1.0 - totalResist).coerceAtLeast(0.0)
                    damage.amount = (damage.amount * factor).toFloat()
                }
            }
        } catch (e: Throwable) {
            // Fail open: never block or alter a hit because of an override error.
            logger.atWarning().withCause(e)
                .log("LocalDamageSystem: per-item combat apply failed — leaving the hit unchanged")
        }
    }

    /**
     * Reads `ItemForge.<subKey>.<causeId>` off [stack]'s metadata as a Double, or null if absent.
     * Uses the direct (non-cloning) `getFromMetadataOrNull` read — cheap enough for the per-hit path.
     */
    private fun readForgeNumber(stack: ItemStack, subKey: String, causeId: String): Double? {
        val forge = stack.getFromMetadataOrNull(LocalScopeFields.METADATA_KEY, Codec.BSON_DOCUMENT)
            ?: return null
        val sub = forge.get(subKey)?.takeIf { it.isDocument }?.asDocument() ?: return null
        return sub.get(causeId)?.takeIf { it.isNumber }?.asNumber()?.doubleValue()
    }
}

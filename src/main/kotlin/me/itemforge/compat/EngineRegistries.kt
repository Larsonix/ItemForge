package me.itemforge.compat

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause

/**
 * Reads Hytale's extensible asset registries live, instead of trusting a list typed out from an
 * old decompile.
 *
 * ## Why this exists
 *
 * ItemForge's whole reason to exist is that it works on items from mods nobody has seen. It does
 * that by introspecting codecs rather than hardcoding fields. But the *keys* offered for the
 * damage maps — DamageResistance, DamageEnhancement, KnockbackResistances,
 * KnockbackEnhancements, and BaseDamage on a weapon — were a frozen set of eight strings copied
 * from the 0.5.0 assets, carrying the comment that they were "enum-keyed and NOT mod-extensible".
 *
 * That comment is wrong, and it was wrong when it was written. [DamageCause] is a
 * `JsonAssetWithMap` asset with its own `AssetBuilderCodec` — any mod can ship one, exactly like
 * a stat type. ItemForge already reads it live elsewhere (`LocalDamageSystem` resolves a cause by
 * index off `DamageCause.getAssetMap()`); only the editor's key list was frozen. So a mod that
 * adds a damage type gets stats it can never edit here, which is precisely the gap ItemForge
 * exists to close.
 *
 * ## Why an exclusion list, not an inclusion list
 *
 * The frozen eight were not merely stale — they were *curated*. The registry also contains causes
 * that describe how the world hurt you (falling, drowning, a command) rather than how a weapon
 * hurts, and putting those in a weapon's damage dropdown would be noise.
 *
 * So the live registry is the source and [NON_COMBAT_DAMAGE_CAUSES] is subtracted from it. That
 * inverts the failure mode: an unknown cause now shows up by default instead of being invisible
 * by default, which is the correct bias for a tool whose job is surfacing modded content.
 *
 * The exclusion list is not invented. It is the engine's own vocabulary: `DamageCause` declares
 * deprecated statics for exactly `COMMAND`, `DROWNING`, `ENVIRONMENT`, `FALL`, `OUT_OF_WORLD` and
 * `SUFFOCATION`, and the asset registry adds the near-duplicate `Environmental`.
 *
 * ## Measured, 2026-08-27
 *
 * The `damage_cause` registry holds 15 members and is **identical** on 0.5.4 and 0.6.0-pre.9:
 * `Bludgeoning, Command, Drowning, Elemental, Environment, Environmental, Fall, Fire, Ice,
 * OutOfWorld, Physical, Poison, Projectile, Slashing, Suffocation`. Fifteen minus the seven
 * excluded is exactly the eight that were hardcoded, so on vanilla this changes nothing visible.
 * It changes what happens on a modded or future server.
 */
object EngineRegistries {

    private val logger = HytaleLogger.forEnclosingClass()

    /**
     * Causes that describe environmental or administrative damage rather than a weapon's damage.
     *
     * Excluded from item-editing dropdowns because an item cannot meaningfully resist or enhance
     * them. Sourced from `DamageCause`'s own deprecated statics plus the `Environmental` asset
     * that sits alongside `Environment` in the shipped registry.
     */
    private val NON_COMBAT_DAMAGE_CAUSES = setOf(
        "Command", "Drowning", "Environment", "Environmental",
        "Fall", "OutOfWorld", "Suffocation"
    )

    /**
     * Used only when the live registry cannot be read — e.g. this is called before assets finish
     * loading. Matches the vanilla combat set measured on 0.5.4 and 0.6.0-pre.9, so the fallback
     * is correct on vanilla and merely incomplete on a modded server.
     */
    private val FALLBACK_COMBAT_DAMAGE_CAUSES = setOf(
        "Physical", "Projectile", "Bludgeoning", "Slashing",
        "Fire", "Ice", "Elemental", "Poison"
    )

    @Volatile
    private var cached: Set<String>? = null

    @Volatile
    private var usedFallback = false

    /** True when the last resolution fell back to the hardcoded vanilla set. */
    val isUsingFallback: Boolean get() = usedFallback

    /**
     * Damage causes an item can plausibly resist, enhance, or deal.
     *
     * Reads the live [DamageCause] registry once and caches it. Order is the registry's own,
     * so the dropdown ordering stays stable across restarts on an unchanged server.
     *
     * Never throws. Any failure falls back to [FALLBACK_COMBAT_DAMAGE_CAUSES] and logs once.
     */
    fun combatDamageCauses(): Set<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val live = readLive()
            if (live != null) {
                cached = live
                usedFallback = false
                return live
            }
            // ⚠ Deliberately do NOT cache the fallback.
            //
            // A failed read usually means "the DamageCause asset store is not registered yet",
            // not "this server has no damage causes". Caching now would freeze the hardcoded
            // vanilla eight in for the entire life of the server, and since nothing calls
            // invalidate(), nothing would ever undo it — a modded server would silently lose
            // every custom damage type with no error anywhere.
            //
            // This is not hypothetical: ItemForge's boot-time EngineProbe scans sample items
            // during plugin start, so the first call to this function now happens far earlier
            // than it used to, and whether it hits a damage-keyed map at all depends on which
            // items happen to sort first in the asset map. Leaving the cache unset means the
            // next caller simply retries.
            usedFallback = true
            return FALLBACK_COMBAT_DAMAGE_CAUSES
        }
    }

    /**
     * Guards the failure-path logs. Because a failed read is no longer cached, this function is
     * retried on every call — and it is called once per damage-keyed map field per scan, which is
     * thousands of times per editor open. Logging each failure would bury the server log; logging
     * once says the same thing.
     */
    @Volatile
    private var warnedFailure = false

    private fun warnOnce(block: () -> Unit) {
        if (!warnedFailure) {
            warnedFailure = true
            block()
        }
    }

    private fun readLive(): Set<String>? = try {
        val all = DamageCause.getAssetMap().assetMap.keys.toList()
        if (all.isEmpty()) {
            warnOnce {
                logger.atWarning().log(
                    "EngineRegistries: DamageCause registry is empty — falling back to the vanilla " +
                        "set for now. Modded damage types are not editable until it populates."
                )
            }
            null
        } else {
            val combat = all.filterNot { it in NON_COMBAT_DAMAGE_CAUSES }.toCollection(LinkedHashSet())
            val discovered = combat - FALLBACK_COMBAT_DAMAGE_CAUSES
            if (discovered.isNotEmpty()) {
                logger.atInfo().log(
                    "EngineRegistries: %d damage cause(s) beyond the vanilla set are now editable: %s",
                    discovered.size, discovered.joinToString(", ")
                )
            }
            val missing = FALLBACK_COMBAT_DAMAGE_CAUSES - combat
            if (missing.isNotEmpty()) {
                // A vanilla cause vanishing is a genuine engine change worth shouting about.
                logger.atWarning().log(
                    "EngineRegistries: %d previously-known damage cause(s) are absent from this " +
                        "server's registry: %s. A Hytale update may have renamed or removed them.",
                    missing.size, missing.joinToString(", ")
                )
            }
            logger.atInfo().log(
                "EngineRegistries: damage causes — %d in registry, %d editable, %d excluded as environmental",
                all.size, combat.size, all.size - combat.size
            )
            combat
        }
    } catch (e: Exception) {
        warnOnce {
            logger.atWarning().withCause(e).log(
                "EngineRegistries: could not read the DamageCause registry — falling back to the " +
                    "vanilla set for now, and retrying on the next call"
            )
        }
        null
    }

    /**
     * Drops the cache so a runtime asset reload that registers new damage causes is picked up.
     * Safe to call at any time; the next read repopulates.
     */
    fun invalidate() {
        synchronized(this) {
            cached = null
            usedFallback = false
            warnedFailure = false
        }
    }
}

package me.itemforge.inspect

import com.hypixel.hytale.logger.HytaleLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central state for Inspect Mode.
 *
 * Holds two pieces of per-player state, both keyed by player UUID:
 *
 *  - **inspecting** — which admins have toggled inspect mode ON (`/itemforge inspect`).
 *  - **crouching** — a cache of each inspecting player's crouch state, refreshed every
 *    tick by [CrouchTrackingSystem]. The cache exists solely because the packet-level
 *    suppressor ([InspectSuppressor]) runs on the netty IO thread, where it cannot read
 *    the world-thread-only `MovementStatesComponent`. The world-thread opener
 *    ([InspectInteractionListener]) reads crouch live and does not depend on the cache
 *    (it merely keeps it warm).
 *
 * ## Threading
 * Both maps are concurrent and safe for the access pattern:
 *  - `inspecting` — written from the command thread (toggle), read from the world thread
 *    (opener, ticking system) and the IO thread (suppressor).
 *  - `crouching` — written from the world thread (ticking system + opener), read from the
 *    IO thread (suppressor).
 *
 * Created in `ItemForgePlugin.setup()` so it exists before [CrouchTrackingSystem] is
 * registered, and shared with the opener, suppressor, and `/itemforge inspect` command.
 */
class InspectModeManager {

    private val logger = HytaleLogger.forEnclosingClass()

    /** Players with inspect mode ON. Backed by a concurrent set. */
    private val inspecting: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Crouch cache for inspecting players (UUID -> crouching). Maintained by [CrouchTrackingSystem]. */
    private val crouching = ConcurrentHashMap<UUID, Boolean>()

    /** @return true if [uuid] currently has inspect mode enabled. */
    fun isInspecting(uuid: UUID): Boolean = inspecting.contains(uuid)

    /** @return true if no player has inspect mode on — fast path for the ticking system. */
    fun hasNoInspectors(): Boolean = inspecting.isEmpty()

    /**
     * Toggles inspect mode for [uuid].
     * @return the new state (true = now ON).
     */
    fun toggle(uuid: UUID): Boolean {
        return if (inspecting.contains(uuid)) {
            inspecting.remove(uuid)
            crouching.remove(uuid)   // drop stale crouch when leaving inspect mode
            logger.atFine().log("Inspect mode OFF for %s", uuid)
            false
        } else {
            inspecting.add(uuid)
            logger.atFine().log("Inspect mode ON for %s", uuid)
            true
        }
    }

    /** Records the live crouch state for [uuid]. Called from the world thread only. */
    fun setCrouching(uuid: UUID, isCrouching: Boolean) {
        crouching[uuid] = isCrouching
    }

    /** @return cached crouch state for [uuid] (false if unknown). Read from the IO thread. */
    fun isCrouching(uuid: UUID): Boolean = crouching[uuid] ?: false

    /** Clears all state for a player. Call on disconnect. */
    fun onPlayerDisconnect(uuid: UUID) {
        inspecting.remove(uuid)
        crouching.remove(uuid)
    }

    /** Clears all state. Call on plugin shutdown. */
    fun shutdown() {
        inspecting.clear()
        crouching.clear()
    }
}

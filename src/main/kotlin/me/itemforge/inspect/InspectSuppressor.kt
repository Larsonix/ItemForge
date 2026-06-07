package me.itemforge.inspect

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.protocol.InteractionType
import com.hypixel.hytale.protocol.Packet
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains
import com.hypixel.hytale.server.core.inventory.InventoryComponent
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters
import com.hypixel.hytale.server.core.io.adapter.PacketFilter
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter
import com.hypixel.hytale.server.core.universe.PlayerRef
import me.itemforge.util.PermissionChecker
import me.itemforge.vuetale.VuetaleIntegration

/**
 * The core of Inspect Mode — both detection AND suppression.
 *
 * Registered as an inbound packet filter (runs on the netty IO thread). When an inspecting,
 * crouching admin starts a Secondary/Use interaction (right-click) with a non-empty held
 * item, two things happen:
 *  1. The originating [SyncInteractionChains] packet is **dropped** — the filter returns
 *     `true`, so `PlayerChannelHandler.channelRead` skips `handler.handle(packet)` and the
 *     vanilla interaction (eat / place / swing / open) never runs server-side.
 *  2. The item's **editor is opened** (deferred to the world thread via `world.execute`).
 *
 * The interaction-chain packet is the single reliable signal for the gesture (verified
 * in-game: `PlayerMouseButtonEvent` does NOT fire for a held-item right-click, so the
 * earlier listener-based opener never triggered). Driving both actions from this one packet
 * means detection and suppression can never get out of step.
 *
 * Crouch is read from [InspectModeManager]'s cache (maintained on the world thread by
 * [CrouchTrackingSystem]) because the IO thread cannot read `MovementStatesComponent`.
 *
 * This filter runs for EVERY inbound packet, so the `is SyncInteractionChains` check is
 * first; the remaining gates only execute for the rare inspecting + crouching admin.
 *
 * Only the chain's `initial` (first-press) entry of type Secondary/Use is matched, so an
 * ongoing/auto-repeat interaction never re-triggers and a held-down click opens the editor
 * exactly once. Left-click (Primary) is intentionally left alone — an admin can still attack
 * while inspect mode is on.
 */
class InspectSuppressor(
    private val manager: InspectModeManager,
    private val vuetale: VuetaleIntegration
) {
    private val logger = HytaleLogger.forEnclosingClass()

    /** The wrapped [PacketFilter] returned by [PacketAdapters.registerInbound] — our deregister handle. */
    private var handle: PacketFilter? = null

    /** Registers the inbound packet filter. */
    fun register() {
        handle = PacketAdapters.registerInbound(PlayerPacketFilter { playerRef, packet ->
            shouldDrop(playerRef, packet)
        })
    }

    /** Deregisters the inbound packet filter. Call on plugin shutdown (it is a static global list). */
    fun unregister() {
        handle?.let {
            try {
                PacketAdapters.deregisterInbound(it)
            } catch (e: Exception) {
                logger.atWarning().withCause(e).log("Inspect: failed to deregister inbound packet filter")
            }
        }
        handle = null
    }

    private fun shouldDrop(playerRef: PlayerRef, packet: Packet): Boolean {
        // Fast path: only interaction-chain packets are candidates for suppression.
        if (packet !is SyncInteractionChains) return false

        val uuid = playerRef.uuid
        if (!manager.isInspecting(uuid)) return false
        if (!manager.isCrouching(uuid)) return false
        if (!PermissionChecker.canEditAnything(uuid)) return false

        for (entry in packet.updates) {
            if (!entry.initial) continue
            val type = entry.interactionType
            if (type != InteractionType.Secondary && type != InteractionType.Use) continue
            val itemId = entry.itemInHandId
            if (itemId == null || itemId == EMPTY_ITEM_ID) continue
            // Matched a deliberate crouch + right-click inspect gesture. Open the editor and
            // drop the packet so the vanilla interaction does not also run.
            openEditorFor(playerRef)
            return true
        }
        return false
    }

    /**
     * Schedules the editor to open for [playerRef]'s held item. Called from the IO thread,
     * but the actual mount is deferred to the world thread (`world.execute`) — mirroring the
     * `/itemforge <id>` command path. The held item is re-read authoritatively on the world
     * thread rather than trusting the packet's client-supplied `itemInHandId`.
     */
    private fun openEditorFor(playerRef: PlayerRef) {
        try {
            if (!vuetale.initialized) return
            val ref = playerRef.reference ?: return
            if (!ref.isValid) return
            val store = ref.store
            val world = store.externalData.world
            world.execute {
                try {
                    if (!ref.isValid) return@execute
                    val held = InventoryComponent.getItemInHand(store, ref) ?: return@execute
                    if (held.isEmpty) return@execute
                    // Inspect is the ONLY path with a concrete held ItemStack, so it's
                    // where ALL per-instance editing is established. Read the held stack HERE on the
                    // world thread (the only safe place) into a StackEditContext that captures both
                    // (A) any mod metadata namespaces (e.g. "SocketReforge") as STACK: sources, and
                    // (B) the engine-native per-instance scalars (quantity/durability/maxDurability)
                    // that power "This Item" free-tier base editing. The context is
                    // ALWAYS non-null in inspect — even a plain item with no metadata gets one, so
                    // the editor can offer the per-item ("This Item") scope. Any EditorExtension
                    // panels are still attached server-side by item id; this path needs no extension
                    // awareness — the editor is otherwise identical to the dashboard one.
                    val stackContext = me.itemforge.provider.MetadataStackReader.read(held.itemId, held)
                    vuetale.openEditor(playerRef, ref, store, held.itemId, stackContext)
                } catch (e: Exception) {
                    logger.atWarning().withCause(e).log("Inspect: failed to open editor")
                }
            }
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("Inspect: failed to schedule editor open")
        }
    }

    private companion object {
        /** ItemStack's empty-stack sentinel id (ItemStack.isEmpty() checks `itemId == "Empty"`). */
        const val EMPTY_ITEM_ID = "Empty"
    }
}

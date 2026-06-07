package me.itemforge.inspect

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.tick.EntityTickingSystem
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore

/**
 * Per-tick system that maintains [InspectModeManager]'s crouch cache for inspecting players.
 *
 * Runs on the world thread as part of the entity-store tick. Its only purpose is to feed
 * crouch state to the IO-thread [InspectSuppressor], which cannot read
 * `MovementStatesComponent` itself (component access is world-thread-confined).
 *
 * The query restricts ticking to entities that have BOTH `MovementStatesComponent` and
 * `PlayerRef` — i.e. players (NPCs have movement states but no `PlayerRef`). Only
 * inspecting players' crouch state is written; everyone else is skipped with a cheap
 * concurrent-set lookup, and the whole tick short-circuits when nobody is inspecting.
 *
 * Pattern verified against `RandomTickSystem` / `PlayerCameraAddSystem`
 * (`ComponentType` implements `Query`, so it can be passed directly to `Query.and`).
 *
 * Registered in `ItemForgePlugin.setup()` via `entityStoreRegistry.registerSystem(...)`.
 */
class CrouchTrackingSystem(
    private val manager: InspectModeManager
) : EntityTickingSystem<EntityStore>() {

    private val movementType = MovementStatesComponent.getComponentType()
    private val playerRefType = PlayerRef.getComponentType()
    private val query: Query<EntityStore> = Query.and(movementType, playerRefType)

    override fun getQuery(): Query<EntityStore> = query

    override fun tick(
        dt: Float,
        index: Int,
        chunk: ArchetypeChunk<EntityStore>,
        store: Store<EntityStore>,
        commandBuffer: CommandBuffer<EntityStore>
    ) {
        // Fast path: nobody is inspecting → nothing to cache this tick.
        if (manager.hasNoInspectors()) return

        val playerRef = chunk.getComponent(index, playerRefType) ?: return
        val uuid = playerRef.uuid
        if (!manager.isInspecting(uuid)) return

        val movement = chunk.getComponent(index, movementType) ?: return
        manager.setCrouching(uuid, movement.movementStates.crouching)
    }
}

package me.itemforge.vuetale.editor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata
import me.itemforge.ItemForgePlugin
import me.itemforge.util.PermissionChecker
import me.itemforge.vuetale.SaveResponse
import java.util.UUID

/**
 * Owns the "stack vs base" save seam — writes that target the single
 * [com.hypixel.hytale.server.core.inventory.ItemStack] in the player's hand (Inspect Mode),
 * NOT the Item asset. Two entry points: per-stack metadata edits ([executeStackSave]) and per-item
 * base-stat edits ([executeLocalBaseSave]).
 *
 * ## Threading
 *
 * Validate + resolve run on the V8 thread (the bridge call); ALL heavy work is deferred to
 * `world.execute` and the method returns immediately so the V8 thread is never held (Bug #8). This
 * path never touches the asset pipeline (no loadAssets, no shared async executor) —
 * `setItemStackForSlot` auto-syncs the inventory to the client. The held-item helpers
 * ([resolvePlayerWorld]/[writeBackHeldItem]/[foldItemDisplay]/[foldForgeSub]) move with their two
 * callers so the copy-on-write read-modify-write stays a single, consistent operation.
 */
class HeldStackSaveHandler(
    private val plugin: ItemForgePlugin,
    private val runtime: EditorRuntime
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    /**
     * Folds Custom Name / Lore field edits in [fields] into [stack]'s `ItemDisplay` metadata — the
     * engine's per-instance display override (a literal [Message] each), which
     * `ItemStack.getDisplayName`/`getDisplayDescription` read. Preserves whichever of name/lore isn't
     * being edited; a blank value clears that part, and clearing both removes the key (stack falls
     * back to the item type's name). Returns [stack] unchanged when no identity field is present.
     *
     * Shared by BOTH held-stack write paths ([executeLocalBaseSave] and [executeStackSave]) so a
     * name/lore edit is applied inside their single copy-on-write read-modify-write — never written
     * as a raw metadata key, and never clobbered by a second write to the same stack.
     */
    private fun foldItemDisplay(
        stack: com.hypixel.hytale.server.core.inventory.ItemStack,
        fields: Map<String, Any?>
    ): com.hypixel.hytale.server.core.inventory.ItemStack {
        val hasName = fields.containsKey(me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_NAME)
        val hasLore = fields.containsKey(me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_LORE)
        if (!hasName && !hasLore) return stack
        val existing = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC)
        var dispName = existing?.name
        var dispDesc = existing?.description
        if (hasName) {
            val t = (fields[me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_NAME] as? String)?.trim()
            dispName = if (t.isNullOrEmpty()) null else Message.raw(t)
        }
        if (hasLore) {
            val t = (fields[me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_LORE] as? String)?.trim()
            dispDesc = if (t.isNullOrEmpty()) null else Message.raw(t)
        }
        val meta = if (dispName == null && dispDesc == null) null else ItemDisplayMetadata(dispName, dispDesc)
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, meta)
    }

    /**
     * Saves per-stack metadata edits to the player's HELD item (Inspect Mode).
     * Unlike asset edits (which mutate the Item type and broadcast to all
     * players via loadAssets), this writes back to the single
     * [com.hypixel.hytale.server.core.inventory.ItemStack] in the player's hand.
     *
     * Flow (all heavy work on the world thread, returns immediately to avoid holding V8 — Bug #8):
     *  1. Permission re-check (editing instance stats = a stat edit).
     *  2. Resolve the player's world/ref/store.
     *  3. world.execute: re-read the live held stack, fold each changed key into a new metadata
     *     document (copy-on-write via withMetadata), write the result back to the held slot.
     *
     * [source] is `"STACK:<namespace>"`; only that namespace's keys are written. The session's
     * cached fields tell us each key's value type for faithful BSON encoding.
     */
    fun executeStackSave(
        playerId: String,
        itemId: String,
        source: String,
        parsed: Map<String, Any>
    ): String {
        // Values may be null (Gson maps JSON null → null) — a null change clears/removes that key.
        @Suppress("UNCHECKED_CAST")
        val fields = (parsed["fields"] as? Map<String, Any?>) ?: emptyMap()
        if (fields.isEmpty()) {
            return gson.toJson(SaveResponse(true, null, "No changes to save", emptyList()))
        }

        val uuid = try {
            UUID.fromString(playerId)
        } catch (e: Exception) {
            logger.atWarning().log("EditorBridge: stack save with unparseable playerId '%s' — denying", playerId)
            return gson.toJson(SaveResponse(false, null, "Could not verify your permissions.", emptyList(), permissionDenied = true))
        }
        if (!PermissionChecker.canEditStats(uuid)) {
            return gson.toJson(SaveResponse(false, null, "You don't have permission to edit item stats.", emptyList(), permissionDenied = true))
        }

        // The session holds each key's declared value type (read on the world thread at open),
        // so we encode the new value to the matching BSON type without re-reading the live stack
        // here. Absent session → the editor was reopened/cleared; deny gracefully.
        val session = runtime.stackSessions[uuid]
            ?: return gson.toJson(SaveResponse(false, null, "This item is no longer open for per-item editing.", emptyList()))
        val typeByKey: Map<String, me.itemforge.scanner.ValueType> =
            (session.fields[source] ?: emptyList()).mapNotNull { f ->
                runCatching { me.itemforge.scanner.ValueType.valueOf(f.valueType) }.getOrNull()?.let { f.id to it }
            }.toMap()

        val resolved = resolvePlayerWorld(playerId)
            ?: return gson.toJson(SaveResponse(false, null, "Could not resolve your player to apply the change.", emptyList()))
        val (world, ref, store) = resolved

        // Defer to the world thread — Store.getComponent + container writes are world-thread only,
        // and we must not hold the V8 thread (Bug #8). setItemStackForSlot auto-syncs; no manual
        // flush / loadAssets (this path never touches the asset pipeline).
        world.execute {
            try {
                if (!ref.isValid) return@execute
                val held = com.hypixel.hytale.server.core.inventory.InventoryComponent.getItemInHand(store, ref)
                    ?: return@execute
                if (held.isEmpty) return@execute

                // Fold each changed key into a fresh metadata document. withMetadata clones +
                // returns a NEW ItemStack (copy-on-write); a null value REMOVES the key (matches
                // "clear a field" semantics and the engine's own withMetadata contract).
                var stack = held
                for ((key, raw) in fields) {
                    // Custom Name/Lore are NOT raw per-stack mod keys — they fold into ItemDisplay
                    // below (shared helper). Skip them here so they're never written as literal
                    // "CustomName"/"CustomLore" metadata keys when a STACK: source is the active save.
                    if (key == me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_NAME ||
                        key == me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_LORE) continue
                    val type = typeByKey[key]
                    val bsonValue: org.bson.BsonValue? = if (raw == null) null else {
                        try {
                            if (type != null) me.itemforge.util.BsonHelper.kotlinToBson(raw, type)
                            else org.bson.BsonString(raw.toString())
                        } catch (e: Exception) {
                            logger.atWarning().withCause(e)
                                .log("EditorBridge: stack save could not encode key '%s' — skipping", key)
                            null
                        }
                    }
                    stack = stack.withMetadata(key, bsonValue)
                }

                // Fold any Custom Name/Lore edit into ItemDisplay in this same write (no clobber).
                stack = foldItemDisplay(stack, fields)

                if (!writeBackHeldItem(ref, store, stack)) {
                    logger.atWarning().log("EditorBridge: stack save could not write back the held item for %s", playerId)
                    return@execute
                }

                plugin.auditLogger.logEdit(playerId, "$itemId (instance)", "[$source]", null, fields.keys.joinToString(","))
                logger.atInfo().log("EditorBridge: applied %d per-stack key(s) on '%s' via '%s' for %s",
                    fields.size, itemId, source, playerId)
            } catch (e: Exception) {
                logger.atSevere().withCause(e).log("EditorBridge: stack save failed for source '%s'", source)
            }
        }

        // Return immediately — the per-stack write happens on the world thread.
        return gson.toJson(SaveResponse(true, null, null, emptyList()))
    }

    /**
     * Saves per-item ("This Item") base-stat edits to the player's HELD item
     * (Inspect Mode). Step 1 (free tier) writes the engine-native per-stack fields
     * directly on the [com.hypixel.hytale.server.core.inventory.ItemStack] — quantity, current
     * durability, and max-durability — which the engine already honors per-instance with no runtime
     * applier. Unlike asset edits (which mutate the Item type for all players), this changes ONLY
     * the one stack in the player's hand.
     *
     * Flow mirrors [executeStackSave]: validate + resolve on V8, then do all world-thread work
     * inside `world.execute` (re-read the live stack, fold edits copy-on-write, write back to the
     * held slot) and return immediately so the V8 thread is never held (Bug #8).
     *
     * Stat fields (combat/attribute tiers) are NOT handled here yet — they require their always-
     * running appliers. An out-of-date UI that sends one is ignored defensively (logged), never
     * written as a dead metadata key.
     */
    fun executeLocalBaseSave(
        playerId: String,
        itemId: String,
        parsed: Map<String, Any>
    ): String {
        @Suppress("UNCHECKED_CAST")
        val fields = (parsed["fields"] as? Map<String, Any?>) ?: emptyMap()
        if (fields.isEmpty()) {
            return gson.toJson(SaveResponse(true, null, "No changes to save", emptyList()))
        }

        val uuid = try {
            UUID.fromString(playerId)
        } catch (e: Exception) {
            logger.atWarning().log("EditorBridge: local-base save with unparseable playerId '%s' — denying", playerId)
            return gson.toJson(SaveResponse(false, null, "Could not verify your permissions.", emptyList(), permissionDenied = true))
        }
        // Per-item base editing is an advanced, stat-level capability (matches executeStackSave).
        if (!PermissionChecker.canEditStats(uuid)) {
            return gson.toJson(SaveResponse(false, null, "You don't have permission to edit item stats.", emptyList(), permissionDenied = true))
        }

        // Combat tier: per-cause damage knobs arrive as "LocalDmg.<cause>" = a PERCENTAGE of normal
        // (100 = 1.0×). Convert to a multiplier; ~1.0 means "back to default" → clear that cause.
        // (No asset-base lookup needed — the knob is already a pure scale.)
        val damageMults = HashMap<String, Double?>()
        for ((key, raw) in fields) {
            if (!me.itemforge.local.LocalScopeFields.isLocalDamageField(key)) continue
            val pct = (raw as? Number)?.toDouble() ?: continue
            val mult = (pct / 100.0).coerceAtLeast(0.0)
            damageMults[me.itemforge.local.LocalScopeFields.causeOfLocalDamage(key)] =
                if (kotlin.math.abs(mult - 1.0) < 1e-6) null else mult
        }

        // Combat tier: per-cause defense knobs arrive as "LocalDef.<cause>" = a resistance PERCENT
        // (25 = take 25% less; negative = more vulnerable). Stored as a fraction; 0 → clear that cause.
        val defResists = HashMap<String, Double?>()
        for ((key, raw) in fields) {
            if (!me.itemforge.local.LocalScopeFields.isLocalDefenseField(key)) continue
            val pct = (raw as? Number)?.toDouble() ?: continue
            val frac = pct / 100.0
            defResists[me.itemforge.local.LocalScopeFields.causeOfLocalDefense(key)] =
                if (kotlin.math.abs(frac) < 1e-6) null else frac
        }

        // Attribute tier: per-stat bonus knobs arrive as "LocalStat.<statId>" = a flat amount added
        // to that stat's max while worn. Stored as-is; 0 → clear that stat.
        val statBonuses = HashMap<String, Double?>()
        for ((key, raw) in fields) {
            if (!me.itemforge.local.LocalScopeFields.isLocalStatField(key)) continue
            val amount = (raw as? Number)?.toDouble() ?: continue
            statBonuses[me.itemforge.local.LocalScopeFields.statIdOfLocalStat(key)] =
                if (kotlin.math.abs(amount) < 1e-9) null else amount
        }

        val resolved = resolvePlayerWorld(playerId)
            ?: return gson.toJson(SaveResponse(false, null, "Could not resolve your player to apply the change.", emptyList()))
        val (world, ref, store) = resolved

        world.execute {
            try {
                if (!ref.isValid) return@execute
                val held = com.hypixel.hytale.server.core.inventory.InventoryComponent.getItemInHand(store, ref)
                    ?: return@execute
                if (held.isEmpty) return@execute
                // Guard against a hand-swap between open and save: only write if the held item is
                // still the one the editor was opened for, so per-item edits can't land on a
                // different item the admin happens to be holding now.
                if (held.itemId != itemId) {
                    logger.atWarning().log(
                        "EditorBridge: local-base save skipped — held item '%s' no longer matches edited '%s'",
                        held.itemId, itemId
                    )
                    return@execute
                }

                // Fold each free-tier edit into a fresh ItemStack (copy-on-write). Each with* call
                // returns a NEW stack; the engine clamps durability to [0, max] and quantity > 0.
                // Apply in a FIXED order — MaxDurability first — so that a same-save Durability edit
                // clamps against the NEW max (map iteration order is not guaranteed).
                var stack = held
                var applied = 0
                (fields[me.itemforge.local.LocalScopeFields.FIELD_MAX_DURABILITY] as? Number)?.let {
                    stack = stack.withMaxDurability(it.toDouble().coerceAtLeast(0.0)); applied++
                }
                (fields[me.itemforge.local.LocalScopeFields.FIELD_DURABILITY] as? Number)?.let {
                    stack = stack.withDurability(it.toDouble()); applied++   // engine clamps to [0, max]
                }
                (fields[me.itemforge.local.LocalScopeFields.FIELD_QUANTITY] as? Number)?.let {
                    // withQuantity(0) returns null (would delete the slot) — floor at 1.
                    val q = it.toInt().coerceAtLeast(1)
                    stack = stack.withQuantity(q) ?: stack; applied++
                }

                // Combat tier: fold the derived per-cause damage multipliers (dmg) AND armor
                // resistances (def) into the stack's ItemForge metadata doc. clone() before mutating
                // — the decoded sub-docs are shared with the (immutable) source stack.
                if (damageMults.isNotEmpty() || defResists.isNotEmpty() || statBonuses.isNotEmpty()) {
                    val forge = (stack.getFromMetadataOrNull(
                        me.itemforge.local.LocalScopeFields.METADATA_KEY,
                        com.hypixel.hytale.codec.Codec.BSON_DOCUMENT
                    )?.clone() ?: org.bson.BsonDocument())
                    foldForgeSub(forge, me.itemforge.local.LocalScopeFields.DMG_SUBKEY, damageMults)
                    foldForgeSub(forge, me.itemforge.local.LocalScopeFields.DEF_SUBKEY, defResists)
                    foldForgeSub(forge, me.itemforge.local.LocalScopeFields.STAT_SUBKEY, statBonuses)
                    // Empty ItemForge doc → remove the key entirely (null clears it).
                    val newForge: org.bson.BsonValue? = if (forge.isEmpty()) null else forge
                    stack = stack.withMetadata(me.itemforge.local.LocalScopeFields.METADATA_KEY, newForge)
                    applied += damageMults.size + defResists.size + statBonuses.size
                }

                // Custom Name / Lore ("This item" scope) — fold into the held stack's ItemDisplay
                // metadata in this same copy-on-write pass (shared helper, also used by
                // executeStackSave so the logic and the single read-modify-write stay consistent).
                val withDisplay = foldItemDisplay(stack, fields)
                if (withDisplay !== stack) { stack = withDisplay; applied++ }

                // Defensive: warn on any field an out-of-date UI sent that has no per-item applier yet
                // (later-tier stats) — never written as a dead key.
                for (key in fields.keys) {
                    if (key != me.itemforge.local.LocalScopeFields.FIELD_MAX_DURABILITY &&
                        key != me.itemforge.local.LocalScopeFields.FIELD_DURABILITY &&
                        key != me.itemforge.local.LocalScopeFields.FIELD_QUANTITY &&
                        key != me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_NAME &&
                        key != me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_LORE &&
                        !me.itemforge.local.LocalScopeFields.isLocalDamageField(key) &&
                        !me.itemforge.local.LocalScopeFields.isLocalDefenseField(key) &&
                        !me.itemforge.local.LocalScopeFields.isLocalStatField(key)
                    ) {
                        logger.atWarning().log(
                            "EditorBridge: local-base save ignoring field '%s' (no per-item applier yet)", key
                        )
                    }
                }

                if (applied == 0) return@execute
                if (!writeBackHeldItem(ref, store, stack)) {
                    logger.atWarning().log("EditorBridge: local-base save could not write back the held item for %s", playerId)
                    return@execute
                }

                plugin.auditLogger.logEdit(playerId, "$itemId (this item)", "[local]", null, fields.keys.joinToString(","))
                logger.atInfo().log("EditorBridge: applied %d per-item base field(s) on '%s' for %s",
                    applied, itemId, playerId)
            } catch (e: Exception) {
                logger.atSevere().withCause(e).log("EditorBridge: local-base save failed for '%s'", itemId)
            }
        }

        // Return immediately — the per-item write happens on the world thread.
        return gson.toJson(SaveResponse(true, null, null, emptyList()))
    }

    /**
     * Folds per-cause [values] (null = remove that cause) into [forge]'s [subKey] sub-document
     * (e.g. `dmg` or `def`), removing the sub-document if it ends up empty. Clones the existing
     * sub-doc before mutating (it is shared with the immutable source stack). Combat tier.
     */
    private fun foldForgeSub(forge: org.bson.BsonDocument, subKey: String, values: Map<String, Double?>) {
        if (values.isEmpty()) return
        val sub = (forge.get(subKey)?.takeIf { it.isDocument }?.asDocument()?.clone() ?: org.bson.BsonDocument())
        for ((cause, v) in values) {
            if (v == null) sub.remove(cause) else sub[cause] = org.bson.BsonDouble(v)
        }
        if (sub.isEmpty()) forge.remove(subKey) else forge[subKey] = sub
    }

    /**
     * Resolves a player-id string to (world, entity ref, store) via the player's Vuetale UI
     * handle — the same reflection path closePage uses. Returns null if the player can't be
     * resolved or has disconnected.
     */
    private fun resolvePlayerWorld(
        playerId: String
    ): Triple<
        com.hypixel.hytale.server.core.universe.world.World,
        com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>
    >? {
        return try {
            val uuid = UUID.fromString(playerId)
            val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid) ?: return null
            val playerRef = ui.javaClass.getMethod("getPlayerRef\$Vuetale").invoke(ui)
                as? com.hypixel.hytale.server.core.universe.PlayerRef ?: return null
            val ref = playerRef.reference ?: return null
            if (!ref.isValid) return null
            val store = ref.store
            Triple(store.externalData.world, ref, store)
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("EditorBridge: could not resolve world for player %s", playerId)
            null
        }
    }

    /**
     * Writes [stack] back into the player's active held slot — the Tool slot when a tool is in
     * use, otherwise the active Hotbar slot (mirrors `InventoryComponent.getItemInHand`).
     * `ItemContainer.setItemStackForSlot` auto-sends the inventory update to the client.
     * World-thread only.
     *
     * @return true if the stack was written, false if no valid active slot was found.
     */
    private fun writeBackHeldItem(
        ref: com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        store: com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>,
        stack: com.hypixel.hytale.server.core.inventory.ItemStack
    ): Boolean {
        val tool = store.getComponent(ref, com.hypixel.hytale.server.core.inventory.InventoryComponent.Tool.getComponentType())
        val container: com.hypixel.hytale.server.core.inventory.container.ItemContainer
        val slot: Byte
        if (tool != null && tool.isUsingToolsItem) {
            container = tool.inventory ?: return false
            slot = tool.activeSlot
        } else {
            val hotbar = store.getComponent(ref, com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar.getComponentType())
                ?: return false
            container = hotbar.inventory ?: return false
            slot = hotbar.activeSlot
        }
        if (slot < 0) return false
        container.setItemStackForSlot(slot.toShort(), stack)
        return true
    }
}

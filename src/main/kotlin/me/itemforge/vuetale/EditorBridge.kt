package me.itemforge.vuetale

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo
import com.hypixel.hytale.server.core.ui.LocalizableString
import me.itemforge.ItemForgePlugin
import me.itemforge.scanner.CodecScanner
import me.itemforge.scanner.FieldDefinition
import me.itemforge.util.PermissionChecker
import me.itemforge.vuetale.editor.BsonOriginalsLookup
import me.itemforge.vuetale.editor.ChangeValidator
import me.itemforge.vuetale.editor.EditorPayloadAssembler
import me.itemforge.vuetale.editor.EditorRuntime
import me.itemforge.vuetale.editor.HeldStackSaveHandler
import me.itemforge.vuetale.editor.OverrideBsonBuilder
import me.itemforge.vuetale.editor.RecipeSaveHandler
import java.util.UUID

/**
 * Kotlin/JS bridge for the ItemForge editor.
 *
 * Exposed as `globalThis.itemForgeBridge` in the V8 runtime via
 * [VuetaleIntegration]. All public methods are callable from JavaScript.
 *
 * ## Threading
 *
 * Bridge methods run on the V8 thread (NOT the game thread). This is safe for
 * ItemForge because:
 * - `ExtraInfo.THREAD_LOCAL` initializes per-thread with defaults (version=MAX_VALUE)
 * - `Item.getAssetMap()` is a ConcurrentHashMap — thread-safe reads
 * - `BuilderCodec.encode()` reads without mutation — thread-safe
 * - `loadAssets()` handles thread safety internally
 * - `configManager.itemOverrides` uses `synchronized(lock)`
 * - `auditLogger` uses `synchronized(lock)`
 * - `closePage()` dispatches to world thread (PageManager is not thread-safe)
 *
 * Evidence: TrailOfOrbis `LootFilterBridge.java` modifies game state directly
 * from V8 thread using the same pattern.
 *
 * ## Bridge Method Contract
 *
 * - Methods return JSON strings — Vue parses the return value
 * - Cannot call `setData()` from bridge (deadlock: setData dispatches to V8)
 * - Game-thread operations dispatched via `world.execute()`
 *
 * ARCHITECTURE.md §7.2, TrailOfOrbis `LootFilterBridge.java:180-549`
 *
 * @param plugin The ItemForge plugin instance (for subsystem access)
 */
class EditorBridge(
    private val plugin: ItemForgePlugin
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    /**
     * Shared editor runtime: the single [java.util.concurrent.ExecutorService]s and per-player
     * caches that this facade and its handlers share by identity. See [EditorRuntime] for the
     * full lifecycle contract of `fieldCache` / `stackSessions` / `pageGeneration` and why the
     * executors must be singletons.
     */
    private val runtime = EditorRuntime()

    /** Recipe save/create seam — pure prepare + async commit (shares [runtime]'s executor). */
    private val recipeHandler = RecipeSaveHandler(plugin, runtime)

    /** Per-stack / per-item "This Item" save seam — world-thread held-item writes (Inspect Mode). */
    private val heldStackHandler = HeldStackSaveHandler(plugin, runtime)

    /** Editor read path — scans the item and assembles the [EditorPayload]. */
    private val assembler = EditorPayloadAssembler(plugin, runtime)

    /**
     * Single daemon thread for debounced dropdown-entry pushes — mirrors
     * [me.itemforge.vuetale.DashboardBridge]'s `gridExecutor`. The actual build + sendUpdate run here,
     * off the V8/world thread, so a picker push never holds the world thread hostage during a packet
     * send (Bug #8). Never blocks JVM shutdown.
     */
    private val pickerExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "itemforge-picker").apply { isDaemon = true }
    }

    /** Per-`"playerId|customId"` pending picker push — cancel-and-reschedule coalesces rapid re-pushes
     *  WITHOUT letting one picker's push cancel another's (distinct customIds = distinct keys). */
    private val pendingPickerPush =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    /**
     * Sets (or clears, when [context] is null) the per-stack edit session for a player.
     * Called by [VuetaleIntegration.openEditor] BEFORE it builds the payload, so the payload
     * assembler sees the session. Inspect passes a context; command/dashboard pass null, which
     * clears any stale session and guarantees plain asset-mode editing.
     */
    fun setStackEdit(playerId: UUID, context: me.itemforge.provider.StackEditContext?) {
        if (context == null) runtime.stackSessions.remove(playerId) else runtime.stackSessions[playerId] = context
    }

    // ── Payload Builder ─────────────────────────────────────────────────

    /**
     * Builds the editor payload JSON for [itemId] — the public bridge entry point (exposed to JS,
     * and called by [VuetaleIntegration]/[DashboardBridge]). Delegates to [EditorPayloadAssembler];
     * see there for the full assembly flow and threading contract.
     */
    fun buildEditorPayload(itemId: String, playerUuid: UUID): String =
        assembler.buildEditorPayload(itemId, playerUuid)

    /**
     * Builds the editor payload OFF the world/V8 thread and invokes [onReady] with the JSON on the
     * worker thread (so the page mounts in one render). Delegates to [EditorPayloadAssembler], which
     * reuses the shared [EditorRuntime.asyncExecutor].
     */
    fun buildEditorPayloadAsync(itemId: String, playerUuid: UUID, onReady: (String) -> Unit) =
        assembler.buildEditorPayloadAsync(itemId, playerUuid, onReady)

    // ── Bridge Methods (callable from JS) ───────────────────────────────

    /**
     * Saves pending changes for an item — the public bridge entry point.
     *
     * Wraps [executeSave] with timing + top-level error handling so a thrown
     * exception always returns a well-formed [SaveResponse] to the UI rather than
     * surfacing as a raw bridge failure.
     *
     * [executeSave] routes by the payload's `source`/`scope` (per-stack, per-item
     * base, mod extension, or the default Item-asset path) and applies these gates
     * before persisting anything:
     * - Gate 0: Permission re-check ([checkSavePermission]) — defense-in-depth, in
     *   case the player's permission was revoked while the editor was open.
     * - Gate 1: Recipe validation (pure — rejects before any field is persisted).
     * - Gate 2: Per-field type + constraint validation.
     * - Execute: build override BSON → persist (debounced) → apply + sync off-thread → audit.
     *
     * @param playerId The player's UUID as string
     * @param itemId The item being edited
     * @param changesJson JSON `{ fields: {...}, recipe: {...}, source?, scope? }` (or a legacy flat map)
     * @return JSON string — [SaveResponse] (success, or error / validation details)
     */
    fun save(playerId: String, itemId: String, changesJson: String): String {
        val t0 = System.nanoTime()
        plugin.v8Watchdog.recordBridgeCall("save", itemId, changesJson.take(100))
        return try {
            val result = executeSave(playerId, itemId, changesJson)
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            logger.atFine().log("[TIMING] save bridge: %dms", elapsed)
            result
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("EditorBridge.save() failed for '%s'", itemId)
            gson.toJson(SaveResponse(
                success = false,
                payload = null,
                error = "Save failed: ${e.message}",
                validationErrors = emptyList()
            ))
        }
    }

    /**
     * Permission gate for [executeSave]. Returns a human-readable
     * denial message if the player lacks permission for ANY section of the
     * save, or null if every section is authorized.
     *
     * Runs synchronously on the V8 thread before any persistence, so an
     * unauthorized section can never produce a partial save. Fields are
     * classified by [FieldDefinition.category]: [CodecScanner.CATEGORY_GENERAL]
     * → General tab (canEditGeneral); everything else → stat tabs (canEditStats).
     * An unknown category fails toward the broader stat permission.
     */
    private fun checkSavePermission(
        playerId: String,
        changesMap: Map<String, Any>,
        fieldMap: Map<String, FieldDefinition>,
        hasRecipeChanges: Boolean
    ): String? {
        val uuid = try {
            UUID.fromString(playerId)
        } catch (e: Exception) {
            // Fail-closed: a malformed player id cannot be authorized.
            logger.atWarning().log("EditorBridge: save with unparseable playerId '%s' — denying", playerId)
            return "Could not verify your permissions."
        }

        var touchesGeneral = false
        var touchesStats = false
        for (fieldId in changesMap.keys) {
            if (fieldMap[fieldId]?.category == CodecScanner.CATEGORY_GENERAL) touchesGeneral = true
            else touchesStats = true
        }

        if (touchesGeneral && !PermissionChecker.canEditGeneral(uuid))
            return "You don't have permission to edit general properties."
        if (touchesStats && !PermissionChecker.canEditStats(uuid))
            return "You don't have permission to edit item stats."
        if (hasRecipeChanges && !PermissionChecker.canEditRecipes(uuid))
            return "You don't have permission to edit recipes."
        return null
    }

    /**
     * Resolves a player-id string to a [PermissionChecker.canReset] verdict.
     * Fail-closed on an unparseable id. Used by reset() and resetField().
     */
    private fun canResetByPlayerId(playerId: String): Boolean {
        val uuid = try {
            UUID.fromString(playerId)
        } catch (e: Exception) {
            logger.atWarning().log("EditorBridge: reset with unparseable playerId '%s' — denying", playerId)
            return false
        }
        return PermissionChecker.canReset(uuid)
    }

    /**
     * Applies any GLOBAL Custom Name / Lore changes present in [parsed]'s `"fields"` via the i18n
     * broadcast engine, then STRIPS those keys from `parsed["fields"]` in place so every downstream
     * save path (stack / extension / asset) only sees real Item fields. Custom Name/Lore is
     * item-level and independent of the selected stat source, so handling it here — before source
     * routing — keeps it correct no matter which source is active. Returns a denial [SaveResponse]
     * JSON when the player lacks General permission, else null (applied, or nothing to do).
     *
     * Only the GLOBAL case routes here; a LOCAL save ([scope] == "local") keeps the identity keys in
     * the map and [executeLocalBaseSave] writes them to the held stack's `ItemDisplay` metadata.
     */
    private fun stripAndApplyGlobalIdentity(playerId: String, itemId: String, parsed: MutableMap<String, Any>): String? {
        @Suppress("UNCHECKED_CAST")
        val fields = parsed["fields"] as? Map<String, Any> ?: return null // identity only travels in new-format "fields"
        val nameId = me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_NAME
        val loreId = me.itemforge.local.LocalScopeFields.FIELD_CUSTOM_LORE
        val hasName = fields.containsKey(nameId)
        val hasLore = fields.containsKey(loreId)
        if (!hasName && !hasLore) return null

        val uuid = runCatching { UUID.fromString(playerId) }.getOrNull()
        if (uuid == null || !PermissionChecker.canEditGeneral(uuid)) {
            return gson.toJson(SaveResponse(false, null,
                "You don't have permission to edit general properties.", emptyList(), permissionDenied = true))
        }
        val item = Item.getAssetMap().getAsset(itemId)
        if (item != null) {
            // A null/blank value reverts that override (the engine restores the stock text).
            if (hasName) plugin.translationOverrides.setName(itemId, item, fields[nameId]?.toString())
            if (hasLore) plugin.translationOverrides.setLore(itemId, item, fields[loreId]?.toString())
            plugin.auditLogger.logEdit(playerId, itemId, "[name/lore]", null, "global")
        }
        parsed["fields"] = fields.filterKeys { it != nameId && it != loreId }
        return null
    }

    /**
     * Core save execution — separated from [save] for clean error handling.
     *
     * ## Threading: V8 deadlock prevention
     *
     * This method runs on the V8 thread (bridge call). loadAssets() broadcasts
     * packets, which acquires player/connection locks. If the game thread is
     * also holding those locks (e.g. processing the same UI event), calling
     * loadAssets() from V8 causes a deadlock — the exact scenario Vuetale's
     * VuetaleUIPage.kt:130-134 warns about for sendUpdate().
     *
     * Fix: validation + BSON building run synchronously on V8 (fast, no locks).
     * The actual apply + commit is dispatched to ForkJoinPool. When it completes,
     * the updated payload is pushed via setData (which safely enqueues to V8).
     */
    private fun executeSave(playerId: String, itemId: String, changesJson: String): String {
        val configManager = plugin.configManager

        // Parse the changes. New format: { "fields": {...}, "recipe": {...} }
        // Legacy format: flat { "fieldId": value, ... } (treated as fields-only)
        val parsed = parseChangesJson(changesJson).toMutableMap()

        val source = parsed["source"] as? String
        val scope = parsed["scope"] as? String

        // Custom Name/Lore (header, item-level) is independent of the selected stat source. For any
        // GLOBAL save, apply it via the i18n engine and strip it from the field map up-front so every
        // source-specific path below sees only real fields. LOCAL saves keep it (executeLocalBaseSave
        // writes the held stack's ItemDisplay). Runs before all routing so source choice never matters.
        if (scope != SCOPE_LOCAL) {
            stripAndApplyGlobalIdentity(playerId, itemId, parsed)?.let { return it }
        }

        // Per-stack save: a "STACK:<namespace>" source edits the HELD item's metadata,
        // not the Item asset. Route to the held-item write path — it re-reads the live stack on
        // the world thread and writes the namespace's keys back via setItemStackForSlot, never
        // touching the override store / loadAssets. Checked BEFORE the extension branch since both
        // are non-BASE. Base/MOD: saves never set a STACK: source, so this is a no-op for them.
        if (source != null && source.startsWith(me.itemforge.provider.StackEditContext.STACK_PREFIX)) {
            return heldStackHandler.executeStackSave(playerId, itemId, source, parsed)
        }

        // Per-item base save: BASE source + Local scope edits the HELD
        // item's engine-native per-stack fields (quantity / durability / max-durability), NOT the
        // Item asset. `scope == "local"` is set by the UI only when the "This Item" toggle is
        // active; it is absent for normal global asset saves, so this never fires for them. Checked
        // before the extension branch (a BASE+local save has source == "BASE" or null).
        if (scope == SCOPE_LOCAL && (source == null || source == "BASE")) {
            return heldStackHandler.executeLocalBaseSave(playerId, itemId, parsed)
        }

        // Extension save: the UI tags the payload with a non-BASE `source` (an
        // extension id) when editing a mod-built panel. Route to the extension path — the mod
        // applies the changes in its own storage (global per item id), never via the Item
        // asset/override store. Base-editor saves never set `source`, so this is a no-op.
        if (source != null && source != "BASE") {
            return executeExtensionSave(playerId, itemId, source, parsed)
        }

        // Global Custom Name/Lore was already applied + stripped from parsed["fields"] at the top of
        // executeSave (stripAndApplyGlobalIdentity), so this path only ever sees real Item fields.
        val changesMap: Map<String, Any>
        val recipeChanges: Map<String, Any>?

        if (parsed.containsKey("fields") || parsed.containsKey("recipe")) {
            @Suppress("UNCHECKED_CAST")
            changesMap = (parsed["fields"] as? Map<String, Any>) ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            recipeChanges = parsed["recipe"] as? Map<String, Any>
        } else {
            changesMap = parsed
            recipeChanges = null
        }

        if (changesMap.isEmpty() && recipeChanges == null) {
            return gson.toJson(SaveResponse(true, null, "No changes to save", emptyList()))
        }

        // Verify item exists
        val item = Item.getAssetMap().getAsset(itemId)
            ?: return gson.toJson(SaveResponse(false, null, "Item '$itemId' not found", emptyList()))

        // Resolve field definitions once — needed both for the permission gate
        // (to classify changes as General vs stats) and for validation/BSON below.
        // Cache hit is the norm (populated on editor open via the game thread);
        // fresh scan only on cache miss (edge case: save without editor open).
        val fieldMap: Map<String, FieldDefinition> = if (changesMap.isEmpty()) emptyMap() else
            runtime.fieldCache[itemId] ?: run {
                logger.atInfo().log("EditorBridge: field cache miss for '%s', performing fresh scan", itemId)
                plugin.codecScanner.scan(item).associateBy { it.id }
            }

        // ── Gate 0: Permission re-check ───────────────────────
        // Defense-in-depth — the UI already disables controls the player can't
        // use, but permission can be revoked WHILE the editor is open. Check
        // BOTH sections up-front so an unauthorized section never causes a
        // partial save. A field is "general" if its category is "General"
        // (matches the UI's tab split); everything else is a stat field.
        val permissionDenial = checkSavePermission(playerId, changesMap, fieldMap, recipeChanges != null)
        if (permissionDenial != null) {
            logger.atWarning().log("EditorBridge: save denied for '%s' — %s", itemId, permissionDenial)
            return gson.toJson(SaveResponse(false, null, permissionDenial, emptyList(), permissionDenied = true))
        }

        // ── Gate 1: Validate the recipe BEFORE persisting ANYTHING ────────
        // prepareRecipeSave is PURE (builds + validates the recipe BSON, no side effects).
        // Validating here — ahead of the field block — keeps the save atomic: if the recipe
        // is invalid (e.g. a created recipe with no inputs yet), we return the error before
        // any field override is persisted, and the recipe override is committed only after
        // field validation passes too.
        val recipePrep = if (recipeChanges != null) recipeHandler.prepare(itemId, item, recipeChanges) else null
        if (recipePrep?.error != null) {
            logger.atWarning().log("EditorBridge: recipe save rejected for '%s' — %s", itemId, recipePrep.error)
            return gson.toJson(SaveResponse(false, null, recipePrep.error, emptyList()))
        }

        // ── Item field changes ────────────────────────────────────────────
        if (changesMap.isNotEmpty()) {
            // Gate 2: Validate each field change
            val validationErrors = ChangeValidator.validate(fieldMap, changesMap)
            if (validationErrors.isNotEmpty()) {
                val errorSummary = validationErrors.joinToString("; ") { "${it.fieldId}: ${it.message}" }
                logger.atWarning().log("EditorBridge: validation failed for '%s': %s", itemId, errorSummary)
                return gson.toJson(SaveResponse(false, null, "Validation failed: $errorSummary", validationErrors))
            }

            // Build override BSON from changes (reuses fieldMap from scan above)
            val overrideBson = OverrideBsonBuilder.build(itemId, fieldMap, changesMap, plugin.codecScanner)

            // Persist to config immediately (debounced write, no locks)
            val overrideJsonObject = com.google.gson.JsonParser.parseString(
                overrideBson.toJson()
            ).asJsonObject
            configManager.itemOverrides.saveOverride(itemId, overrideJsonObject)

            // A direct edit to this item invalidates any pending batch-undo that includes
            // it — otherwise undoing the batch would clobber this manual edit (red-team R5).
            plugin.dashboardBridge.invalidateUndoBufferForItem(itemId)

            // Dispatch the heavy work (apply + loadAssets + sync) OFF the V8 thread.
            java.util.concurrent.CompletableFuture.runAsync({
                val asyncStart = System.nanoTime()
                try {
                    val overrideEngine = plugin.overrideEngine
                    val auditLogger = plugin.auditLogger

                    val success = overrideEngine.applyAndSync(itemId, overrideBson)
                    if (!success) {
                        logger.atWarning().log("EditorBridge: async applyAndSync failed for '%s'", itemId)
                        return@runAsync
                    }

                    // Update dashboard cache so navigate-back shows fresh data
                    plugin.dashboardBridge.updateCachedItem(itemId)

                    for ((fieldId, newValue) in changesMap) {
                        val originalValue = BsonOriginalsLookup.byId(plugin.originalsCache.get(itemId), fieldId)
                        auditLogger.logEdit(playerId, itemId, fieldId, originalValue, newValue)
                    }

                    val asyncElapsed = (System.nanoTime() - asyncStart) / 1_000_000
                    logger.atFine().log("[TIMING] async applyAndSync: %dms for '%s' (%d fields)", asyncElapsed, itemId, changesMap.size)
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("EditorBridge: async save failed for '%s'", itemId)
                }
            }, runtime.asyncExecutor).thenRunAsync({
                refreshHeldItems(playerId, itemId)
            }, runtime.asyncExecutor)
        }

        // ── Recipe changes ───────────────────────────────────────────────
        // Already validated above (recipePrep). Persist + dispatch the async apply now,
        // after field validation has passed, so the two sections commit together.
        if (recipePrep != null) {
            recipeHandler.commit(playerId, itemId, recipePrep)
        }

        // Return immediately — apply happens async
        return gson.toJson(SaveResponse(
            success = true,
            payload = null,
            error = null,
            validationErrors = emptyList()
        ))
    }

    /**
     * Saves a mod [EditorExtension]'s panel edits for [itemId].
     *
     * The extension applies the changes in its OWN storage, **globally per item id** — nothing
     * touches the Item asset, override store, or loadAssets. Returns immediately; the apply
     * runs on a background worker (the extension owns persistence + any live re-sync).
     *
     * @param extensionId The selected source (a registered extension id).
     * @param parsed The full parsed payload; field changes live under `"fields"`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun executeExtensionSave(
        playerId: String,
        itemId: String,
        extensionId: String,
        parsed: Map<String, Any>
    ): String {
        val fields = (parsed["fields"] as? Map<String, Any>) ?: emptyMap()
        if (fields.isEmpty()) {
            return gson.toJson(SaveResponse(true, null, "No changes to save", emptyList()))
        }

        // Permission: editing extension-managed values is treated as a stat edit.
        val uuid = try {
            UUID.fromString(playerId)
        } catch (e: Exception) {
            logger.atWarning().log("EditorBridge: extension save with unparseable playerId '%s' — denying", playerId)
            return gson.toJson(SaveResponse(false, null, "Could not verify your permissions.", emptyList(), permissionDenied = true))
        }
        if (!PermissionChecker.canEditStats(uuid)) {
            return gson.toJson(SaveResponse(false, null, "You don't have permission to edit this item.", emptyList(), permissionDenied = true))
        }

        val extension = plugin.extensionRegistry.byId(extensionId)
            ?: return gson.toJson(SaveResponse(false, null, "Source '$extensionId' is no longer available.", emptyList()))
        val item = Item.getAssetMap().getAsset(itemId)
            ?: return gson.toJson(SaveResponse(false, null, "Item '$itemId' not found", emptyList()))

        // Coerce each value to the field's declared input type, using the extension's current
        // panel (rebuilt once) as the source of truth for types. Unknown fields pass through.
        val typeById: Map<String, String?> = (try {
            extension.buildPanel(item)
        } catch (e: Exception) {
            emptyList()
        }).filter { it.kind == me.itemforge.provider.EditorComponent.KIND_FIELD && it.id != null }
            .associate { it.id!! to it.inputType }
        val coerced: Map<String, Any?> = fields.mapValues { (id, raw) -> coerceComponentValue(typeById[id], raw) }

        // Apply on a background worker (off the V8 thread). The extension persists + re-syncs.
        java.util.concurrent.CompletableFuture.runAsync({
            try {
                extension.applyChanges(item, coerced)
                plugin.auditLogger.logEdit(playerId, itemId, "[$extensionId]", null, coerced.keys.joinToString(","))
                logger.atInfo().log("EditorBridge: applied %d extension field(s) on '%s' via '%s' for %s",
                    coerced.size, itemId, extensionId, playerId)
            } catch (e: Exception) {
                logger.atSevere().withCause(e).log("EditorBridge: extension '%s'.applyChanges() threw", extensionId)
            }
        }, runtime.asyncExecutor)

        return gson.toJson(SaveResponse(true, null, null, emptyList()))
    }

    /**
     * Handles a button press in a mod [EditorExtension] panel. Runs the extension's
     * `onAction` on a background worker, then re-pushes a fresh editor payload so the rebuilt
     * panel (reflecting whatever the action changed) renders. Exposed to JS as
     * `itemForgeBridge.extensionAction(...)`.
     */
    fun extensionAction(playerId: String, itemId: String, extensionId: String, actionId: String): String {
        plugin.v8Watchdog.recordBridgeCall("extensionAction", itemId, actionId)
        return try {
            val uuid = UUID.fromString(playerId)
            if (!PermissionChecker.canEditStats(uuid)) {
                return gson.toJson(mapOf("success" to false, "permissionDenied" to true))
            }
            val extension = plugin.extensionRegistry.byId(extensionId)
                ?: return gson.toJson(mapOf("success" to false, "error" to "Source unavailable"))
            val item = Item.getAssetMap().getAsset(itemId)
                ?: return gson.toJson(mapOf("success" to false, "error" to "Item not found"))

            java.util.concurrent.CompletableFuture.runAsync({
                try {
                    extension.onAction(item, actionId)
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("EditorBridge: extension '%s'.onAction('%s') threw", extensionId, actionId)
                }
            }, runtime.asyncExecutor).thenRunAsync({
                // Re-push the rebuilt panel so the UI reflects the action's result.
                try {
                    val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid)
                    if (ui != null) ui.setData("editor", buildEditorPayload(itemId, uuid))
                } catch (e: Exception) {
                    logger.atWarning().withCause(e).log("EditorBridge: failed to re-push panel after action for '%s'", itemId)
                }
            }, runtime.v8Executor)

            gson.toJson(mapOf("success" to true))
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("EditorBridge.extensionAction() failed for '%s'", itemId)
            gson.toJson(mapOf("success" to false))
        }
    }

    /** Coerces a UI value to a panel field's declared input type for applyChanges. */
    private fun coerceComponentValue(inputType: String?, raw: Any?): Any? = when (inputType) {
        "integer" -> (raw as? Number)?.toInt() ?: raw
        "number" -> (raw as? Number)?.toDouble() ?: raw
        "toggle" -> raw as? Boolean ?: raw
        else -> raw   // text / dropdown → keep as String
    }

    // (Held-stack save logic extracted to me.itemforge.vuetale.editor.HeldStackSaveHandler)


    /**
     * Parses the changes JSON from the bridge call.
     *
     * The UI sends: `{ "MaxDurability": 200.0, "MaxStack": 50, "Quality": "Rare" }`
     * Gson deserializes numbers as Double by default.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseChangesJson(changesJson: String): Map<String, Any> {
        return try {
            gson.fromJson(changesJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) {
            logger.atWarning().log("EditorBridge: Failed to parse changes JSON: %s", e.message)
            emptyMap()
        }
    }

    // (Recipe-save logic extracted to me.itemforge.vuetale.editor.RecipeSaveHandler)



    companion object {
        /**
         * Save-scope marker. The UI sets `scope == "local"` in the save
         * payload when the "This Item" toggle is active, routing a BASE-source save to the held-item
         * write path ([executeLocalBaseSave]) instead of the global asset override. Absent/"global"
         * → normal asset save. Must match the SCOPE_LOCAL constant in Editor.vue.
         */
        const val SCOPE_LOCAL = "local"
    }

    /**
     * Resets all overrides for an item, reverting to original mod/vanilla values.
     *
     * Flow:
     * 1. Remove override from config (synchronous, safe on V8 thread)
     * 2. Dispatch async: revert via OverrideEngine (loadAssets — must be off V8)
     * 3. Dispatch async: audit log the reset
     * 4. Return {success: true} immediately — Vue handles display via afterReset=true
     *
     * The Vue side displays correct reverted values without a server data push:
     * afterReset=true causes getDisplayValue() to use field.originalValue, and
     * suppresses all "modified" indicators. Fresh server data arrives when the
     * admin reopens the editor.
     *
     * @param playerId The player's UUID as string
     * @param itemId The item to reset
     * @return JSON string with {success: true} or error payload
     */
    fun reset(playerId: String, itemId: String): String {
        val t0 = System.nanoTime()
        plugin.v8Watchdog.recordBridgeCall("reset", itemId)
        return try {
            // Permission gate — re-check before mutating anything.
            if (!canResetByPlayerId(playerId)) {
                logger.atWarning().log("EditorBridge: reset denied for '%s' by %s", itemId, playerId)
                return gson.toJson(mapOf(
                    "success" to false,
                    "permissionDenied" to true,
                    "error" to "You don't have permission to reset items."
                ))
            }

            val configManager = plugin.configManager

            // Remove item field overrides from persistent config (safe, no locks)
            configManager.itemOverrides.removeOverride(itemId)

            // Remove the recipe override (if any). Covers BOTH an edited generated recipe and
            // a recipe ItemForge created for an item that had none. Computed here, before
            // removal, so the async revert/delete below knows which path applies.
            val item = Item.getAssetMap().getAsset(itemId)
            val recipeId = item?.let { CraftingRecipe.generateIdFromItemRecipe(it, 0) }
            val hadRecipeOverride = recipeId != null && configManager.recipeOverrides.hasOverride(recipeId)
            // A "created" recipe is one we registered for an item the game generates none for
            // (its recipeToGenerate field is null → hasRecipesToGenerate() == false). Reset must
            // DELETE such a recipe (no original to revert to); an edited generated recipe reverts.
            val recipeWasCreated = item != null && hadRecipeOverride && !item.hasRecipesToGenerate()
            if (recipeId != null && hadRecipeOverride) {
                configManager.recipeOverrides.removeOverride(recipeId)
            }

            // NOTE: fieldCache is intentionally NOT cleared here. The cache contains
            // codec metadata (valueType, constraints, readOnly state) that is determined
            // by the codec structure, not by override values. This metadata remains valid
            // after revert. Keeping the cache prevents Bug #3 (heavy codec scan on V8
            // thread) if the admin saves new changes in the same editor session.

            // Dispatch revert (loadAssets) off V8 thread. Uses asyncExecutor to
            // avoid ForkJoinPool contention with Vuetale's sendUpdateAsync.
            val revertFuture = java.util.concurrent.CompletableFuture.runAsync({
                try {
                    val overrideEngine = plugin.overrideEngine
                    val auditLogger = plugin.auditLogger

                    val reverted = overrideEngine.revertItem(itemId)
                    if (!reverted) {
                        logger.atWarning().log("EditorBridge.reset(): No original snapshot for '%s'", itemId)
                    }

                    // Revert an edited generated recipe to its original, OR delete a recipe we
                    // created (no original → remove the asset + broadcast an UpdateRecipes Remove).
                    if (recipeId != null && hadRecipeOverride) {
                        if (recipeWasCreated) {
                            plugin.recipeOverrideEngine.deleteRecipe(recipeId)
                        } else {
                            plugin.recipeOverrideEngine.revertRecipe(recipeId)
                        }
                    }

                    // Update dashboard cache so navigate-back shows fresh data
                    plugin.dashboardBridge.updateCachedItem(itemId)

                    auditLogger.logReset(playerId, itemId)
                    logger.atInfo().log("Reset all overrides for '%s' by %s", itemId, playerId)
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("EditorBridge: async reset failed for '%s'", itemId)
                }
            }, runtime.asyncExecutor)

            // After revert completes: refresh held items + push fresh payload to UI.
            // Fresh payload is needed because the Vue side holds stale field metadata
            // (isNotSet, effectDescription, state) from the pre-reset payload. Without
            // this push, text fields show old values (Core.TextField vt-skip-update) and
            // "Not set" indicators don't reappear.
            //
            // Runs on V8 executor: safe for buildEditorPayload (reads reverted item,
            // updates fieldCache) and setData (buffers for next V8 tick). Single-threaded
            // executor means no race with Vue rendering or save operations.
            revertFuture.thenRunAsync({
                refreshHeldItems(playerId, itemId)
            }, runtime.asyncExecutor).thenRunAsync({
                try {
                    val uuid = java.util.UUID.fromString(playerId)
                    val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid)
                    if (ui != null) {
                        val freshPayload = buildEditorPayload(itemId, uuid)
                        ui.setData("editor", freshPayload)
                        logger.atInfo().log("Reset: pushed fresh payload for '%s'", itemId)
                    }
                } catch (e: Exception) {
                    logger.atWarning().log("Reset: failed to push fresh payload for '%s': %s", itemId, e.message)
                }
            }, runtime.v8Executor)

            // Return immediately — revert happens async
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            logger.atFine().log("[TIMING] reset bridge: %dms", elapsed)
            gson.toJson(mapOf("success" to true))
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("EditorBridge.reset() failed for '%s'", itemId)
            gson.toJson(buildErrorPayload(itemId, "Reset failed: ${e.message}"))
        }
    }

    /**
     * Resets a single field override, keeping other overrides intact.
     *
     * Flow:
     * 1. Remove the field from the override config
     * 2. If override is now empty, do a full reset
     * 3. If override still has fields, re-apply the remaining override
     * 4. Audit log
     * 5. Return updated payload
     *
     * @param playerId The player's UUID as string
     * @param itemId The item being edited
     * @param fieldId The dot-separated field ID to reset (e.g., "MaxDurability")
     * @return JSON string of updated EditorPayload
     */
    /**
     * Resets a single field override, keeping other overrides intact.
     * Same async pattern — revertItem/applyAndSync call loadAssets().
     */
    fun resetField(playerId: String, itemId: String, fieldId: String): String {
        plugin.v8Watchdog.recordBridgeCall("resetField", itemId, fieldId)
        return try {
            // Permission gate — per-field reset is still a reset.
            if (!canResetByPlayerId(playerId)) {
                logger.atWarning().log("EditorBridge: resetField denied for '%s.%s' by %s", itemId, fieldId, playerId)
                return gson.toJson(mapOf(
                    "success" to false,
                    "permissionDenied" to true,
                    "error" to "You don't have permission to reset items."
                ))
            }

            val configManager = plugin.configManager

            // Config changes are safe on V8 thread (no locks)
            val topLevelKey = fieldId.split(".").first()
            configManager.itemOverrides.removeField(itemId, topLevelKey)
            val remainingOverride = configManager.itemOverrides.getOverride(itemId)

            // NOTE: fieldCache intentionally kept — codec metadata (valueType, constraints)
            // is valid regardless of override state. See reset() comment for rationale.

            // Dispatch revert + re-apply off V8 thread. Uses asyncExecutor to
            // avoid ForkJoinPool contention with Vuetale's sendUpdateAsync.
            java.util.concurrent.CompletableFuture.runAsync({
                try {
                    val overrideEngine = plugin.overrideEngine
                    val auditLogger = plugin.auditLogger

                    if (remainingOverride == null || remainingOverride.size() == 0) {
                        overrideEngine.revertItem(itemId)
                    } else {
                        overrideEngine.revertItem(itemId)
                        val remainingBson = org.bson.BsonDocument.parse(remainingOverride.toString())
                        overrideEngine.applyAndSync(itemId, remainingBson)
                    }

                    // Update dashboard cache so navigate-back shows fresh data
                    plugin.dashboardBridge.updateCachedItem(itemId)

                    auditLogger.logEdit(playerId, itemId, fieldId, null, "(reset)")
                    logger.atInfo().log("Reset field '%s' on '%s' by %s", fieldId, itemId, playerId)
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("EditorBridge: async resetField failed for '%s.%s'", itemId, fieldId)
                }
            }, runtime.asyncExecutor)

            gson.toJson(mapOf("success" to true))
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("EditorBridge.resetField() failed for '%s.%s'", itemId, fieldId)
            gson.toJson(buildErrorPayload(itemId, "Reset field failed: ${e.message}"))
        }
    }

    /**
     * Closes the editor page for a player.
     *
     * Dispatches to world thread because:
     * - `PlayerUi.closePage()` only unmounts the Vue app — does NOT release cursor/movement
     * - `PageManager.setPage(Page.None)` must be called on the world thread to release input controls
     *
     * Follows the exact TrailOfOrbis pattern:
     * 1. Get PlayerRef from PlayerUi via reflection (Kotlin `internal` → JVM mangled name)
     * 2. Get World from entity store
     * 3. Dispatch to world thread
     * 4. Unmount Vue + release page controls
     *
     * Evidence: ToO `LootFilterBridge.java:84-106` (closePage),
     * `LootFilterBridge.java:154-174` (navigateOnWorldThread)
     */
    fun closePage(playerId: String) {
        plugin.v8Watchdog.recordBridgeCall("closePage", playerId)
        try {
            // Dashboard flow: editor was opened via navigate() from dashboard.
            // Navigate back to dashboard instead of full page dismiss — the Vue
            // app stays alive, no cleanup needed, just swap the component.
            if (plugin.dashboardBridge.hasPlayerState(playerId)) {
                plugin.dashboardBridge.navigateBack(playerId)
                return
            }

            // Direct flow: editor opened via `/itemforge <id>` — full page dismiss.
            val uuid = java.util.UUID.fromString(playerId)
            val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid) ?: return

            val playerRef = try {
                ui.javaClass.getMethod("getPlayerRef\$Vuetale").invoke(ui)
                    as? com.hypixel.hytale.server.core.universe.PlayerRef
            } catch (_: Exception) { null }

            if (playerRef == null) {
                logger.atWarning().log("closePage: Could not resolve PlayerRef for %s — admin must press Escape", playerId)
                return
            }

            val ref = playerRef.reference
            if (ref == null || !ref.isValid) {
                logger.atWarning().log("closePage: PlayerRef invalid for %s — skipping", playerId)
                return
            }

            runtime.fieldCache.clear()

            val store = ref.store
            val world = store.externalData.world
            val gen = runtime.pageGeneration[playerId]?.get() ?: 0L

            // ── KOTLIN-ONLY APP CLEANUP (no JS unmount) ─────────────────────
            //
            // Previous approach called `_vt.getUserApp(id).unmount()` directly on
            // V8Runtime. This hangs permanently in Javet native code:
            //   V8Native.scriptExecute → V8ValueObject.<init> → addReference
            // terminateExecution() cannot recover native hangs — the entire V8
            // executor is bricked until server restart (30s+ stall confirmed).
            //
            // Fix: skip the JS unmount entirely. Kotlin-side cleanup (isMounted=false,
            // closeAll, unregister callbacks, onDirty=null) is sufficient:
            // - isMounted=false prevents App.unmount() from calling evalScriptAsync
            //   when onDismiss fires on the world thread
            // - closeAll() removes all event bindings (prevents phantom bindings)
            // - unregisterHostCallbacks cleans up Javet function references
            // - onDirty=null prevents stray dirty ticks from sending updates
            //
            // Trade-off: Vue's JS onUnmounted hooks don't fire, orphaned Vue app
            // stays in V8 memory until GC or next page open. Acceptable for an
            // admin-only tool — same trade-off already accepted for Escape dismiss.
            try {
                val page = ui.javaClass.getDeclaredField("page").let { f ->
                    f.isAccessible = true
                    f.get(ui)
                }
                if (page != null) {
                    val app = page.javaClass.getDeclaredField("app").let { f ->
                        f.isAccessible = true
                        f.get(page) as? li.kelp.vuetale.app.App
                    }
                    if (app != null) {
                        // Kotlin-side cleanup — no JS execution, no native hang risk
                        app.javaClass.getDeclaredField("isMounted").let { f ->
                            f.isAccessible = true
                            f.set(app, false)
                        }
                        app.eventRegistry.closeAll()
                        runCatching {
                            li.kelp.vuetale.javascript.JSEngine.instance.bridge
                                .unregisterHostCallbacksForApp(app.getId())
                        }
                        app.onDirty = null

                        logger.atInfo().log("closePage: App cleanup complete for %s (no JS unmount)", playerId)
                    }
                }
            } catch (e: Exception) {
                logger.atWarning().withCause(e).log("closePage: App cleanup failed for %s — will attempt page dismiss anyway", playerId)
            }

            // ── DISMISS PAGE ON WORLD THREAD ────────────────────────────────
            //
            // Dispatch via CompletableFuture with a small delay before world.execute.
            // The delay ensures handleDataEvent has fully returned and V8 has completed
            // its tick cycle before pageManager.setPage fires on the world thread.
            // This was the VERIFIED WORKING pattern from the 12:23 test (3 cycles, 0 errors).
            // Removing the delay caused regressions — do NOT change this.
            java.util.concurrent.CompletableFuture.runAsync {
                try {
                    Thread.sleep(50)
                    world.execute {
                        try {
                            val currentGen = runtime.pageGeneration[playerId]?.get() ?: -1L
                            if (currentGen != gen) return@execute

                            val player = store.getComponent(
                                ref,
                                com.hypixel.hytale.server.core.entity.entities.Player.getComponentType()
                            )
                            player?.pageManager?.setPage(
                                ref, store,
                                com.hypixel.hytale.protocol.packets.interface_.Page.None
                            )
                        } catch (e: Exception) {
                            logger.atWarning().withCause(e).log("closePage: page dismiss failed for %s", playerId)
                        }
                    }
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("closePage: async dispatch failed for %s", playerId)
                }
            }

            logger.atInfo().log("Closed editor page for player %s", playerId)
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("EditorBridge.closePage() failed for '%s'", playerId)
        }
    }

    // ── Deferred UI Flush ──────────────────────────────────────────────

    /**
     * Schedules a deferred UI flush on the V8 executor.
     *
     * Called from JS event handlers after modifying reactive state. The task
     * runs AFTER the current V8 callback returns — it queues behind the active
     * task on the single-thread executor. By that point, handleDataEvent's
     * `.get()` has unblocked the game thread, so the flush runs with the same
     * thread safety as a normal V8 tick.
     *
     * The flush:
     * 1. Runs `v8Runtime.await(RunNoWait)` → processes Vue's render microtasks
     * 2. Fires `onDirty` on any dirty app → `sendUpdateAsync` → ForkJoinPool
     *
     * This eliminates the **50ms tick delay**: instead of waiting for the next
     * `scheduleWithFixedDelay` tick, the UI update dispatches within ~1ms of
     * the callback completing.
     *
     * ## Why this is safe (unlike the old inline flushUpdates — Bug #8)
     *
     * Bug #8 called flushUpdates INSIDE a bridge callback — the game thread
     * was still blocked in `.get()`, and `onDirty → sendUpdateAsync → sendUpdate`
     * could deadlock on player/page locks held by the game thread.
     *
     * The deferred version runs AFTER `.get()` unblocks (separate executor task).
     * The game thread is free. `sendUpdateAsync` dispatches to ForkJoinPool, which
     * sends the packet without lock contention. Identical to a normal tick.
     *
     * ## Redundancy safety
     *
     * Multiple `requestFlush()` calls in the same callback each submit a task.
     * The first processes all dirty state; subsequent tasks are no-ops (isDirty
     * already false). A regular tick arriving between tasks is also fine — it
     * processes dirty state first, the deferred task becomes a no-op.
     */
    fun requestFlush() {
        val submitTime = System.nanoTime()
        try {
            runtime.v8Executor.submit {
                val startTime = System.nanoTime()
                val queueMs = (startTime - submitTime) / 1_000_000
                try {
                    // 1. Flush V8 microtasks — runs Vue's pending scheduler flush
                    val v8Runtime = li.kelp.vuetale.javascript.VueBridge::class.java
                        .getDeclaredField("v8Runtime").let { f ->
                            f.isAccessible = true
                            f.get(li.kelp.vuetale.javascript.JSEngine.instance.bridge)
                                as com.caoccao.javet.interop.V8Runtime
                        }
                    v8Runtime.await(com.caoccao.javet.enums.V8AwaitMode.RunNoWait)

                    val afterAwait = System.nanoTime()

                    // 2. Fire onDirty for any app marked dirty by the microtask flush.
                    //    Mirrors JSEngine.tickInternal() lines 296-303.
                    li.kelp.vuetale.app.AppManager.apps.values.forEach { app ->
                        if (app.isDirty) {
                            app.isDirty = false
                            runCatching { app.onDirty?.invoke() }
                        }
                    }

                    val endTime = System.nanoTime()
                    val awaitMs = (afterAwait - startTime) / 1_000_000
                    val dirtyMs = (endTime - afterAwait) / 1_000_000
                    val totalMs = (endTime - submitTime) / 1_000_000
                    if (totalMs > 5) { // Only log if notable
                        logger.atFine().log(
                            "[TIMING] requestFlush: queue=%dms await=%dms dirty=%dms total=%dms",
                            queueMs, awaitMs, dirtyMs, totalMs
                        )
                    }
                } catch (_: Exception) {
                    // Silent — regular tick handles it eventually
                }
            }
        } catch (_: Exception) {
            // Executor may be shut down during server shutdown
        }
    }

    // ── Data-driven dropdown entries (perf) ──────────────────────────────
    //
    // Large dropdowns (the shared FieldPicker + the dashboard mod filter) push their option list as a
    // single DropdownEntryInfo[] data array instead of rendering one native <DropdownEntry> per option
    // — the engine renders/virtualizes the rows itself. Mirrors DashboardBridge.refreshGrid: cheap
    // parse + schedule on V8, the build + sendUpdate deferred off-tick (never hold the world thread
    // hostage during a packet send — Bug #8). Exposed on globalThis.itemForgeBridge; called by both
    // FieldPicker.vue and Dashboard.vue.

    /**
     * Populates a native `<DropdownBox id="customId">`'s `Entries` from [entriesJson] (a JSON array of
     * `{label, value, tooltip?}` in display order). Called on mount, on entry-set change, and after any
     * structural re-render that would wipe the pushed entries (the FieldPicker repush token).
     *
     * Debounced per `"playerId|customId"` so distinct pickers never cancel each other and a burst
     * coalesces into one push that lands after the pending Vue re-render. Returns immediately.
     *
     * @param playerId    player UUID string
     * @param customId    the Vue `id` on the target `<DropdownBox>`
     * @param entriesJson JSON array of `{label, value, tooltip?}`; null/blank pushes an empty list
     */
    fun refreshPickerEntries(playerId: String?, customId: String?, entriesJson: String?): String {
        plugin.v8Watchdog.recordBridgeCall("refreshPickerEntries", playerId ?: "null")
        return try {
            if (playerId.isNullOrBlank() || customId.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing playerId/customId"))

            // Parse with the streaming parser (no custom-class reflection) so deserialization is
            // guaranteed-correct. Label/Tooltip are LocalizableString (not raw String) — our text is
            // dynamic, so wrap with fromString (NOT fromMessageId, which expects an i18n key).
            val arr = if (entriesJson.isNullOrBlank()) com.google.gson.JsonArray()
                      else com.google.gson.JsonParser.parseString(entriesJson).asJsonArray
            val entries = Array(arr.size()) { i ->
                val o = arr[i].asJsonObject
                val label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val value = o.get("value")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val tip = o.get("tooltip")?.takeIf { !it.isJsonNull }?.asString
                if (tip.isNullOrEmpty())
                    DropdownEntryInfo(LocalizableString.fromString(label), value)
                else
                    DropdownEntryInfo(LocalizableString.fromString(label), value, LocalizableString.fromString(tip))
            }

            val uuid = UUID.fromString(playerId)
            val key = "$playerId|$customId"

            // Coalesce per (player, picker): cancel any push still pending for THIS picker, reschedule.
            pendingPickerPush.remove(key)?.cancel(false)
            val future = pickerExecutor.schedule({
                try {
                    // Returns false if the picker's tab/source isn't mounted yet — harmless; the
                    // picker re-pushes from its own onMounted when it appears.
                    li.kelp.vuetale.app.PlayerUiManager.get(uuid)?.page?.pushDropdownEntries(customId, entries)
                } catch (e: Throwable) {
                    logger.atWarning().withCause(e).log(
                        "refreshPickerEntries: push failed for %s/%s", playerId, customId
                    )
                } finally {
                    pendingPickerPush.remove(key)
                }
            }, 90L, java.util.concurrent.TimeUnit.MILLISECONDS)
            pendingPickerPush[key] = future

            gson.toJson(mapOf("success" to true, "count" to entries.size))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("refreshPickerEntries failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "refresh failed")))
        }
    }

    // ── Material Search ─────────────────────────────────────────────────

    /**
     * Searches all items and resource types by fuzzy word-matching.
     *
     * Called from Vue when the admin types in the recipe input search panel.
     * Returns a JSON array of top 8 matches: `[{id, displayName, type}, ...]`
     * where type is "item" or "resource".
     *
     * Thread safety: runs on V8 thread. [MaterialIndex.search] is pure computation
     * on an immutable list — no world thread access, no locks. Safe to call from
     * any bridge callback.
     *
     * @param query Admin's search text (e.g., "iron ingot" or "wood")
     * @return JSON array string of [MaterialIndex.MaterialEntry] results
     */
    fun searchMaterials(query: String): String {
        if (query.isBlank() || query.length < 2) return "[]"
        val results = plugin.materialIndex.search(query.trim(), limit = 8)
        return gson.toJson(results)
    }

    // ── Inventory Refresh ────────────────────────────────────────────────

    /**
     * Refreshes held ItemStacks for all online players after an Item asset change.
     *
     * Uses Universe.get().getWorlds() internally to iterate ALL worlds — no need
     * to resolve a specific world from the admin's player reference.
     *
     * Called from the async CompletableFuture (ForkJoinPool). The refresher
     * dispatches to each world's thread internally for thread safety.
     */
    private fun refreshHeldItems(playerId: String, itemId: String) {
        try {
            plugin.inventoryRefresher.refreshItemStacks(itemId)
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log(
                "EditorBridge: Failed to refresh held items for '%s'", itemId
            )
        }
    }

    // ── Lifecycle Hooks ──────────────────────────────────────────────────

    /**
     * Called by [VuetaleIntegration.openEditor] after a new editor page opens.
     * Increments the page generation counter so any in-flight deferred close
     * from a previous session knows to abort.
     */
    fun onEditorOpened(playerId: String) {
        runtime.pageGeneration.computeIfAbsent(playerId) { java.util.concurrent.atomic.AtomicLong(0) }
            .incrementAndGet()
    }

    /**
     * Called by [DashboardBridge.navigateBack] when the admin navigates from
     * Editor back to Dashboard. Clears the field cache for this item since
     * the editor is no longer active.
     */
    fun onEditorClosed(playerId: String) {
        runtime.fieldCache.clear()
        // Clear any per-stack edit session so it can't leak onto a later open.
        runCatching { runtime.stackSessions.remove(UUID.fromString(playerId)) }
    }

    /**
     * Cleans up per-player state when a player disconnects.
     * Called from [VuetaleIntegration.onPlayerDisconnect].
     */
    fun onPlayerDisconnect(playerId: String) {
        runtime.pageGeneration.remove(playerId)
        runtime.fieldCache.clear()
        // Clear any per-stack edit session.
        runCatching { runtime.stackSessions.remove(UUID.fromString(playerId)) }
    }
}

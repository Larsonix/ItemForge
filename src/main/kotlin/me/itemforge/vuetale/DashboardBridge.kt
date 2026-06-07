package me.itemforge.vuetale

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import me.itemforge.ItemForgePlugin
import me.itemforge.core.BatchOperation
import me.itemforge.core.ScaleTarget
import me.itemforge.scanner.ValueType
import me.itemforge.metadata.ItemNameResolver
import me.itemforge.util.BsonHelper
import org.bson.BsonDocument
import org.bson.BsonType
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin/JS bridge for the ItemForge dashboard.
 *
 * Exposed as `globalThis.dashboardBridge` in the V8 runtime via
 * [VuetaleIntegration]. All public methods are callable from JavaScript.
 *
 * ## Architecture: Single Page with navigate()
 *
 * Dashboard and Editor are separate Vue components within a SINGLE Vuetale page.
 * Navigation uses [PlayerUi.navigate] to swap the rendered component without
 * unmounting/remounting the Vue app. This is dramatically safer than close+reopen:
 * - No evalScriptAsync hang risk (no page dismiss)
 * - No world thread dispatch needed for navigation
 * - Vue reactive store survives across navigations
 * - Client sees smooth component swap, not page flicker
 *
 * Evidence: Vuetale `App.navigateTo()` (App.kt:282-296) — changes componentPath
 * and triggers Vue router-like component swap without unmounting.
 *
 * ## Threading
 *
 * - [buildDashboardPayload] runs on the **game thread** (ExtraInfo required for
 *   damage extraction via codec encode). Called from openDashboard inside world.execute.
 * - Bridge methods run on the **V8 thread** — return JSON, push data via PlayerUi.
 * - Navigation via navigate() is safe from V8 thread (dispatches to V8 executor
 *   internally, same-thread check passes).
 *
 * ## Payload Caching
 *
 * Building the payload encodes each item's InteractionVars BSON (~0.1ms/item,
 * ~550ms for 5,523 items). Top-level stats (durability, level) use direct property
 * access (~0.01ms). Cached in [cachedPayload], invalidated when overrides change.
 *
 * @param plugin The ItemForge plugin instance (for subsystem access)
 */
class DashboardBridge(
    private val plugin: ItemForgePlugin
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    /**
     * Cached dashboard payload — built OFF the tick thread by [runWarm] and swapped in atomically.
     * KEPT as a stale-but-valid fallback across invalidations (never nulled), so opens and
     * navigate-back stay instant during a re-warm instead of flashing an empty 0-item payload.
     * The [warmGeneration] guard is what prevents publishing data from a now-stale snapshot.
     */
    @Volatile
    private var cachedPayload: DashboardPayload? = null

    /** Per-player dashboard state for navigation-back preservation. */
    private val playerState = ConcurrentHashMap<String, DashboardState>()

    /**
     * Per-player entity references for world thread dispatch.
     * Populated in [onDashboardOpened], consumed in navigate-back and closeDashboard.
     */
    private val playerRefs = ConcurrentHashMap<String, PlayerRefData>()

    /**
     * Page generation counter — prevents deferred close from killing a newly-opened page.
     * Same pattern as [EditorBridge.pageGeneration].
     */
    private val pageGeneration = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    // ── Batch Operations ──────────────────────────────────────

    /**
     * Single-thread executor for ALL batch asset mutations (apply/reset/scale/undo).
     *
     * Why a dedicated single thread:
     * - loadAssets broadcasts packets — MUST be off the V8 thread (the bridge runs on
     *   V8 during handleDataEvent, which holds the world thread hostage; broadcasting
     *   from there deadlocks — Bug #8).
     * - Serializing batch ops on one thread prevents two operations racing on the
     *   shared Item/Recipe asset maps.
     * Daemon thread so it never blocks JVM shutdown (no explicit lifecycle needed).
     */
    private val batchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "itemforge-batch").apply { isDaemon = true }
    }

    // ── Native ItemGrid population (Creative-style browser) ─────────────────
    //
    // The dashboard grid view is a single native <ItemGrid> whose Slots are pushed from
    // here (Vue's :slots serialization hangs at scale + emits invalid markup — see
    // docs/DASHBOARD_TOOLTIP_AND_ITEMGRID_INVESTIGATION.md). The push runs OFF the V8/world
    // thread (VuetaleUIPage.pushItemGridSlots → sendUpdate self-marshals to world.execute),
    // mirroring the batch-method threading rules (never sendUpdate on the hostage world
    // thread). Scheduled (not immediate) so a burst of filter/sort changes coalesces into a
    // single push, AND so the push lands AFTER any pending structural re-render that would
    // otherwise wipe the injected Slots (clear("#App")+appendInline drops them; the grid is
    // re-pushed on every relevant Vue action).

    /** Single daemon thread for debounced grid pushes — never blocks JVM shutdown. */
    private val gridExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "itemforge-grid").apply { isDaemon = true }
    }

    /** Per-player pending grid push — cancel-and-reschedule coalesces rapid refreshes. */
    private val pendingGridPush = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    /** Per-player stat-batch undo context (15s window). Mutually exclusive with [recipeUndoBuffers]. */
    private val undoBuffers = ConcurrentHashMap<String, StatUndoContext>()

    /** Per-player recipe-batch undo context (15s window). Mutually exclusive with [undoBuffers]. */
    private val recipeUndoBuffers = ConcurrentHashMap<String, RecipeUndoContext>()

    /** Per-player weapon-damage-batch undo context (15s window). Snapshot-based: a
     *  multi-attack weapon's per-attack values can't be rebuilt from one number, so we
     *  restore each item's pre-batch override snapshot. Mutually exclusive with the others. */
    private val wdmgUndoBuffers = ConcurrentHashMap<String, WdmgUndoContext>()

    /** Advanced-filter field-value cache: fieldId -> { itemId -> value }. Values are typed
     *  (Double / Boolean / String) per the field's kind. Cleared on any override change
     *  so filters never act on stale values. */
    private val fieldValueCache = ConcurrentHashMap<String, Map<String, Any>>()

    /** Cached global field-catalog JSON. Built OFF the tick thread by [runWarm] (schema walk +
     *  the data-derived weapon-damage types) and kept as a stale-but-valid fallback across asset
     *  reloads (the warm swaps it atomically), so opens stay instant. */
    @Volatile
    private var cachedGlobalCatalogJson: String? = null

    // ── Off-tick Index Warm (instant open, never freezes the tick) ──────────
    //
    // The dashboard payload and the global field catalog are built HERE, off the world/V8 thread,
    // on a dedicated daemon executor — never synchronously during openDashboard (that per-item
    // codec scan was a ~21s server-wide freeze on the first open). openDashboard only reads the
    // published volatile caches. requestWarm() is debounced + generation-guarded so a burst of
    // asset reloads coalesces into one coherent rebuild and a reload mid-build never publishes
    // stale data. See [runWarm].

    /** Single daemon thread for the off-tick payload+catalog build. Never blocks JVM shutdown. */
    private val warmExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "itemforge-warm").apply { isDaemon = true }
    }

    /** At most one warm runs at a time (the single-thread executor + this CAS guard). */
    private val warmInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Bumped by every real invalidation (asset reload). A warm whose generation changed
     *  mid-build rebuilds rather than publishing data from a now-stale asset snapshot. */
    private val warmGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /** Debounce handle — cancel-and-reschedule coalesces a burst of reload events into one warm. */
    @Volatile
    private var pendingWarm: java.util.concurrent.ScheduledFuture<*>? = null

    /** Players who opened the dashboard before the cache was warm. Drained (pushed real data) when
     *  the warm completes. Value = the warmGeneration registered at (diagnostic). */
    private val pendingOpens = ConcurrentHashMap<String, Long>()

    /**
     * Stat-batch undo context.
     *
     * [freshItemIds] are the items that had NO override at all before the batch — on
     * undo they are fully removed from config (clean restore), instead of writing the
     * pre-value back as a redundant override.
     */
    private data class StatUndoContext(
        val buffer: me.itemforge.core.BatchUndoBuffer,
        val freshItemIds: Set<String>
    )

    /** Recipe-batch undo context — just the recipe IDs to revert (RecipeOriginalsCache holds the originals). */
    private data class RecipeUndoContext(
        val recipeIds: List<String>,
        val target: me.itemforge.core.ScaleTarget,
        val timestamp: java.time.Instant
    )

    /**
     * Weapon-damage-batch undo context. [snapshots] is each changed item's PRE-BATCH
     * override (a deep copy from [ItemOverrideStore.getOverride]); null means the item had
     * no override before the batch. On undo, each item is restored to exactly this state.
     */
    private data class WdmgUndoContext(
        val snapshots: Map<String, com.google.gson.JsonObject?>,
        val targetField: String,
        val timestamp: java.time.Instant
    )

    // ── Payload Building ────────────────────────────────────────────────

    /**
     * Requests an off-tick rebuild of the payload + catalog caches. Debounced — a burst of
     * asset-reload events collapses into a single warm. Safe to call from ANY thread (world, V8,
     * boot); it only schedules onto [warmExecutor]. This is the ONLY way the heavy build runs —
     * the tick thread must never call [buildDashboardPayload]/[buildCatalogJson] synchronously.
     */
    @Synchronized
    fun requestWarm(reason: String) {
        pendingWarm?.cancel(false)
        pendingWarm = warmExecutor.schedule(
            { runWarm(reason) }, WARM_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    /**
     * Builds the payload + global catalog off the tick thread and atomically publishes them.
     *
     * Loops while [warmGeneration] keeps changing (a reload landed mid-build) so it never
     * publishes data built from a now-stale asset snapshot. Runs ONLY on [warmExecutor].
     */
    private fun runWarm(reason: String) {
        if (!warmInProgress.compareAndSet(false, true)) return // a warm is already running
        try {
            var gen: Long
            do {
                gen = warmGeneration.get()
                val t0 = System.nanoTime()
                // Snapshot the asset map so a concurrent mod reload can't tear the iteration.
                val snapshot = ArrayList(Item.getAssetMap().assetMap.entries)
                val dmgTypes = HashSet<String>()
                val payload = buildDashboardPayload(snapshot, dmgTypes)
                val tPayload = (System.nanoTime() - t0) / 1_000_000
                val catalogJson = buildCatalogJson(dmgTypes)
                val tCatalog = (System.nanoTime() - t0) / 1_000_000 - tPayload

                if (warmGeneration.get() == gen) {
                    // No newer invalidation arrived during the build — publish. The volatile
                    // writes are the happens-before barrier for readers in openDashboard.
                    cachedPayload = payload
                    cachedGlobalCatalogJson = catalogJson
                    logger.atInfo().log(
                        "warm[%s]: %d items, payload %dms + catalog %dms, %d damage types",
                        reason, payload.items.size, tPayload, tCatalog, dmgTypes.size
                    )
                    drainPendingOpens()
                } else {
                    logger.atInfo().log(
                        "warm[%s]: superseded mid-build (gen %d->%d) — rebuilding",
                        reason, gen, warmGeneration.get()
                    )
                }
            } while (warmGeneration.get() != gen)
        } catch (e: Throwable) {
            logger.atWarning().withCause(e).log("warm[%s] failed", reason)
        } finally {
            warmInProgress.set(false)
        }
    }

    /** Whether both caches are populated (warm). Once the first warm completes they stay
     *  populated — reloads swap them atomically, never null them — so opens stay instant. */
    fun isWarm(): Boolean = cachedPayload != null && cachedGlobalCatalogJson != null

    /** The warmed global catalog JSON, or an empty (valid) catalog until the first warm lands. */
    fun getCatalogJson(): String =
        cachedGlobalCatalogJson ?: gson.toJson(mapOf("success" to true, "fields" to emptyList<Any>()))

    /** Live item count — for the "Indexing N items…" loading state when the cache isn't warm. */
    fun totalItemCount(): Int = Item.getAssetMap().assetMap.size

    /** Registers a player who opened before the cache was warm; [drainPendingOpens] pushes data
     *  to them when the warm completes. */
    fun registerPendingOpen(playerId: String) {
        pendingOpens[playerId] = warmGeneration.get()
    }

    /**
     * Pushes the freshly-warmed payload + catalog to every player who opened before warm. Runs on
     * [warmExecutor]; setData is buffered + thread-safe from any thread (no flushUpdates/evalScript
     * — compliant with the Vuetale hostage-thread rules). Atomic remove => each player pushed once.
     */
    private fun drainPendingOpens() {
        if (pendingOpens.isEmpty()) return
        val dashJson = getDashboardData()
        val catJson = cachedGlobalCatalogJson ?: return
        for (playerId in pendingOpens.keys) {
            if (pendingOpens.remove(playerId) == null) continue
            val uuid = runCatching { java.util.UUID.fromString(playerId) }.getOrNull() ?: continue
            val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid) ?: continue
            ui.setData("dashboard", dashJson)
            ui.setData("fieldCatalog", catJson)
            ui.setData("dashboardStatus", "{\"warm\":true}")
        }
    }

    /**
     * Builds the global field catalog JSON from the codec SCHEMA ([CodecScanner.discoverAllFields])
     * plus the data-derived weapon-damage types collected during the payload pass. No per-item
     * codec scan — the cheap replacement for the old 1200-item global scan. Runs on [warmExecutor].
     */
    private fun buildCatalogJson(dmgTypes: Set<String>): String {
        val catalog = LinkedHashMap<String, CatalogEntry>()
        for (f in plugin.codecScanner.discoverAllFields()) {
            if (catalog.containsKey(f.id)) continue
            catalogEntryFromField(f)?.let { catalog[f.id] = it }
        }
        // Synthetic normalized weapon-damage fields (filter + batch), from the live type union.
        if (dmgTypes.isNotEmpty()) {
            val allId = "@wdmg.${me.itemforge.util.InteractionVarsBson.ALL_TYPES}"
            catalog.putIfAbsent(allId, CatalogEntry(allId, "All Damage", CAT_WEAPON_DAMAGE, "numeric", null, true))
            for (type in dmgTypes.sorted()) {
                val id = "@wdmg.$type"
                catalog.putIfAbsent(id, CatalogEntry(id, type, CAT_WEAPON_DAMAGE, "numeric", null, true))
            }
        }
        val byCat = catalog.values.groupingBy { it.category }.eachCount()
        logger.atInfo().log(
            "catalog(global): %d fields across %d categories — %s",
            catalog.size, byCat.size, byCat.toString()
        )
        return gson.toJson(mapOf("success" to true, "fields" to catalogFieldsJsonList(catalog)))
    }

    /** Maps a [me.itemforge.scanner.FieldDefinition] to a catalog entry, or null to drop it
     *  (InteractionVars per-item fields). Shared by the warm ([buildCatalogJson]) and the
     *  selection scan ([accumulateCatalog]) so the two views never diverge. */
    private fun catalogEntryFromField(f: me.itemforge.scanner.FieldDefinition): CatalogEntry? {
        if (f.id.startsWith("InteractionVars")) return null
        val kind = when (f.valueType) {
            ValueType.DOUBLE, ValueType.INTEGER -> "numeric"
            ValueType.BOOLEAN -> "boolean"
            ValueType.STRING -> if (!f.constraints.options.isNullOrEmpty()) "enum" else "text"
        }
        val batchEditable = (f.valueType == ValueType.DOUBLE || f.valueType == ValueType.INTEGER) && !f.requiresPathB
        return CatalogEntry(
            id = f.id,
            label = f.displayName,
            category = if (f.category.isBlank()) "General" else f.category,
            kind = kind,
            options = f.constraints.options,
            batchEditable = batchEditable
        )
    }

    /** Serializes catalog entries to the UI's CatalogField JSON shape ({id,label,category,kind,
     *  batchEditable,options?}). */
    private fun catalogFieldsJsonList(catalog: Map<String, CatalogEntry>): List<Map<String, Any?>> =
        catalog.values.map { e ->
            val m = linkedMapOf<String, Any?>(
                "id" to e.id,
                "label" to e.label,
                "category" to e.category,
                "kind" to e.kind,
                "batchEditable" to e.batchEditable
            )
            if (e.options != null) m["options"] = e.options
            m
        }

    /**
     * Forces a full payload rebuild on the next dashboard open.
     * Called from [AssetLoadListener] when item overrides change.
     */
    fun invalidateCache() {
        // A real asset (re)load changed the data — bump the generation so an in-flight warm
        // rebuilds instead of publishing a now-stale snapshot, then kick off the off-tick re-warm.
        warmGeneration.incrementAndGet()
        // Advanced-filter values may now be stale — drop them so the next filter rescans.
        fieldValueCache.clear()
        // NOTE: cachedPayload AND cachedGlobalCatalogJson are KEPT as stale-but-valid fallbacks so
        // opens stay instant during the brief re-warm window; runWarm swaps both atomically.
        requestWarm("invalidate")
    }

    /**
     * Builds the full dashboard payload — extracts summary data for every item.
     *
     * Performance breakdown for 5,523 items:
     * - Direct property access (durability, level): ~50ms total
     * - Name resolution (I18n + fallback): ~200ms total
     * - BSON encode for damage extraction: ~500ms total
     * - Override count lookups: ~50ms total
     * Total: ~800ms — acceptable one-time cost, cached for reuse.
     */
    private fun buildDashboardPayload(
        snapshot: List<Map.Entry<String, Item>>,
        dmgTypeSink: MutableSet<String>
    ): DashboardPayload {
        val t0 = System.nanoTime()
        val modSourceTracker = plugin.modSourceTracker
        val tagCache = plugin.tagCache
        val overrideStore = plugin.configManager.itemOverrides
        val codecScanner = plugin.codecScanner
        val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()

        val items = mutableListOf<DashboardItem>()

        for ((itemId, item) in snapshot) {
            try {
                val hasOverride = overrideStore.hasOverride(itemId)
                val overrideCount = if (hasOverride) {
                    overrideStore.getOverriddenFieldIds(itemId).size
                } else 0

                // Armor stats: single Armor field encode → extract all stats at once.
                // Previously encoded Armor twice (health + defense). Now encodes once
                // and pulls health, stamina, defense, fireRes, slot from the same BSON.
                val armorStats = extractArmorStats(item)

                // Damage extraction requires BSON encode (InteractionVars is a ContainedAsset
                // — no direct property access on the decoded interaction chain).
                // Only encode if the item actually has InteractionVars (weapon/consumable).
                val damage = extractPrimaryDamage(item, codecScanner, extraInfo)

                items.add(DashboardItem(
                    id = itemId,
                    name = ItemNameResolver.resolve(item),
                    type = tagCache.getItemType(item) ?: "Other",
                    mod = modSourceTracker.getModName(itemId),
                    hasOverride = hasOverride,
                    overrideCount = overrideCount,
                    durability = extractIntProperty(item, "MaxDurability"),
                    health = armorStats.health,
                    damage = damage,
                    speed = extractToolSpeed(item),
                    defense = armorStats.defense,
                    level = extractIntProperty(item, "ItemLevel"),
                    quality = extractStringProperty(item, "Quality"),
                    stamina = armorStats.stamina,
                    fireRes = armorStats.fireRes,
                    slot = armorStats.slot,
                    maxStack = extractIntProperty(item, "MaxStack")
                ))

                // Collect the weapon-damage-type union as a free by-product — this powers the
                // catalog's @wdmg.* fields (no DamageType enum exists, so they MUST come from live
                // data) without a separate item scan.
                dmgTypeSink.addAll(
                    me.itemforge.util.InteractionVarsBson.allDamageTypes(codecScanner, item)
                )
            } catch (e: Exception) {
                // Skip items that fail — log but don't crash the dashboard
                logger.atWarning().log(
                    "DashboardBridge: Failed to build summary for '%s': %s",
                    itemId, e.message
                )
            }
        }

        // Pre-sort by name — the default sort column. V8's Timsort is O(n) on
        // pre-sorted input, so the initial sortedItems computed is effectively free.
        // Follows the ecosystem pattern: all vendor mods sort server-side.
        items.sortBy { it.name.lowercase() }

        val elapsed = (System.nanoTime() - t0) / 1_000_000
        logger.atInfo().log(
            "Dashboard payload: %d items in %dms (%d overrides)",
            items.size, elapsed, overrideStore.overrideCount()
        )

        // Build typeOptions: real tag values + "Other" if any items lack a Type tag.
        // "Other" only appears when items actually have it — avoids empty filter option.
        val typeOpts = buildList {
            addAll(tagCache.getValuesForKey("Type").sorted())
            if (items.any { it.type == "Other" }) add("Other")
        }

        return DashboardPayload(
            items = items,
            typeOptions = typeOpts,
            modOptions = modSourceTracker.getAllModNames().sorted(),
            overrideCount = overrideStore.overrideCount(),
            totalCount = items.size
        )
    }

    // ── Stat Extraction — Direct Property Access ────────────────────────
    //
    // Top-level Item properties are public getters (verified at runtime).
    // We use codec encode ONLY for InteractionVars damage (ContainedAsset — no getter).
    // Everything else is a direct read: item.maxDurability, item.itemLevel, etc.
    //
    // The Item class exposes these through BuilderField getters that return the
    // Java field value. Accessing them is O(1) — no BSON encoding needed.

    /**
     * Extracts an integer property from a top-level Item field via BSON encode.
     *
     * Uses a targeted single-field encode instead of the full 54-field encode.
     * Falls back to null if the field doesn't exist on this item.
     */
    private fun extractIntProperty(item: Item, fieldName: String): Int? {
        return try {
            val entries = plugin.codecScanner.itemCodec.entries
            val fieldList = entries[fieldName] ?: return null
            val field = fieldList.last()
            val doc = BsonDocument()
            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            @Suppress("UNCHECKED_CAST")
            (field as com.hypixel.hytale.codec.builder.BuilderField<Any, Any>).encode(doc, item, extraInfo)
            val value = doc[fieldName] ?: return null
            when (value.bsonType) {
                BsonType.INT32 -> value.asInt32().value
                BsonType.DOUBLE -> value.asDouble().value.toInt()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /**
     * Extracts a string property from a top-level Item field via BSON encode.
     * Used for Quality (enum-like string field — "Common", "Uncommon", etc.).
     */
    private fun extractStringProperty(item: Item, fieldName: String): String? {
        return try {
            val entries = plugin.codecScanner.itemCodec.entries
            val fieldList = entries[fieldName] ?: return null
            val field = fieldList.last()
            val doc = BsonDocument()
            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            @Suppress("UNCHECKED_CAST")
            (field as com.hypixel.hytale.codec.builder.BuilderField<Any, Any>).encode(doc, item, extraInfo)
            val value = doc[fieldName] ?: return null
            if (value.bsonType == BsonType.STRING) value.asString().value else null
        } catch (_: Exception) { null }
    }

    /**
     * Extracted armor stats from a single Armor field encode.
     * All nullable — null means the item has no Armor component or the stat doesn't exist.
     */
    private data class ArmorStats(
        val health: Double?,
        val stamina: Double?,
        val defense: Double?,
        val fireRes: Double?,
        val slot: String?
    ) {
        companion object {
            val EMPTY = ArmorStats(null, null, null, null, null)
        }
    }

    /**
     * Extracts all dashboard-relevant armor stats from a single Armor field encode.
     *
     * Encodes the "Armor" BuilderField ONCE, then navigates the BSON document to
     * extract multiple stats. This replaces N separate extractArmorModifier calls
     * (each encoding Armor independently) with 1 encode + N navigations.
     *
     * For DamageResistance, the CalculationType matters:
     * - "Multiplicative" → percentage (0.09 = 9%) → multiplied by 100 for display
     * - "Additive" → flat damage reduction (5 = blocks 5 damage) → kept as-is
     * All standard (non-debug) armor uses Multiplicative for DamageResistance.
     * Confirmed across Wool, Wood, Iron, Trork, Leather, Diving, Thorium, etc.
     */
    private fun extractArmorStats(item: Item): ArmorStats {
        return try {
            val entries = plugin.codecScanner.itemCodec.entries
            val fieldList = entries["Armor"] ?: return ArmorStats.EMPTY
            val field = fieldList.last()
            val doc = BsonDocument()
            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            @Suppress("UNCHECKED_CAST")
            (field as com.hypixel.hytale.codec.builder.BuilderField<Any, Any>).encode(doc, item, extraInfo)
            val armorBson = doc["Armor"] ?: return ArmorStats.EMPTY
            if (armorBson.bsonType != BsonType.DOCUMENT) return ArmorStats.EMPTY
            val armorDoc = armorBson.asDocument()

            // StatModifiers: Additive values (Health +17, Stamina +100)
            val health = extractModifierAmount(armorDoc, "StatModifiers", "Health")
            val stamina = extractModifierAmount(armorDoc, "StatModifiers", "Stamina")

            // DamageResistance: Multiplicative → ×100, Additive → as-is
            val defense = extractResistanceValue(armorDoc, "DamageResistance", "Physical")
            val fireRes = extractResistanceValue(armorDoc, "DamageResistance", "Fire")

            // ArmorSlot: Head, Chest, Hands, Legs
            val slotVal = armorDoc["ArmorSlot"]
            val slot = if (slotVal != null && slotVal.bsonType == BsonType.STRING) {
                slotVal.asString().value
            } else null

            ArmorStats(health, stamina, defense, fireRes, slot)
        } catch (_: Exception) { ArmorStats.EMPTY }
    }

    /**
     * Extracts a modifier Amount from a stat map inside an already-encoded Armor BSON.
     * Used for StatModifiers (Health, Stamina) where CalculationType is always Additive.
     */
    private fun extractModifierAmount(armorDoc: BsonDocument, mapKey: String, statKey: String): Double? {
        val map = armorDoc[mapKey] ?: return null
        if (map.bsonType != BsonType.DOCUMENT) return null
        val stat = map.asDocument()[statKey] ?: return null
        if (stat.bsonType != BsonType.ARRAY) return null
        val amount = BsonHelper.extractModifierAmount(stat.asArray()) ?: return null
        return (amount as? Number)?.toDouble()
    }

    /**
     * Extracts a DamageResistance value from already-encoded Armor BSON.
     *
     * Reads both Amount and CalculationType from the first modifier entry.
     * Multiplicative values (0.09 = 9%) are multiplied by 100 for user-friendly display.
     * Additive values (flat reduction) are returned as-is.
     *
     * BSON structure: `{ "Physical": [{"Amount": 0.09, "CalculationType": "Multiplicative"}] }`
     */
    private fun extractResistanceValue(armorDoc: BsonDocument, mapKey: String, statKey: String): Double? {
        val map = armorDoc[mapKey] ?: return null
        if (map.bsonType != BsonType.DOCUMENT) return null
        val stat = map.asDocument()[statKey] ?: return null
        if (stat.bsonType != BsonType.ARRAY) return null
        val arr = stat.asArray()
        if (arr.isEmpty()) return null
        val first = arr[0]
        if (first.bsonType != BsonType.DOCUMENT) return null
        val modDoc = first.asDocument()

        val amountVal = modDoc["Amount"] ?: return null
        val amount = when (amountVal.bsonType) {
            BsonType.DOUBLE -> amountVal.asDouble().value
            BsonType.INT32 -> amountVal.asInt32().value.toDouble()
            else -> return null
        }

        // Check CalculationType: Multiplicative values are 0.0–1.0 scale → ×100 for display
        val calcType = modDoc["CalculationType"]
        val isMultiplicative = calcType != null &&
            calcType.bsonType == BsonType.STRING &&
            calcType.asString().value == "Multiplicative"

        return if (isMultiplicative) amount * 100.0 else amount
    }

    /**
     * Extracts Tool.Speed via targeted single-field encode.
     */
    private fun extractToolSpeed(item: Item): Double? {
        return try {
            val entries = plugin.codecScanner.itemCodec.entries
            val fieldList = entries["Tool"] ?: return null
            val field = fieldList.last()
            val doc = BsonDocument()
            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            @Suppress("UNCHECKED_CAST")
            (field as com.hypixel.hytale.codec.builder.BuilderField<Any, Any>).encode(doc, item, extraInfo)
            val toolBson = doc["Tool"] ?: return null
            if (toolBson.bsonType != BsonType.DOCUMENT) return null
            val speed = toolBson.asDocument()["Speed"] ?: return null
            when (speed.bsonType) {
                BsonType.DOUBLE -> speed.asDouble().value
                BsonType.INT32 -> speed.asInt32().value.toDouble()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /**
     * Extracts the primary damage value from InteractionVars via full item BSON encode.
     *
     * InteractionVars uses a ContainedAsset codec — no direct property getter exists.
     * We encode the full item and navigate: InteractionVars → first var → Interactions[0]
     * → DamageCalculator → BaseDamage → sum all damage type values.
     *
     * Only called when the item likely has InteractionVars (checked via field existence).
     */
    /**
     * Total weapon damage = sum of every BaseDamage type across EVERY attack var (not just
     * the first swing). Powers the dashboard "Damage" column and the instant "@wdmg.All"
     * filter fast-path. Delegates to the shared [me.itemforge.util.InteractionVarsBson] so
     * the column, the filter, and the batch view all agree.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun extractPrimaryDamage(
        item: Item,
        codecScanner: me.itemforge.scanner.CodecScanner,
        extraInfo: com.hypixel.hytale.codec.ExtraInfo
    ): Double? = me.itemforge.util.InteractionVarsBson.sumBaseDamage(
        codecScanner, item, me.itemforge.util.InteractionVarsBson.ALL_TYPES
    )

    // ── Surgical Cache Update ──────────────────────────────────────────

    /**
     * Updates a single item's entry in the cached dashboard payload.
     *
     * Called from [EditorBridge] after async save/reset/resetField completes —
     * by that point, [OverrideEngine.applyAndSync] or [revertItem] has already
     * updated the item in the asset map, so re-extracting stats yields current values.
     *
     * Creates a NEW [DashboardPayload] with the updated item and swaps the
     * [cachedPayload] volatile reference — thread-safe atomic replacement.
     *
     * ## Threading
     *
     * Safe to call from any thread. Stat extraction uses `ExtraInfo.THREAD_LOCAL`
     * which initializes per-thread with defaults (version=MAX_VALUE). This is the
     * same guarantee that allows [EditorBridge] to encode items on V8 thread.
     *
     * ## Why not just invalidateCache()?
     *
     * `invalidateCache()` marks the cache dirty but defers rebuild to the next
     * full dashboard open (game thread). If the admin navigates back before that,
     * [navigateBack] pushes stale data via [getDashboardData]. This method ensures
     * the cache reflects the edit BEFORE navigate-back.
     *
     * @param itemId The item that was just saved/reset
     */
    fun updateCachedItem(itemId: String) {
        // This item's values changed — drop the advanced-filter cache so filters rescan.
        fieldValueCache.clear()
        val currentPayload = cachedPayload ?: return
        val item = Item.getAssetMap().getAsset(itemId)
        if (item == null) {
            logger.atWarning().log("updateCachedItem: item '%s' not found in asset map", itemId)
            return
        }

        try {
            val overrideStore = plugin.configManager.itemOverrides
            val hasOverride = overrideStore.hasOverride(itemId)
            val overrideCount = if (hasOverride) {
                overrideStore.getOverriddenFieldIds(itemId).size
            } else 0

            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            val damage = extractPrimaryDamage(item, plugin.codecScanner, extraInfo)
            val armorStats = extractArmorStats(item)

            val updatedItem = DashboardItem(
                id = itemId,
                name = ItemNameResolver.resolve(item),
                type = plugin.tagCache.getItemType(item) ?: "Other",
                mod = plugin.modSourceTracker.getModName(itemId),
                hasOverride = hasOverride,
                overrideCount = overrideCount,
                durability = extractIntProperty(item, "MaxDurability"),
                health = armorStats.health,
                damage = damage,
                speed = extractToolSpeed(item),
                defense = armorStats.defense,
                level = extractIntProperty(item, "ItemLevel"),
                quality = extractStringProperty(item, "Quality"),
                stamina = armorStats.stamina,
                fireRes = armorStats.fireRes,
                slot = armorStats.slot,
                maxStack = extractIntProperty(item, "MaxStack")
            )

            // Atomic swap: build new payload with the updated item, replace volatile ref.
            // This is the surgical per-item path (editor save). A full off-tick re-warm still
            // covers changes from other sources (asset reload) via invalidateCache → requestWarm.
            val updatedItems = currentPayload.items.map {
                if (it.id == itemId) updatedItem else it
            }
            cachedPayload = currentPayload.copy(
                items = updatedItems,
                overrideCount = updatedItems.count { it.hasOverride }
            )

            logger.atInfo().log(
                "Dashboard cache: updated '%s' (hasOverride=%b, fields=%d)",
                itemId, hasOverride, overrideCount
            )
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log(
                "updateCachedItem failed for '%s' — navigate-back will show stale data", itemId
            )
        }
    }

    // ── Bridge Methods (callable from JS) ───────────────────────────────

    /**
     * Returns the cached dashboard payload as JSON.
     * Called from Dashboard.vue for initial data (if setData wasn't used).
     */
    fun getDashboardData(): String {
        return gson.toJson(cachedPayload ?: DashboardPayload(
            items = emptyList(),
            typeOptions = emptyList(),
            modOptions = emptyList(),
            overrideCount = 0,
            totalCount = 0
        ))
    }

    /**
     * Navigates from Dashboard to Editor for a specific item.
     *
     * Uses [PlayerUi.navigate] to swap the Vue component WITHOUT unmounting
     * the app — preserves the V8 context and avoids page dismiss/reopen.
     *
     * Flow:
     * 1. Store current dashboard state (filters, sort) server-side
     * 2. Build editor payload for the target item (on V8 — uses field cache)
     * 3. Push editor data via setData (buffered for new component)
     * 4. Call navigate() to swap to Editor component
     *
     * @param playerId The player's UUID as string
     * @param itemId The item to edit
     * @param stateJson JSON string of current dashboard filter/sort state
     * @return JSON with editor payload data, or error
     */
    /**
     * Navigates from Dashboard to Editor for a specific item.
     *
     * Parameters are nullable to prevent Javet NPE during argument conversion:
     * JavaScript undefined/null → Kotlin non-nullable String triggers
     * KotlinNullPointerException BEFORE the method body runs, escaping any
     * try/catch inside the method. Nullable params + explicit null-checks
     * ensure ALL errors are caught and logged.
     */
    fun navigateToEditor(playerId: String?, itemId: String?, stateJson: String?): String {
        return try {
            plugin.v8Watchdog.recordBridgeCall("navigateToEditor", playerId ?: "null", itemId ?: "null")

            if (playerId.isNullOrBlank() || itemId.isNullOrBlank()) {
                logger.atWarning().log(
                    "navigateToEditor: null/blank args — playerId='%s', itemId='%s'",
                    playerId, itemId
                )
                return gson.toJson(mapOf("success" to false, "error" to "Missing playerId or itemId"))
            }

            // 1. Store dashboard state for navigate-back
            val state = try {
                gson.fromJson(stateJson ?: "{}", DashboardState::class.java)
            } catch (_: Exception) {
                DashboardState()
            }
            playerState[playerId] = state

            // 2. Build editor payload (uses cached field definitions).
            //    Pass the player UUID so per-tab edit permissions are embedded
            //    in the payload — same as the openEditor path.
            val editorBridge = plugin.vuetaleIntegration.bridge
            if (editorBridge == null) {
                logger.atWarning().log("navigateToEditor: EditorBridge is null")
                return gson.toJson(mapOf("success" to false, "error" to "Editor not initialized"))
            }
            val uuid = java.util.UUID.fromString(playerId)

            // 3. Build the single-item editor payload OFF the V8 thread. This bridge method runs on
            //    V8 and holds the world thread hostage, so a ~17ms+ codec scan here directly delays
            //    the tick. When the scan completes we push the data THEN navigate, so the Editor
            //    mounts with data already buffered (one render, no empty→full crash surface). The
            //    dashboard stays visible until navigate fires (~17ms later) — no freeze, no flicker.
            editorBridge.buildEditorPayloadAsync(itemId, uuid) { payloadJson ->
                val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid)
                if (ui == null) {
                    logger.atWarning().log("navigateToEditor: PlayerUi gone for %s", playerId)
                    return@buildEditorPayloadAsync
                }
                ui.setData("editor", payloadJson)
                ui.navigate("@itemforge/pages/Editor")
                editorBridge.onEditorOpened(playerId)
            }

            // Return immediately — V8 is freed; navigate happens off-thread when the scan completes.
            gson.toJson(mapOf("success" to true))
        } catch (e: Throwable) {
            // Catch Throwable (not just Exception) — catches Error, NPE, everything
            logger.atSevere().withCause(e).log(
                "navigateToEditor FAILED for playerId='%s', itemId='%s': %s",
                playerId, itemId, e.javaClass.name
            )
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: e.javaClass.name)))
        }
    }

    /**
     * Navigates from Editor back to Dashboard.
     *
     * Restores the preserved dashboard state (filters, sort, view mode)
     * and swaps back to the Dashboard component via navigate().
     *
     * @param playerId The player's UUID as string
     * @return JSON {success: true}
     */
    fun navigateBack(playerId: String): String {
        plugin.v8Watchdog.recordBridgeCall("navigateBack", playerId)
        return try {
            val uuid = java.util.UUID.fromString(playerId)
            val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid)
            if (ui != null) {
                // Push preserved dashboard state before navigating
                val state = playerState[playerId]
                if (state != null) {
                    ui.setData("dashboardState", gson.toJson(state))
                }
                // Push cached dashboard payload (may have been invalidated + rebuilt)
                ui.setData("dashboard", getDashboardData())
                // Re-push the warmed field catalog (Kotlin-side read; never a synchronous scan).
                ui.setData("fieldCatalog", getCatalogJson())
                // Navigate back to Dashboard component
                ui.navigate("@itemforge/pages/Dashboard")
            }
            // Clear editor field cache (no longer editing)
            plugin.vuetaleIntegration.bridge!!.onEditorClosed(playerId)

            gson.toJson(mapOf("success" to true))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("navigateBack failed for '%s'", playerId)
            gson.toJson(mapOf("success" to false, "error" to e.message))
        }
    }

    // ── Native ItemGrid Bridge Method (Creative-style grid) ─────────────
    //
    // Threading: refreshGrid runs on V8 (inside handleDataEvent → hostage world thread).
    // It does only cheap work (parse + schedule) and returns immediately; the actual slot
    // build + sendUpdate are deferred to gridExecutor (off V8/world), so the world thread is
    // never held hostage during a packet send (Bug #8). All params nullable to survive
    // Javet's null→non-null conversion NPE.

    /**
     * Populates the dashboard's native `<ItemGrid id="ifgrid">` with [idsJson] (a JSON
     * string array of item ids, in display order). Called from Dashboard.vue on mount and
     * whenever the filtered/sorted set changes, plus after any deliberate structural event
     * (view switch, overlay close, toast dismiss) that would wipe the injected slots.
     *
     * Debounced [GRID_PUSH_DELAY_MS]: coalesces filter bursts and lands the push after any
     * pending Vue structural re-render. Returns immediately with `{success, count, capped}`.
     *
     * @param playerId player UUID string
     * @param idsJson  JSON array of item ids (display order); null/blank clears the grid
     */
    fun refreshGrid(playerId: String?, idsJson: String?): String {
        plugin.v8Watchdog.recordBridgeCall("refreshGrid", playerId ?: "null")
        return try {
            if (playerId.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing playerId"))

            val allIds = if (idsJson.isNullOrBlank()) emptyList() else parseStringList(idsJson)
            // Cap to keep one Set command well under the ~4MB per-command data limit and the
            // client's per-frame slot budget. Search/filters reach items beyond the cap; the
            // footer already prompts narrowing the search.
            val capped = if (allIds.size > NATIVE_GRID_CAP) allIds.subList(0, NATIVE_GRID_CAP).toList() else allIds
            val uuid = java.util.UUID.fromString(playerId)

            // Coalesce: cancel any push still pending for this player, reschedule.
            pendingGridPush.remove(playerId)?.cancel(false)
            val future = gridExecutor.schedule({
                try {
                    buildAndPushGrid(uuid, capped)
                } catch (e: Throwable) {
                    // Throwable: an unloaded-asset ItemStack ctor or codec edge must never tear
                    // down the grid daemon thread.
                    logger.atWarning().withCause(e).log("refreshGrid: push failed for %s", playerId)
                } finally {
                    pendingGridPush.remove(playerId)
                }
            }, GRID_PUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            pendingGridPush[playerId] = future

            gson.toJson(mapOf(
                "success" to true,
                "count" to capped.size,
                "capped" to (allIds.size > NATIVE_GRID_CAP)
            ))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("refreshGrid failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "refresh failed")))
        }
    }

    /**
     * Builds native [ItemGridSlot]s for [ids] (display order) and pushes them into the
     * player's live `#ifgrid` element. Runs on [gridExecutor] (off V8/world).
     *
     * A bad/unloaded id yields an EMPTY placeholder slot rather than being skipped, so slot
     * indices stay aligned with the id list the client clicks into (the Vue side maps a
     * clicked SlotIndex back through the SAME ordered id list). Reading the asset map and
     * constructing ItemStacks off-thread is safe — these are read-only asset lookups (same
     * guarantee used by updateCachedItem / fieldValues).
     */
    private fun buildAndPushGrid(uuid: java.util.UUID, ids: List<String>) {
        val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid) ?: return
        val page = ui.page ?: return
        val assetMap = Item.getAssetMap()
        var iconlessPlaceholders = 0
        val slots = Array(ids.size) { i ->
            val id = ids[i]
            try {
                val asset = assetMap.getAsset(id)
                // ROOT FIX for the client crash: the native ItemGrid makes the CLIENT resolve
                // each slot's icon from the item. An item with NO resolvable icon (no Icon field
                // and no baked PNG) null-derefs the client's slot layout pass and crashes the
                // ENTIRE session — a single bad item near the scroll region takes everything
                // down (verified in-game 2026-05-30, crash near Weapon_Wand_Tribal). 99.2% of
                // items declare an explicit, validator-checked Icon (CommonAssetValidator.
                // ICON_ITEM); only those are guaranteed client-renderable, so we gate on it.
                //
                // Icon-less stragglers (and any id whose ItemStack ctor throws) become a SAFE
                // empty placeholder: no ItemStack → the client renders a bare slot, never
                // resolves a missing icon, and physically cannot NRE. The placeholder keeps the
                // slot index aligned with the Vue-side id list so click mapping never drifts,
                // and these items remain fully visible/editable via the table view + search.
                if (asset != null && asset.icon != null) {
                    com.hypixel.hytale.server.core.ui.ItemGridSlot(
                        com.hypixel.hytale.server.core.inventory.ItemStack(id, 1)
                    ).apply { setActivatable(true) }
                } else {
                    iconlessPlaceholders++
                    com.hypixel.hytale.server.core.ui.ItemGridSlot()
                        .apply { setActivatable(true); setSkipItemQualityBackground(true) }
                }
            } catch (_: Throwable) {
                // ItemStack ctor throws on qty<=0 / id=="Empty" / unresolved asset — keep the
                // index slot as an empty placeholder so click mapping never drifts.
                iconlessPlaceholders++
                com.hypixel.hytale.server.core.ui.ItemGridSlot()
                    .apply { setSkipItemQualityBackground(true) }
            }
        }
        if (iconlessPlaceholders > 0) {
            logger.atInfo().log(
                "refreshGrid: %d of %d grid slots are safe placeholders (icon-less items, shown blank to avoid the client-crash null-deref)",
                iconlessPlaceholders, ids.size
            )
        }
        val pushed = page.pushItemGridSlots(GRID_CUSTOM_ID, slots)
        if (!pushed) {
            // Grid element not in the current render tree (e.g. table view active, or the page
            // is mid-rebuild). Harmless — the next syncGrid call re-pushes.
            logger.atFine().log("refreshGrid: '#%s' not found for %s (grid view inactive?)", GRID_CUSTOM_ID, uuid)
        }
    }

    // ── Batch Bridge Methods (callable from JS) ────────────────
    //
    // Threading model (see batchExecutor doc + EditorBridge.executeSave):
    //   • PREVIEW methods are pure reads (codec encode + arithmetic, NO loadAssets) →
    //     safe to run synchronously on the V8 thread and return JSON.
    //   • APPLY/RESET/SCALE/UNDO call loadAssets (packet broadcast) → MUST be
    //     fire-and-forget on batchExecutor (never block/return their result on V8, or
    //     the hostage world thread deadlocks — Bug #8). They return {started:true};
    //     the UI drives the undo toast from the preview data it already has.
    // All params are nullable String to survive Javet's null→non-null conversion NPE.

    // ── Field Catalog (unified · dynamic · complete) ────────────────────────
    //
    // ONE authoritative list of every modifiable field, grouped by category and
    // typed — consumed by BOTH the dashboard select-filter and the batch-stats
    // edit overlay. Built dynamically from CodecScanner (the SAME source the editor
    // edits from), so anything editable is filterable. Nothing is hardcoded.

    /**
     * One catalog entry.
     *
     * [kind] tells the UI how to filter the field ("numeric" → range, "boolean" →
     * true/false, "enum" → one-of [options], "text" → substring). [batchEditable]
     * gates the field for the NUMERIC batch-edit overlay — false for filter-only
     * fields like normalized weapon damage (the batch engine can't yet write those)
     * and for Path-B fields.
     */
    private data class CatalogEntry(
        val id: String,
        val label: String,
        val category: String,
        val kind: String,
        val options: List<String>?,
        val batchEditable: Boolean
    )

    /**
     * Returns the field catalog as JSON `{ success, fields:[{id,label,category,kind,options?,
     * batchEditable}] }`.
     *
     * GLOBAL scope ([itemIdsJson] null/blank): served from the warmed [cachedGlobalCatalogJson]
     * built off-tick by [runWarm]/[buildCatalogJson]. This is NEVER built synchronously here — the
     * old per-item global scan was the ~21s tick freeze. Until the first warm lands it returns an
     * empty (valid) catalog; the dashboard shows a loading state and is back-filled by
     * [drainPendingOpens].
     *
     * SELECTION scope (batch overlay): scans exactly the chosen items (bounded by
     * [CATALOG_SELECTION_SCAN_CAP]) — user-initiated, off the open hot path.
     */
    fun fieldCatalog(itemIdsJson: String?): String {
        plugin.v8Watchdog.recordBridgeCall("fieldCatalog", if (itemIdsJson.isNullOrBlank()) "global" else "selection")
        // Global → warmed cache only (never a synchronous scan).
        if (itemIdsJson.isNullOrBlank()) return getCatalogJson()
        return try {
            val catalog = LinkedHashMap<String, CatalogEntry>()
            var scanned = 0
            for (id in parseStringList(itemIdsJson)) {
                if (scanned >= CATALOG_SELECTION_SCAN_CAP) break
                val item = Item.getAssetMap().getAsset(id) ?: continue
                scanned++
                accumulateCatalog(catalog, item)
            }
            logger.atInfo().log("fieldCatalog(selection): %d fields from %d items", catalog.size, scanned)
            gson.toJson(mapOf("success" to true, "fields" to catalogFieldsJsonList(catalog)))
        } catch (e: Throwable) {
            // Throwable (not Exception): never let a StackOverflowError from a bad item escape.
            logger.atWarning().withCause(e).log("fieldCatalog(selection) failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "catalog scan failed")))
        }
    }

    /**
     * Adds one item's editable fields (+ normalized weapon damage) into [catalog].
     * Deduplicates by field id, so calling it across many representatives yields the
     * union of every field shape seen.
     */
    private fun accumulateCatalog(catalog: LinkedHashMap<String, CatalogEntry>, item: Item) {
      try {
        for (f in plugin.codecScanner.scan(item)) {
            // Per-item var-named weapon damage (InteractionVars.Swing_Left_…) is noise in a shared
            // list — catalogEntryFromField drops it; normalized "@wdmg.<Type>" entries added below.
            if (catalog.containsKey(f.id)) continue
            catalogEntryFromField(f)?.let { catalog[f.id] = it }
        }
        // Normalized weapon damage: the primary interaction's BaseDamage.<Type>, one
        // uniform field per damage type across ALL weapons (filter-only — batch deferred),
        // plus a summed "Total". This exposes weapon damage to filters without leaking
        // the per-item interaction-var names.
        val dmgTypes = me.itemforge.util.InteractionVarsBson.allDamageTypes(plugin.codecScanner, item)
        if (dmgTypes.isNotEmpty()) {
            // "All Damage": filters by total weapon output AND batch-scales every damage type
            // on every attack at once. Both it and the per-type fields are batch-editable via
            // the multi-attack clone-and-modify path (BatchEngine.applyWeaponDamageBatch).
            val allId = "@wdmg.${me.itemforge.util.InteractionVarsBson.ALL_TYPES}"
            if (!catalog.containsKey(allId)) {
                catalog[allId] = CatalogEntry(allId, "All Damage", CAT_WEAPON_DAMAGE, "numeric", null, true)
            }
            for (type in dmgTypes) {
                val id = "@wdmg.$type"
                if (catalog.containsKey(id)) continue
                catalog[id] = CatalogEntry(id, type, CAT_WEAPON_DAMAGE, "numeric", null, true)
            }
        }
      } catch (t: Throwable) {
        // A pathologically deep / recursive contained-asset chain (Interactions / interaction
        // Next-chain) can StackOverflow inside codecScanner.scan or the codec encode. That is
        // an Error, not an Exception, so it would otherwise tear down the world thread. Skip
        // this one item's fields and keep building the catalog from the rest.
        logger.atWarning().log("fieldCatalog: skipped '%s' (scan overflowed: %s)", item.id, t.javaClass.simpleName)
      }
    }

    /**
     * Returns `{ itemId: value }` for a single numeric field across ALL items, so
     * the client can filter by that field instantly (operator/threshold applied
     * client-side after this one-time fetch). Cached per field; the cache is
     * cleared whenever overrides change ([invalidateCache] / [updateCachedItem]).
     *
     * Runs synchronously on V8 (~one targeted encode per item). Returns
     * `{ success, values: { id: number } }`.
     */
    fun fieldValues(fieldId: String?): String {
        plugin.v8Watchdog.recordBridgeCall("fieldValues", fieldId ?: "null")
        return try {
            if (fieldId.isNullOrBlank()) return gson.toJson(mapOf("success" to false, "error" to "Missing field"))

            val cached = fieldValueCache[fieldId]
            if (cached != null) return gson.toJson(mapOf("success" to true, "values" to cached))

            val values = HashMap<String, Any>()
            for ((id, item) in Item.getAssetMap().assetMap) {
                val v = extractTypedFieldValue(item, fieldId) ?: continue
                values[id] = v
            }
            fieldValueCache[fieldId] = values
            gson.toJson(mapOf("success" to true, "values" to values))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("fieldValues failed for '%s'", fieldId)
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "scan failed")))
        }
    }

    /**
     * Typed extractor for an arbitrary catalog field ID. Returns the value as the
     * natural Kotlin type so the client can filter numerics by range, booleans by
     * true/false, and enums/strings by equality:
     * - `Double`  — top-level primitives, component primitives, modifier-array amounts,
     *               and the normalized "@wdmg.<Type>" / "@wdmg.Total" weapon-damage paths
     * - `Boolean` — boolean primitives (Consumable, DropOnDeath, …)
     * - `String`  — enum / free-text primitives (Quality, …)
     *
     * Targeted single-component encode — far cheaper than a full scan per item.
     * Returns null if the field is absent on this item.
     */
    private fun extractTypedFieldValue(item: Item, fieldId: String): Any? {
        // Normalized weapon damage — summed across EVERY attack var (so multi-attack
        // weapons report full output). "@wdmg.All" sums all types; "@wdmg.<Type>" one type.
        if (fieldId.startsWith("@wdmg.")) {
            return me.itemforge.util.InteractionVarsBson.sumBaseDamage(
                plugin.codecScanner, item, fieldId.substringAfter("@wdmg.")
            )
        }

        val parts = fieldId.split(".")
        if (parts.isEmpty()) return null
        return try {
            val extraInfo = com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get()
            val fieldList = plugin.codecScanner.itemCodec.entries[parts[0]] ?: return null
            val field = fieldList.last()
            val doc = BsonDocument()
            @Suppress("UNCHECKED_CAST")
            (field as com.hypixel.hytale.codec.builder.BuilderField<Any, Any>).encode(doc, item, extraInfo)

            var cur: org.bson.BsonValue = doc[parts[0]] ?: return null
            for (i in 1 until parts.size) {
                if (!cur.isDocument) return null
                cur = cur.asDocument()[parts[i]] ?: return null
            }
            when {
                cur.isInt32 -> cur.asInt32().value.toDouble()
                cur.isInt64 -> cur.asInt64().value.toDouble()
                cur.isDouble -> cur.asDouble().value
                cur.isBoolean -> cur.asBoolean().value
                cur.isString -> cur.asString().value
                // Modifier arrays. DamageResistance is shown ×100 in the dashboard
                // columns (0.09 → 9), so filter on the same scale or "Ice >= 5" breaks.
                cur.isArray ->
                    if (parts.contains("DamageResistance")) resistanceAmount(cur.asArray())
                    else (BsonHelper.extractModifierAmount(cur.asArray()) as? Number)?.toDouble()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    // Weapon-damage navigation (multi-attack sum, damage-type discovery, clone-and-modify
    // override) lives in me.itemforge.util.InteractionVarsBson — shared with the editor and
    // the batch engine so all three views of weapon damage stay consistent.

    /** First modifier's Amount, ×100 when Multiplicative — matches the dashboard's
     *  DamageResistance column display (extractResistanceValue). */
    private fun resistanceAmount(arr: org.bson.BsonArray): Double? {
        if (arr.isEmpty()) return null
        val first = arr[0]
        if (!first.isDocument) return null
        val d = first.asDocument()
        val amountVal = d["Amount"] ?: return null
        val amount = when {
            amountVal.isDouble -> amountVal.asDouble().value
            amountVal.isInt32 -> amountVal.asInt32().value.toDouble()
            else -> return null
        }
        val calc = d["CalculationType"]
        val multiplicative = calc != null && calc.isString && calc.asString().value == "Multiplicative"
        return if (multiplicative) amount * 100.0 else amount
    }

    // The batch-stats field list is served by [fieldCatalog] (selection scope), and
    // the overlay keeps only entries with batchEditable=true. One catalog, two views.

    /**
     * Dry-run preview of a stat batch op. Synchronous (pure read). Returns
     * `{ success, rows:[{itemId,itemName,oldValue,newValue,skipped,reason}], appliedCount, skippedCount }`.
     */
    fun batchPreview(playerId: String?, itemIdsJson: String?, fieldId: String?, operation: String?, operandStr: String?): String {
        plugin.v8Watchdog.recordBridgeCall("batchPreview", playerId ?: "null", fieldId ?: "null")
        return try {
            if (itemIdsJson.isNullOrBlank() || fieldId.isNullOrBlank() || operation.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing arguments"))
            val itemIds = parseStringList(itemIdsJson)
            if (itemIds.isEmpty()) return gson.toJson(mapOf("success" to false, "error" to "No items selected"))
            val op = parseOperation(operation)
                ?: return gson.toJson(mapOf("success" to false, "error" to "Unknown operation: $operation"))
            val operand = operandStr?.toDoubleOrNull() ?: 0.0

            val rows = plugin.batchEngine.preview(itemIds, fieldId, op, operand)
            gson.toJson(mapOf(
                "success" to true,
                "rows" to rows,
                "appliedCount" to rows.count { !it.skipped },
                "skippedCount" to rows.count { it.skipped }
            ))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("batchPreview failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "preview failed")))
        }
    }

    /**
     * Applies a stat batch op to all items (fire-and-forget). Persists each override,
     * stores a 15s undo buffer, refreshes held items. Returns `{success, started}`.
     */
    fun batchApply(playerId: String?, itemIdsJson: String?, fieldId: String?, operation: String?, operandStr: String?): String {
        plugin.v8Watchdog.recordBridgeCall("batchApply", playerId ?: "null", fieldId ?: "null")
        return try {
            if (playerId.isNullOrBlank() || itemIdsJson.isNullOrBlank() || fieldId.isNullOrBlank() || operation.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing arguments"))
            val itemIds = parseStringList(itemIdsJson)
            if (itemIds.isEmpty()) return gson.toJson(mapOf("success" to false, "error" to "No items selected"))
            val op = parseOperation(operation)
                ?: return gson.toJson(mapOf("success" to false, "error" to "Unknown operation: $operation"))
            val operand = operandStr?.toDoubleOrNull()
                ?: return gson.toJson(mapOf("success" to false, "error" to "Invalid value"))

            val isWdmg = fieldId.startsWith("@wdmg.")
            java.util.concurrent.CompletableFuture.supplyAsync({
                val overrideStore = plugin.configManager.itemOverrides
                // Capture pre-batch undo state BEFORE any config mutation.
                // Normal fields: which items were unmodified (undo fully removes them).
                // Weapon damage: each item's pre-batch override snapshot (deep copy) — the
                // only way to restore per-attack values a single number can't reconstruct.
                val freshItemIds = if (!isWdmg) itemIds.filterNot { overrideStore.hasOverride(it) }.toSet() else emptySet()
                val wdmgSnapshots = if (isWdmg) itemIds.associateWith { overrideStore.getOverride(it) } else emptyMap()

                val applyResult = plugin.batchEngine.apply(itemIds, fieldId, op, operand)

                val changedIds = mutableListOf<String>()
                for (r in applyResult.result.items) {
                    if (r.success && r.overrideBson != null) {
                        val json = com.google.gson.JsonParser.parseString(r.overrideBson.toJson()).asJsonObject
                        overrideStore.saveOverride(r.itemId, json)
                        updateCachedItem(r.itemId)
                        changedIds.add(r.itemId)
                    }
                }

                // New batch supersedes any pending undo for this player (all three kinds).
                undoBuffers.remove(playerId)
                recipeUndoBuffers.remove(playerId)
                wdmgUndoBuffers.remove(playerId)
                if (isWdmg) {
                    if (changedIds.isNotEmpty()) {
                        wdmgUndoBuffers[playerId] = WdmgUndoContext(
                            wdmgSnapshots.filterKeys { it in changedIds }, fieldId, java.time.Instant.now()
                        )
                    }
                } else {
                    applyResult.undoBuffer?.let { undoBuffers[playerId] = StatUndoContext(it, freshItemIds) }
                }

                plugin.auditLogger.logBatch(playerId, applyResult.result.successCount, fieldId, op.name, operand.toString())
                logger.atInfo().log("Batch apply by %s: %d applied, %d skipped on '%s'",
                    playerId, applyResult.result.successCount, applyResult.result.failCount, fieldId)
                changedIds
            }, batchExecutor).thenAcceptAsync({ ids ->
                // Refresh held ItemStacks AFTER commit completes (locks released) — never inline (Bug #6).
                if (ids.isNotEmpty()) {
                    try { plugin.inventoryRefresher.refreshItemStacks(ids.toSet()) }
                    catch (e: Exception) { logger.atWarning().withCause(e).log("batch apply: inventory refresh failed") }
                }
            }, batchExecutor).exceptionally { e ->
                logger.atSevere().withCause(e).log("batchApply async failed for %s", playerId); null
            }

            gson.toJson(mapOf("success" to true, "started" to true))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("batchApply failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "apply failed")))
        }
    }

    /**
     * Removes ALL overrides (item + recipe) for the selected items and reverts them
     * live in a single sync (fire-and-forget). Returns `{success, started}`.
     */
    fun batchReset(playerId: String?, itemIdsJson: String?): String {
        plugin.v8Watchdog.recordBridgeCall("batchReset", playerId ?: "null")
        return try {
            if (playerId.isNullOrBlank() || itemIdsJson.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing arguments"))
            val itemIds = parseStringList(itemIdsJson)
            if (itemIds.isEmpty()) return gson.toJson(mapOf("success" to false, "error" to "No items selected"))

            java.util.concurrent.CompletableFuture.supplyAsync({
                val itemStore = plugin.configManager.itemOverrides
                val recipeStore = plugin.configManager.recipeOverrides
                val resetIds = mutableListOf<String>()
                // 1. Remove config overrides FIRST so the subsequent revert commit finds
                //    nothing to re-apply (no AssetLoadListener feedback loop).
                for (id in itemIds) {
                    val had = itemStore.hasOverride(id)
                    itemStore.removeOverride(id)
                    val rid = resolveRecipeId(id)
                    if (rid != null && recipeStore.hasOverride(rid)) {
                        recipeStore.removeOverride(rid)
                        plugin.recipeOverrideEngine.revertRecipe(rid)
                    }
                    if (had) resetIds.add(id)
                }
                // 2. Revert all items live in a single AssetCommitter sync.
                plugin.overrideEngine.batchRevertItems(itemIds)
                itemIds.forEach { updateCachedItem(it) }
                // 3. Reset invalidates any pending undo (all three kinds).
                undoBuffers.remove(playerId)
                recipeUndoBuffers.remove(playerId)
                wdmgUndoBuffers.remove(playerId)
                plugin.auditLogger.logBatch(playerId, resetIds.size, "(all fields)", "RESET", "")
                logger.atInfo().log("Batch reset by %s: %d items cleared", playerId, resetIds.size)
                itemIds.toList()
            }, batchExecutor).thenAcceptAsync({ ids ->
                if (ids.isNotEmpty()) {
                    try { plugin.inventoryRefresher.refreshItemStacks(ids.toSet()) }
                    catch (e: Exception) { logger.atWarning().withCause(e).log("batch reset: inventory refresh failed") }
                }
            }, batchExecutor).exceptionally { e ->
                logger.atSevere().withCause(e).log("batchReset async failed for %s", playerId); null
            }

            gson.toJson(mapOf("success" to true, "started" to true))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("batchReset failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "reset failed")))
        }
    }

    /**
     * Dry-run preview of a recipe scale. Synchronous (codec encode only, no decode/commit).
     * `target` = "INPUTS" | "TIME". Non-craftable items appear as skipped rows.
     */
    fun batchRecipePreview(playerId: String?, itemIdsJson: String?, scalePercentStr: String?, target: String?): String {
        plugin.v8Watchdog.recordBridgeCall("batchRecipePreview", playerId ?: "null", target ?: "null")
        return try {
            if (itemIdsJson.isNullOrBlank() || target.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing arguments"))
            val itemIds = parseStringList(itemIdsJson)
            if (itemIds.isEmpty()) return gson.toJson(mapOf("success" to false, "error" to "No items selected"))
            val tgt = parseTarget(target)
                ?: return gson.toJson(mapOf("success" to false, "error" to "Unknown target: $target"))
            val scalePercent = scalePercentStr?.toDoubleOrNull() ?: 100.0

            val craftable = mutableListOf<Pair<String, String>>() // itemId -> recipeId
            val notCraftable = mutableListOf<String>()
            for (id in itemIds) {
                val rid = resolveRecipeId(id)
                if (rid != null) craftable.add(id to rid) else notCraftable.add(id)
            }

            val previews = plugin.batchRecipeEngine.previewScale(craftable.map { it.second }, scalePercent, tgt)
            val rows = craftable.mapIndexed { i, pair ->
                val itemId = pair.first
                val p = previews[i]
                val item = Item.getAssetMap().getAsset(itemId)
                mapOf(
                    "itemId" to itemId,
                    "itemName" to (if (item != null) ItemNameResolver.resolve(item) else itemId),
                    "skipped" to p.skipped,
                    "reason" to p.reason,
                    "changes" to p.changes
                )
            } + notCraftable.map { itemId ->
                val item = Item.getAssetMap().getAsset(itemId)
                mapOf(
                    "itemId" to itemId,
                    "itemName" to (if (item != null) ItemNameResolver.resolve(item) else itemId),
                    "skipped" to true,
                    "reason" to "Not craftable (no recipe)",
                    "changes" to emptyList<Any>()
                )
            }

            gson.toJson(mapOf(
                "success" to true,
                "rows" to rows,
                "appliedCount" to rows.count { it["skipped"] == false },
                "skippedCount" to rows.count { it["skipped"] == true }
            ))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("batchRecipePreview failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "preview failed")))
        }
    }

    /**
     * Scales recipe inputs or time across the selected items' recipes (fire-and-forget).
     * Encode+persist on V8, decode+commit async (mirrors executeSaveRecipe). 15s undo.
     */
    fun batchRecipeScale(playerId: String?, itemIdsJson: String?, scalePercentStr: String?, target: String?): String {
        plugin.v8Watchdog.recordBridgeCall("batchRecipeScale", playerId ?: "null", target ?: "null")
        return try {
            if (playerId.isNullOrBlank() || itemIdsJson.isNullOrBlank() || target.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing arguments"))
            val itemIds = parseStringList(itemIdsJson)
            if (itemIds.isEmpty()) return gson.toJson(mapOf("success" to false, "error" to "No items selected"))
            val tgt = parseTarget(target)
                ?: return gson.toJson(mapOf("success" to false, "error" to "Unknown target: $target"))
            val scalePercent = scalePercentStr?.toDoubleOrNull()
                ?: return gson.toJson(mapOf("success" to false, "error" to "Invalid value"))

            // ── V8 phase: encode (version-gated → must be on V8) + persist override ──
            val recipeStore = plugin.configManager.recipeOverrides
            val toApply = ArrayList<Pair<String, BsonDocument>>()
            for (id in itemIds) {
                val rid = resolveRecipeId(id) ?: continue
                val scaled = plugin.batchRecipeEngine.scaleToBson(rid, scalePercent, tgt) ?: continue
                val json = com.google.gson.JsonParser.parseString(scaled.toJson()).asJsonObject
                recipeStore.saveOverride(rid, json) // recipe overrides are full-replacement
                toApply.add(rid to scaled)
            }
            if (toApply.isEmpty())
                return gson.toJson(mapOf("success" to false, "error" to "No craftable recipes in selection"))

            val recipeIds = toApply.map { it.first }

            // ── async phase: decode + commit (loadAssets must be off V8) ──
            java.util.concurrent.CompletableFuture.runAsync({
                try {
                    var ok = 0
                    for ((rid, bson) in toApply) {
                        if (plugin.recipeOverrideEngine.applyAndSync(rid, bson)) ok++
                    }
                    undoBuffers.remove(playerId)
                    wdmgUndoBuffers.remove(playerId)
                    recipeUndoBuffers[playerId] = RecipeUndoContext(recipeIds, tgt, java.time.Instant.now())
                    plugin.auditLogger.logBatchRecipe(playerId, ok, "SCALE ${tgt.name}", "$scalePercent%")
                    invalidateCache()
                    logger.atInfo().log("Batch recipe scale by %s: %d recipes (%s %s%%)", playerId, ok, tgt.name, scalePercent)
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("batchRecipeScale async failed for %s", playerId)
                }
            }, batchExecutor)

            gson.toJson(mapOf("success" to true, "started" to true, "recipeCount" to recipeIds.size))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("batchRecipeScale failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "scale failed")))
        }
    }

    /**
     * Undoes the player's last batch (stat or recipe), if within the 15s window.
     * Fire-and-forget revert. Returns `{success, started}` or `{success:false, expired}`.
     */
    fun batchUndo(playerId: String?): String {
        plugin.v8Watchdog.recordBridgeCall("batchUndo", playerId ?: "null")
        return try {
            if (playerId.isNullOrBlank())
                return gson.toJson(mapOf("success" to false, "error" to "Missing playerId"))
            val now = java.time.Instant.now()

            val statCtx = undoBuffers[playerId]
            if (statCtx != null) {
                undoBuffers.remove(playerId)
                if (now.isAfter(statCtx.buffer.timestamp.plusSeconds(UNDO_WINDOW_SECONDS)))
                    return gson.toJson(mapOf("success" to false, "expired" to true, "error" to "Undo window expired"))

                java.util.concurrent.CompletableFuture.supplyAsync({
                    // revert() does the live restore AND returns the override to re-persist
                    // per item (simple dot-path, or the full InteractionVars clone for weapon
                    // damage) — so persistence works uniformly for every field kind.
                    val revertResults = plugin.batchEngine.revert(statCtx.buffer)
                    val itemStore = plugin.configManager.itemOverrides
                    for (r in revertResults) {
                        if (!r.success) continue
                        if (r.itemId in statCtx.freshItemIds) {
                            itemStore.removeOverride(r.itemId) // had nothing before → clean removal
                        } else if (r.overrideBson != null) {
                            itemStore.saveOverride(
                                r.itemId,
                                com.google.gson.JsonParser.parseString(r.overrideBson.toJson()).asJsonObject
                            )
                        }
                        updateCachedItem(r.itemId)
                    }
                    plugin.auditLogger.logBatchUndo(playerId, statCtx.buffer.itemIds.size, statCtx.buffer.targetField)
                    statCtx.buffer.itemIds
                }, batchExecutor).thenAcceptAsync({ ids ->
                    if (ids.isNotEmpty()) {
                        try { plugin.inventoryRefresher.refreshItemStacks(ids.toSet()) }
                        catch (e: Exception) { logger.atWarning().withCause(e).log("batch undo: inventory refresh failed") }
                    }
                }, batchExecutor).exceptionally { e ->
                    logger.atSevere().withCause(e).log("batchUndo async failed for %s", playerId); null
                }
                return gson.toJson(mapOf("success" to true, "started" to true))
            }

            val recipeCtx = recipeUndoBuffers[playerId]
            if (recipeCtx != null) {
                recipeUndoBuffers.remove(playerId)
                if (now.isAfter(recipeCtx.timestamp.plusSeconds(UNDO_WINDOW_SECONDS)))
                    return gson.toJson(mapOf("success" to false, "expired" to true, "error" to "Undo window expired"))

                java.util.concurrent.CompletableFuture.runAsync({
                    try {
                        val recipeStore = plugin.configManager.recipeOverrides
                        for (rid in recipeCtx.recipeIds) {
                            plugin.recipeOverrideEngine.revertRecipe(rid)
                            recipeStore.removeOverride(rid)
                        }
                        plugin.auditLogger.logBatchUndo(playerId, recipeCtx.recipeIds.size, "recipe:${recipeCtx.target.name}")
                        invalidateCache()
                    } catch (e: Exception) {
                        logger.atSevere().withCause(e).log("batchUndo (recipe) async failed for %s", playerId)
                    }
                }, batchExecutor)
                return gson.toJson(mapOf("success" to true, "started" to true))
            }

            val wdmgCtx = wdmgUndoBuffers[playerId]
            if (wdmgCtx != null) {
                wdmgUndoBuffers.remove(playerId)
                if (now.isAfter(wdmgCtx.timestamp.plusSeconds(UNDO_WINDOW_SECONDS)))
                    return gson.toJson(mapOf("success" to false, "expired" to true, "error" to "Undo window expired"))

                java.util.concurrent.CompletableFuture.supplyAsync({
                    val itemStore = plugin.configManager.itemOverrides
                    // 1. Restore CONFIG first (matches batchReset): replace each item's override
                    //    with its pre-batch snapshot (remove first so saveOverride sets fresh,
                    //    not deep-merges). Ensures the live re-decode can't pick the batch
                    //    override back up on any subsequent reload.
                    for ((itemId, json) in wdmgCtx.snapshots) {
                        itemStore.removeOverride(itemId)
                        if (json != null) itemStore.saveOverride(itemId, json)
                    }
                    // 2. Live restore (one batched re-decode): original + pre-batch override.
                    val bsonSnapshots = wdmgCtx.snapshots.mapValues { (_, json) ->
                        json?.let { org.bson.BsonDocument.parse(it.toString()) }
                    }
                    plugin.overrideEngine.batchRestoreSnapshots(bsonSnapshots)
                    wdmgCtx.snapshots.keys.forEach { updateCachedItem(it) }
                    plugin.auditLogger.logBatchUndo(playerId, wdmgCtx.snapshots.size, wdmgCtx.targetField)
                    wdmgCtx.snapshots.keys.toList()
                }, batchExecutor).thenAcceptAsync({ ids ->
                    if (ids.isNotEmpty()) {
                        try { plugin.inventoryRefresher.refreshItemStacks(ids.toSet()) }
                        catch (e: Exception) { logger.atWarning().withCause(e).log("batch undo (wdmg): inventory refresh failed") }
                    }
                }, batchExecutor).exceptionally { e ->
                    logger.atSevere().withCause(e).log("batchUndo (wdmg) async failed for %s", playerId); null
                }
                return gson.toJson(mapOf("success" to true, "started" to true))
            }

            gson.toJson(mapOf("success" to false, "error" to "Nothing to undo"))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("batchUndo failed")
            gson.toJson(mapOf("success" to false, "error" to (e.message ?: "undo failed")))
        }
    }

    /**
     * Drops any stat-batch undo buffer that references [itemId]. Called by EditorBridge
     * when an item is edited directly — undoing the batch afterward would clobber that
     * manual edit (red-team R5).
     */
    fun invalidateUndoBufferForItem(itemId: String) {
        undoBuffers.entries.removeIf { it.value.buffer.preValues.containsKey(itemId) }
        wdmgUndoBuffers.entries.removeIf { it.value.snapshots.containsKey(itemId) }
    }

    // ── Batch Helpers ───────────────────────────────────────────────────

    private fun parseStringList(json: String): List<String> = try {
        gson.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun parseOperation(s: String): BatchOperation? =
        try { BatchOperation.valueOf(s.uppercase()) } catch (_: Exception) { null }

    private fun parseTarget(s: String): ScaleTarget? =
        try { ScaleTarget.valueOf(s.uppercase()) } catch (_: Exception) { null }

    /** Resolves an item to its primary crafting recipe ID, or null if not craftable. */
    private fun resolveRecipeId(itemId: String): String? {
        val item = Item.getAssetMap().getAsset(itemId) ?: return null
        return try {
            val rid = com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
                .generateIdFromItemRecipe(item, 0)
            if (com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
                    .getAssetMap().getAsset(rid) != null) rid else null
        } catch (_: Exception) { null }
    }

    /**
     * Closes the dashboard page entirely (returns to game world).
     *
     * Performs Kotlin-side app cleanup and dismisses the page on the world thread.
     * Uses the same proven pattern as EditorBridge.closePage().
     *
     * @param playerId The player's UUID as string
     */
    fun closeDashboard(playerId: String) {
        plugin.v8Watchdog.recordBridgeCall("closeDashboard", playerId)
        try {
            val uuid = java.util.UUID.fromString(playerId)

            // No longer waiting on a warm push for this player.
            pendingOpens.remove(playerId)

            // Kotlin-side app cleanup (prevent evalScriptAsync hang on Escape)
            cleanupCurrentPage(uuid)

            val refData = playerRefs[playerId]
            if (refData != null && refData.ref.isValid) {
                val gen = pageGeneration[playerId]?.get() ?: 0L
                java.util.concurrent.CompletableFuture.runAsync {
                    try {
                        Thread.sleep(50)
                        val world = refData.store.externalData.world
                        world.execute {
                            try {
                                val currentGen = pageGeneration[playerId]?.get() ?: -1L
                                if (currentGen != gen) return@execute

                                val player = refData.store.getComponent(
                                    refData.ref,
                                    com.hypixel.hytale.server.core.entity.entities.Player.getComponentType()
                                )
                                player?.pageManager?.setPage(
                                    refData.ref, refData.store,
                                    com.hypixel.hytale.protocol.packets.interface_.Page.None
                                )
                            } catch (e: Exception) {
                                logger.atWarning().withCause(e).log(
                                    "closeDashboard: page dismiss failed for %s", playerId
                                )
                            }
                        }
                    } catch (e: Exception) {
                        logger.atSevere().withCause(e).log(
                            "closeDashboard: async dispatch failed for %s", playerId
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("closeDashboard failed for '%s'", playerId)
        }
    }

    // ── Page Lifecycle ──────────────────────────────────────────────────

    /**
     * Performs Kotlin-side cleanup of the current Vuetale page.
     *
     * Same cleanup pattern as EditorBridge.closePage():
     * - isMounted = false (prevents evalScriptAsync hang on Escape)
     * - eventRegistry.closeAll() (prevents phantom bindings)
     * - unregisterHostCallbacks (cleans Javet references)
     * - onDirty = null (prevents stray dirty ticks)
     */
    private fun cleanupCurrentPage(uuid: java.util.UUID) {
        try {
            val ui = li.kelp.vuetale.app.PlayerUiManager.get(uuid) ?: return
            val page = ui.javaClass.getDeclaredField("page").let { f ->
                f.isAccessible = true
                f.get(ui)
            } ?: return
            val app = page.javaClass.getDeclaredField("app").let { f ->
                f.isAccessible = true
                f.get(page) as? li.kelp.vuetale.app.App
            } ?: return

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
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log(
                "cleanupCurrentPage failed for %s", uuid
            )
        }
    }

    /**
     * Called by [VuetaleIntegration.openDashboard] after the dashboard page opens.
     * Stores player entity references and increments page generation counter.
     */
    fun onDashboardOpened(
        playerId: String,
        playerRef: PlayerRef,
        ref: Ref<EntityStore>,
        store: Store<EntityStore>
    ) {
        playerRefs[playerId] = PlayerRefData(playerRef, ref, store)
        pageGeneration.computeIfAbsent(playerId) { java.util.concurrent.atomic.AtomicLong(0) }
            .incrementAndGet()
    }

    // ── State Queries ───────────────────────────────────────────────────

    /** Whether this player has saved dashboard state (came from dashboard). */
    fun hasPlayerState(playerId: String): Boolean = playerState.containsKey(playerId)

    /** Returns the saved dashboard state, or null. */
    fun getPlayerState(playerId: String): DashboardState? = playerState[playerId]

    /** Cleans up per-player state on disconnect. */
    fun onPlayerDisconnect(playerId: String) {
        playerState.remove(playerId)
        playerRefs.remove(playerId)
        pageGeneration.remove(playerId)
        undoBuffers.remove(playerId)
        recipeUndoBuffers.remove(playerId)
        wdmgUndoBuffers.remove(playerId)
        pendingGridPush.remove(playerId)?.cancel(false)
        pendingOpens.remove(playerId)
    }

    // ── Inner Data Class ────────────────────────────────────────────────

    /** Stored entity references for world thread dispatch during page close. */
    data class PlayerRefData(
        val playerRef: PlayerRef,
        val ref: Ref<EntityStore>,
        val store: Store<EntityStore>
    )

    companion object {
        /** Undo buffer lifetime — 15s server-side, slightly longer than the 10s client toast
         *  so a click at ~9.8s never races into an "expired" error. */
        private const val UNDO_WINDOW_SECONDS = 15L

        /** Vue `id` on the dashboard's native ItemGrid — the selector the host pushes into. */
        private const val GRID_CUSTOM_ID = "ifgrid"

        /** Max native ItemGrid slots in one push. The grid's `Slots` is set WHOLESALE in a
         *  single UICommand (no append/stream primitive — UICommandBuilder only has set/
         *  append-document), and one CustomUICommand is capped at ~4 MB. ItemGridSlot(id,1) ≈
         *  ~120 bytes serialized → ~30k is the HARD network ceiling for one push; 20k (≈2.4 MB)
         *  keeps a safe margin even with long modded item IDs. The client also holds the whole
         *  array in RAM (it virtualizes RENDERING only), so this doubles as a client-memory
         *  bound. Beyond this the only paths are search/filter narrowing (what the Creative
         *  menu does) or pagination. MUST match TS GRID_NATIVE_CAP. Tunable after profiling. */
        private const val NATIVE_GRID_CAP = 20000

        /** Debounce/settle delay for grid pushes (ms). Coalesces filter bursts and lands the
         *  push after Vue's next-tick structural re-render (which fires on the V8 tick and would
         *  otherwise wipe the injected Slots). Below human perception for a deliberate action. */
        private const val GRID_PUSH_DELAY_MS = 90L

        /** Category label for normalized weapon-damage catalog fields ("@wdmg.*"). */
        private const val CAT_WEAPON_DAMAGE = "Weapon Damage"

        /** Debounce for [requestWarm] — coalesces a burst of asset-reload events into one
         *  off-tick rebuild. Short enough to feel immediate, long enough to absorb a multi-mod
         *  reload storm. */
        private const val WARM_DEBOUNCE_MS = 250L

        // The global catalog is now built from the codec SCHEMA ([CodecScanner.discoverAllFields]),
        // not by sampling items, so the old per-(type×mod) sampling caps are gone. Selection scope
        // (batch overlay) still scans the chosen items — a sample covers their variety.
        private const val CATALOG_SELECTION_SCAN_CAP = 80
    }
}

package me.itemforge

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent
import com.hypixel.hytale.assetstore.map.DefaultAssetMap
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import me.itemforge.audit.AuditLogger
import me.itemforge.config.ConfigManager
import me.itemforge.core.AssetCommitter
import me.itemforge.core.HealthMonitor
import me.itemforge.core.InventoryRefresher
import me.itemforge.core.OriginalsCache
import me.itemforge.core.OverrideEngine
import me.itemforge.core.TranslationOverrideEngine
import me.itemforge.core.RecipeOriginalsCache
import me.itemforge.core.RecipeOverrideEngine
import me.itemforge.core.V8Watchdog
import me.itemforge.metadata.BenchRegistry
import me.itemforge.scanner.RecipeScanner
import me.itemforge.entry.AssetLoadListener
import me.itemforge.entry.ItemForgeCommand
import me.itemforge.metadata.ItemNameResolver
import me.itemforge.metadata.ModSourceTracker
import me.itemforge.metadata.TagCache
import me.itemforge.provider.ExtensionRegistry
import me.itemforge.scanner.CodecScanner
import me.itemforge.util.NativeDependencyLoader
import me.itemforge.vuetale.VuetaleIntegration
import me.itemforge.inspect.CrouchTrackingSystem
import me.itemforge.inspect.InspectModeManager
import me.itemforge.inspect.InspectSuppressor
import com.hypixel.hytale.event.EventRegistration
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent
import com.creditor.Creditor
import org.bson.BsonDocument

/**
 * ItemForge — In-game item property editor for Hytale server admins.
 *
 * Discovers all editable fields via BuilderCodec introspection, modifies values
 * through the codec's own encode/decode API, and syncs to all players via
 * AssetStore.loadAssets(). Zero reflection on game classes.
 *
 * UI via Vuetale (Vue 3 + TypeScript) — bundled as library. One JAR install for server owners.
 *
 * ## Lifecycle (verified against PluginBase.java source)
 *
 * setup() → Register event listeners (eventRegistry available from PluginBase constructor)
 * start() → Load configs, init codecs, run health check, build metadata, apply overrides, register commands
 * shutdown() → Flush pending writes, optionally revert overrides, flush audit log
 *
 * ## Evidence
 *
 * CraftingPlugin registers LoadedAssetsEvent listeners in setup() (CraftingPlugin.java:141-144).
 * Commands registered via commandRegistry (initialized in PluginBase constructor, usable from setup() onward).
 */
class ItemForgePlugin(init: JavaPluginInit) : JavaPlugin(init) {

    // ── Subsystems ──────────────────────────────────────────────────────
    // Initialized in start() after assets are loaded.
    // Event listeners registered in setup() check `started` before accessing.

    // Visibility: `internal` so EditorBridge (same module) can access subsystems.
    // Not `public` — these are implementation details, not plugin API.
    internal lateinit var configManager: ConfigManager
        private set
    internal lateinit var codecScanner: CodecScanner
        private set
    internal lateinit var originalsCache: OriginalsCache
        private set
    internal lateinit var committer: AssetCommitter
        private set
    internal lateinit var overrideEngine: OverrideEngine
        private set
    /** Global custom Name/Lore overrides (the last pre-release feature — i18n broadcast engine). */
    internal lateinit var translationOverrides: TranslationOverrideEngine
        private set
    internal lateinit var healthMonitor: HealthMonitor
        private set
    internal lateinit var modSourceTracker: ModSourceTracker
        private set
    internal lateinit var tagCache: TagCache
        private set
    internal lateinit var auditLogger: AuditLogger
        private set
    internal lateinit var inventoryRefresher: InventoryRefresher
        private set
    private lateinit var assetLoadListener: AssetLoadListener
    internal lateinit var vuetaleIntegration: VuetaleIntegration
        private set
    internal lateinit var v8Watchdog: V8Watchdog
        private set
    internal lateinit var dashboardBridge: me.itemforge.vuetale.DashboardBridge
        private set
    internal lateinit var benchRegistry: BenchRegistry
        private set
    internal lateinit var materialIndex: me.itemforge.metadata.MaterialIndex
        private set
    internal lateinit var recipeScanner: RecipeScanner
        private set
    internal lateinit var recipeOriginalsCache: RecipeOriginalsCache
        private set
    internal lateinit var recipeOverrideEngine: RecipeOverrideEngine
        private set
    internal lateinit var batchEngine: me.itemforge.core.BatchEngine
        private set
    internal lateinit var batchRecipeEngine: me.itemforge.core.BatchRecipeEngine
        private set

    /** EditorExtension registry — the public UI-extension API surface for mods. */
    internal lateinit var extensionRegistry: ExtensionRegistry
        private set

    // ── Inspect Mode ────────────────────────────────────────
    // Manager created in setup() (the crouch ticking system needs it during the
    // ECS registration phase); opener/suppressor/disconnect wired in start().
    internal lateinit var inspectModeManager: InspectModeManager
        private set
    private var inspectSuppressor: InspectSuppressor? = null
    private var inspectDisconnectRegistration: EventRegistration<*, *>? = null

    /** Whether start() completed successfully. Guards event handler access. */
    @Volatile
    private var started = false

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun setup() {
        logger.atInfo().log("ItemForge setup — registering event listeners")

        // Register LoadedAssetsEvent<Item> listener.
        // Must be in setup() to catch the initial asset load.
        // Handler checks `started` before accessing subsystems.
        // Evidence: CraftingPlugin.java:141-144 registers in setup().
        eventRegistry.register(
            LoadedAssetsEvent::class.java,
            Item::class.java,
            ::handleItemsLoaded
        )

        // Inspect Mode: create the shared state manager and register the
        // per-tick crouch cache here in setup() — ECS systems must be registered during
        // the registration phase. The manager has no dependencies so it can exist now;
        // the opener / suppressor / disconnect listener are wired in start() once
        // VuetaleIntegration exists. The crouch system no-ops until a player toggles
        // inspect mode on, so registering it before start() is harmless.
        inspectModeManager = InspectModeManager()
        entityStoreRegistry.registerSystem(CrouchTrackingSystem(inspectModeManager))

        // Combat tier: ItemForge's own always-on per-item WEAPON DAMAGE
        // applier. The engine reads a weapon's damage from the item asset, never from per-stack
        // metadata, so a per-item ("This Item") damage override only takes effect through this
        // system — it scales each hit by the weapon's stored per-DamageCause multiplier. Registered
        // in the filter-damage group (see LocalDamageSystem.getGroup). No subsystem dependencies
        // (it reads only the attacker's held-stack metadata), so registering it in the registration
        // phase is safe; it no-ops on any weapon without an ItemForge override.
        entityStoreRegistry.registerSystem(me.itemforge.local.LocalDamageSystem())

        // Attribute tier: ItemForge's own per-tick ENTITY-STAT applier.
        // The engine grants gear stats (max health/stamina/etc.) from the item asset, not per-stack
        // metadata, so a per-item ("This Item") stat bonus only takes effect through this system — it
        // maintains a StaticModifier on the wearer for each per-item bonus on their WORN armor. Marker
        // interface EntityStatsSystems.StatModifyingSystem ensures correct tick ordering. No subsystem
        // deps; no-ops on any entity without ItemForge stat overrides.
        entityStoreRegistry.registerSystem(me.itemforge.local.LocalStatSystem())

        // Creditor (bundled library) — registers the /credits command + the Credits
        // asset store against our registries. Mirrors Creditor's own Main.setup().
        // commandRegistry/assetRegistry are usable from setup() onward (PluginBase).
        // Library mode: no supporter badge (by design). See build.gradle.kts strip task.
        Creditor.setup(this)
    }

    override fun start() {
        logger.atInfo().log("ItemForge starting — initializing subsystems")

        // Creditor forward-compat start hook (currently a no-op in the library).
        // Called early to mirror Creditor's own Main.start() ordering.
        Creditor.start(this)

        try {
            // 0. Preload bundled native deps (libatomic.so.1) BEFORE Vuetale boots V8.
            // Minimal read-only hosts (e.g. PebbleHost) lack libatomic and can't install it;
            // without this, Javet's V8 native load fails with UnsatisfiedLinkError. Best-effort
            // and idempotent — no-op on hosts that already have it. See NativeDependencyLoader.
            NativeDependencyLoader.preloadV8NativeDeps()

            // 1. Load configs (YAML + JSON overrides)
            configManager = ConfigManager(dataDirectory, logger)
            configManager.loadAll()

            // 2. Initialize codec scanner (cache Item/Recipe BuilderCodecs)
            codecScanner = CodecScanner()
            codecScanner.init()

            // 3. Run health check (verify codec pipeline accessible)
            healthMonitor = HealthMonitor(codecScanner)
            val report = healthMonitor.check()
            for (line in report.format().lines()) {
                if (line.isNotBlank()) {
                    if (line.contains("FAIL")) {
                        logger.atWarning().log(line)
                    } else {
                        logger.atInfo().log(line)
                    }
                }
            }
            if (!report.allPassed) {
                logger.atSevere().log(
                    "ItemForge health check failed — some features may not work correctly"
                )
            }

            // 4. Build metadata caches
            modSourceTracker = ModSourceTracker()
            modSourceTracker.scan()
            ItemNameResolver.init(modSourceTracker)

            // 4b. Snapshot the EntityStatType registry now that mod attribution exists.
            // Lets CodecScanner offer EVERY registered stat (vanilla + any mod's, e.g.
            // Hexcode's Magic_Power/Volatility) as addable on items with a StatModifiers
            // component, each attributed to its source mod. Must follow modSourceTracker.scan().
            codecScanner.initStatTypes(modSourceTracker)

            tagCache = TagCache()
            tagCache.scan()

            logger.atInfo().log(
                "Metadata: %d mods, %d item types, %d total items",
                modSourceTracker.getAllModNames().size,
                tagCache.getValuesForKey("Type").size,
                Item.getAssetMap().assetMap.size
            )

            // 5. Initialize core engine
            committer = AssetCommitter()
            originalsCache = OriginalsCache(codecScanner.itemCodec)
            overrideEngine = OverrideEngine(codecScanner, originalsCache, committer, logger)

            // Global custom Name/Lore engine. Needs dataDirectory + ItemNameResolver (init'd above).
            // Loads persisted overrides now; they are re-broadcast to each player on join (see the
            // PlayerConnectEvent listener in this method) and applied live on edit.
            translationOverrides = TranslationOverrideEngine(dataDirectory, logger)
            translationOverrides.load()

            // 5b. Initialize recipe subsystems
            benchRegistry = BenchRegistry(logger)
            benchRegistry.init()

            // MaterialIndex: lazy-built on first search, but register now for invalidation
            materialIndex = me.itemforge.metadata.MaterialIndex(codecScanner, logger)

            recipeScanner = RecipeScanner(codecScanner)
            recipeOriginalsCache = RecipeOriginalsCache(codecScanner.recipeCodec)
            recipeOverrideEngine = RecipeOverrideEngine(codecScanner, recipeOriginalsCache, committer, logger)

            // 5c. Batch engines. Must exist before dashboardBridge (which holds
            // them) is created and registered on V8 globalThis — same ordering rule as
            // dashboardBridge itself (Bug #10b: lateinit accessed in a queued V8 lambda).
            batchEngine = me.itemforge.core.BatchEngine(codecScanner, overrideEngine, committer, logger)
            batchRecipeEngine = me.itemforge.core.BatchRecipeEngine(codecScanner, logger)

            // 5d. StatProvider registry + public API. Created before Vuetale/inspect
            // so EditorBridge and InspectSuppressor can reference it. ItemForgeAPI.init flushes
            // any providers a mod buffered before ItemForge finished starting (load-order safe).
            extensionRegistry = ExtensionRegistry()
            ItemForgeAPI.init(extensionRegistry)
            // Mods register their own EditorExtension via ItemForgeAPI.registerExtension(...).
            // (A built-in DemoEditorExtension lived here during development — removed for release.)

            // 6. Initialize inventory refresher (updates held items after overrides)
            inventoryRefresher = InventoryRefresher(logger)

            // 7. Initialize audit logger
            auditLogger = AuditLogger(
                logFile = dataDirectory.resolve("logs").resolve("itemforge-changes.log"),
                enabled = configManager.pluginConfig.behavior.auditLogEnabled,
                logger = logger
            )

            // 7. Initialize asset load listener
            assetLoadListener = AssetLoadListener(
                configManager, overrideEngine, originalsCache, committer,
                modSourceTracker, tagCache, logger,
                materialIndex = materialIndex
            )

            // 8. Mark as started — event handler can now access subsystems
            started = true

            // 9. Apply saved overrides (catches gap if LoadedAssetsEvent fired before start)
            applySavedOverrides()
            applySavedRecipeOverrides()

            // 10. Initialize dashboard bridge (needs all subsystems, but NOT VuetaleIntegration).
            // MUST be created BEFORE Vuetale init — init() registers it on V8 globalThis
            // via async runOnV8Thread. If dashboardBridge isn't set yet, the lateinit access
            // throws UninitializedPropertyAccessException, caught silently, leaving
            // globalThis.dashboardBridge undefined at runtime.
            dashboardBridge = me.itemforge.vuetale.DashboardBridge(this)
            assetLoadListener.dashboardBridge = dashboardBridge

            // 11. Initialize Vuetale UI (per ARCHITECTURE.md §10.2 step 7)
            // Done after subsystems are ready because EditorBridge needs access to them.
            // Vuetale init is non-blocking — V8 engine starts on a daemon thread.
            vuetaleIntegration = VuetaleIntegration(this)
            vuetaleIntegration.init()

            // 12. Start V8 watchdog — prevents server freeze if V8 callback hangs.
            // Must be after Vuetale init (needs V8 runtime + executor).
            v8Watchdog = V8Watchdog()
            v8Watchdog.start()

            // 13. Register commands
            commandRegistry.registerCommand(
                ItemForgeCommand(
                    configManager = configManager,
                    healthMonitor = healthMonitor,
                    modSourceTracker = modSourceTracker,
                    tagCache = tagCache,
                    reloadHandler = ::performReload,
                    vuetaleIntegration = vuetaleIntegration,
                    inspectModeManager = inspectModeManager,
                    extensionRegistry = extensionRegistry
                )
            )

            // 14. Wire Inspect Mode. The suppressor is the whole mechanism: an
            // IO-thread inbound filter on SyncInteractionChains that, for an inspecting,
            // crouching admin's right-click, drops the vanilla action AND opens the held
            // item's editor. The interaction-chain packet is the single reliable gesture
            // signal (verified in-game: PlayerMouseButtonEvent does not fire for held-item
            // right-clicks). Crouch is fed to it by the CrouchTrackingSystem (setup()).
            inspectSuppressor = InspectSuppressor(inspectModeManager, vuetaleIntegration).also { it.register() }

            // 15. Player-disconnect cleanup — wires the disconnect event:
            // clears inspect/crouch state and Vuetale per-player session state.
            inspectDisconnectRegistration = eventRegistry.register(PlayerDisconnectEvent::class.java) { event ->
                handlePlayerDisconnect(event)
            }

            // Re-send active global Name/Lore overrides to each joining player. The engine's join-time
            // translation Init is built from its own language map (which our runtime overrides aren't
            // part of), so without this a late joiner would see stock names until the next edit. Fires
            // after the player connects → after the engine's Init, so our AddOrUpdate merges on top.
            eventRegistry.register(PlayerConnectEvent::class.java) { event ->
                if (started) {
                    try {
                        translationOverrides.resendTo(event.playerRef)
                    } catch (e: Exception) {
                        logger.atWarning().withCause(e).log("ItemForge: name/lore re-send on join failed")
                    }
                }
            }

            // 16. Anonymous usage analytics (HStats — hstats.dev). Once-per-JVM (the guard
            // lives in Metrics, since HStats schedules an uncancellable server-global task);
            // respects the metrics.enabled opt-out; version sourced live from the manifest so
            // per-version analytics track the real build. Never fatal — failures are swallowed.
            val itemForgeVersion = runCatching { getManifest().getVersion().toString() }.getOrDefault("Unknown")
            me.itemforge.metrics.Metrics.init(
                enabled = configManager.pluginConfig.metrics.enabled,
                version = itemForgeVersion,
                log = { msg -> logger.atInfo().log(msg) }
            )

            logger.atInfo().log("ItemForge started — %d item overrides, %d recipe overrides",
                configManager.itemOverrideCount(),
                configManager.recipeOverrideCount()
            )

            // Warm the dashboard payload + field catalog in the BACKGROUND now (off the tick), so
            // the very first /itemforge open is instant for everyone. The old synchronous build on
            // first open froze the whole server ~21s with a large modpack. Re-warms automatically
            // after any asset reload (AssetLoadListener → invalidateCache → requestWarm).
            dashboardBridge.requestWarm("boot")

        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("ItemForge failed to start")
        }
    }

    override fun shutdown() {
        logger.atInfo().log("ItemForge shutting down")
        started = false

        try {
            // 0. Inspect Mode teardown — remove the hook first so no new gestures
            // fire mid-shutdown. The inbound packet filter MUST be deregistered: PacketAdapters
            // holds a static global list that outlives this plugin instance.
            inspectSuppressor?.unregister()
            inspectDisconnectRegistration?.unregister()
            if (::inspectModeManager.isInitialized) inspectModeManager.shutdown()

            // Release the public API registry so a reload starts clean.
            ItemForgeAPI.shutdown()

            // 1. Stop V8 watchdog (before Vuetale shutdown — needs V8 alive)
            if (::v8Watchdog.isInitialized) {
                v8Watchdog.shutdown()
            }

            // 2. Shut down Vuetale integration (before config flush)
            if (::vuetaleIntegration.isInitialized) {
                vuetaleIntegration.shutdown()
            }

            // 2. Flush all pending config writes
            if (::configManager.isInitialized) {
                configManager.shutdown()
            }

            // 2. Optionally revert all overrides
            if (::configManager.isInitialized &&
                configManager.pluginConfig.behavior.revertOnDisable &&
                ::overrideEngine.isInitialized
            ) {
                val overriddenIds = configManager.itemOverrides.getOverriddenItemIds()
                var reverted = 0
                for (itemId in overriddenIds) {
                    if (overrideEngine.revertItem(itemId)) reverted++
                }
                logger.atInfo().log("Reverted %d item override(s) on disable", reverted)
            }

            // 3. Flush audit log
            if (::auditLogger.isInitialized) {
                auditLogger.flush()
            }

        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("Error during ItemForge shutdown")
        }

        logger.atInfo().log("ItemForge shutdown complete")
    }

    // ── Event Handlers ──────────────────────────────────────────────────

    /**
     * Handles LoadedAssetsEvent for Item assets.
     *
     * Registered in setup() — may fire before start() completes.
     * The [started] guard defers to [applySavedOverrides] for pre-start events.
     */
    private fun handleItemsLoaded(
        event: LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>>
    ) {
        if (!started) return

        try {
            assetLoadListener.onItemsLoaded(event)
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("Error handling LoadedAssetsEvent")
        }
    }

    /**
     * Handles player disconnect: clears Inspect Mode state (inspect toggle + crouch cache)
     * and Vuetale per-player session state. Registered in start().
     */
    private fun handlePlayerDisconnect(event: PlayerDisconnectEvent) {
        if (!started) return
        try {
            val uuid = event.playerRef.uuid
            inspectModeManager.onPlayerDisconnect(uuid)
            if (::vuetaleIntegration.isInitialized) {
                vuetaleIntegration.onPlayerDisconnect(uuid)
            }
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("Error handling player disconnect")
        }
    }

    // ── Reload Orchestration ────────────────────────────────────────────

    /**
     * Full reload: re-reads config files AND diff-applies override changes.
     *
     * Backs the `/itemforge reload` admin command: edit the override JSON on disk
     * (or have an external tool write it), reload, and see the changes applied
     * live with no restart.
     *
     * Flow:
     * 1. Snapshot currently applied override IDs
     * 2. Re-read all config files from disk
     * 3. Snapshot new override IDs from the re-read files
     * 4. Diff: find added, removed, and changed overrides
     * 5. Revert removed overrides to original values
     * 6. Apply new and changed overrides
     * 7. Return human-readable summary
     */
    fun performReload(): String {
        // 1. Snapshot state BEFORE reload
        val previousIds = configManager.itemOverrides.getOverriddenItemIds()
        val previousOverrides = configManager.itemOverrides.getSnapshot()

        // 2. Re-read config files from disk
        val configSummary = configManager.reloadAll()

        // 3. Snapshot state AFTER reload
        val currentIds = configManager.itemOverrides.getOverriddenItemIds()

        // 4. Compute diff
        val removed = previousIds - currentIds          // Were applied, no longer in file
        val added = currentIds - previousIds            // New in file, not yet applied
        val maybeChanged = previousIds.intersect(currentIds) // Present in both — check values

        val changed = maybeChanged.filter { itemId ->
            val oldJson = previousOverrides[itemId]?.toString()
            val newJson = configManager.itemOverrides.getOverride(itemId)?.toString()
            oldJson != newJson
        }.toSet()

        // 5. Revert removed overrides
        var reverted = 0
        for (itemId in removed) {
            if (overrideEngine.revertItem(itemId)) reverted++
        }

        // 6. Apply new and changed overrides
        var applied = 0
        val toApply = added + changed
        val itemMap = Item.getAssetMap()
        val modifiedItems = mutableListOf<Item>()

        for (itemId in toApply) {
            val overrideJson = configManager.itemOverrides.getOverride(itemId) ?: continue
            val item = itemMap.getAsset(itemId) ?: continue

            // Capture originals if this is a brand new override
            if (!originalsCache.has(itemId)) {
                originalsCache.capture(itemId, item)
            }

            val overrideBson = try {
                BsonDocument.parse(overrideJson.toString())
            } catch (_: Exception) { continue }

            if (overrideEngine.applyWithoutSync(itemId, overrideBson)) {
                itemMap.getAsset(itemId)?.let { modifiedItems.add(it) }
                applied++
            }
        }

        // 7. Batch commit newly applied items
        if (modifiedItems.isNotEmpty()) {
            committer.commitItems(modifiedItems)
        }

        // 8. Refresh held ItemStacks for all modified/reverted items.
        // Uses Universe.get().getWorlds() internally — works across all worlds.
        val allAffectedIds = (toApply + removed)
        if (allAffectedIds.isNotEmpty()) {
            try {
                inventoryRefresher.refreshItemStacks(allAffectedIds)
            } catch (e: Exception) {
                logger.atWarning().withCause(e).log(
                    "InventoryRefresher: Could not refresh held items during reload"
                )
            }
        }

        // 9. Clear originals for removed items (no longer needed)
        for (itemId in removed) {
            originalsCache.remove(itemId)
        }

        // Invalidate dashboard cache — item stats changed from reload
        if (::dashboardBridge.isInitialized) {
            dashboardBridge.invalidateCache()
        }

        val summary = "Reloaded: $applied applied, $reverted reverted, " +
            "${currentIds.size - changed.size - added.size} unchanged"
        logger.atInfo().log(summary)
        return summary
    }

    // ── Startup Override Application ────────────────────────────────────

    /**
     * Applies all saved overrides from config to live items.
     *
     * Called during start() to catch overrides that should have been applied
     * during LoadedAssetsEvent but couldn't because start() hadn't completed.
     */
    private fun applySavedOverrides() {
        val snapshot = configManager.itemOverrides.getSnapshot()
        if (snapshot.isEmpty()) return

        val itemMap = Item.getAssetMap()
        val appliedIds = mutableListOf<String>()

        for ((itemId, overrideJson) in snapshot) {
            val item = itemMap.getAsset(itemId) ?: continue

            // Skip if already applied by a LoadedAssetsEvent after start() completed
            if (originalsCache.has(itemId)) continue

            // Capture originals before override
            originalsCache.capture(itemId, item)

            val overrideBson = try {
                BsonDocument.parse(overrideJson.toString())
            } catch (_: Exception) { continue }

            if (overrideEngine.applyWithoutSync(itemId, overrideBson)) {
                appliedIds.add(itemId)
            }
        }

        // Batch commit only the items we actually applied overrides to
        if (appliedIds.isNotEmpty()) {
            val modifiedItems = appliedIds.mapNotNull { itemMap.getAsset(it) }
            if (modifiedItems.isNotEmpty()) {
                committer.commitItems(modifiedItems)
            }
            logger.atInfo().log("Applied %d saved override(s) on startup", appliedIds.size)
        }
    }

    /**
     * Applies any saved recipe overrides from recipes.json on startup.
     *
     * Same pattern as [applySavedOverrides] but for CraftingRecipe assets.
     * Captures original recipe BSON before first override, then applies.
     */
    private fun applySavedRecipeOverrides() {
        val snapshot = configManager.recipeOverrides.getSnapshot()
        if (snapshot.isEmpty()) return

        var applied = 0

        for ((recipeId, overrideJson) in snapshot) {
            val overrideBson = try {
                BsonDocument.parse(overrideJson.toString())
            } catch (_: Exception) { continue }

            // applyAndSync handles BOTH cases: an EDIT (recipe exists in the map → it captures
            // the original snapshot first) and a CREATE (no asset → registers a brand-new
            // recipe). The create case is how an ItemForge-created recipe — for an item the game
            // never generates one for — survives restarts: nothing else re-registers it.
            if (recipeOverrideEngine.applyAndSync(recipeId, overrideBson)) {
                applied++
            }
        }

        if (applied > 0) {
            logger.atInfo().log("Applied %d saved recipe override(s) on startup", applied)
        }
    }
}

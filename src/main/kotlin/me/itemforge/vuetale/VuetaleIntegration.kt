package me.itemforge.vuetale

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import li.kelp.vuetale.app.PlayerUi
import li.kelp.vuetale.app.PlayerUiManager
import li.kelp.vuetale.javascript.JSEngine
import li.kelp.vuetale.javascript.ModuleRegistry
import li.kelp.vuetale.javascript.VueBridge
import me.itemforge.ItemForgePlugin
import java.util.UUID

/**
 * Manages Vuetale UI lifecycle for ItemForge.
 *
 * Responsibilities:
 * - Module registration (maps "itemforge" to classpath resources in our JAR)
 * - V8 bridge setup (exposes [EditorBridge] as `globalThis.itemForgeBridge`)
 * - Page opening (editor, dashboard in future phases)
 * - Player disconnect cleanup
 *
 * ## Vuetale API Pattern (from TrailOfOrbis VuetaleIntegration.java)
 *
 * 1. Register module aliases via [ModuleRegistry] — maps `@itemforge/` imports
 *    to `vuetale/itemforge/` in our JAR's classpath resources
 * 2. Trigger V8 init via `JSEngine.getInstance()`
 * 3. Register bridge as globalThis variable via V8Runtime reflection
 * 4. Open pages via [PlayerUiManager.getOrCreate] + [PlayerUi.openPage]
 * 5. Push data via [PlayerUi.setData] (safe from game thread)
 *
 * ## Evidence
 *
 * - TrailOfOrbis `VuetaleIntegration.java:38-76` (exact same initialization pattern)
 * - TrailOfOrbis `VuetaleIntegration.java:102-123` (page opening + setData)
 * - Vuetale `ModuleRegistry.kt` (registerModule API)
 * - Vuetale `PlayerUiManager.kt` (getOrCreate + openPage API)
 *
 * @param plugin The ItemForge plugin instance (for subsystem access)
 */
class VuetaleIntegration(
    private val plugin: ItemForgePlugin
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()

    /** The Kotlin↔JS bridge exposed as globalThis.itemForgeBridge. */
    internal var bridge: EditorBridge? = null
        private set

    /** Whether init() completed successfully. */
    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * Initializes the Vuetale runtime, registers modules, and exposes the bridge.
     *
     * Must be called during plugin `start()` after all subsystems are initialized.
     * Safe to call multiple times — subsequent calls are no-ops.
     *
     * ## Module Registration
     *
     * "core" must be registered with OUR class (not Vuetale's) because Vuetale's
     * core resources (Common.js, useData.js, renderer.js, etc.) are shaded into
     * our JAR. The classloader needs to find them through our class reference.
     *
     * "itemforge" maps `@itemforge/pages/Editor` to `vuetale/itemforge/pages/Editor.vue.js`
     * in our JAR resources.
     */
    fun init() {
        if (initialized) return

        try {
            // Vuetale debug logging MUST stay disabled. When enabled, every dirty property
            // change logs the entire rendered UI tree (~7500 lines) via app.root.render(0)
            // in VuetaleUIPage.emitPropertySet(). A single keystroke produces 15-27 dirty
            // properties → 100K-200K lines of log output → V8 thread stalls → client crash.
            // Only enable temporarily for single-shot debugging, never during interactive use.
            li.kelp.vuetale.javascript.DebugConfig.enabled = false

            // Register module aliases for classpath resource loading.
            // "core" = Vuetale's own runtime (renderer, loader, Common.js, useData.js)
            //          Shaded into our JAR — needed for standalone installs without other
            //          Vuetale plugins. If another plugin already registered "core", this
            //          overwrites it (last registration wins — ModuleRegistry is a simple map).
            //          Both classloaders have identical core resources, so this is safe.
            // "itemforge" = our Vue pages and components
            // Evidence: ToO VuetaleIntegration.java:48-50 (same pattern)
            ModuleRegistry.registerModule("core", VuetaleIntegration::class.java, null)
            ModuleRegistry.registerModule("itemforge", VuetaleIntegration::class.java, null)

            // Trigger lazy V8 engine initialization
            val engine = JSEngine.instance

            // Create bridge and expose as globalThis.itemForgeBridge
            bridge = EditorBridge(plugin)

            // Register bridge in V8 global scope via reflection.
            // Vuetale's distributed JAR doesn't expose a public registerJavaBridge method,
            // so we access V8Runtime through VueBridge's private field — same pattern used
            // by TrailOfOrbis (VuetaleIntegration.java:59-68).
            engine.runOnV8Thread {
                try {
                    val field = VueBridge::class.java.getDeclaredField("v8Runtime")
                    field.isAccessible = true
                    val v8Runtime = field.get(engine.bridge) as com.caoccao.javet.interop.V8Runtime
                    v8Runtime.globalObject.set("itemForgeBridge", bridge)
                    v8Runtime.globalObject.set("dashboardBridge", plugin.dashboardBridge)

                    // NOTE: Do NOT modify globalThis.ktBridge (JS Proxy, property
                    // override, etc). The Javet proxy wrapping VueBridge is used by
                    // Vuetale's renderer for EVERY element operation (create, insert,
                    // remove, patchProp). Any interception layer breaks method binding,
                    // causes stale V8 references, and corrupts the renderer on subsequent
                    // page opens. TrailOfOrbis doesn't touch ktBridge — neither should we.
                    //
                    // Null/undefined props (which trigger hasStructuralChanges in VueBridge)
                    // are handled at the template level: use v-bind conditional spread
                    // instead of passing nullable values as direct props.
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("Failed to register itemForgeBridge in V8 runtime")
                }
                null // runOnV8Thread expects Function0<T>, return null
            }

            initialized = true
            logger.atInfo().log("Vuetale initialized — itemForgeBridge + dashboardBridge registered")

        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("Failed to initialize Vuetale runtime")
        }
    }

    /**
     * Opens the item editor page for a player.
     *
     * 1. Permission check (browse or edit)
     * 2. Create/get PlayerUi
     * 3. Open the Editor page via Vuetale
     * 4. Push initial payload via setData (safe from game thread)
     *
     * @param playerRef The player to open the editor for
     * @param ref Entity reference (required by Vuetale for player tracking)
     * @param store Entity store
     * @param itemId The item to edit
     */
    fun openEditor(
        playerRef: PlayerRef,
        ref: Ref<EntityStore>,
        store: Store<EntityStore>,
        itemId: String,
        stackContext: me.itemforge.provider.StackEditContext? = null
    ) {
        if (!initialized || bridge == null) {
            logger.atWarning().log("Cannot open editor — Vuetale not initialized")
            return
        }

        // The editor-open gate (canEditAnything) is enforced at the command level
        // (executeEditCommand). Per-tab edit permissions are computed inside
        // buildEditorPayload from the player's UUID and embedded in the payload;
        // the bridge re-checks on every write (permission may be revoked while the
        // editor is open). NOTE: PlayerRef DOES implement PermissionHolder
        // (PlayerRef.java:44, verified 2026-05-29) — the old SDK_FINDINGS note to
        // the contrary is stale; permissions are UUID-keyed via PermissionsModule.

        val playerId = playerRef.uuid

        // Set (or clear) the per-stack edit session BEFORE building the payload, so
        // buildEditorPayload sees it. Inspect passes a context (the held item's
        // metadata, already read + serialized on the world thread); command/dashboard pass null,
        // which clears any stale session and guarantees plain asset-mode editing.
        bridge!!.setStackEdit(playerId, stackContext)

        // Get or create the player's Vuetale UI handle (world thread)
        val ui = PlayerUiManager.getOrCreate(playerId, playerRef, ref, store)
        val world = store.externalData.world

        // Build the single-item editor payload OFF the world tick. The codec scan is ~17ms (worse
        // on deep InteractionVars/recipe chains) — never block the tick on it. When it's ready we
        // hop BACK to the world thread to open the page with the data ALREADY buffered, so the
        // editor mounts in a single render. Buffering data before openPage avoids the empty→full
        // re-render (clear + appendInline / property updates on already-rendered elements) that can
        // crash the Hytale client (Vuetale wiki: "Updating background property crashes the client").
        // Net effect: tick-safe AND crash-safe.
        bridge!!.buildEditorPayloadAsync(itemId, playerId) { payloadJson ->
            world.execute {
                try {
                    ui.setData("editor", payloadJson)
                    ui.setData("playerId", playerId.toString())
                    ui.openPage("@itemforge/pages/Editor", CustomPageLifetime.CanDismiss)

                    // Increment page generation — any in-flight deferred close from a previous
                    // editor session sees the mismatch and aborts instead of unmounting this page.
                    bridge!!.onEditorOpened(playerId.toString())

                    // ── Prevent Vuetale's evalScriptAsync unmount hang on Escape ──
                    // On Escape, Hytale fires onDismiss → removeApp → App.unmount() →
                    // evalScriptAsync(unmount), which hangs in Javet native code (not recoverable
                    // via terminateExecution). Fix: set isMounted=false AFTER mount completes so
                    // unmount() returns immediately. Poll-wait (not fixed sleep): mount time varies
                    // <100ms..>1s with item complexity / V8 load.
                    java.util.concurrent.CompletableFuture.runAsync {
                        try {
                            for (attempt in 1..150) {
                                Thread.sleep(100)
                                val playerUi = PlayerUiManager.get(playerId) ?: continue
                                val page = playerUi.javaClass.getDeclaredField("page").let { f ->
                                    f.isAccessible = true
                                    f.get(playerUi)
                                } ?: continue
                                val app = page.javaClass.getDeclaredField("app").let { f ->
                                    f.isAccessible = true
                                    f.get(page) as? li.kelp.vuetale.app.App
                                } ?: continue
                                if (app.isMounted) {
                                    app.javaClass.getDeclaredField("isMounted").let { f ->
                                        f.isAccessible = true
                                        f.set(app, false)
                                    }
                                    logger.atInfo().log(
                                        "Disabled evalScriptAsync unmount for '%s' after %dms — Escape dismiss is now safe",
                                        itemId, attempt * 100
                                    )
                                    return@runAsync
                                }
                            }
                            logger.atWarning().log(
                                "Mount never completed for '%s' after 15s — evalScriptAsync unmount not disabled",
                                itemId
                            )
                        } catch (e: Exception) {
                            logger.atWarning().withCause(e).log(
                                "Failed to disable evalScriptAsync unmount for '%s' — Escape may still freeze",
                                itemId
                            )
                        }
                    }

                    logger.atInfo().log("Opened editor for '%s' (player: %s)", itemId, playerId)
                } catch (e: Exception) {
                    logger.atSevere().withCause(e).log("openEditor: world dispatch failed for '%s'", itemId)
                }
            }
        }
    }

    /**
     * Opens the dashboard page for a player.
     *
     * Builds the dashboard payload (summary of all items), pushes it via setData,
     * and opens the Dashboard Vue component. Same lifecycle pattern as openEditor().
     *
     * The dashboard opens as `@itemforge/pages/Dashboard`. When the admin clicks
     * an item, [DashboardBridge.navigateToEditor] uses PlayerUi.navigate() to swap
     * to the Editor component without page dismiss/reopen.
     *
     * @param playerRef The player to open the dashboard for
     * @param ref Entity reference
     * @param store Entity store
     */
    fun openDashboard(
        playerRef: PlayerRef,
        ref: Ref<EntityStore>,
        store: Store<EntityStore>
    ) {
        if (!initialized) {
            logger.atWarning().log("Cannot open dashboard — Vuetale not initialized")
            return
        }

        val dashboardBridge = plugin.dashboardBridge
        val playerId = playerRef.uuid

        val ui = PlayerUiManager.getOrCreate(playerId, playerRef, ref, store)
        ui.setData("playerId", playerId.toString())

        // Push preserved state if navigating back from editor
        val state = dashboardBridge.getPlayerState(playerId.toString())
        if (state != null) {
            ui.setData("dashboardState", com.google.gson.Gson().toJson(state))
        }

        // The payload + catalog are warmed OFF the tick thread (DashboardBridge.runWarm). The open
        // does ZERO heavy codec work — it only reads the published caches. This is what makes the
        // open instant and removes the ~21s server-wide freeze the old synchronous
        // buildPayloadIfNeeded()/fieldCatalog("") caused on the first open.
        if (dashboardBridge.isWarm()) {
            // Warm: data buffered before openPage → single render pass (the proven pattern).
            ui.setData("dashboard", dashboardBridge.getDashboardData())
            ui.setData("fieldCatalog", dashboardBridge.getCatalogJson())
            ui.setData("dashboardStatus", "{\"warm\":true}")
        } else {
            // Not warm yet (rare: first open within ~2s of boot, or right after a reload). Open with
            // a loading state; the off-tick warm pushes the real data via drainPendingOpens when it
            // completes. NEVER build synchronously here.
            ui.setData("dashboardStatus", "{\"warm\":false,\"total\":${dashboardBridge.totalItemCount()}}")
            dashboardBridge.registerPendingOpen(playerId.toString())
            dashboardBridge.requestWarm("open") // idempotent safety net if the boot warm was missed
        }

        // Open the Dashboard page
        ui.openPage("@itemforge/pages/Dashboard", CustomPageLifetime.CanDismiss)

        // Track player refs for navigation and page close
        dashboardBridge.onDashboardOpened(
            playerId.toString(), playerRef, ref, store
        )

        // isMounted=false poll-wait (same pattern as openEditor — prevents
        // evalScriptAsync hang when Escape is pressed)
        java.util.concurrent.CompletableFuture.runAsync {
            try {
                for (attempt in 1..150) {
                    Thread.sleep(100)
                    val playerUi = PlayerUiManager.get(playerId) ?: continue
                    val page = playerUi.javaClass.getDeclaredField("page").let { f ->
                        f.isAccessible = true
                        f.get(playerUi)
                    } ?: continue
                    val app = page.javaClass.getDeclaredField("app").let { f ->
                        f.isAccessible = true
                        f.get(page) as? li.kelp.vuetale.app.App
                    } ?: continue

                    if (app.isMounted) {
                        app.javaClass.getDeclaredField("isMounted").let { f ->
                            f.isAccessible = true
                            f.set(app, false)
                        }
                        logger.atInfo().log(
                            "Dashboard: disabled evalScriptAsync after %dms (player: %s)",
                            attempt * 100, playerId
                        )
                        return@runAsync
                    }
                }
                logger.atWarning().log(
                    "Dashboard mount never completed after 15s (player: %s)", playerId
                )
            } catch (e: Exception) {
                logger.atWarning().withCause(e).log(
                    "Dashboard: failed to disable evalScriptAsync (player: %s)", playerId
                )
            }
        }

        logger.atInfo().log("Opened dashboard (player: %s, warm: %b)", playerId, dashboardBridge.isWarm())
    }

    /**
     * Cleans up Vuetale state when a player disconnects.
     * Call from a player disconnect event handler.
     */
    fun onPlayerDisconnect(playerId: UUID) {
        if (!initialized) return
        bridge?.onPlayerDisconnect(playerId.toString())
        plugin.dashboardBridge.onPlayerDisconnect(playerId.toString())
        PlayerUiManager.remove(playerId)
    }

    /**
     * Shuts down the Vuetale integration.
     * Call during plugin shutdown before config flush.
     */
    fun shutdown() {
        if (!initialized) return

        initialized = false
        bridge = null

        // Note: we do NOT call JSEngine.close() here because other plugins
        // may still be using the shared V8 runtime. Vuetale handles its own
        // lifecycle. We only clean up our references.
        logger.atInfo().log("Vuetale integration shut down")
    }
}

package li.kelp.vuetale.hytale

import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime
import com.hypixel.hytale.protocol.packets.interface_.Page
import com.hypixel.hytale.protocol.packets.interface_.SetPage
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo
import com.hypixel.hytale.server.core.ui.ItemGridSlot
import com.hypixel.hytale.server.core.ui.builder.EventData
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import li.kelp.vuetale.app.App
import li.kelp.vuetale.app.AppManager
import li.kelp.vuetale.app.AppType
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType
import li.kelp.vuetale.javascript.DebugConfig
import li.kelp.vuetale.javascript.JSEngine
import li.kelp.vuetale.property.*
import li.kelp.vuetale.tree.Element
import li.kelp.vuetale.tree.ElementContainer
import li.kelp.vuetale.tree.RootElement
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Hytale UI page backed by a Vue application.
 *
 * ### Lifecycle
 * 1. Construct – creates (or re-creates) an [App] for the given [appOwner] + [appType].
 * 2. `build()` – Hytale calls this when the screen opens.
 *    - Mounts the Vue app so the element tree is synchronously populated.
 *    - Appends the static root layout via `uiCommandBuilder.append(rootUiPath)`.
 *    - Injects the rendered element tree into `#App` via `appendInline`.
 *    - Registers all collected event bindings with `uiEventBuilder`.
 *    - Sets [App.onDirty] so subsequent Vue re-renders push incremental updates.
 * 3. `handleDataEvent()` – Hytale calls this when a registered UI event fires.
 *    - Looks up the Vue callback by [VuetaleEventData.routingKey].
 *    - Invokes the callback on the V8 thread via [JSEngine].
 * 4. `onDismiss()` – cleans up the [App] and removes it from [AppManager].
 *
 * ### Thread safety
 * The [App.onDirty] callback fires on the **`vuetale-v8`** daemon thread.  If Hytale's
 * `sendUpdate()` must be called from the game/server thread, wrap the call inside
 * `world.execute { … }` (or equivalent) before assigning [App.onDirty].
 *
 * @param playerRef   The player this page belongs to.
 * @param appOwner    Stable identifier for the Vue app (typically the player name).
 * @param appType     [AppType.Page] or [AppType.Hud].
 * @param lifetime    When the player may dismiss the screen.
 * @param rootUiPath  Path to the static `.ui` file that contains the `#App` container.
 *                    Resolved by Hytale's asset system (adjust to match your mod's pack layout).
 */
class VuetaleUIPage(
    playerRef: PlayerRef,
    appOwner: String,
    appType: AppType = AppType.Page,
    lifetime: CustomPageLifetime = CustomPageLifetime.CanDismiss,
    /** Initial component to render, e.g. `"vt:@core/pages/Dashboard"`. May be changed later via [App.navigateTo]. */
    componentPath: String? = null,
) : InteractiveCustomUIPage<VuetaleEventData>(playerRef, lifetime, VuetaleEventData.CODEC) {

    private val logger = Logger.getLogger("VuetaleUIPage[$appOwner-$appType]")

    /**
     * Set to false in [onDismiss] so any in-flight async sendUpdate dispatched from
     * [App.onDirty] is silently dropped rather than calling into a dismissed page.
     */
    @Volatile
    private var isActive = true

    /** The Vuetale app that owns the element tree for this page. */
    val app: App = run {
        val existingApp = AppManager.getApp(AppManager.getAppId(appOwner, appType))

        if (existingApp != null) {
            // Silence the dirty callback so the V8 tick doesn't fire onDirty while
            // we're in the middle of re-opening the page (deadlock risk, see below).
            existingApp.onDirty = null
            existingApp.isDirty = false

            // If a new component path was requested, navigate the live Vue app to it
            // instead of tearing down and recreating everything.
            if (componentPath != null && componentPath != existingApp.componentPath) {
                existingApp.navigateTo(componentPath)
            }

            existingApp
        } else {
            // No existing app – create a fresh one.
            // Note: we intentionally do NOT call removeApp here; if somehow a stale
            // entry existed it would have been caught by the branch above.
            AppManager.createApp(appOwner, appType, componentPath)
        }
    }

    // ── build ──────────────────────────────────────────────────────────────

    override fun build(
        ref: Ref<EntityStore>,
        uiCommandBuilder: UICommandBuilder,
        uiEventBuilder: UIEventBuilder,
        store: Store<EntityStore>
    ) {
        // ── Purge phantom bindings ────────────────────────────────────────
        // setData() updates the JS reactive data store BEFORE build() runs.
        // If the previous session's Vue app hasn't been unmounted yet (its
        // evalScriptAsync unmount is queued AFTER setData on the V8 executor),
        // the data change triggers a re-render on the OLD Vue app. That re-render
        // creates phantom elements whose el.app points to THIS (new) Kotlin App
        // (because AppManager already has it), so their event bindings land in
        // THIS EventRegistry. Clearing it here ensures mount() starts with a
        // clean slate — only bindings from the NEW Vue app's mount survive.
        app.eventRegistry.closeAll()

        // 1. Mount Vue – synchronously populates the Kotlin element tree
        app.mount()

        // 2. Load the static root layout (defines group #App {})
        uiCommandBuilder.append("Common.ui")
        uiCommandBuilder.append("Pages/HytaleRoot.ui")

        // 3. Inject the entire rendered tree into #App
        val rendered = app.root.render(0) // strip newlines to avoid Hytale parser issues
        uiCommandBuilder.appendInline("#App", rendered)
        if (DebugConfig.enabled) logger.info ("[vuetaledebug] Initial render:\n$rendered")

        // 4. Register all Vue event bindings collected during mount
        registerEventBindings(uiEventBuilder)

        // Clear stale mount-time tracking: all inserts/patches during mount are already
        // covered by the appendInline above.  If we leave these lists populated, the first
        // onDirty invocation would re-process mount-time data and emit bogus structural
        // commands referencing element IDs that may no longer exist in the client's UI.
        app.hasStructuralChanges = false
        app.dirtyElementIds.clear()
        app.removedElementSelectors.clear()
        app.insertedElements.clear()
        app.isDirty = false

        // The appendInline above put the entire initial tree on the client. Record its ids so
        // later structural updates can tell a genuinely-new element from a repositioned one.
        clientElementIds.clear()
        clientElementIds.addAll(collectTreeElementIds(app.root))

        // 5. Wire up the dirty → incremental-update pipeline.
        //    This lambda runs on the vuetale-v8 thread.
        //
        //    IMPORTANT: sendUpdate() must NEVER be called directly on the V8 thread.
        //    If sendUpdate() blocks (acquiring a Hytale player/page lock) and at the
        //    same time a second openCustomPage call on the game thread is waiting for
        //    the V8 thread via evalScript(), the two threads deadlock permanently.
        //    Dispatching via CompletableFuture.runAsync() keeps the V8 tick non-blocking.
        app.onDirty = {
            val propStructural = app.hasStructuralChanges
            val dirtyIds = app.dirtyElementIds.toSet()
            val removedSelectors = app.removedElementSelectors.toList()
            val insertedElements = app.insertedElements.toList()

            // Reset tracking state before building the update so any mutations that
            // fire during sendUpdate are captured for the *next* batch.
            app.hasStructuralChanges = false
            app.dirtyElementIds.clear()
            app.removedElementSelectors.clear()
            app.insertedElements.clear()

            val hasElementStructural = removedSelectors.isNotEmpty() || insertedElements.isNotEmpty()

            when {
                // A property was *removed* (null/undefined bind). There is no targeted command
                // for "unset property", so a full clear + re-render is the only safe option.
                propStructural -> fullRebuild()

                // Elements were inserted and/or removed. Emit *targeted* remove/insert commands
                // so the scroll container (and the rest of the tree) is never destroyed — this
                // preserves the client's scroll position across the action. Falls back to a full
                // rebuild if the change can't be expressed safely (a stale or duplicated selector
                // would otherwise risk a client disconnect).
                hasElementStructural -> {
                    if (!emitTargetedStructural(removedSelectors, insertedElements, dirtyIds)) {
                        fullRebuild()
                    }
                }

                // Pure property changes on existing elements.
                dirtyIds.isNotEmpty() -> emitIncremental(dirtyIds)
            }
        }
    }

    // ── Scroll-preserving update strategy ───────────────────────────────────
    //
    // The Hytale client resets a TopScrolling container's scroll position whenever the
    // element (or an ancestor) is destroyed and recreated. The legacy path rebuilt the
    // ENTIRE page (`clear("#App") + appendInline`) on *any* structural change, so every
    // v-if toggle / list add+remove / panel open reset scroll across every UI. These helpers
    // instead emit the minimal targeted commands the Custom UI protocol already supports
    // (Remove / InsertBefore / Append), leaving the scroll container and the rest of the tree
    // alive so the client keeps the scroll offset. A full rebuild remains the safe fallback.

    /** Element ids (no `#`) the client currently holds. Mutated only on the V8 thread (onDirty/build). */
    private val clientElementIds = HashSet<String>()

    /** Full clear + re-render of #App. The unconditional fallback — always correct, but resets scroll. */
    private fun fullRebuild() {
        val cmdBuilder = UICommandBuilder()
            .clear("#App")
            .appendInline("#App", app.root.render(0))
        val evtBuilder = UIEventBuilder()
        registerEventBindings(evtBuilder)
        // The whole tree is what the client will now hold.
        clientElementIds.clear()
        clientElementIds.addAll(collectTreeElementIds(app.root))
        sendUpdateAsync(cmdBuilder, evtBuilder, false)
    }

    /** Incremental property update: re-send the properties of each dirty (existing) element. */
    private fun emitIncremental(dirtyIds: Set<String>) {
        val cmdBuilder = UICommandBuilder()
        for (rawId in dirtyIds) {
            val element = Element.idElementMap[rawId] ?: continue
            val selector = element.buildUniqueSelector()
            for (prop in element.properties.values) {
                if (!emitPropertySet(cmdBuilder, selector, prop)) {
                    fullRebuild()
                    return
                }
            }
        }
        sendUpdateAsync(cmdBuilder)
    }

    /**
     * Emit targeted structural commands for the inserted/removed elements in this batch, plus
     * property sets for any other dirty elements. Returns `false` (→ caller falls back to
     * [fullRebuild]) when the change can't be expressed safely with targeted commands — e.g. an
     * existing element was *repositioned* (which would duplicate its id), or an unsupported
     * property type was encountered.
     */
    private fun emitTargetedStructural(
        removedSelectors: List<String>,
        insertedElements: List<App.InsertedElement>,
        dirtyIds: Set<String>
    ): Boolean {
        val insertedRefs = insertedElements.map { it.child }.toSet()

        // Determine the top-level inserted subtree roots (whose parent is NOT itself newly
        // inserted — nested elements are carried by their ancestor's rendered markup).
        // Iterate the deduped set: Vue may emit the same child more than once per batch.
        val topLevel = ArrayList<Element>()
        for (child in insertedRefs) {
            if (!isReachableFromRoot(child)) continue          // inserted then removed/moved out this batch
            // A reorder of an element the client already has would duplicate its id — fall back.
            if (child.getId() in clientElementIds) return false
            val parent = child.parent ?: return false
            if (parent in insertedRefs) continue               // nested → covered by ancestor's render
            topLevel.add(child)
        }

        // All ids contained in the inserted subtrees (used for binding scope + dirty-skip).
        val insertedIds = HashSet<String>()
        for (root in topLevel) collectTreeElementIdsRecursive(root, insertedIds)

        val cmd = UICommandBuilder()
        var commandCount = 0

        // 1. Removals first. Selectors were captured at removal time = ids the client holds.
        //    Deduped: the same element must never be removed twice (second = remove-of-missing).
        for (sel in removedSelectors.toSet()) {
            cmd.remove(sel)
            commandCount++
        }

        // 2. Insertions, ordered against the live tree.
        for (child in topLevel) {
            val parent = child.parent ?: return false
            val parentSelector = if (parent is RootElement) "#App" else parent.buildUniqueSelector()
            val anchor = nextStableSibling(parent, child, insertedIds)
            val markup = child.render(0)
            if (anchor != null) {
                cmd.insertBeforeInline(anchor.buildUniqueSelector(), markup)
            } else {
                cmd.appendInline(parentSelector, markup)
            }
            commandCount++
        }

        // 3. Property sets for elements that changed but were NOT (re)created above.
        for (rawId in dirtyIds) {
            if (rawId in insertedIds) continue
            val element = Element.idElementMap[rawId] ?: continue
            val selector = element.buildUniqueSelector()
            for (prop in element.properties.values) {
                if (!emitPropertySet(cmd, selector, prop)) return false
                commandCount++
            }
        }

        if (commandCount == 0) return true  // everything cancelled out — nothing to send

        // 4. Bindings for the NEW subtrees only. Client-side bindings are additive, so
        //    re-sending existing elements' bindings would duplicate their callbacks.
        val evt = UIEventBuilder()
        registerEventBindingsForIds(evt, insertedIds)

        // Keep our view of the client's element set in sync with what we're about to send.
        clientElementIds.removeAll(removedSelectors.map { it.removePrefix("#") }.toSet())
        clientElementIds.addAll(insertedIds)

        sendUpdateAsync(cmd, evt, false)
        return true
    }

    /** True if [element] is still attached to this page's live tree (root === app.root). */
    private fun isReachableFromRoot(element: Element): Boolean {
        var cur: Element? = element
        while (cur != null) {
            if (cur === app.root) return true
            cur = cur.parent
        }
        return false
    }

    /**
     * The nearest following sibling of [child] that the client already holds (not inserted this
     * batch), to anchor an `InsertBefore`. Removed siblings are already detached from
     * [parent].children, so they're naturally skipped. Returns null → append to the parent.
     */
    private fun nextStableSibling(parent: ElementContainer, child: Element, insertedIds: Set<String>): Element? {
        val idx = parent.children.indexOf(child)
        if (idx < 0) return null
        for (i in (idx + 1) until parent.children.size) {
            val sibling = parent.children[i]
            if (sibling.getId() !in insertedIds) return sibling
        }
        return null
    }

    /** Register event bindings only for elements whose id is in [ids] (the newly inserted subtrees). */
    private fun registerEventBindingsForIds(uiEventBuilder: UIEventBuilder, ids: Set<String>) {
        for (binding in app.eventRegistry.getAllBindings()) {
            val rawId = binding.elementSelector.removePrefix("#")
            if (rawId !in ids) continue
            val eventData = EventData.of("RoutingKey", binding.routingKey).let {
                if (binding.bindingType == CustomUIEventBindingType.ValueChanged)
                    it.append("@Value", "${binding.elementSelector}.Value")
                else it
            }
            uiEventBuilder.addEventBinding(binding.bindingType, binding.elementSelector, eventData, false)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Dispatch a [sendUpdate] call asynchronously on the common ForkJoin pool.
     *
     * The [App.onDirty] callback fires on the `vuetale-v8` thread.  Calling [sendUpdate]
     * directly from that thread risks a deadlock: if [sendUpdate] acquires a Hytale
     * player/page lock and the game thread is simultaneously waiting for the V8 thread
     * via [JSEngine.evalScript] (e.g. during a second [build] call), both threads stall
     * forever.  Dispatching here keeps the V8 tick non-blocking.
     */
    private fun sendUpdateAsync(cmdBuilder: UICommandBuilder, evtBuilder: UIEventBuilder, lockInterface: Boolean) {
        if (DebugConfig.enabled) logger.info ("[vuetaledebug] Update render:\n${app.root.render(0)}")

        CompletableFuture.runAsync {
            if (isActive) runCatching { sendUpdate(cmdBuilder, evtBuilder, lockInterface) }
        }
    }

    private fun sendUpdateAsync(cmdBuilder: UICommandBuilder) {
        if (DebugConfig.enabled) logger.info ("[vuetaledebug] Update render:\n${app.root.render(0)}")

        CompletableFuture.runAsync {
            if (isActive) runCatching { sendUpdate(cmdBuilder, false) }
        }
    }

    private fun registerEventBindings(uiEventBuilder: UIEventBuilder) {
        // Defense-in-depth: only send bindings for elements actually in the render tree.
        // The eventRegistry.closeAll() in build() should prevent phantom bindings, but
        // this guard ensures a binding mismatch can never crash the client with
        // "Target element in CustomUI event binding was not found".
        val treeIds = collectTreeElementIds(app.root)
        var skipped = 0

        for (binding in app.eventRegistry.getAllBindings()) {
            val rawId = binding.elementSelector.removePrefix("#")
            if (rawId !in treeIds) {
                skipped++
                continue
            }

            // Only ValueChanged carries a Value payload – all other event types
            // (Activating, RightClicking, …) have no Value property on the element
            // and Hytale crashes with "Could not gather property value" if we request it.
            val eventData = EventData.of("RoutingKey", binding.routingKey).let {
                if (binding.bindingType == CustomUIEventBindingType.ValueChanged)
                    it.append("@Value", "${binding.elementSelector}.Value")
                else it
            }
            uiEventBuilder.addEventBinding(
                binding.bindingType,
                binding.elementSelector,
                eventData,
                false
            )
        }

        if (skipped > 0) {
            logger.warning("Filtered $skipped phantom event binding(s) not in render tree")
        }
    }

    /**
     * Recursively collect all element IDs in the tree rooted at [element].
     * Matches the exact set of elements that [Element.render] will serialize.
     */
    private fun collectTreeElementIds(element: Element): Set<String> {
        val ids = mutableSetOf<String>()
        collectTreeElementIdsRecursive(element, ids)
        return ids
    }

    private fun collectTreeElementIdsRecursive(element: Element, ids: MutableSet<String>) {
        ids.add(element.getId())
        if (element is ElementContainer) {
            for (child in element.children) {
                collectTreeElementIdsRecursive(child, ids)
            }
        }
    }

    /**
     * Emit a single `UICommandBuilder.set` command for [prop] on [elementSelector].
     *
     * Returns `true` if the property was handled, `false` if the type is unsupported
     * and the caller should fall back to a full re-render.
     *
     * [PropertyRecord] is handled recursively using dotted-path selectors
     * (e.g. `#id.Anchor.Left`).
     */
    private fun emitPropertySet(
        builder: UICommandBuilder,
        elementSelector: String,
        prop: Property
    ): Boolean {
        val path = "$elementSelector.${prop.name}"
        val ret = when (prop) {
            is PropertyString -> {
                val v = prop.value
                if (v != null) builder.set(path, v) else builder.setNull(path)
                true
            }

            is PropertyNumber -> {
                val v = prop.value
                when {
                    v == null -> builder.setNull(path)
                    v is Int -> builder.set(path, v)
                    v is Float -> builder.set(path, v)
                    v is Double -> builder.set(path, v)
                    else -> builder.set(path, v.toDouble())
                }
                true
            }

            is PropertyBoolean -> {
                val v = prop.value
                if (v != null) builder.set(path, v) else builder.setNull(path)
                true
            }

            is PropertyEnum -> {
                // Enum values are plain identifier strings (e.g. "Center", "Top")
                val v = prop.value
                if (v != null) builder.set(path, v) else builder.setNull(path)
                true
            }

            is PropertyRecord -> {
                // Recurse: emit one set command per sub-property with a dotted path
                prop.map.values.all { subProp ->
                    emitPropertySet(builder, "$elementSelector.${prop.name}", subProp)
                }
            }

            else -> false  // unknown type – trigger fallback
        }

        if (DebugConfig.enabled) logger.info (
                "[vuetaledebug] Changing property ${prop.name} of $elementSelector to ${prop.toString()}, Render:\n${
                    app.root.render(
                        0
                    )
                }"
                )

        return ret
    }

    // ── ItemForge extension: host-driven native ItemGrid population ─────────

    /**
     * Push a native `ItemGrid`'s `Slots` from host (Kotlin) code, bypassing Vue's
     * `:slots` serialization (which hangs at scale and emits invalid markup — see
     * `docs/DASHBOARD_TOOLTIP_AND_ITEMGRID_INVESTIGATION.md`). This is the engine's own
     * idiom (`EntitySpawnPage` does the same `set("#X.Slots", ItemGridSlot[])`).
     *
     * The element is found by the `id` set in Vue markup, which Vuetale stores as
     * [Element.customId]; the rendered selector is `"<customId><8 rand>"`, so we resolve
     * it live (walking [App.root]) rather than hardcoding the random suffix. A single
     * incremental `set("#<id>.Slots", slots)` is sent. Because Vue never binds `:slots`,
     * `Slots` is absent from the element's property map, so Vuetale's incremental render
     * (onDirty) never overwrites it. NOTE: a *structural* re-render (`clear("#App") +
     * appendInline`) WILL wipe it — the host must re-push after those.
     *
     * Threading: [InteractiveCustomUIPage.sendUpdate] self-marshals onto the world thread
     * via `world.execute`, so this is safe from any non-V8, non-world thread (e.g. a host
     * executor). NEVER call it from the V8 thread or directly on the world thread (the
     * freeze rules — a hostage world thread can't process the marshalled task).
     *
     * @param customId the Vue `id` attribute on the target `<ItemGrid>` element.
     * @param slots    the native slots to display (empty array clears the grid).
     * @return `true` if the element was found and an update was dispatched; `false` if the
     *         element isn't in the current render tree (e.g. the grid view isn't active).
     */
    fun pushItemGridSlots(customId: String, slots: Array<ItemGridSlot>): Boolean {
        if (!isActive) return false
        val gridId = findRenderedIdByCustomId(app.root, customId) ?: return false
        // Off-tree (incremental) Set — clear=false. Vue's render tree is untouched, so no
        // event-binding churn; the grid's own SlotClicking bindings (registered at mount)
        // stay valid.
        val cmd = UICommandBuilder().set("#$gridId.Slots", slots)
        sendUpdate(cmd, false)
        return true
    }

    /**
     * Push a native `DropdownBox`'s `Entries` list from host (Kotlin) code, bypassing Vue's
     * child-`DropdownEntry` rendering (which builds one native element per option — slow at scale and
     * the cause of slow editor/dashboard opens on heavy modpacks). This is the engine's own idiom:
     * Hytale builtin pages do `set("#sel.Entries", List<DropdownEntryInfo>)` and the client
     * renders/virtualizes the rows itself from the data.
     *
     * Twin of [pushItemGridSlots]: the target `<DropdownBox>` is found by its Vue `id`
     * ([Element.customId]); a single off-tree `set("#<id>.Entries", entries)` is sent. Because Vue
     * never binds `:entries`, `Entries` is absent from the element's property map, so the incremental
     * render (onDirty) never overwrites it. We deliberately do NOT push `.Value` here — Vue owns
     * `:value` on the DropdownBox; pushing Value host-side would fight Vue's incremental render of the
     * selection. NOTE: a *structural* re-render (`clear("#App")+appendInline`) WILL wipe `Entries` —
     * the host must re-push after those (FieldPicker re-pushes on its repush token).
     *
     * Threading: identical to [pushItemGridSlots] — [sendUpdate] self-marshals onto the world thread,
     * so this is safe from any non-V8, non-world thread (a host executor). NEVER call it from the V8
     * thread or directly on the world thread.
     *
     * @param customId the Vue `id` attribute on the target `<DropdownBox>` element.
     * @param entries  the entry rows to display (empty array clears the list).
     * @return `true` if the element was found and an update was dispatched; `false` if the element
     *         isn't in the current render tree (e.g. its tab/source isn't mounted) — the caller relies
     *         on a later re-push, exactly as the grid does.
     */
    fun pushDropdownEntries(customId: String, entries: Array<DropdownEntryInfo>): Boolean {
        if (!isActive) return false
        val dropdownId = findRenderedIdByCustomId(app.root, customId) ?: return false
        val cmd = UICommandBuilder().set("#$dropdownId.Entries", entries)
        sendUpdate(cmd, false)
        return true
    }

    /**
     * Depth-first search of the live element tree for the element whose [Element.customId]
     * equals [customId], returning its rendered [Element.getId]. Scoped to this page's tree
     * (not the global [Element.idElementMap]) so a stale element from another page can never
     * be targeted.
     */
    private fun findRenderedIdByCustomId(element: Element, customId: String): String? {
        if (element.customId == customId) return element.getId()
        if (element is ElementContainer) {
            for (child in element.children) {
                findRenderedIdByCustomId(child, customId)?.let { return it }
            }
        }
        return null
    }

    // ── handleDataEvent ────────────────────────────────────────────────────

    override fun handleDataEvent(
        ref: Ref<EntityStore>,
        store: Store<EntityStore>,
        data: VuetaleEventData
    ) {
        super.handleDataEvent(ref, store, data)

        // Drop events that arrive after the page has been dismissed (e.g. stale packets
        // sent by the client after pressing ESC). Without this guard, runOnV8Thread's
        // Future.get() can throw InterruptedException if the thread is interrupted
        // during page teardown.
        if (!isActive) return

        // Capture only plain values here – never capture the V8ValueFunction itself outside
        // the V8 thread, because a concurrent hot-reload may close the old runtime and clear
        // the event registry between this line and the task actually executing on the V8 thread.
        val routingKey = data.routingKey
        val value = data.value

        try {
            JSEngine.instance.runOnV8Thread {
                // Re-fetch the binding *inside* the V8 task so we always use the live reference.
                // If a hot-reload fired in the meantime, forceReset() will have cleared the
                // registry and this returns null – skipping callVoid before touching the closed runtime.
                val liveBinding = app.eventRegistry.findByRoutingKey(routingKey)
                if (liveBinding == null) {
                    logger.warning("No binding found for routingKey='$routingKey' (may be stale after hot-reload)")
                    return@runOnV8Thread
                }
                runCatching {
                    liveBinding.callback.callVoid(null, value)
                }.onFailure {
                    logger.warning("Error invoking callback for '$routingKey': ${it.message}")
                }
            }
        } catch (_: InterruptedException) {
            // The server thread was interrupted (e.g. during world shutdown or page dismissal).
            // Restore the interrupt flag and drop this event silently.
            Thread.currentThread().interrupt()
            logger.fine("handleDataEvent interrupted for routingKey='$routingKey' (page may have been dismissed)")
            return
        } catch (e: Exception) {
            logger.warning("handleDataEvent failed for routingKey='$routingKey': ${e.message}")
            return
        }

        // ── ACK sendUpdate REMOVED (ItemForge performance patch) ─────────
        //
        // The original code called sendUpdate() here to "acknowledge" the
        // event. But sendUpdate() → PageManager.updateCustomPage() increments
        // customPageRequiredAcknowledgments, which DISCARDS all subsequent
        // client events until the client ACKs the empty update. Combined with
        // the content update from onDirty (also increments the counter), this
        // forces TWO full client-server round trips per interaction — the
        // primary source of perceived UI lag (200-500ms per click).
        //
        // Without the ACK, the counter stays at 0 until onDirty's content
        // update fires. Events remain accepted during that window. Stale
        // events (for destroyed elements) are handled gracefully:
        // eventRegistry.findByRoutingKey returns null → warning logged → no-op.
        //
        // The content update from onDirty still sends sendUpdate with actual
        // UI commands, so the client eventually receives fresh state. The
        // counter then increments to 1 (not 2), requiring only ONE round
        // trip before new events are accepted — 50% improvement.
    }

    // ── onDismiss ──────────────────────────────────────────────────────────

    override fun onDismiss(ref: Ref<EntityStore>, store: Store<EntityStore>) {
        // Prevent any in-flight async sendUpdate from reaching a dismissed page.
        isActive = false
        // Detach the dirty callback first to avoid any stray update after unmount
        app.onDirty = null

        // Only remove this specific App instance.  If the user opened the page a
        // second time before dismissing the first, the constructor will have already
        // replaced this app in AppManager with a new one.  Calling removeApp by
        // owner+type would then unmount the *new* app, killing the second session.
        if (AppManager.getApp(app.getId()) === app) {
            AppManager.removeApp(app.owner, app.type)
        }

        // ── Stuck-tooltip fix (ESC dismiss) ──────────────────────────────────
        // The item/text tooltip is a single GLOBAL client HUD overlay (ItemTooltip.ui,
        // referenced for style only in Game/Interface/Common.ui — instantiated by client
        // engine code, NOT part of any page). It shows on hover of an item-bearing element
        // and hides only when the client RE-EVALUATES hover and finds nothing under the
        // cursor.
        //
        // When the player presses ESC on a CanDismiss page, the dismiss is CLIENT-
        // AUTHORITATIVE: the client tears the page document down itself, then merely
        // notifies the server (PageManager.handleEvent → case Dismiss → onDismiss(); no
        // SetPage is sent, no ack incremented — verified, Hytale 0.5.x). Unlike a built-in
        // page close, the client does NOT clear the global tooltip overlay on that path, so
        // a tooltip the cursor was hovering (e.g. the dashboard ItemGrid's InfoDisplay=
        // Tooltip) orphans on screen until the next hover elsewhere. The Close-button /
        // navigate flows don't exhibit this because they are SERVER-driven (setPage(None) /
        // a clear+appendInline document update) and/or move the cursor off the item first —
        // both of which trigger the client's hover re-evaluation.
        //
        // Fix: re-assert SetPage(None) to the client so it runs the same page-transition
        // path the working flows use. Written as a RAW packet — NOT pageManager.setPage —
        // because during onDismiss the manager's customPage still points at THIS page, so
        // setPage(None) would re-enter onDismiss (infinite recursion). This is the exact
        // packet PageManager.setPage emits (writeNoCache(SetPage(Page.None, false))), minus
        // the server-state mutation. Synchronous on purpose: in the page-REPLACE path
        // (openCustomPage → old.onDismiss → new page sent) the new-page packet follows
        // immediately on the same thread and wins; deferring could instead close the freshly
        // opened page. In the Close-button path it's a harmless duplicate SetPage(None).
        runCatching {
            playerRef.packetHandler.writeNoCache(SetPage(Page.None, false))
        }.onFailure { logger.warning("onDismiss: tooltip-clear SetPage(None) re-assert failed: ${it.message}") }

        super.onDismiss(ref, store)
    }
}


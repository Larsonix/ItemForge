package me.itemforge.entry

import com.hypixel.hytale.common.util.StringUtil
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import me.itemforge.config.ConfigManager
import me.itemforge.core.HealthMonitor
import me.itemforge.inspect.InspectModeManager
import me.itemforge.metadata.ModSourceTracker
import me.itemforge.metadata.TagCache
import me.itemforge.provider.ExtensionRegistry
import me.itemforge.util.PermissionChecker
import me.itemforge.vuetale.VuetaleIntegration
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult
import java.util.concurrent.CompletableFuture

/**
 * Root command: `/itemforge`
 *
 * Subcommands:
 * - `/itemforge status` — health check, mod/item counts, override summary
 * - `/itemforge reload` — re-read config files AND apply/revert override changes
 * - `/itemforge edit <item_id>` — open editor for specific item
 *
 * Shorthand (via addUsageVariant):
 * - `/itemforge <item_id>` — same as `/itemforge edit <id>`
 *   Same pattern as Hytale's `/warp <name>` (WarpCommand.java:34).
 *   Evidence: AbstractCommand.java:559 (variant routing in checkForExecutingSubcommands).
 *
 * Dashboard (zero args):
 * - `/itemforge` (no args) → opens balance dashboard
 *
 * Inspect mode:
 * - `/itemforge inspect` → toggles crouch + right-click editing for the sender
 *
 * ## Why AbstractAsyncCommand, not AbstractCommandCollection
 *
 * AbstractCommandCollection.executeAsync() is `final` — always shows usage text.
 * Variants are keyed by required-arg count (AbstractCommand.java:427), and the
 * routing condition (line 560) is `this.totalNumRequiredParameters != argCount`.
 * Since the collection has 0 required args and a zero-arg variant also has 0,
 * `0 != 0` is false → variant never fires → usage text always shown.
 *
 * By extending AbstractAsyncCommand directly, `/itemforge` (0 args) fires our
 * executeAsync() which opens the dashboard. Subcommands and the 1-arg EditVariant
 * still route correctly — addSubCommand/addUsageVariant are on AbstractCommand.
 */
class ItemForgeCommand(
    configManager: ConfigManager,
    healthMonitor: HealthMonitor,
    modSourceTracker: ModSourceTracker,
    tagCache: TagCache,
    reloadHandler: () -> String,
    private val vuetaleIntegration: VuetaleIntegration,
    inspectModeManager: InspectModeManager,
    extensionRegistry: ExtensionRegistry
) : AbstractAsyncCommand("itemforge", "ItemForge - in-game item property editor") {

    init {
        addSubCommand(StatusSubCommand(healthMonitor, configManager, modSourceTracker, tagCache))
        addSubCommand(ReloadSubCommand(reloadHandler))
        addSubCommand(EditSubCommand(vuetaleIntegration, configManager))
        addSubCommand(InspectSubCommand(inspectModeManager))
        addSubCommand(ExtensionsSubCommand(extensionRegistry))
        addUsageVariant(EditVariant(vuetaleIntegration, configManager))
    }

    /**
     * Fires when `/itemforge` is typed with zero args (no subcommand match).
     * Opens the balance dashboard.
     */
    override fun executeAsync(context: CommandContext): CompletableFuture<Void> {
        val sender = context.sender()

        if (!sender.hasPermission(PermissionChecker.ADMIN) &&
            !sender.hasPermission(PermissionChecker.COMMAND_BROWSE)) {
            context.sendMessage(Message.raw("You don't have permission to use ItemForge."))
            return CompletableFuture.completedFuture(null)
        }

        if (!vuetaleIntegration.initialized) {
            context.sendMessage(Message.raw("Vuetale UI is not available. Check server logs for initialization errors."))
            return CompletableFuture.completedFuture(null)
        }

        val ref = context.senderAsPlayerRef()
        if (ref == null || !ref.isValid) {
            context.sendMessage(Message.raw("This command can only be used by a player in the world."))
            return CompletableFuture.completedFuture(null)
        }

        // Dispatch to world thread — Store.getComponent() has thread assertion
        val store = ref.store
        val world = store.externalData.world

        world.execute {
            try {
                val playerRef = store.getComponent(ref, PlayerRef.getComponentType())
                if (playerRef == null) {
                    context.sendMessage(Message.raw("Could not resolve player entity."))
                    return@execute
                }
                vuetaleIntegration.openDashboard(playerRef, ref, store)
            } catch (e: Exception) {
                context.sendMessage(Message.raw("Failed to open dashboard: ${e.message}"))
            }
        }

        return CompletableFuture.completedFuture(null)
    }
}

/**
 * `/itemforge status` — shows plugin health check, mod/item counts, override summary.
 */
private class StatusSubCommand(
    private val healthMonitor: HealthMonitor,
    private val configManager: ConfigManager,
    private val modSourceTracker: ModSourceTracker,
    private val tagCache: TagCache
) : AbstractAsyncCommand("status", "Show ItemForge status and health check") {

    override fun executeAsync(context: CommandContext): CompletableFuture<Void> {
        val sender = context.sender()

        if (!sender.hasPermission(PermissionChecker.ADMIN) &&
            !sender.hasPermission(PermissionChecker.COMMAND_BROWSE)) {
            context.sendMessage(Message.raw("You don't have permission to use this command."))
            return CompletableFuture.completedFuture(null)
        }

        val report = healthMonitor.check()
        context.sendMessage(Message.raw("--- ItemForge Status ---"))
        context.sendMessage(Message.raw("Health: ${report.passCount}/${report.checks.size} checks passed"))

        for (check in report.checks) {
            if (!check.passed) {
                context.sendMessage(Message.raw("  FAIL: ${check.name} - ${check.detail}"))
            }
        }

        val modCount = modSourceTracker.getAllModNames().size
        val typeCount = tagCache.getValuesForKey("Type").size
        val itemOverrides = configManager.itemOverrideCount()
        val recipeOverrides = configManager.recipeOverrideCount()

        context.sendMessage(Message.raw("Mods: $modCount"))
        context.sendMessage(Message.raw("Item types: $typeCount"))
        context.sendMessage(Message.raw("Item overrides: $itemOverrides"))
        context.sendMessage(Message.raw("Recipe overrides: $recipeOverrides"))
        context.sendMessage(Message.raw("---"))

        return CompletableFuture.completedFuture(null)
    }
}

// ── Shared Edit Logic ──────────────────────────────────────────────────

/**
 * Populates tab-complete suggestions for item ID arguments.
 * Shows overridden items first (most likely edit targets), then fuzzy matches.
 * Used by both [EditSubCommand] and [EditVariant].
 */
private fun suggestItemIds(configManager: ConfigManager, textEntered: String?, result: SuggestionResult) {
    val allIds = Item.getAssetMap().assetMap.keys
    if (textEntered.isNullOrEmpty()) {
        val overridden = configManager.itemOverrides.getOverriddenItemIds()
        for (id in overridden.take(5)) {
            result.suggest(id)
        }
        if (overridden.size < 5) {
            allIds.sorted().take(5 - overridden.size).forEach { result.suggest(it) }
        }
    } else {
        StringUtil.sortByFuzzyDistance(textEntered, allIds, 10).forEach { result.suggest(it) }
    }
}

/**
 * Shared execution for editing an item: permission check → item lookup →
 * world thread dispatch → openEditor. Used by both [EditSubCommand] and [EditVariant].
 */
private fun executeEditCommand(
    context: CommandContext,
    itemId: String,
    vuetaleIntegration: VuetaleIntegration
): CompletableFuture<Void> {
    val sender = context.sender()

    // Open the editor for anyone with ANY edit permission. Tabs the
    // player can't edit render read-only; the bridge re-checks on every write.
    // (Was ADMIN-only, which made the granular edit nodes unreachable.)
    if (!PermissionChecker.canEditAnything(sender)) {
        context.sendMessage(Message.raw("You don't have permission to edit items."))
        return CompletableFuture.completedFuture(null)
    }

    if (!vuetaleIntegration.initialized) {
        context.sendMessage(Message.raw("Vuetale UI is not available. Check server logs for initialization errors."))
        return CompletableFuture.completedFuture(null)
    }

    val item: Item? = Item.getAssetMap().getAsset(itemId)
    if (item == null) {
        context.sendMessage(Message.raw("Item '$itemId' not found."))
        return CompletableFuture.completedFuture(null)
    }

    val ref = context.senderAsPlayerRef()
    if (ref == null || !ref.isValid) {
        context.sendMessage(Message.raw("This command can only be used by a player in the world."))
        return CompletableFuture.completedFuture(null)
    }

    // Dispatch to world thread — Store.getComponent() has a thread assertion.
    // AbstractAsyncCommand runs on ForkJoinPool, not WorldThread.
    val store = ref.store
    val world = store.externalData.world

    world.execute {
        try {
            val playerRef = store.getComponent(ref, PlayerRef.getComponentType())
            if (playerRef == null) {
                context.sendMessage(Message.raw("Could not resolve player entity."))
                return@execute
            }
            vuetaleIntegration.openEditor(playerRef, ref, store, itemId)
        } catch (e: Exception) {
            context.sendMessage(Message.raw("Failed to open editor: ${e.message}"))
        }
    }

    return CompletableFuture.completedFuture(null)
}

// ── Edit Commands ──────────────────────────────────────────────────────

/**
 * `/itemforge edit <item_id>` — explicit subcommand form.
 * The shorthand `/itemforge <id>` is handled by [EditVariant].
 */
private class EditSubCommand(
    private val vuetaleIntegration: VuetaleIntegration,
    private val configManager: ConfigManager
) : AbstractAsyncCommand("edit", "Open the editor for a specific item") {

    private val itemIdArg = withRequiredArg<String>(
        "item_id",
        "The item ID to edit (e.g., Armor_Iron_Chest)",
        com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING
    ).suggest { _, textEntered, _, result ->
        suggestItemIds(configManager, textEntered, result)
    }

    override fun executeAsync(context: CommandContext): CompletableFuture<Void> =
        executeEditCommand(context, itemIdArg.get(context), vuetaleIntegration)
}

/**
 * `/itemforge <item_id>` — shorthand variant (no subcommand name).
 *
 * Registered via [addUsageVariant]. When no subcommand matches but 1 arg
 * is provided, Hytale's command system routes here. Same pattern as
 * `/warp <name>` (WarpGoVariantCommand.java).
 */
private class EditVariant(
    private val vuetaleIntegration: VuetaleIntegration,
    private val configManager: ConfigManager
) : AbstractAsyncCommand("Open the editor for a specific item") {

    private val itemIdArg = withRequiredArg<String>(
        "item_id",
        "The item ID to edit (e.g., Armor_Iron_Chest)",
        com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING
    ).suggest { _, textEntered, _, result ->
        suggestItemIds(configManager, textEntered, result)
    }

    override fun executeAsync(context: CommandContext): CompletableFuture<Void> =
        executeEditCommand(context, itemIdArg.get(context), vuetaleIntegration)
}

/**
 * `/itemforge inspect` — toggles Inspect Mode for the sender.
 *
 * While inspect mode is ON, crouch + right-clicking a held item opens that item's editor
 * (handled by InspectInteractionListener) instead of performing the item's normal
 * interaction (suppressed by InspectSuppressor). Toggling again turns it off.
 *
 * Gated by [PermissionChecker.canEditAnything] — the same gate as opening the editor — so
 * a player can never reach the editor via the gesture that they couldn't reach via command.
 * Player-only: the toggle is keyed by player UUID, resolved on the world thread.
 */
private class InspectSubCommand(
    private val inspectModeManager: InspectModeManager
) : AbstractAsyncCommand("inspect", "Toggle inspect mode - crouch + right-click an item to edit it") {

    override fun executeAsync(context: CommandContext): CompletableFuture<Void> {
        val sender = context.sender()

        if (!PermissionChecker.canEditAnything(sender)) {
            context.sendMessage(Message.raw("You don't have permission to use inspect mode."))
            return CompletableFuture.completedFuture(null)
        }

        val ref = context.senderAsPlayerRef()
        if (ref == null || !ref.isValid) {
            context.sendMessage(Message.raw("Inspect mode can only be used by a player in the world."))
            return CompletableFuture.completedFuture(null)
        }

        // Resolve the player UUID on the world thread (Store.getComponent has a thread assertion).
        val store = ref.store
        val world = store.externalData.world

        world.execute {
            try {
                val playerRef = store.getComponent(ref, PlayerRef.getComponentType())
                if (playerRef == null) {
                    context.sendMessage(Message.raw("Could not resolve player entity."))
                    return@execute
                }
                val nowOn = inspectModeManager.toggle(playerRef.uuid)
                if (nowOn) {
                    context.sendMessage(Message.raw(
                        "Inspect mode ON - crouch + right-click an item to open its editor. " +
                            "Run /itemforge inspect again to turn it off."
                    ))
                } else {
                    context.sendMessage(Message.raw("Inspect mode OFF."))
                }
            } catch (e: Exception) {
                context.sendMessage(Message.raw("Failed to toggle inspect mode: ${e.message}"))
            }
        }

        return CompletableFuture.completedFuture(null)
    }
}

/**
 * `/itemforge extensions` — lists the registered [me.itemforge.provider.EditorExtension]s.
 *
 * Editor extensions are the public API by which mods plug their own panels into the editor to
 * expose item fields/configs ItemForge can't read automatically. This command lets an admin
 * verify what's registered and which extensions are currently bypassed.
 */
private class ExtensionsSubCommand(
    private val extensionRegistry: ExtensionRegistry
) : AbstractAsyncCommand("extensions", "List registered editor extensions (mod API)") {

    override fun executeAsync(context: CommandContext): CompletableFuture<Void> {
        val sender = context.sender()
        if (!sender.hasPermission(PermissionChecker.ADMIN) &&
            !sender.hasPermission(PermissionChecker.COMMAND_BROWSE)) {
            context.sendMessage(Message.raw("You don't have permission to use this command."))
            return CompletableFuture.completedFuture(null)
        }

        val all = extensionRegistry.all()
        if (all.isEmpty()) {
            context.sendMessage(Message.raw(
                "No editor extensions registered. Mods register via ItemForgeAPI.registerExtension(...) " +
                    "to add their own editor panels for items."
            ))
            return CompletableFuture.completedFuture(null)
        }

        context.sendMessage(Message.raw("Registered editor extensions (${all.size}):"))
        for (extension in all) {
            val id = try { extension.getExtensionId() } catch (e: Exception) { "?" }
            val name = try { extension.getDisplayName() } catch (e: Exception) { id }
            val bypassed = if (extensionRegistry.isBypassed(id)) "  [bypassed]" else ""
            context.sendMessage(Message.raw("  - $name  ($id)$bypassed"))
        }
        return CompletableFuture.completedFuture(null)
    }
}

/**
 * `/itemforge reload` — full reload: re-reads config AND applies override diff.
 *
 * The [reloadHandler] lambda is provided by ItemForgePlugin.performReload(),
 * which orchestrates the full diff-and-apply cycle across all subsystems.
 */
private class ReloadSubCommand(
    private val reloadHandler: () -> String
) : AbstractAsyncCommand("reload", "Reload ItemForge configuration and apply changes") {

    override fun executeAsync(context: CommandContext): CompletableFuture<Void> {
        val sender = context.sender()

        if (!sender.hasPermission(PermissionChecker.ADMIN)) {
            context.sendMessage(Message.raw("You don't have permission to reload ItemForge."))
            return CompletableFuture.completedFuture(null)
        }

        context.sendMessage(Message.raw("Reloading ItemForge..."))

        try {
            val summary = reloadHandler()
            context.sendMessage(Message.raw(summary))
        } catch (e: Exception) {
            context.sendMessage(Message.raw("Reload failed: ${e.message}"))
        }

        return CompletableFuture.completedFuture(null)
    }
}

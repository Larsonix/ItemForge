package me.itemforge.util

import com.hypixel.hytale.server.core.permissions.PermissionHolder
import com.hypixel.hytale.server.core.permissions.PermissionsModule
import com.hypixel.hytale.logger.HytaleLogger
import java.util.UUID

/**
 * Centralized permission checking for all ItemForge actions.
 *
 * ## Permission Tree
 *
 * ```
 * itemforge.admin              → full access (all below)
 * itemforge.edit.stats         → modify stat modifiers, damage, durability
 * itemforge.edit.recipes       → modify recipe inputs, time, bench requirements
 * itemforge.edit.general       → modify quality, item level, stack size
 * itemforge.command.browse     → open the item browser dashboard
 * itemforge.command.reset      → revert items to default values
 * ```
 *
 * ## Hierarchy
 *
 * Hytale's PermissionHolder.hasPermission() checks exact nodes only — no
 * built-in wildcard/hierarchy. This checker implements the hierarchy:
 * every check falls back to `itemforge.admin`. A player with `itemforge.admin`
 * passes ALL permission checks.
 *
 * If the server uses LuckPerms (detected on our test server), admins can also
 * use `itemforge.*` wildcards — LuckPerms handles that expansion. Our fallback
 * to `.admin` is a safety net for servers without wildcard support.
 *
 * ## Usage Pattern (from EDGE_CASES.md EC-10.1)
 *
 * **Check on every write, not just on open.** Permission can be revoked while
 * the editor is open. Every save, reset, and batch operation re-checks.
 *
 * ```kotlin
 * // In SaveFlowHandler:
 * if (!PermissionChecker.canEditStats(player)) {
 *     pushEvent(playerRef, "permissionLost")
 *     return
 * }
 * ```
 */
object PermissionChecker {

    private val logger = HytaleLogger.forEnclosingClass()

    // ── Permission Node Constants ────────────────────────────────────────

    /** Full access — grants all permissions below. */
    const val ADMIN = "itemforge.admin"

    /** Modify stat modifiers, damage values, durability, and resistance. */
    const val EDIT_STATS = "itemforge.edit.stats"

    /** Modify recipe inputs, quantities, crafting time, and bench requirements. */
    const val EDIT_RECIPES = "itemforge.edit.recipes"

    /** Modify quality, item level, stack size, and other general properties. */
    const val EDIT_GENERAL = "itemforge.edit.general"

    /** Open the item browser dashboard (/itemforge). */
    const val COMMAND_BROWSE = "itemforge.command.browse"

    /** Revert items to their original values (/itemforge reset). */
    const val COMMAND_RESET = "itemforge.command.reset"

    // ── Permission Checks ────────────────────────────────────────────────

    /**
     * Can the player open the item browser dashboard?
     * Required for `/itemforge` command.
     */
    fun canBrowse(holder: PermissionHolder): Boolean {
        return check(holder, COMMAND_BROWSE)
    }

    /**
     * Can the player modify stat modifiers, damage, durability, and resistance?
     * Checked on every save for stat/component fields.
     */
    fun canEditStats(holder: PermissionHolder): Boolean {
        return check(holder, EDIT_STATS)
    }

    /**
     * Can the player modify recipe inputs, quantities, time, and bench requirements?
     * Checked on every recipe save.
     */
    fun canEditRecipes(holder: PermissionHolder): Boolean {
        return check(holder, EDIT_RECIPES)
    }

    /**
     * Can the player modify general properties (quality, level, stack size, etc.)?
     * Checked on every save for general tab fields.
     */
    fun canEditGeneral(holder: PermissionHolder): Boolean {
        return check(holder, EDIT_GENERAL)
    }

    /**
     * Can the player revert items to their original values?
     * Checked on single-item reset and batch reset.
     */
    fun canReset(holder: PermissionHolder): Boolean {
        return check(holder, COMMAND_RESET)
    }

    /**
     * Can the player edit ANY field on the given item?
     * Used to determine if the editor should open in read-only vs edit mode.
     * Returns true if the player has any edit permission.
     */
    fun canEditAnything(holder: PermissionHolder): Boolean {
        return holder.hasPermission(ADMIN) ||
            holder.hasPermission(EDIT_STATS) ||
            holder.hasPermission(EDIT_RECIPES) ||
            holder.hasPermission(EDIT_GENERAL)
    }

    /**
     * Does the player have any ItemForge access at all?
     * Used as a quick gate before any ItemForge functionality.
     */
    fun hasAnyAccess(holder: PermissionHolder): Boolean {
        return holder.hasPermission(ADMIN) ||
            holder.hasPermission(COMMAND_BROWSE) ||
            holder.hasPermission(EDIT_STATS) ||
            holder.hasPermission(EDIT_RECIPES) ||
            holder.hasPermission(EDIT_GENERAL) ||
            holder.hasPermission(COMMAND_RESET)
    }

    // ── UUID-Keyed Checks (for bridge re-validation on every write) ──────
    //
    // Vuetale bridge methods (save/reset) run on the V8 thread and receive only
    // the player's UUID string — no PermissionHolder. Permissions are UUID-keyed
    // under the hood: PlayerRef.hasPermission(id) delegates to
    // PermissionsModule.get().hasPermission(uuid, id) (PlayerRef.java:208).
    // We call the same backend directly so writes can be re-checked even after
    // the editor was opened (permission may be revoked mid-session — see the
    // class doc "Check on every write, not just on open").

    /** Can the player (by UUID) modify stat modifiers, damage, durability, resistance? */
    fun canEditStats(uuid: UUID): Boolean = check(uuid, EDIT_STATS)

    /** Can the player (by UUID) modify recipe inputs, quantities, time, benches? */
    fun canEditRecipes(uuid: UUID): Boolean = check(uuid, EDIT_RECIPES)

    /** Can the player (by UUID) modify general properties (quality, level, stack)? */
    fun canEditGeneral(uuid: UUID): Boolean = check(uuid, EDIT_GENERAL)

    /** Can the player (by UUID) revert items to their original values? */
    fun canReset(uuid: UUID): Boolean = check(uuid, COMMAND_RESET)

    /** Does the player (by UUID) have any edit permission? Used as the editor-open gate. */
    fun canEditAnything(uuid: UUID): Boolean =
        check(uuid, ADMIN) || check(uuid, EDIT_STATS) ||
            check(uuid, EDIT_RECIPES) || check(uuid, EDIT_GENERAL)

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Checks a specific permission with ADMIN fallback.
     * Every check goes through here to ensure consistent hierarchy behavior.
     */
    private fun check(holder: PermissionHolder, node: String): Boolean {
        return holder.hasPermission(node) || holder.hasPermission(ADMIN)
    }

    /**
     * UUID-keyed check with ADMIN fallback. Mirrors [check] (PermissionHolder)
     * but resolves through [PermissionsModule] for off-thread bridge calls.
     *
     * Fail-closed: if PermissionsModule is somehow unavailable (should never
     * happen — it is a core server module), deny rather than grant. Logged so
     * the failure is visible rather than silently locking out a legitimate admin.
     */
    private fun check(uuid: UUID, node: String): Boolean {
        return try {
            val module = PermissionsModule.get()
            module.hasPermission(uuid, node) || module.hasPermission(uuid, ADMIN)
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log(
                "PermissionChecker: UUID check failed for node '%s' (uuid=%s) — denying", node, uuid
            )
            false
        }
    }
}

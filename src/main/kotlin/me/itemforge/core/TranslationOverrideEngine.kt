package me.itemforge.core

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.protocol.UpdateType
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.modules.i18n.I18nModule
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import me.itemforge.config.TranslationOverrideStore
import me.itemforge.metadata.ItemNameResolver
import java.nio.file.Path

/**
 * Applies and persists **global custom Name / Lore overrides** for an item *type* — the global half
 * of the last pre-release feature (the local half is per-stack `ItemDisplay` metadata, handled in
 * `EditorBridge`).
 *
 * ## Why a broadcast, not an asset override
 * An item's display name is NOT a literal asset field — Hytale resolves it from a translation *key*
 * (`server.items.<id>.name`), looked up client-side against the translation table the server sends.
 * So "rename this item type for everyone" means: push a new VALUE for that key to every client.
 * That is exactly what the engine's own `I18nModule` does for bench labels at load time
 * (`UpdateTranslations(AddOrUpdate, …)` broadcast). We use the same **public** packet path:
 *  - **On change** → broadcast `UpdateTranslations(AddOrUpdate)` to every connected client.
 *  - **On join** → re-send all active overrides to the joining player ([resendTo]), because the
 *    engine's join-time translation Init is built from its own (private) language map, which our
 *    runtime overrides are not part of.
 * This is language-agnostic (the client maps key→text regardless of its locale) and needs no
 * reflection into engine internals.
 *
 * ## Revert
 * Clearing a custom name re-broadcasts the item's ORIGINAL text (read from `I18nModule.getMessage`,
 * which still returns the untouched file-loaded value — we never mutate the server's language map),
 * restoring every client to the stock name.
 *
 * ## Boundary (honest, v1)
 * The override targets the key the item *currently resolves to* (`item.getTranslationKey()`). For the
 * overwhelming majority that is the item's own unique `server.items.<id>.name`. A few items (quality
 * variants) *borrow* another item's key; renaming such an item also renames the others that share the
 * key. We log a warning in that case rather than silently reassigning the asset's key (reassignment is
 * a future enhancement); the admin owns that choice.
 *
 * Threading: [setName]/[setLore] are called from the editor save flow; the packet broadcast only
 * enqueues to each client's channel (thread-safe), so no world-thread hop is required.
 */
class TranslationOverrideEngine(
    dataDirectory: Path,
    private val logger: HytaleLogger
) {
    private val store = TranslationOverrideStore(
        dataDirectory.resolve("overrides").resolve("translations.json"),
        logger
    )

    /** Loads persisted overrides at plugin start. (Re-broadcast happens lazily per player on join.) */
    fun load() {
        val count = store.load()
        if (count > 0) logger.atInfo().log("ItemForge: loaded %d custom name/lore override(s)", count)
    }

    // ── Editor prefill (current effective text) ────────────────────────────────────────────────

    /** The item type's CURRENT effective display name: the active override if any, else the stock name. */
    fun currentName(itemId: String, item: Item): String =
        store.get(itemId)?.name ?: ItemNameResolver.resolve(item)

    /** The item type's CURRENT effective lore/description: the active override if any, else the stock
     *  description (empty when the item has none). */
    fun currentLore(itemId: String, item: Item): String =
        store.get(itemId)?.lore ?: stockLore(item)

    // ── Apply ───────────────────────────────────────────────────────────────────────────────────

    /** Sets ([text] non-blank) or reverts ([text] null/blank) the item type's custom NAME. */
    fun setName(itemId: String, item: Item, text: String?) {
        val key = item.translationKey
        warnIfBorrowed(itemId, key, "name")
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        store.setName(itemId, key, normalized)
        val effective = normalized ?: (stockMessage(key) ?: ItemNameResolver.resolve(item))
        broadcast(mapOf(key to effective))
        logger.atInfo().log("ItemForge: global name for '%s' %s", itemId,
            if (normalized != null) "set to \"$normalized\"" else "reverted")
    }

    /** Sets or reverts the item type's custom LORE/description. */
    fun setLore(itemId: String, item: Item, text: String?) {
        val key = item.descriptionTranslationKey
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        store.setLore(itemId, key, normalized)
        // Reverting lore: restore the stock description, or empty string when the item never had one
        // (a key with no entry would otherwise render as the raw key on the client).
        val effective = normalized ?: (stockMessage(key) ?: "")
        broadcast(mapOf(key to effective))
        logger.atInfo().log("ItemForge: global lore for '%s' %s", itemId,
            if (normalized != null) "set" else "reverted")
    }

    // ── Player join re-send ───────────────────────────────────────────────────────────────────

    /**
     * Re-sends every active name/lore override to a player who just joined. The engine's join-time
     * translation Init does not include our runtime overrides, so without this a late joiner would
     * see stock names until the next change. Called from a `PlayerConnectEvent` listener.
     */
    fun resendTo(playerRef: PlayerRef) {
        val map = HashMap<String, String>()
        for ((_, e) in store.snapshot()) {
            val nk = e.nameKey; val nv = e.name
            if (nk != null && nv != null) map[nk] = nv
            val lk = e.loreKey; val lv = e.lore
            if (lk != null && lv != null) map[lk] = lv
        }
        if (map.isEmpty()) return
        try {
            playerRef.packetHandler.write(UpdateTranslations(UpdateType.AddOrUpdate, map))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("ItemForge: failed to re-send name/lore overrides on join")
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────

    private fun broadcast(map: Map<String, String>) {
        if (map.isEmpty()) return
        try {
            Universe.get()?.broadcastPacketNoCache(UpdateTranslations(UpdateType.AddOrUpdate, HashMap(map)))
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("ItemForge: failed to broadcast name/lore translation update")
        }
    }

    /** The stock (file-loaded) value for a translation key, or null if absent. */
    private fun stockMessage(key: String): String? = I18nModule.get()?.getMessage(DEFAULT_LANGUAGE, key)

    /** The item's stock description text, empty when it has none. */
    private fun stockLore(item: Item): String = stockMessage(item.descriptionTranslationKey) ?: ""

    /** Logs when an item's resolved key isn't its own unique key (shared/borrowed → edits bleed). */
    private fun warnIfBorrowed(itemId: String, key: String, which: String) {
        val unique = "server.items.$itemId.$which"
        if (key != unique) {
            logger.atWarning().log(
                "ItemForge: '%s' resolves its %s from a shared key '%s' (not '%s'); a global %s edit " +
                    "also affects other items using that key.", itemId, which, key, unique, which
            )
        }
    }

    companion object {
        private const val DEFAULT_LANGUAGE = "en-US"
    }
}

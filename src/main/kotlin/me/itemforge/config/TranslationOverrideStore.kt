package me.itemforge.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hypixel.hytale.logger.HytaleLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistent storage for **global custom Name / Lore overrides** (the last pre-release feature).
 *
 * Unlike [ItemOverrideStore] (which stores stat overrides applied to the item *asset*), a custom
 * name/lore is NOT an asset field — Hytale resolves an item's display name from a translation *key*
 * (`server.items.<id>.name`), and the only way to change it server-wide is to push a new VALUE for
 * that key to every client (`UpdateTranslations` broadcast). So this store records, per item id, the
 * admin's custom name/lore text **and the translation key each targets**, so [TranslationOverrideEngine]
 * can (a) broadcast the value on change, and (b) re-send the full set to any player who joins later.
 *
 * ## JSON schema
 * ```json
 * {
 *   "schema_version": 1,
 *   "overrides": {
 *     "Iron_Sword": { "name": "Excalibur", "nameKey": "server.items.Iron_Sword.name",
 *                     "lore": "A legendary blade.", "loreKey": "server.items.Iron_Sword.description" }
 *   }
 * }
 * ```
 *
 * ## Thread safety
 * The in-memory map is guarded by [lock]; writes happen on admin save actions (rare), reads on
 * editor open and player join. Saves are synchronous via [AtomicFileWriter] — there is no hot path
 * here (an admin renaming an item is a deliberate, infrequent action), so the debounce machinery
 * [ItemOverrideStore] needs is unnecessary.
 */
class TranslationOverrideStore(
    private val filePath: Path,
    private val logger: HytaleLogger? = null
) {

    /** One item's custom name/lore + the translation keys they target. Any field may be null. */
    data class Entry(
        var name: String? = null,
        var nameKey: String? = null,
        var lore: String? = null,
        var loreKey: String? = null
    ) {
        /** True once this entry carries nothing — used to prune empty entries from the map/file. */
        fun isEmpty(): Boolean = name == null && lore == null
    }

    private data class FileModel(
        val schema_version: Int = SCHEMA_VERSION,
        // Gson can deserialize a JSON `null` value into this map regardless of Kotlin nullability,
        // so the value type is nullable to match reality — load() filters the nulls out.
        val overrides: MutableMap<String, Entry?> = HashMap()
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val writer = AtomicFileWriter()
    private val lock = Any()

    /** item id → its override entry. Only non-empty entries are kept. */
    private val overrides = HashMap<String, Entry>()

    /** Loads overrides from disk. Tolerant: a missing/corrupt file simply yields an empty store. */
    fun load(): Int {
        synchronized(lock) {
            overrides.clear()
            if (!Files.exists(filePath)) return 0
            try {
                val json = Files.readString(filePath)
                val model = gson.fromJson(json, FileModel::class.java)
                if (model?.overrides != null) {
                    for ((id, entry) in model.overrides) {
                        if (entry != null && !entry.isEmpty()) overrides[id] = entry
                    }
                }
            } catch (e: Exception) {
                logger?.atWarning()?.withCause(e)
                    ?.log("TranslationOverrideStore: failed to read %s — starting empty", filePath.fileName)
                overrides.clear()
            }
            return overrides.size
        }
    }

    /** A copy of the current entry for [itemId], or null if none. */
    fun get(itemId: String): Entry? = synchronized(lock) { overrides[itemId]?.copy() }

    /** A snapshot of every active entry (id → entry copy), for broadcasting/re-sending on join. */
    fun snapshot(): Map<String, Entry> =
        synchronized(lock) { overrides.mapValues { it.value.copy() } }

    /**
     * Sets (text non-null) or clears (text null) this item's custom NAME, recording the [key] it
     * targets. Persists immediately. Prunes the entry/file when it becomes empty.
     */
    fun setName(itemId: String, key: String, text: String?) = mutate(itemId) { e ->
        e.name = text
        e.nameKey = if (text == null) null else key
    }

    /** Sets or clears this item's custom LORE, recording the [key] it targets. Persists immediately. */
    fun setLore(itemId: String, key: String, text: String?) = mutate(itemId) { e ->
        e.lore = text
        e.loreKey = if (text == null) null else key
    }

    private inline fun mutate(itemId: String, block: (Entry) -> Unit) {
        synchronized(lock) {
            val entry = overrides.getOrPut(itemId) { Entry() }
            block(entry)
            if (entry.isEmpty()) overrides.remove(itemId)
            persist()
        }
    }

    /** Writes the current map to disk atomically. Caller holds [lock]. Never throws to the caller. */
    private fun persist() {
        try {
            val model = FileModel(SCHEMA_VERSION, HashMap<String, Entry?>(overrides))
            writer.write(filePath, gson.toJson(model))
        } catch (e: Exception) {
            logger?.atSevere()?.withCause(e)
                ?.log("TranslationOverrideStore: failed to persist %s", filePath.fileName)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

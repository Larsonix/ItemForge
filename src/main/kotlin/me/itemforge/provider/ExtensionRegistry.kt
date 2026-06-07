package me.itemforge.provider

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the registered [EditorExtension]s and answers "which extensions have a panel for this
 * item?" queries.
 *
 * Ordered (registration order) and thread-safe — mods register from arbitrary threads at
 * startup, and lookups run while building the editor payload. Every call into an extension is
 * exception-isolated here so a buggy extension can never crash a lookup.
 *
 * Created in `ItemForgePlugin.start()` and exposed to mods via [me.itemforge.ItemForgeAPI].
 * The editor lists every managing extension as a selectable source alongside "Base Item".
 */
class ExtensionRegistry {

    private val logger = HytaleLogger.forEnclosingClass()

    private val extensions = CopyOnWriteArrayList<EditorExtension>()
    private val bypassed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Registers (or replaces, by id) an extension. Ignores blank/throwing ids. */
    fun register(extension: EditorExtension) {
        val id = try {
            extension.getExtensionId()
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log("EditorExtension.getExtensionId() threw during registration — ignoring")
            return
        }
        if (id.isBlank()) {
            logger.atWarning().log("EditorExtension registered with a blank id — ignoring")
            return
        }
        extensions.removeIf { safeId(it) == id }
        extensions.add(extension)
        logger.atInfo().log("ItemForge: registered EditorExtension '%s' (%d total)", id, extensions.size)
    }

    /** All registered extensions, in registration order (snapshot). */
    fun all(): List<EditorExtension> = extensions.toList()

    /** The extension with the given id, or null. */
    fun byId(id: String): EditorExtension? = extensions.firstOrNull { safeId(it) == id }

    /**
     * Every non-bypassed extension that has a panel for [item], in registration order.
     * Exception-isolated: an extension whose [EditorExtension.manages] throws is skipped.
     */
    fun findManaging(item: Item): List<EditorExtension> =
        extensions.filter { !bypassed.contains(safeId(it)) && safeManages(it, item) }

    fun bypass(id: String, on: Boolean) {
        if (on) bypassed.add(id) else bypassed.remove(id)
    }

    fun isBypassed(id: String): Boolean = bypassed.contains(id)

    private fun safeId(e: EditorExtension): String =
        try { e.getExtensionId() } catch (ex: Exception) { "" }

    private fun safeManages(e: EditorExtension, item: Item): Boolean =
        try {
            e.manages(item)
        } catch (ex: Exception) {
            logger.atWarning().withCause(ex).log("EditorExtension '%s'.manages() threw — treating as not-managing", safeId(e))
            false
        }
}

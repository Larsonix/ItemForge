package me.itemforge

import com.hypixel.hytale.logger.HytaleLogger
import me.itemforge.provider.EditorExtension
import me.itemforge.provider.ExtensionRegistry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public extension API for ItemForge.
 *
 * A mod calls [registerExtension] once at startup to plug its **own panel** into the ItemForge
 * editor — exposing any item fields/configs/values it manages that ItemForge can't read
 * automatically (the mod's own storage, computed values, anywhere). See [EditorExtension] for
 * the full contract; edits are global per item id.
 *
 * ```java
 * // In your plugin's setup()/start():
 * ItemForgeAPI.registerExtension(new MyEditorExtension());
 * ```
 *
 * Load-order safe: registrations made before ItemForge finishes starting are buffered and
 * flushed once the registry is ready. All methods are static for clean Java use.
 */
object ItemForgeAPI {

    private val logger = HytaleLogger.forEnclosingClass()

    @Volatile
    private var registry: ExtensionRegistry? = null

    /** Extensions registered before [init] — flushed into the registry once it exists. */
    private val pending = CopyOnWriteArrayList<EditorExtension>()

    /**
     * Registers an [EditorExtension]. Safe to call from any mod's setup()/start() regardless of
     * load order — buffered and registered automatically once ItemForge is ready.
     */
    @JvmStatic
    fun registerExtension(extension: EditorExtension) {
        val r = registry
        if (r != null) {
            r.register(extension)
        } else {
            pending.add(extension)
            logger.atInfo().log("ItemForge: EditorExtension buffered (ItemForge not ready yet) — will register on startup")
        }
    }

    /** True once ItemForge has initialized its extension registry. */
    @JvmStatic
    fun isReady(): Boolean = registry != null

    // ── Internal lifecycle (called by ItemForgePlugin) ──────────────────

    internal fun init(reg: ExtensionRegistry) {
        registry = reg
        if (pending.isNotEmpty()) {
            val buffered = pending.toList()
            pending.clear()
            for (e in buffered) reg.register(e)
            logger.atInfo().log("ItemForge: flushed %d buffered EditorExtension(s)", buffered.size)
        }
    }

    internal fun shutdown() {
        registry = null
    }
}

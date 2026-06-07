package me.itemforge.vuetale.editor

import me.itemforge.provider.StackEditContext
import me.itemforge.scanner.FieldDefinition
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * Single owner of the editor's shared runtime — the executors and per-player caches that every
 * editor collaborator (the [me.itemforge.vuetale.EditorBridge] facade plus its handlers) must
 * share by identity.
 *
 * ## Why this exists
 *
 * The editor's threading correctness depends on there being exactly ONE of each executor:
 * - [asyncExecutor] serializes ALL async work (asset-field save apply, reset/resetField revert,
 *   recipe commit, async payload build). If any handler created its own executor, that
 *   serialization guarantee would silently break.
 * - [v8Executor] is the single Vuetale V8 executor used for deferred flushes and payload re-pushes.
 *
 * By passing the same [EditorRuntime] instance to every collaborator, each holds only a `val`
 * reference to these singletons — it physically cannot spin up its own. This centralizes the
 * freeze-critical cache lifecycle (documented below) in one auditable place.
 *
 * Narrow charter: executors + the three named caches ONLY. Anything with behavior gets its own
 * class. This object never reaches into the plugin.
 */
class EditorRuntime {

    /**
     * Dedicated single-thread executor for ItemForge async work (save apply, reset revert,
     * inventory refresh, recipe commit, async payload build). Isolates from ForkJoinPool to prevent
     * contention with Vuetale's sendUpdateAsync — the ForkJoinPool thread that sends UI update
     * packets should never wait behind our heavy applyAndSync.
     *
     * Without this: save dispatches applyAndSync to ForkJoinPool. Vuetale's sendUpdateAsync also
     * uses ForkJoinPool. If applyAndSync (50-200ms) runs first, the visual "Saved!" update is
     * delayed by that entire duration.
     *
     * With this: applyAndSync runs on a separate thread. ForkJoinPool is free for sendUpdateAsync
     * immediately.
     */
    val asyncExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "ItemForge-async").also { it.isDaemon = true }
        }

    /**
     * Per-item cached field definitions from the most recent codec scan.
     *
     * Populated during the payload build (when the editor opens, on the game thread).
     * Consumed during save (avoids re-scanning on the V8 thread).
     * Cleared on close ([me.itemforge.vuetale.EditorBridge.closePage]) and
     * [me.itemforge.vuetale.EditorBridge.onPlayerDisconnect].
     *
     * NOT cleared on reset or resetField — the cache contains codec metadata (valueType,
     * constraints, readOnly) that is determined by the codec structure, not by override values.
     * This metadata remains valid after revert, and keeping it prevents Bug #3 (heavy codec scan on
     * V8 thread) if the admin saves new changes in the same editor session after a reset.
     *
     * This cache is critical for V8 performance: codecScanner.scan() introspects ~54 codec fields
     * with BSON encoding — too heavy to run synchronously on V8 while handleDataEvent has the game
     * thread blocked via runOnV8Thread().get(). TrailOfOrbis bridge methods are near-instant (simple
     * state reads). Ours must be too.
     *
     * Thread safety: ConcurrentHashMap for atomic put/get/remove. FieldDefinition is immutable
     * (data class). No cross-thread mutation of values.
     */
    val fieldCache = ConcurrentHashMap<String, Map<String, FieldDefinition>>()

    /**
     * Active per-stack (Inspect Mode) edit sessions, keyed by player UUID.
     *
     * Set by [me.itemforge.vuetale.EditorBridge.setStackEdit] when
     * [me.itemforge.vuetale.VuetaleIntegration.openEditor] is invoked from inspect with a pre-built
     * [StackEditContext] (the held item's metadata, read + serialized ONCE on the world thread).
     * Read by the payload assembler to add the per-stack sources/fields to the payload, and by the
     * held-stack save handler to target the held instance. Absent → plain asset-mode editing
     * (dashboard/command opens always clear it).
     *
     * Cleared on close/disconnect (every editor-exit path) so a stale session can never leak
     * per-stack rows onto a later asset-mode open of a different item.
     */
    val stackSessions = ConcurrentHashMap<UUID, StackEditContext>()

    /**
     * Per-player page generation counter for close race condition prevention.
     *
     * Incremented when an editor page opens ([me.itemforge.vuetale.EditorBridge.onEditorOpened]).
     * Checked when a deferred close fires ([me.itemforge.vuetale.EditorBridge.closePage] world.execute
     * lambda). If the generation changed between enqueue and execution, a new editor was opened and
     * the deferred close must be skipped — otherwise it would unmount the NEW page, leaving the
     * client with a rendered but dead UI.
     */
    val pageGeneration = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Lazy reference to the Vuetale V8 executor for deferred flush/re-push scheduling.
     * Same reflection pattern as V8Watchdog (V8Watchdog.kt:106-108).
     *
     * MUST stay lazy: it reflects into [li.kelp.vuetale.javascript.JSEngine.instance], which is not
     * ready when the bridge is constructed (registration happens later in VuetaleIntegration.init()).
     * Lazy resolution defers the field access to first use (a flush or re-push), never at
     * construction time.
     */
    val v8Executor: ScheduledExecutorService by lazy {
        val executorField = li.kelp.vuetale.javascript.JSEngine::class.java
            .getDeclaredField("v8Executor")
        executorField.isAccessible = true
        executorField.get(li.kelp.vuetale.javascript.JSEngine.instance)
            as ScheduledExecutorService
    }
}

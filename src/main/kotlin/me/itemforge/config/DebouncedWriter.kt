package me.itemforge.config

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Coalesces rapid config writes into a single disk write after a debounce window.
 *
 * When an admin makes rapid changes (click-click-click-save or batch operations),
 * each save would trigger a disk write. DebouncedWriter absorbs all writes during
 * the debounce window and only writes the final content once the window closes.
 *
 * ## Cancel-Reschedule Pattern
 *
 * Every call to [schedule] captures the latest content and resets the timer.
 * Only when the timer expires without a new [schedule] call does the write
 * actually happen. This means:
 *
 * ```
 * t=0ms    schedule("v1")  → timer starts (2000ms)
 * t=500ms  schedule("v2")  → timer cancelled, restarted (2000ms from now)
 * t=800ms  schedule("v3")  → timer cancelled, restarted (2000ms from now)
 * t=2800ms timer expires   → writes "v3" to disk (v1 and v2 never written)
 * ```
 *
 * This is superior to the CAS-gate pattern (EndgameAndQoL CraftingTab:38-56)
 * because it guarantees the LATEST content is written, not the first after
 * a debounce window opens.
 *
 * ## Thread Safety
 *
 * - [pendingWrite] is an [AtomicReference] — lock-free updates from any thread
 * - [scheduledTask] mutations are synchronized — prevents cancel/schedule races
 * - [AtomicFileWriter.write] is thread-safe for different paths
 * - The scheduled executor is single-threaded — no concurrent writes to same file
 *
 * ## Evidence
 *
 * - EndgameAndQoL `CraftingTab.java:38-56` — AtomicLong + CAS, 2s debounce
 * - EndgameAndQoL `ConfigSaveManager.java` — dirty flag + debounce
 * - ToO `ItemSyncCoordinator.java:472-503` — cancel-reschedule with ScheduledExecutorService
 * - ToO `ItemSyncCoordinator.java:443-466` — graceful shutdown with awaitTermination
 *
 * ## Lifecycle
 *
 * Create during ConfigManager init. Call [shutdown] during plugin disable —
 * this flushes any pending write synchronously before the JVM exits.
 *
 * @param writer The atomic file writer for disk I/O
 * @param delayMs Debounce delay in milliseconds (default 2000ms)
 * @param logger Logger for write errors (writes happen async — can't throw to caller)
 */
class DebouncedWriter(
    private val writer: AtomicFileWriter,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val logger: HytaleLogger? = null
) {

    /** Pending write content: path + string content. Null if nothing pending. */
    private val pendingWrite = AtomicReference<PendingWrite?>(null)

    /** The scheduled flush task. Guarded by [taskLock]. */
    private var scheduledTask: ScheduledFuture<*>? = null
    private val taskLock = Any()

    /** Single-thread executor for async writes. */
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ItemForge-DebouncedWriter").apply {
            isDaemon = true // Don't prevent JVM shutdown
        }
    }

    /** Whether shutdown has been called. Prevents scheduling after shutdown. */
    @Volatile
    private var shuttingDown = false

    /**
     * Schedules a debounced write. The content will be written to disk after
     * [delayMs] unless another [schedule] call arrives first.
     *
     * Thread-safe — can be called from any thread (UI callbacks, event handlers, etc.).
     *
     * @param path The target file path
     * @param content The string content to write
     */
    fun schedule(path: Path, content: String) {
        if (shuttingDown) return

        // Capture the latest content (lock-free)
        pendingWrite.set(PendingWrite(path, content))

        // Cancel existing timer and start a new one
        synchronized(taskLock) {
            scheduledTask?.cancel(false)
            scheduledTask = scheduler.schedule(::flush, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Flushes any pending write immediately. Called by the scheduled timer
     * and during shutdown.
     *
     * Atomically captures and clears the pending content, then writes to disk.
     * If the write fails, logs the error — there's no caller to throw to since
     * this runs on the scheduler thread (or shutdown path).
     */
    fun flush() {
        val pending = pendingWrite.getAndSet(null) ?: return

        try {
            writer.write(pending.path, pending.content)
        } catch (e: IOException) {
            logger?.atSevere()?.log("DebouncedWriter: Failed to write ${pending.path.fileName}: ${e.message}")
            // Re-set the pending write so it can be retried on next schedule or shutdown
            pendingWrite.compareAndSet(null, pending)
        }
    }

    /**
     * Synchronous flush + executor shutdown. Ensures all pending writes complete
     * before the plugin disables.
     *
     * Call sequence during plugin shutdown:
     * 1. Cancel any pending scheduled task (don't need the timer — we're flushing now)
     * 2. Flush pending content synchronously on the calling thread
     * 3. Shut down the executor and wait for completion
     *
     * Follows ToO ItemSyncCoordinator:443-466 shutdown pattern:
     * cancel pending → clear state → shutdown executor → awaitTermination.
     */
    fun shutdown() {
        shuttingDown = true

        // Cancel the pending timer — we'll flush manually below
        synchronized(taskLock) {
            scheduledTask?.cancel(false)
            scheduledTask = null
        }

        // Flush any pending write on the calling thread (synchronous)
        flush()

        // Shut down the executor
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (_: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Returns true if there's a pending write that hasn't been flushed yet.
     * Used by tests and health checks.
     */
    fun hasPending(): Boolean = pendingWrite.get() != null

    /**
     * Holder for a pending write operation.
     * Immutable — safe to pass between threads via AtomicReference.
     */
    private data class PendingWrite(
        val path: Path,
        val content: String
    )

    companion object {
        /** Default debounce delay: 2 seconds. Matches EndgameAndQoL's proven 2s window. */
        const val DEFAULT_DELAY_MS = 2000L

        /** Max time to wait for executor shutdown during plugin disable. */
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}

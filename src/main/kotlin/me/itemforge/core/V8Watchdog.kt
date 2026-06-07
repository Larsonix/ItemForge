package me.itemforge.core

import com.caoccao.javet.interop.V8Runtime
import com.hypixel.hytale.logger.HytaleLogger
import li.kelp.vuetale.javascript.JSEngine
import li.kelp.vuetale.javascript.VueBridge
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors V8 thread health and forcefully terminates hung callbacks.
 *
 * ## Why this exists
 *
 * Vuetale's `handleDataEvent` blocks the game thread via `runOnV8Thread().get()`.
 * If a V8 callback hangs (Vue infinite reactive loop, VueBridge corruption, any
 * JS that doesn't return), the game thread is hostage → entire server freezes.
 * This has caused 9 documented server-killing incidents.
 *
 * ## How it works
 *
 * 1. A **heartbeat task** runs on the V8 executor (same single thread as V8 ticks
 *    and callbacks), updating an AtomicLong timestamp every [HEARTBEAT_INTERVAL_MS].
 *    When V8 is stuck in a hung callback, the heartbeat can't run (thread occupied).
 *
 * 2. A **watchdog daemon thread** checks the heartbeat every [CHECK_INTERVAL_MS].
 *    If the heartbeat is stale by more than [timeoutMs], V8 is stuck.
 *
 * 3. On stall detection: calls [V8Runtime.terminateExecution] from the watchdog
 *    thread (thread-safe per Javet API). This interrupts the running JS code,
 *    throwing [com.caoccao.javet.exceptions.JavetTerminatedException]. The V8
 *    executor thread is freed, `.get()` unblocks, game thread resumes.
 *
 * 4. V8 **survives termination** — `JavetTerminatedException.isContinuable()` is
 *    `true` in normal cases. The next V8 tick executes normally. The editor UI may
 *    be in an inconsistent state (admin should close and reopen).
 *
 * ## Thread model
 *
 * - Heartbeat: runs on `vuetale-v8` thread (V8 executor)
 * - Watchdog: runs on `itemforge-v8-watchdog` daemon thread
 * - terminateExecution(): called from watchdog thread (cross-thread safe)
 *
 * ## Access pattern
 *
 * V8Runtime and executor are accessed via reflection (same pattern as
 * [me.itemforge.vuetale.EditorBridge.closePage]). This is the only way —
 * Vuetale doesn't expose these in its public API.
 *
 * @param timeoutMs How long V8 can be unresponsive before termination (default 5s)
 */
class V8Watchdog(
    private val timeoutMs: Long = 5000L
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()

    // ── V8 references (set during start via reflection) ──────────────────

    private var v8Runtime: V8Runtime? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var watchdogThread: Thread? = null

    // ── Health tracking ──────────────────────────────────────────────────

    /** Last time the V8 thread executed our heartbeat. Nanosecond timestamp. */
    private val lastHeartbeat = AtomicLong(0L)

    /** How many times we've had to forcefully terminate V8. */
    val terminationCount = AtomicInteger(0)

    @Volatile
    private var running = false

    // ── Diagnostics — records what V8 was doing when it stalled ─────────

    /** Last bridge method called from JS. Set by bridge methods via [recordBridgeCall]. */
    @Volatile
    private var lastBridgeCall: String = "(none)"

    /** The V8 thread reference — captured during heartbeat for stack trace on stall. */
    @Volatile
    private var v8Thread: Thread? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Starts the watchdog. Call after [VuetaleIntegration.init] (V8 must be running).
     *
     * Accesses JSEngine internals via reflection:
     * - `VueBridge.v8Runtime` → [V8Runtime] for terminateExecution()
     * - `JSEngine.v8Executor` → [ScheduledExecutorService] for heartbeat scheduling
     */
    fun start() {
        try {
            val engine = JSEngine.instance

            // Get V8Runtime via VueBridge (same reflection as EditorBridge.closePage)
            val v8RuntimeField = VueBridge::class.java.getDeclaredField("v8Runtime")
            v8RuntimeField.isAccessible = true
            v8Runtime = v8RuntimeField.get(engine.bridge) as V8Runtime

            // Get V8 executor for heartbeat scheduling
            val executorField = JSEngine::class.java.getDeclaredField("v8Executor")
            executorField.isAccessible = true
            val executor = executorField.get(engine) as ScheduledExecutorService

            // Seed heartbeat so the watchdog doesn't false-positive during startup
            lastHeartbeat.set(System.nanoTime())

            // Register heartbeat on V8 thread — runs alongside V8 ticks.
            // When V8 is stuck in a callback, this can't run → heartbeat goes stale.
            // Also captures the V8 thread reference for stack trace on stall.
            heartbeatFuture = executor.scheduleWithFixedDelay({
                lastHeartbeat.set(System.nanoTime())
                if (v8Thread == null) v8Thread = Thread.currentThread()
            }, 0L, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)

            // Start watchdog daemon
            running = true
            watchdogThread = Thread(::watchdogLoop, "itemforge-v8-watchdog").apply {
                isDaemon = true
                start()
            }

            logger.atInfo().log(
                "V8Watchdog started — timeout: %dms, heartbeat: %dms, check: %dms",
                timeoutMs, HEARTBEAT_INTERVAL_MS, CHECK_INTERVAL_MS
            )
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log(
                "V8Watchdog failed to start — server freeze protection DISABLED"
            )
        }
    }

    /**
     * Stops the watchdog. Call during plugin shutdown before V8 engine closes.
     */
    fun shutdown() {
        running = false
        heartbeatFuture?.cancel(false)
        watchdogThread?.interrupt()
        try {
            watchdogThread?.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logger.atInfo().log(
            "V8Watchdog stopped — %d forced termination(s) during session",
            terminationCount.get()
        )
    }

    // ── Bridge Call Tracking ────────────────────────────────────────────

    /**
     * Records the current bridge method call. Called at the START of each
     * EditorBridge method so the watchdog knows what triggered the hang.
     *
     * Example: `watchdog.recordBridgeCall("save", "BlueAmulet", "{MaxDurability:200}")`
     *
     * Thread safety: volatile write from V8 thread, volatile read from watchdog thread.
     */
    fun recordBridgeCall(method: String, vararg args: String) {
        lastBridgeCall = if (args.isEmpty()) method else "$method(${args.joinToString(", ")})"
    }

    // ── Watchdog Loop ────────────────────────────────────────────────────

    private fun watchdogLoop() {
        while (running) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS)
                if (!running) break
                checkHealth()
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /**
     * Checks if V8 is responsive. If the heartbeat is stale beyond [timeoutMs],
     * forcefully terminates V8 execution.
     *
     * After termination:
     * - V8 callback throws JavetTerminatedException (caught by Vuetale's runCatching)
     * - runOnV8Thread().get() unblocks → game thread resumes
     * - V8 is fully usable for new scripts (isContinuable=true in normal cases)
     * - The editor UI may be inconsistent — admin should close and reopen
     */
    private fun checkHealth() {
        val heartbeat = lastHeartbeat.get()
        if (heartbeat == 0L) return // Not yet initialized

        val stalenessMs = (System.nanoTime() - heartbeat) / 1_000_000

        if (stalenessMs > timeoutMs) {
            val count = terminationCount.incrementAndGet()

            // ── Capture diagnostics BEFORE terminating ──────────────────
            val bridgeCall = lastBridgeCall
            val threadSnapshot = v8Thread

            // V8 thread stack trace — shows exactly where V8 was stuck.
            // Could be in VueBridge.patchProp (structural change), in
            // callback.callVoid (JS execution), in our bridge method, etc.
            val stackTrace = if (threadSnapshot != null) {
                try {
                    threadSnapshot.stackTrace
                        .take(30)
                        .joinToString("\n    ") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                } catch (_: Exception) { "(unable to capture stack trace)" }
            } else {
                "(V8 thread reference not captured)"
            }

            logger.atSevere().log(
                "V8 STALL DETECTED — no heartbeat for %dms (threshold: %dms). Recovery #%d\n" +
                "  Last bridge call: %s\n" +
                "  V8 thread stack trace:\n    %s\n" +
                "  Terminating V8 execution to unblock server.",
                stalenessMs, timeoutMs, count, bridgeCall, stackTrace
            )

            val runtime = v8Runtime
            if (runtime == null) {
                logger.atSevere().log("V8Watchdog: cannot terminate — V8Runtime reference lost")
                return
            }

            try {
                // terminateExecution() is thread-safe per Javet API.
                // It interrupts the currently-running JS script. The V8 isolate
                // remains alive and can execute new scripts afterward.
                runtime.terminateExecution()

                logger.atWarning().log(
                    "V8 execution terminated. Server resumed. " +
                    "The active editor page may need to be closed and reopened."
                )
            } catch (e: Exception) {
                logger.atSevere().withCause(e).log(
                    "V8Watchdog: terminateExecution() failed — server may remain frozen"
                )
            }
        }
    }

    companion object {
        /** How often the heartbeat task runs on the V8 thread. */
        private const val HEARTBEAT_INTERVAL_MS = 1000L

        /** How often the watchdog checks the heartbeat. */
        private const val CHECK_INTERVAL_MS = 2000L
    }
}

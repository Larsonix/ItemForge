package me.itemforge.metrics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wiring for HStats anonymous usage analytics (hstats.dev).
 *
 * The reporting itself lives in the verbatim [HStats] Java class — the service's
 * policy forbids modifying that file beyond its package name. This object owns
 * everything *around* it that the policy still leaves to us: the reporting key,
 * the once-per-JVM guard, and the opt-out check.
 *
 * ## What HStats reports (anonymous — see [HStats] source)
 *
 * A random per-server UUID, online player count, OS name/version, Java version,
 * CPU core count, and which mods are installed. No player names, IPs, or world
 * data. Server owners can additionally opt out by setting `enabled=false` in the
 * `hstats-server-uuid.txt` file HStats writes to the server's working directory.
 *
 * ## Why a once-per-JVM guard
 *
 * The [HStats] constructor schedules a recurring report on the server-global
 * `HytaleServer.SCHEDULED_EXECUTOR` and exposes no handle to cancel it. If
 * ItemForge is disabled and re-enabled (a full plugin reload), constructing it
 * again would stack a second reporter that runs forever — duplicating every
 * report. [started] ensures exactly one reporter per process. The unavoidable
 * trade-off (an HStats limitation, not ours): once started, the reporter keeps
 * running until the server restarts, even if ItemForge is later disabled. Its
 * reports are harmless and the server owner can opt out via the file above.
 */
object Metrics {

    /**
     * ItemForge's private HStats reporting key — the "private server reporting key"
     * from the hstats.dev dashboard, NOT the public Mod ID used for pages/embeds.
     *
     * This ships compiled into the JAR: every server running ItemForge reports under
     * this single key, so it identifies the *mod*, not the server. While it is blank,
     * metrics stay fully disabled and nothing is ever sent.
     */
    private const val REPORTING_KEY = "4a3259a8-0d1e-4fd2-a2f8-ec9bed293709" // ItemForge private reporting key (hstats.dev)

    /** Guards against stacking duplicate reporters across plugin reloads (one per JVM). */
    private val started = AtomicBoolean(false)

    /**
     * Initializes HStats once per JVM, if enabled and configured.
     *
     * Safe to call on every [me.itemforge.ItemForgePlugin.start]: the guard makes
     * repeat calls no-ops. Any failure constructing [HStats] is swallowed (metrics
     * must never take the plugin down) and the guard is released so a later, healthy
     * start can retry.
     *
     * @param enabled server-owner opt-out from config.yml (`metrics.enabled`)
     * @param version the running ItemForge version, for per-version analytics
     * @param log     sink for status lines (wired to the plugin logger)
     */
    fun init(enabled: Boolean, version: String, log: (String) -> Unit) {
        if (!enabled) {
            log("HStats metrics disabled by config (metrics.enabled: false)")
            return
        }
        if (REPORTING_KEY.isBlank()) {
            log("HStats metrics not configured (no reporting key set) — skipping")
            return
        }
        if (!started.compareAndSet(false, true)) return // already reporting this JVM

        try {
            HStats(REPORTING_KEY, version)
            log("HStats metrics enabled (hstats.dev) — reporting as version $version")
        } catch (e: Throwable) {
            started.set(false)
            log("HStats metrics failed to initialize: ${e.message}")
        }
    }
}

package me.itemforge.util

import com.hypixel.hytale.logger.HytaleLogger
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Preloads bundled native libraries that Javet's V8 runtime needs but that minimal,
 * read-only Linux game hosts (e.g. PebbleHost) do not ship — currently libatomic.so.1.
 *
 * Javet's V8 native library declares a DT_NEEDED dependency on libatomic.so.1. When the
 * host lacks it and the OS is read-only (no `apt install libatomic1`), V8 fails to load
 * and ItemForge cannot start. We ship libatomic.so.1 inside the JAR and System.load() it
 * here, BEFORE Vuetale boots V8: glibc's loader then reuses the already-loaded object by
 * SONAME to satisfy the V8 library's DT_NEEDED, even under RTLD_LOCAL.
 *
 * MUST be called before the first li.kelp.vuetale.javascript.JSEngine access
 * (VuetaleIntegration.init()). Idempotent and strictly best-effort: any failure is logged
 * and swallowed so a host that already provides libatomic — or any non-Linux/x86_64 host —
 * behaves exactly as before. This can only help; it can never regress a working host.
 */
object NativeDependencyLoader {
    private val logger = HytaleLogger.forEnclosingClass()

    @Volatile
    private var attempted = false

    /** Preload bundled V8 native deps (libatomic.so.1). Safe to call repeatedly. */
    fun preloadV8NativeDeps() {
        if (attempted) return
        attempted = true

        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("linux")) return // only Linux hosts hit the libatomic gap

        val arch = System.getProperty("os.arch", "").lowercase()
        if (arch != "amd64" && arch != "x86_64") return // Vuetale ships only the x86_64 V8 native lib

        val resourcePath = "/native/linux-x86_64/libatomic.so.1"
        try {
            val input = NativeDependencyLoader::class.java.getResourceAsStream(resourcePath)
            if (input == null) {
                logger.atWarning().log("Bundled libatomic.so.1 missing from JAR at %s — skipping preload", resourcePath)
                return
            }
            val tmp = Files.createTempFile("itemforge-libatomic-", ".so")
            tmp.toFile().deleteOnExit()
            input.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            System.load(tmp.toAbsolutePath().toString())
            logger.atInfo().log("Preloaded bundled libatomic.so.1 for Javet V8 (extracted to %s)", tmp)
        } catch (t: Throwable) {
            // Host may already provide libatomic, or extraction/load failed for another reason.
            // Either way Javet's own load proceeds exactly as before — never regress a working host.
            logger.atWarning().withCause(t).log("Could not preload bundled libatomic.so.1 — continuing (host may already provide it)")
        }
    }
}

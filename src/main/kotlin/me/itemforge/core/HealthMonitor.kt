package me.itemforge.core

import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.modules.i18n.I18nModule
import me.itemforge.scanner.CodecScanner

/**
 * Verifies the codec pipeline works at startup.
 *
 * Runs a series of checks to confirm that all APIs ItemForge depends on are
 * accessible and returning expected data. If any critical check fails, the
 * plugin should log the report and operate in degraded mode (or refuse to start).
 *
 * ## What It Checks
 *
 * - Item and CraftingRecipe codecs are accessible as BuilderCodec
 * - Key fields exist in the Item codec (MaxDurability, Armor, Weapon, etc.)
 * - Item.cachedPacket field is reflectable (our only reflection target)
 * - I18nModule is available for name resolution
 * - Item count and mod count are reasonable (sanity check)
 *
 * ## When To Run
 *
 * Call [check] once during plugin `start()`, after CodecScanner.init().
 * The results are logged and optionally displayed via `/itemforge status`.
 */
class HealthMonitor(
    private val codecScanner: CodecScanner
) {

    /**
     * Runs all health checks and returns a structured report.
     *
     * Never throws — all checks are individually guarded.
     */
    fun check(): HealthReport {
        val checks = mutableListOf<HealthCheck>()

        // ── Codec Access ────────────────────────────────────────────────

        checks.add(checkNotNull("Item AssetStore") {
            Item.getAssetStore()
        })

        checks.add(checkNotNull("Item codec as BuilderCodec") {
            codecScanner.itemCodec
        })

        checks.add(checkNotNull("CraftingRecipe codec as BuilderCodec") {
            codecScanner.recipeCodec
        })

        // ── Key Fields ──────────────────────────────────────────────────

        val entries = try { codecScanner.itemCodec.entries } catch (_: Exception) { null }

        val criticalFields = listOf(
            "MaxDurability", "Armor", "Weapon", "Tool", "Glider",
            "Utility", "Container", "InteractionVars", "Quality",
            "ItemLevel", "MaxStack"
        )

        if (entries != null) {
            checks.add(HealthCheck(
                name = "Item codec field count",
                passed = entries.isNotEmpty(),
                detail = "${entries.size} fields"
            ))

            for (fieldName in criticalFields) {
                val fieldList = entries[fieldName]
                checks.add(HealthCheck(
                    name = "Field: $fieldName",
                    passed = fieldList != null && fieldList.isNotEmpty(),
                    detail = if (fieldList != null) "${fieldList.size} version(s)" else "NOT FOUND"
                ))
            }
        } else {
            checks.add(HealthCheck(
                name = "Item codec entries",
                passed = false,
                detail = "getEntries() returned null or threw"
            ))
        }

        // ── cachedPacket Reflection ─────────────────────────────────────

        checks.add(checkField("Item.cachedPacket") {
            val field = Item::class.java.getDeclaredField("cachedPacket")
            field.isAccessible = true
            field
        })

        // ── I18n Module ─────────────────────────────────────────────────

        checks.add(checkNotNull("I18nModule") {
            I18nModule.get()
        })

        // ── Asset Counts ────────────────────────────────────────────────

        val itemCount = try {
            Item.getAssetMap().assetMap.size
        } catch (_: Exception) { -1 }

        checks.add(HealthCheck(
            name = "Total items in asset map",
            passed = itemCount > 0,
            detail = "$itemCount items"
        ))

        // ── Build Report ────────────────────────────────────────────────

        return HealthReport(checks)
    }

    // ── Check Helpers ───────────────────────────────────────────────────

    private fun checkNotNull(name: String, supplier: () -> Any?): HealthCheck {
        return try {
            val result = supplier()
            HealthCheck(
                name = name,
                passed = result != null,
                detail = if (result != null) "OK" else "null"
            )
        } catch (e: Exception) {
            HealthCheck(
                name = name,
                passed = false,
                detail = "${e::class.java.simpleName}: ${e.message}"
            )
        }
    }

    private fun checkField(name: String, accessor: () -> Any): HealthCheck {
        return try {
            accessor()
            HealthCheck(name = name, passed = true, detail = "accessible")
        } catch (e: Exception) {
            HealthCheck(name = name, passed = false, detail = "${e::class.java.simpleName}: ${e.message}")
        }
    }
}

/**
 * Result of a single health check.
 */
data class HealthCheck(
    val name: String,
    val passed: Boolean,
    val detail: String
)

/**
 * Complete health report containing all check results.
 */
data class HealthReport(
    val checks: List<HealthCheck>
) {
    /** True if all checks passed. */
    val allPassed: Boolean get() = checks.all { it.passed }

    /** Number of checks that passed. */
    val passCount: Int get() = checks.count { it.passed }

    /** Number of checks that failed. */
    val failCount: Int get() = checks.count { !it.passed }

    /** Formats the report for console output. */
    fun format(): String {
        val sb = StringBuilder()
        sb.appendLine("ItemForge Health Check: ${passCount}/${checks.size} passed")

        for (check in checks) {
            val icon = if (check.passed) "PASS" else "FAIL"
            sb.appendLine("  [$icon] ${check.name}: ${check.detail}")
        }

        if (!allPassed) {
            sb.appendLine("WARNING: Some checks failed. ItemForge may not function correctly.")
        }

        return sb.toString()
    }
}

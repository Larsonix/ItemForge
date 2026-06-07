package me.itemforge.config

import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parsed representation of config.yml — the human-editable plugin settings.
 *
 * Contains value caps (limits), behavior flags, and thresholds that control
 * how ItemForge operates. These are separate from item overrides (which are
 * machine-generated JSON in the overrides directory).
 *
 * ## Loading Hierarchy
 *
 * 1. Load default-config.yml from JAR resources (shipped defaults)
 * 2. If plugins/ItemForge/config.yml exists, overlay it on top
 * 3. If config.yml doesn't exist, copy default-config.yml out
 *
 * ## Usage
 *
 * ```kotlin
 * val config = PluginConfig.load(configDir.resolve("config.yml"))
 * if (value > config.limits.maxDamage) {
 *     // Reject — exceeds server owner's configured cap
 * }
 * ```
 *
 * Immutable after construction. Reload creates a new instance.
 */
data class PluginConfig(
    val limits: Limits,
    val behavior: Behavior,
    val metrics: Metrics = Metrics()
) {
    /**
     * Value caps — admins cannot exceed these via the UI.
     *
     * These are soft limits enforced by ItemForge's editor, not by the codec.
     * The codec's own validators (ThrowingValidationResults) are a separate
     * safety net for values that would break the game engine.
     */
    data class Limits(
        /** Maximum damage value for any weapon/attack field. */
        val maxDamage: Double = DEFAULT_MAX_DAMAGE,

        /** Maximum health modifier value (stat modifier amount). */
        val maxHealthModifier: Double = DEFAULT_MAX_HEALTH_MODIFIER,

        /** Maximum durability value. */
        val maxDurability: Double = DEFAULT_MAX_DURABILITY,

        /** Maximum recipe input quantity per slot. */
        val maxRecipeQuantity: Int = DEFAULT_MAX_RECIPE_QUANTITY,

        /** Maximum crafting time in seconds. */
        val maxRecipeTime: Double = DEFAULT_MAX_RECIPE_TIME
    ) {
        companion object {
            const val DEFAULT_MAX_DAMAGE = 10000.0
            const val DEFAULT_MAX_HEALTH_MODIFIER = 10000.0
            const val DEFAULT_MAX_DURABILITY = 100000.0
            const val DEFAULT_MAX_RECIPE_QUANTITY = 999
            const val DEFAULT_MAX_RECIPE_TIME = 3600.0
        }
    }

    /**
     * Behavior flags — control how ItemForge operates.
     */
    data class Behavior(
        /**
         * If true, all item overrides are reverted when the plugin is disabled.
         * Items return to their original mod/vanilla values on shutdown.
         * Default false — overrides persist across restarts.
         */
        val revertOnDisable: Boolean = DEFAULT_REVERT_ON_DISABLE,

        /**
         * Show a warning overlay when a multiplicative stat modifier exceeds this
         * value. Prevents accidental 50x damage multipliers.
         * Set to 0 to disable the warning.
         */
        val warnMultiplierThreshold: Double = DEFAULT_WARN_MULTIPLIER_THRESHOLD,

        /**
         * If true, all changes are logged to itemforge-changes.log.
         * Audit trail for accountability on multi-admin servers.
         */
        val auditLogEnabled: Boolean = DEFAULT_AUDIT_LOG_ENABLED
    ) {
        companion object {
            const val DEFAULT_REVERT_ON_DISABLE = false
            const val DEFAULT_WARN_MULTIPLIER_THRESHOLD = 5.0
            const val DEFAULT_AUDIT_LOG_ENABLED = true
        }
    }

    /**
     * Anonymous usage analytics (HStats — hstats.dev).
     *
     * A courtesy opt-out layered on top of HStats' own file-based opt-out
     * (`hstats-server-uuid.txt`). When [enabled] is false ItemForge never
     * constructs the reporter, so nothing is ever sent. See [me.itemforge.metrics.Metrics]
     * for exactly what HStats reports (all anonymous — no player names, IPs, or world data).
     */
    data class Metrics(
        /** Report anonymous usage stats to hstats.dev? Default true. */
        val enabled: Boolean = DEFAULT_ENABLED
    ) {
        companion object {
            const val DEFAULT_ENABLED = true
        }
    }

    companion object {

        /** Default config — all settings at their defaults. */
        val DEFAULT = PluginConfig(Limits(), Behavior(), Metrics())

        /**
         * Loads config from the given file path.
         *
         * If the file doesn't exist, copies default-config.yml from JAR resources
         * to the path and returns default config.
         *
         * @param configFile Path to config.yml
         * @return Parsed config
         */
        fun load(configFile: Path): PluginConfig {
            // Ensure the file exists — copy defaults if not
            if (!Files.exists(configFile)) {
                copyDefaultConfig(configFile)
                return DEFAULT
            }

            return try {
                Files.newBufferedReader(configFile).use { reader ->
                    parse(reader)
                }
            } catch (e: Exception) {
                // Config is corrupt or unparseable — use defaults
                DEFAULT
            }
        }

        /**
         * Parses a YAML reader into a PluginConfig.
         *
         * Tolerant of missing sections — each section defaults independently.
         * Unknown keys are silently ignored (forward compatibility).
         */
        fun parse(reader: Reader): PluginConfig {
            val yaml = Yaml()
            val raw: Map<String, Any?>? = yaml.load(reader)

            if (raw == null) return DEFAULT

            val limits = parseLimits(raw["limits"])
            val behavior = parseBehavior(raw["behavior"])
            val metrics = parseMetrics(raw["metrics"])

            return PluginConfig(limits, behavior, metrics)
        }

        /**
         * Copies default-config.yml from JAR resources to the target path.
         * Creates parent directories if needed.
         */
        private fun copyDefaultConfig(targetPath: Path) {
            val parent = targetPath.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }

            val resourceStream: InputStream = PluginConfig::class.java
                .getResourceAsStream("/default-config.yml")
                ?: return // Resource not found — silent fallback to defaults

            resourceStream.use { input ->
                Files.copy(input, targetPath)
            }
        }

        // ── Section Parsers ─────────────────────────────────────────────

        @Suppress("UNCHECKED_CAST")
        private fun parseLimits(raw: Any?): Limits {
            if (raw == null || raw !is Map<*, *>) return Limits()
            val map = raw as Map<String, Any?>

            return Limits(
                maxDamage = readDouble(map, "max_damage", Limits.DEFAULT_MAX_DAMAGE),
                maxHealthModifier = readDouble(map, "max_health_modifier", Limits.DEFAULT_MAX_HEALTH_MODIFIER),
                maxDurability = readDouble(map, "max_durability", Limits.DEFAULT_MAX_DURABILITY),
                maxRecipeQuantity = readInt(map, "max_recipe_quantity", Limits.DEFAULT_MAX_RECIPE_QUANTITY),
                maxRecipeTime = readDouble(map, "max_recipe_time", Limits.DEFAULT_MAX_RECIPE_TIME)
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseBehavior(raw: Any?): Behavior {
            if (raw == null || raw !is Map<*, *>) return Behavior()
            val map = raw as Map<String, Any?>

            return Behavior(
                revertOnDisable = readBoolean(map, "revert_on_disable", Behavior.DEFAULT_REVERT_ON_DISABLE),
                warnMultiplierThreshold = readDouble(map, "warn_multiplier_threshold", Behavior.DEFAULT_WARN_MULTIPLIER_THRESHOLD),
                auditLogEnabled = readBoolean(map, "audit_log_enabled", Behavior.DEFAULT_AUDIT_LOG_ENABLED)
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseMetrics(raw: Any?): Metrics {
            if (raw == null || raw !is Map<*, *>) return Metrics()
            val map = raw as Map<String, Any?>

            return Metrics(
                enabled = readBoolean(map, "enabled", Metrics.DEFAULT_ENABLED)
            )
        }

        // ── Safe Readers ────────────────────────────────────────────────

        private fun readDouble(map: Map<String, Any?>, key: String, default: Double): Double {
            val value = map[key] ?: return default
            return when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: default
                else -> default
            }
        }

        private fun readInt(map: Map<String, Any?>, key: String, default: Int): Int {
            val value = map[key] ?: return default
            return when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: default
                else -> default
            }
        }

        private fun readBoolean(map: Map<String, Any?>, key: String, default: Boolean): Boolean {
            val value = map[key] ?: return default
            return when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> default
            }
        }
    }
}

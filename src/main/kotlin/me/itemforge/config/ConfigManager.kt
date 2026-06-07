package me.itemforge.config

import me.itemforge.config.migration.ConfigMigration
import me.itemforge.config.migration.MigrationRunner
import java.nio.file.Path
import com.hypixel.hytale.logger.HytaleLogger

/**
 * Central coordinator for all ItemForge configuration.
 *
 * Owns and wires together all config subsystems: plugin settings (YAML),
 * item overrides (JSON), recipe overrides (JSON), backups, debounced writes,
 * and schema migrations.
 *
 * ## File Layout
 *
 * ```
 * plugins/ItemForge/
 * ├── config.yml                    → [pluginConfig]
 * ├── overrides/
 * │   ├── items.json                → [itemOverrides]
 * │   └── recipes.json              → [recipeOverrides]
 * ├── backups/
 * │   ├── items.v1.bak              → [backupManager]
 * │   └── items.snapshot.*.json     → [backupManager]
 * └── logs/
 *     └── itemforge-changes.log     → (AuditLogger, not managed here)
 * ```
 *
 * ## Lifecycle
 *
 * ```
 * Plugin.start():
 *   configManager = ConfigManager(dataDir, logger)
 *   configManager.loadAll()
 *
 * Plugin.shutdown():
 *   configManager.shutdown()  // flushes all pending writes
 *
 * /itemforge reload:
 *   configManager.reloadAll()
 * ```
 *
 * @param dataDir The plugin's data directory (e.g., plugins/ItemForge/)
 * @param logger Plugin logger
 */
class ConfigManager(
    private val dataDir: Path,
    private val logger: HytaleLogger
) {

    // ── File Paths ──────────────────────────────────────────────────────

    private val configFile = dataDir.resolve("config.yml")
    private val itemOverridesFile = dataDir.resolve("overrides").resolve("items.json")
    private val recipeOverridesFile = dataDir.resolve("overrides").resolve("recipes.json")
    private val backupDir = dataDir.resolve("backups")

    // ── Subsystems ──────────────────────────────────────────────────────

    /** Atomic file writer — shared by all stores. */
    private val atomicWriter = AtomicFileWriter()

    /** Debounced writer for item overrides. */
    private val itemDebouncedWriter = DebouncedWriter(atomicWriter, logger = logger)

    /** Debounced writer for recipe overrides (separate — different write frequency). */
    private val recipeDebouncedWriter = DebouncedWriter(atomicWriter, logger = logger)

    /** Backup manager for all config files. */
    val backupManager = BackupManager(backupDir, logger = logger)

    /**
     * Migration runner for item overrides.
     * No migrations registered for v1 — will be populated when v2 is created.
     */
    private val itemMigrationRunner = MigrationRunner(
        migrations = itemMigrations(),
        logger = logger
    )

    /**
     * Migration runner for recipe overrides.
     */
    private val recipeMigrationRunner = MigrationRunner(
        migrations = recipeMigrations(),
        logger = logger
    )

    // ── Public Config Access ────────────────────────────────────────────

    /** Plugin settings (limits, behavior flags). Immutable — reload creates new instance. */
    var pluginConfig: PluginConfig = PluginConfig.DEFAULT
        private set

    /** Item override store. */
    lateinit var itemOverrides: ItemOverrideStore
        private set

    /** Recipe override store. */
    lateinit var recipeOverrides: RecipeOverrideStore
        private set

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Loads all config files from disk. Call once during plugin start.
     *
     * Creates default files if they don't exist (first install).
     * Validates and migrates if needed. Logs summary.
     */
    fun loadAll() {
        // 1. Plugin config (YAML)
        pluginConfig = PluginConfig.load(configFile)
        logger.atInfo().log("Loaded plugin config from config.yml")

        // 2. Item overrides (JSON)
        itemOverrides = ItemOverrideStore(
            filePath = itemOverridesFile,
            debouncedWriter = itemDebouncedWriter,
            migrationRunner = itemMigrationRunner,
            backupManager = backupManager,
            logger = logger
        )
        val itemCount = itemOverrides.load()

        // 3. Recipe overrides (JSON)
        recipeOverrides = RecipeOverrideStore(
            filePath = recipeOverridesFile,
            debouncedWriter = recipeDebouncedWriter,
            migrationRunner = recipeMigrationRunner,
            backupManager = backupManager,
            logger = logger
        )
        val recipeCount = recipeOverrides.load()

        logger.atInfo().log("Config loaded: $itemCount item override(s), $recipeCount recipe override(s)")
    }

    /**
     * Reloads all config files from disk. Called by `/itemforge reload`.
     *
     * Flushes pending writes first to avoid reading stale data,
     * then re-reads all files.
     *
     * @return Summary string for command feedback
     */
    fun reloadAll(): String {
        // Flush pending writes so we read the latest saved state
        itemDebouncedWriter.flush()
        recipeDebouncedWriter.flush()

        pluginConfig = PluginConfig.load(configFile)
        val itemCount = itemOverrides.reload()
        val recipeCount = recipeOverrides.reload()

        val summary = "Reloaded: $itemCount item override(s), $recipeCount recipe override(s)"
        logger.atInfo().log(summary)
        return summary
    }

    /**
     * Shuts down all config subsystems. Call during plugin disable.
     *
     * Flushes all pending debounced writes synchronously to ensure
     * nothing is lost on shutdown.
     */
    fun shutdown() {
        itemDebouncedWriter.shutdown()
        recipeDebouncedWriter.shutdown()
        logger.atInfo().log("Config manager shut down — all pending writes flushed")
    }

    // ── Convenience Delegates ───────────────────────────────────────────
    // These delegate to the appropriate store, providing a cleaner API
    // for callers that don't need to know which store handles what.

    /** Whether any override exists for the given item. */
    fun hasItemOverride(itemId: String): Boolean = itemOverrides.hasOverride(itemId)

    /** Get the set of overridden field IDs for an item (for CodecScanner). */
    fun getOverriddenFieldIds(itemId: String): Set<String> = itemOverrides.getOverriddenFieldIds(itemId)

    /** Total number of item overrides. */
    fun itemOverrideCount(): Int = itemOverrides.overrideCount()

    /** Total number of recipe overrides. */
    fun recipeOverrideCount(): Int = recipeOverrides.overrideCount()

    // ── Migration Registration ──────────────────────────────────────────

    /**
     * Returns the list of item override migrations.
     * Empty for v1 — populated when future schema versions are created.
     */
    private fun itemMigrations(): List<ConfigMigration> {
        return emptyList() // No migrations yet — schema v1 is the first version
    }

    /**
     * Returns the list of recipe override migrations.
     * Empty for v1 — populated when future schema versions are created.
     */
    private fun recipeMigrations(): List<ConfigMigration> {
        return emptyList() // No migrations yet — schema v1 is the first version
    }
}

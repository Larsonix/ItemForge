package me.itemforge.metadata

import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.util.MessageUtil
import me.itemforge.util.ValueFormatter

/**
 * Resolves human-readable display names for items.
 *
 * Three-tier resolution:
 * 1. **I18n translation** — `I18nModule.getMessage("en-US", item.translationKey)`
 *    Returns the official localized name (e.g., "Iron Cuirass", "Small Sandswept Chest").
 * 2. **Mod-prefix stripping** — Uses [ModSourceTracker] to detect when the item ID
 *    starts with its source mod name. Strips the mod prefix, then applies type-prefix
 *    stripping. Example: `Hexcode_Weapon_Fire_Sword` → strip "Hexcode_" → strip
 *    "Weapon_" → "Fire Sword". Handles mods with spaces ("The Armory" → "TheArmory_").
 * 3. **ID formatting fallback** — strips type prefix and replaces underscores
 *    (e.g., "Armor_Iron_Chest" → "Iron Chest").
 *
 * ## Why Tier 2 Exists
 *
 * I18n only covers ~60% of items (vanilla). Modded items rarely ship en-US `.lang`
 * files, so they fall through to ID formatting. The type-prefix regex (Tier 3) handles
 * standard prefixes (Armor_, Weapon_, etc.) but NOT mod-specific prefixes. Modded items
 * like `Hexcode_Special_Sword` would display as "Hexcode Special Sword" — the mod name
 * leaks into the display name. Tier 2 catches these cases dynamically using the mod
 * source we already track at runtime.
 *
 * ## Runtime Evidence (Probe 1.6)
 *
 * - I18nModule available at `start()` — confirmed
 * - 62.5% resolution rate on sample items (5/8 translated) — Tier 2 improves this
 * - Translation keys come from `Item.getTranslationKey()` which checks
 *   `translationProperties.getName()` first, falling back to `"server.items.{id}.name"`
 *
 * ## Thread Safety
 *
 * `I18nModule.get()` returns a singleton. `getMessage()` reads from a ConcurrentHashMap.
 * `ModSourceTracker.getModName()` reads from a HashMap (populated once at startup,
 * updated atomically via `track()`). Safe to call from any thread.
 */
object ItemNameResolver {

    /**
     * Optional mod source tracker for Tier 2 (mod-prefix stripping).
     * Set via [init] during plugin startup. Null before init or if unavailable —
     * resolution gracefully skips Tier 2 and falls through to Tier 3.
     */
    @Volatile
    private var modSourceTracker: ModSourceTracker? = null

    /**
     * Initializes the name resolver with a [ModSourceTracker] for mod-prefix stripping.
     * Call once during plugin startup, after [ModSourceTracker.scan] completes.
     */
    fun init(tracker: ModSourceTracker) {
        modSourceTracker = tracker
    }

    /**
     * Resolves a display name for the given item.
     *
     * Always returns a non-empty string. Never throws.
     *
     * @param item The Item to resolve a name for
     * @return The translated name if available, otherwise a formatted version of the item ID
     */
    fun resolve(item: Item): String {
        // Tier 1: I18n translation
        val translated = resolveViaI18n(item)
        if (translated != null) return translated

        // Tier 2: Mod-prefix stripping + type-prefix stripping
        val modStripped = resolveViaModPrefix(item.id)
        if (modStripped != null) return modStripped

        // Tier 3: Type-prefix stripping only (existing regex-based fallback)
        return ValueFormatter.formatItemId(item.id)
    }

    /**
     * Resolves a display name for an item by its ID.
     *
     * Looks up the item in the asset map, then resolves. If the item doesn't exist,
     * falls back to mod-prefix stripping then ID formatting.
     *
     * @param itemId The item's asset ID (e.g., "Armor_Iron_Chest")
     * @return The resolved display name
     */
    fun resolve(itemId: String): String {
        val item = try {
            Item.getAssetMap().getAsset(itemId)
        } catch (_: Exception) { null }

        if (item != null) return resolve(item)

        // No Item object — can't try I18n, but can still try mod-prefix stripping
        return resolveViaModPrefix(itemId) ?: ValueFormatter.formatItemId(itemId)
    }

    /**
     * Attempts to produce a clean name by stripping the mod prefix from the item ID.
     *
     * Uses [ModSourceTracker] to get the item's source mod name, then checks if the
     * item ID starts with that mod name (case-insensitive). If so, strips the prefix
     * and applies [ValueFormatter.formatItemId] to the remainder (which also strips
     * type prefixes like "Armor_", "Weapon_").
     *
     * Multiple prefix variants are tried to handle common naming conventions:
     * - Direct: "Hexcode_" (mod name = "Hexcode", item = "Hexcode_Fire_Sword")
     * - No spaces: "TheArmory_" (mod name = "The Armory")
     * - No hyphens: "PillowsNPlushies_" (mod name = "Pillows-N-Plushies")
     *
     * Returns null if the mod is unknown, is vanilla ("Hytale"), or the item ID
     * doesn't start with any mod prefix variant.
     */
    private fun resolveViaModPrefix(itemId: String): String? {
        val tracker = modSourceTracker ?: return null
        val modName = tracker.getModName(itemId)

        // Skip vanilla items (handled by I18n or type-prefix stripping) and unknowns
        if (modName == "Unknown" || modName == "Hytale") return null

        // Build candidate prefixes from the mod display name
        val prefixes = buildList {
            add("${modName}_")
            val noSpaces = modName.replace(" ", "")
            if (noSpaces != modName) add("${noSpaces}_")
            val noHyphens = modName.replace("-", "")
            if (noHyphens != modName) add("${noHyphens}_")
            val noSpecial = modName.replace(" ", "").replace("-", "")
            if (noSpecial != modName && noSpecial != noSpaces && noSpecial != noHyphens) {
                add("${noSpecial}_")
            }
        }

        for (prefix in prefixes) {
            if (itemId.startsWith(prefix, ignoreCase = true)) {
                val remainder = itemId.substring(prefix.length)
                if (remainder.isNotEmpty()) {
                    // Apply type-prefix stripping to the remainder too.
                    // Example: "Hexcode_Weapon_Fire_Sword" → "Weapon_Fire_Sword" → "Fire Sword"
                    return ValueFormatter.formatItemId(remainder)
                }
            }
        }

        return null
    }

    /**
     * Resolves the item's display name via Hytale's I18n + template substitution.
     *
     * Uses [MessageUtil.formatMessageToPlainString] — the same server-side method
     * Hytale uses internally (disconnect messages, server logs). This handles:
     * - Direct translations: `server.items.Food_Bread.name` → `"Bread"`
     * - Template substitution: `"{material} Cuirass"` with `nameArguments =
     *   {"material": "server.materials.adamantite.name"}` → `"Adamantite Cuirass"`
     * - Recursive resolution: argument values that are themselves translation keys
     * - Children/concatenation: messages composed of multiple parts
     *
     * ## Borrowed Translation Key Detection
     *
     * Some items inherit translation keys from other items (quality variants,
     * mod overrides). Item "Relentless" may have its translation key set to
     * `server.items.Armor_Adamantite_Chest.name`, producing "Adamantite Cuirass"
     * — the wrong name. We detect this by checking if the translation key follows
     * the pattern `server.items.{OTHER_ID}.name` where OTHER_ID differs from the
     * item's own ID. When detected, I18n is skipped and Tier 2/3 handle it.
     *
     * Shared template keys (`server.armor.cuirass.name`) don't match this pattern
     * and are kept — template substitution produces the correct name.
     *
     * Returns null if translation is unavailable, empty, echoes the key, or is
     * borrowed from another item.
     */
    private fun resolveViaI18n(item: Item): String? {
        val translationKey = try { item.translationKey } catch (_: Exception) { return null }

        // Detect borrowed translation keys: if the key matches the pattern
        // "server.items.{SOME_ID}.name" but SOME_ID is not this item's ID,
        // the translation belongs to a different item. Skip I18n — the name
        // would be wrong (e.g., quality variant "Relentless" showing "Adamantite Cuirass").
        val borrowedMatch = ITEM_KEY_PATTERN.matchEntire(translationKey)
        if (borrowedMatch != null && borrowedMatch.groupValues[1] != item.id) {
            return null
        }

        val translationMessage = try {
            item.translationMessage
        } catch (_: Exception) {
            return null
        }

        val formattedMessage = try {
            translationMessage.formattedMessage
        } catch (_: Exception) {
            return null
        }

        val resolved = try {
            MessageUtil.formatMessageToPlainString(formattedMessage)
        } catch (_: Exception) {
            return null
        }

        if (resolved.isNullOrBlank()) return null

        // Filter cases where MessageUtil returned the raw translation key
        // (meaning no actual translation exists in the lang file).
        if (resolved == translationKey) return null

        return resolved.trim()
    }

    /** Matches the standard item translation key pattern `server.items.{id}.name`. */
    private val ITEM_KEY_PATTERN = Regex("""^server\.items\.(.+)\.name$""")
}

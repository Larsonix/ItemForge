package me.itemforge.util

/**
 * Display formatting utilities for the ItemForge UI.
 *
 * Converts internal data representations (PascalCase keys, raw numbers,
 * modifier structures) into human-readable strings for the editor and dashboard.
 *
 * All functions are pure — no state, no SDK dependencies.
 *
 * ## Consumers
 *
 * - **CodecScanner**: [formatFieldName] when building FieldDefinition.displayName
 * - **ItemNameResolver**: [formatItemId] as fallback when I18n translation unavailable
 * - **EditorBridge**: [formatEffectDescription] for computed description below inputs
 * - **DashboardBridge**: [formatNumber] for stat columns in table view
 */
object ValueFormatter {

    // ── Field Name Formatting ────────────────────────────────────────────

    /**
     * Converts a PascalCase JSON key to a human-readable display name.
     *
     * Splits on lowercase→uppercase transitions:
     * - "MaxDurability" → "Max Durability"
     * - "DurabilityLossOnHit" → "Durability Loss On Hit"
     * - "FallSpeedMultiplier" → "Fall Speed Multiplier"
     * - "ArmorSlot" → "Armor Slot"
     * - "Health" → "Health" (no change for single word)
     *
     * Used by CodecScanner when building [FieldDefinition.displayName] from
     * the codec's PascalCase JSON keys.
     */
    fun formatFieldName(pascalCase: String): String {
        if (pascalCase.isEmpty()) return pascalCase

        val result = StringBuilder()
        result.append(pascalCase[0])

        for (i in 1 until pascalCase.length) {
            val c = pascalCase[i]
            if (c.isUpperCase() && i > 0 && pascalCase[i - 1].isLowerCase()) {
                result.append(' ')
            }
            result.append(c)
        }

        return result.toString()
    }

    /**
     * Converts a category key to a human-readable section header.
     *
     * Uses [formatFieldName] as a base, then applies category-specific overrides
     * for cleaner display:
     * - "Armor" → "Armor Stats"
     * - "Weapon" → "Weapon Stats"
     * - "Tool" → "Tool Properties"
     * - "Glider" → "Flight Physics"
     * - "Utility" → "Utility Effects"
     * - "Container" → "Container"
     * - Other components → formatted PascalCase
     *
     * Top-level fields use "General" as their category (handled by CodecScanner,
     * not this function).
     */
    fun formatCategoryName(componentKey: String): String {
        return when (componentKey) {
            "Armor" -> "Armor Stats"
            "Weapon" -> "Weapon Stats"
            "Tool" -> "Tool Properties"
            "Glider" -> "Flight Physics"
            "Utility" -> "Utility Effects"
            "Container" -> "Container"
            "PortalKey" -> "Portal Key"
            "InteractionVars" -> "Weapon Damage"
            "InteractionConfig" -> "Interaction Config"
            else -> formatFieldName(componentKey)
        }
    }

    // ── Item ID Formatting ───────────────────────────────────────────────

    /**
     * Formats an item ID as a human-readable name when I18n translation is unavailable.
     *
     * Strips common type prefixes and replaces underscores with spaces:
     * - "Armor_Iron_Chest" → "Iron Chest"
     * - "Weapon_Battleaxe_Adamantite" → "Battleaxe Adamantite"
     * - "Food_Bread" → "Bread"
     * - "Furniture_Desert_Chest_Small" → "Desert Chest Small"
     *
     * Covers all 34+ vanilla item categories discovered at runtime.
     */
    fun formatItemId(id: String): String {
        return id
            .replaceFirst(TYPE_PREFIX_REGEX, "")
            .replace('_', ' ')
    }

    /**
     * Regex matching common item ID type prefixes.
     * Built from the 34 vanilla categories + mod categories observed at runtime.
     */
    private val TYPE_PREFIX_REGEX = Regex(
        "^(Armor|Weapon|Tool|Food|Potion|Glider|Container|Ingredient|Bench|Utility" +
        "|Fish|Plant|Ore|Deco|Furniture|Trap|Upgrade|MISC|NPC_Spawner|EggSpawner" +
        "|EditorTool|Editor|Fluid|Rail|Rubble|Rock|Soil|Coops|Electrum|Bone|Cloth" +
        "|Wood|Hypixel|Recipe)_"
    )

    // ── Interaction Var Formatting ───────────────────────────────────────

    /**
     * Converts an InteractionVars entry name to a human-readable category header.
     *
     * Replaces underscores with spaces for direct display:
     * - "Swing_Left_Damage" → "Swing Left Damage"
     * - "Groundslam_Damage" → "Groundslam Damage"
     * - "Effect" → "Effect"
     *
     * Each attack type becomes its own section in the Properties tab,
     * so the admin sees separate sections per attack (Light, Charged, Signature).
     */
    fun formatInteractionVarName(varName: String): String {
        return varName.replace("_", " ")
    }

    // ── Effect Descriptions ──────────────────────────────────────────────

    /**
     * Computes a human-readable effect description for a stat/damage field.
     *
     * Used in the editor to show what a value MEANS below the input field
     * (UX_DESIGN.md §5.5.1 — NN/G H6 recognition over recall).
     *
     * @param value The numeric value (amount or damage)
     * @param calculationType "Additive" or "Multiplicative" (null if not a stat modifier)
     * @param statOrTypeName The stat name ("Health") or damage type ("Physical")
     * @param fieldCategory The category context ("Armor Stats", "Damage Resistance", "Weapon Damage")
     * @return A description like "+25 Health" or "12% Physical Resistance", or null if not applicable
     */
    fun formatEffectDescription(
        value: Double,
        calculationType: String?,
        statOrTypeName: String,
        fieldCategory: String
    ): String? {
        return when {
            // Category-specific descriptions take priority over generic calcType.
            // Resistance/Enhancement/Damage categories carry semantic meaning about
            // WHAT the modifier does. The generic Additive/Multiplicative branches
            // are fallbacks for StatModifiers entries (Armor Stats, Weapon Stats, etc.)
            // that don't have a category-specific format.
            //
            // Without this ordering, a DamageResistance entry with CalculationType=
            // "Multiplicative" would match the Multiplicative branch and produce
            // "12% Physical" instead of the correct "12% Physical damage reduction".

            // Resistance fields — two modifier systems (ResistanceModifier.java:80-83):
            // Percent: "5% Physical damage reduction" (value 0.05 = 5%)
            // Flat:    "+5 Physical damage reduction" (value is absolute amount)
            fieldCategory.contains("Resistance", ignoreCase = true) -> {
                if (calculationType.equals("Flat", ignoreCase = true)) {
                    "+${formatNumber(value)} $statOrTypeName damage reduction"
                } else {
                    "${formatPercent(value)} $statOrTypeName damage reduction"
                }
            }

            // Enhancement fields: Additive "+25 Physical damage bonus", Multiplicative "12% bonus"
            fieldCategory.contains("Enhancement", ignoreCase = true) -> {
                if (calculationType.equals("Additive", ignoreCase = true)) {
                    "+${formatNumber(value)} $statOrTypeName damage bonus"
                } else {
                    "${formatPercent(value)} $statOrTypeName damage bonus"
                }
            }

            // Damage fields (not resistance/enhancement): "Deals 42 Physical damage"
            fieldCategory.contains("Damage", ignoreCase = true) -> {
                "Deals ${formatNumber(value)} $statOrTypeName damage"
            }

            // Generic stat modifiers (StatModifiers entries — Armor Stats, Weapon Stats, etc.)
            calculationType.equals("Additive", ignoreCase = true) -> {
                "+${formatNumber(value)} $statOrTypeName"
            }

            calculationType.equals("Multiplicative", ignoreCase = true) -> {
                "${formatPercent(value)} $statOrTypeName"
            }

            // Speed/multiplier fields: "2x base speed"
            statOrTypeName.contains("Speed", ignoreCase = true) ||
                statOrTypeName.contains("Multiplier", ignoreCase = true) -> {
                "${formatNumber(value)}x base ${statOrTypeName.lowercase()}"
            }

            // No applicable description
            else -> null
        }
    }

    // ── Number Formatting ────────────────────────────────────────────────

    /**
     * Formats a number for clean display. Removes unnecessary trailing zeros:
     * - 25.0 → "25"
     * - 0.12 → "0.12"
     * - 1.50 → "1.5"
     * - 100.0 → "100"
     */
    fun formatNumber(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toBigDecimal().stripTrailingZeros().toPlainString()
        }
    }

    /**
     * Formats a number for integer display.
     */
    fun formatNumber(value: Int): String {
        return value.toString()
    }

    /**
     * Formats a multiplicative value as a percentage:
     * - 0.12 → "12%"
     * - 0.5 → "50%"
     * - 1.0 → "100%"
     * - 0.05 → "5%"
     */
    fun formatPercent(value: Double): String {
        val percent = value * 100.0
        return "${formatNumber(percent)}%"
    }
}

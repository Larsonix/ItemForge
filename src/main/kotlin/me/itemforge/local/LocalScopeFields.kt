package me.itemforge.local

import me.itemforge.provider.StackEditContext
import me.itemforge.vuetale.SerializedFieldDefinition

/**
 * Per-item ("This Item") scope knowledge — the single source of truth for which fields can be
 * edited on a single held [com.hypixel.hytale.server.core.inventory.ItemStack] instead of globally
 * on the item type.
 *
 * ## Why this exists
 * The Hytale engine reads an item's stats from the item ASSET, never from per-stack metadata
 * (verified across `StatModifiersManager`, `EntityStatsSystems`, `DamageSystems`). The only values
 * it honors per-instance natively are quantity, durability and max-durability. So:
 *  - **Free tier (Step 1, here):** quantity / durability / max-durability are per-item-editable
 *    *today* by writing the real `ItemStack` fields — no runtime applier needed.
 *  - **Combat / attribute tiers (later):** damage, defense, health, etc. become local-capable only
 *    once their always-running applier ships ([me.itemforge.local] ECS systems). As each lands,
 *    its stat ids are added to [isLocalCapable] — this object is the registry that the editor reads
 *    to decide which fields to enable vs. grey in Local scope.
 *
 * Keeping all of this in ONE place means the UI never hardcodes capability and each tier flips its
 * fields on by editing exactly this file.
 */
object LocalScopeFields {

    /**
     * Top-level `ItemStack` metadata key under which ALL of ItemForge's per-item stat overrides live
     * (combat/attribute tiers). A single small BsonDocument keeps client packets lean and namespaces
     * cleanly. Free-tier fields (quantity/durability/max-durability) are NOT stored here — they are
     * real engine-native ItemStack fields written directly.
     */
    const val METADATA_KEY = "ItemForge"
    /** Sub-document of [METADATA_KEY] holding per-`DamageCause` weapon-damage multipliers (combat tier). */
    const val DMG_SUBKEY = "dmg"
    /** Sub-document of [METADATA_KEY] holding per-`DamageCause` armor RESISTANCE fractions (combat tier). */
    const val DEF_SUBKEY = "def"
    /** Sub-document of [METADATA_KEY] holding per-`EntityStatType` flat bonuses (attribute tier). */
    const val STAT_SUBKEY = "stat"

    /**
     * Synthetic field-id prefix for the per-item damage knobs shown in "This Item" scope —
     * `LocalDmg.<DamageCause>` (e.g. `LocalDmg.Physical`). Per-item damage can only be a per-cause
     * scale (the engine knows only the cause at hit time, not which attack), so instead of the per-
     * attack BaseDamage rows the local editor shows ONE percentage knob per damage type. The save
     * path maps these straight to [DMG_SUBKEY] multipliers (percent / 100).
     */
    const val LOCAL_DMG_PREFIX = "LocalDmg."

    /** True for a synthetic per-cause damage percentage field (id `LocalDmg.<cause>`). */
    fun isLocalDamageField(fieldId: String): Boolean = fieldId.startsWith(LOCAL_DMG_PREFIX)

    /** The `DamageCause` id behind a [LOCAL_DMG_PREFIX] field id. */
    fun causeOfLocalDamage(fieldId: String): String = fieldId.removePrefix(LOCAL_DMG_PREFIX)

    /**
     * Synthetic field-id prefix for the per-item DEFENSE knobs shown in "This Item" scope —
     * `LocalDef.<DamageCause>` (e.g. `LocalDef.Physical`). One per cause the armor resists; each is an
     * "extra resistance %" applied per-instance on top of the armor's normal resistance.
     */
    const val LOCAL_DEF_PREFIX = "LocalDef."

    /** True for a synthetic per-cause defense (resistance) field (id `LocalDef.<cause>`). */
    fun isLocalDefenseField(fieldId: String): Boolean = fieldId.startsWith(LOCAL_DEF_PREFIX)

    /** The `DamageCause` id behind a [LOCAL_DEF_PREFIX] field id. */
    fun causeOfLocalDefense(fieldId: String): String = fieldId.removePrefix(LOCAL_DEF_PREFIX)

    /** An armor resistance field, e.g. `Armor.DamageResistance.Physical`. Used to discover which causes
     *  an armor piece resists, so the local editor can offer one per-cause resistance knob each. */
    fun isResistanceField(fieldId: String): Boolean = fieldId.startsWith("Armor.DamageResistance.")

    /** The `DamageCause` id an armor resistance field targets — the last `.`-segment. */
    fun causeOfResistance(fieldId: String): String = fieldId.substringAfterLast(".")

    /**
     * Synthetic field-id prefix for the per-item ENTITY-STAT bonus knobs shown in "This Item" scope —
     * `LocalStat.<EntityStatType id>` (e.g. `LocalStat.Health`). Each is a flat additive bonus applied
     * to the wearer while the item is worn (attribute tier).
     */
    const val LOCAL_STAT_PREFIX = "LocalStat."

    /** True for a synthetic per-stat bonus field (id `LocalStat.<statId>`). */
    fun isLocalStatField(fieldId: String): Boolean = fieldId.startsWith(LOCAL_STAT_PREFIX)

    /** The `EntityStatType` id behind a [LOCAL_STAT_PREFIX] field id. */
    fun statIdOfLocalStat(fieldId: String): String = fieldId.removePrefix(LOCAL_STAT_PREFIX)

    /** An armor stat-modifier field, e.g. `Armor.StatModifiers.Health`. Used to discover which entity
     *  stats an armor piece grants, so the local editor can offer one per-stat bonus knob each. */
    fun isStatModifierField(fieldId: String): Boolean = fieldId.startsWith("Armor.StatModifiers.")

    /** The `EntityStatType` id an armor stat-modifier field targets — the last `.`-segment. */
    fun statIdOfModifier(fieldId: String): String = fieldId.substringAfterLast(".")

    /** Asset field id: the item type's max-durability (also a real per-stack value the engine reads). */
    const val FIELD_MAX_DURABILITY = "MaxDurability"
    /** Synthetic instance-only field id: the held stack's quantity. */
    const val FIELD_QUANTITY = "Quantity"
    /** Synthetic instance-only field id: the held stack's current durability. */
    const val FIELD_DURABILITY = "Durability"

    /**
     * Synthetic field id for the item's custom display NAME — editable in BOTH scopes (GLOBAL = the
     * item type's translation, broadcast to all clients; LOCAL = this held instance's `ItemDisplay`
     * metadata). Rendered bespoke in the editor header, not in a tab. See [buildNameField].
     */
    const val FIELD_CUSTOM_NAME = "CustomName"
    /** Synthetic field id for the item's custom LORE/description — same dual-scope model as
     *  [FIELD_CUSTOM_NAME], rendered as a multiline box in the header. See [buildLoreField]. */
    const val FIELD_CUSTOM_LORE = "CustomLore"

    /**
     * Codec-scanned ASSET fields that can ALSO be overridden per-instance in the current build.
     * Step 1: only [FIELD_MAX_DURABILITY] (the engine reads a stack's own max-durability). The
     * synthetic instance-only fields ([FIELD_QUANTITY] / [FIELD_DURABILITY]) are not asset fields,
     * so they are not listed here — they are emitted directly by [buildInstanceFields].
     *
     * Combat/attribute tiers will widen this (by id and, for stat arrays, by id-prefix in
     * [isLocalCapable]).
     */
    private val LOCAL_CAPABLE_ASSET_FIELDS: Set<String> = setOf(FIELD_MAX_DURABILITY)

    /**
     * Whether the codec-scanned asset field [fieldId] can be edited at Local (per-instance) scope.
     * Free tier: [FIELD_MAX_DURABILITY]. (Weapon damage is local-editable too, but NOT per-attack —
     * it is surfaced as separate synthetic per-cause percentage knobs, see [buildDamageFields], so the
     * per-attack BaseDamage rows themselves are not individually local-capable.) Later tiers widen
     * this. Synthetic instance/damage fields carry their own localCapable flag and skip this check.
     */
    fun isLocalCapable(fieldId: String): Boolean = fieldId in LOCAL_CAPABLE_ASSET_FIELDS

    /**
     * A weapon base-damage field, e.g. `InteractionVars.Swing.BaseDamage.Physical`. Used to discover
     * which damage causes a weapon deals (the segment after `.BaseDamage.`), so the local editor can
     * offer one per-cause percentage knob per cause.
     */
    fun isDamageField(fieldId: String): Boolean =
        fieldId.startsWith("InteractionVars.") && fieldId.contains(".BaseDamage.")

    /** The `DamageCause` id a per-attack damage field targets — the segment after `.BaseDamage.`. */
    fun causeOf(fieldId: String): String = fieldId.substringAfterLast(".BaseDamage.")

    /**
     * Returns a copy of [field] annotated with its Local-scope capability + current per-instance
     * value, or the field unchanged when it isn't local-capable. Applied to every codec-scanned field
     * when the editor opens in inspect mode so the UI can enable/grey the right value per scope.
     * (MaxDurability → the held stack's actual max-durability.)
     */
    fun withLocalCapability(field: SerializedFieldDefinition, ctx: StackEditContext): SerializedFieldDefinition {
        if (field.id == FIELD_MAX_DURABILITY) {
            return field.copy(localCapable = true, localValue = ctx.maxDurability)
        }
        if (field.id in LOCAL_CAPABLE_ASSET_FIELDS) return field.copy(localCapable = true)
        return field
    }

    /**
     * Builds the per-item DAMAGE knobs shown in "This Item" scope — ONE percentage field per damage
     * [causes] the weapon deals (e.g. `Physical (%)`, `Fire (%)`). 100 = default; 150 = 1.5× every
     * attack of that cause. This is the honest representation of what the engine permits (a per-cause
     * scale), replacing the misleading per-attack BaseDamage rows in local mode. Current value comes
     * from the stack's existing multiplier ([StackEditContext.damageMultipliers], default 1.0).
     */
    fun buildDamageFields(causes: List<String>, ctx: StackEditContext): List<SerializedFieldDefinition> =
        causes.map { cause ->
            val pct = Math.round((ctx.damageMultipliers[cause] ?: 1.0) * 100.0).toInt()
            SerializedFieldDefinition(
                id = LOCAL_DMG_PREFIX + cause,
                displayName = "$cause (%)",
                category = "Damage",
                valueType = "INTEGER",
                currentValue = pct,
                calculationType = null,
                originalValue = null,
                min = 0.0,
                max = null,
                step = 1.0,
                maxDecimals = 0,
                options = null,
                readOnly = false,
                state = "DEFAULT",
                tooltip = "Per-item $cause damage as a percent of normal (100 = default, 150 = 1.5×). " +
                    "Scales every $cause attack of this weapon - the game can't set per-swing values on one item.",
                effectDescription = null,
                isNotSet = false,
                sourceMod = null,
                globalCapable = false,
                localCapable = true,
                localValue = pct
            )
        }

    /**
     * Builds the per-item DEFENSE knobs shown in "This Item" scope — ONE "resistance %" field per
     * damage [causes] the armor piece resists (e.g. `Physical resist (%)`). The value is EXTRA
     * resistance layered on THIS one piece on top of its normal resistance: 0 = none, 25 = take 25%
     * less of that damage type, negative = more vulnerable. Current value comes from the stack's
     * existing per-item resistance ([StackEditContext.damageResist], default 0).
     */
    fun buildDefenseFields(causes: List<String>, ctx: StackEditContext): List<SerializedFieldDefinition> =
        causes.map { cause ->
            val pct = Math.round((ctx.damageResist[cause] ?: 0.0) * 100.0).toInt()
            SerializedFieldDefinition(
                id = LOCAL_DEF_PREFIX + cause,
                displayName = "$cause resist (%)",
                category = "Damage Resistance",
                valueType = "INTEGER",
                currentValue = pct,
                calculationType = null,
                originalValue = null,
                min = null,
                max = null,
                step = 1.0,
                maxDecimals = 0,
                options = null,
                readOnly = false,
                state = "DEFAULT",
                tooltip = "Extra per-item $cause resistance for THIS armor piece, on top of its normal " +
                    "resistance (0 = none, 25 = take 25% less $cause damage, negative = more vulnerable).",
                effectDescription = null,
                isNotSet = false,
                sourceMod = null,
                globalCapable = false,
                localCapable = true,
                localValue = pct
            )
        }

    /**
     * Builds the per-item ATTRIBUTE knobs shown in "This Item" scope — ONE flat-bonus field per
     * entity [stats] the armor grants (e.g. `Health (+)`, `Stamina (+)`). The value is an EXTRA flat
     * amount added to that stat's max while the piece is worn (0 = none, +50 = +50 max, negative =
     * penalty). Current value comes from the stack's existing per-item bonus
     * ([StackEditContext.statBonuses], default 0). Generic over any registered stat, including modded.
     */
    fun buildStatFields(stats: List<String>, ctx: StackEditContext): List<SerializedFieldDefinition> =
        stats.map { statId ->
            val amount = ctx.statBonuses[statId] ?: 0.0
            SerializedFieldDefinition(
                id = LOCAL_STAT_PREFIX + statId,
                displayName = "$statId (+)",
                category = "Armor Stats",
                valueType = "DOUBLE",
                currentValue = amount,
                calculationType = null,
                originalValue = null,
                min = null,
                max = null,
                step = null,
                maxDecimals = null,
                options = null,
                readOnly = false,
                state = "DEFAULT",
                tooltip = "Extra per-item $statId this armor piece grants while worn, on top of its " +
                    "normal stats (0 = none, +50 = +50 max, negative = penalty).",
                effectDescription = null,
                isNotSet = false,
                sourceMod = null,
                globalCapable = false,
                localCapable = true,
                localValue = amount
            )
        }

    /**
     * The synthetic instance-only fields for the held stack — values that exist only per-instance
     * (no item-type equivalent): Quantity, and Durability for durable items. They are
     * `globalCapable = false` so the UI hides them entirely in Global scope and shows them only when
     * editing "This Item". Rendered inline (they are always "set" — every stack has a quantity).
     */
    fun buildInstanceFields(ctx: StackEditContext): List<SerializedFieldDefinition> {
        val fields = ArrayList<SerializedFieldDefinition>(2)

        fields.add(
            SerializedFieldDefinition(
                id = FIELD_QUANTITY,
                displayName = "Quantity",
                category = "General",
                valueType = "INTEGER",
                currentValue = null,
                calculationType = null,
                originalValue = null,
                // Engine requires quantity > 0; 0 would delete the slot. Clamp floor at 1.
                min = 1.0,
                max = null,
                step = 1.0,
                maxDecimals = 0,
                options = null,
                readOnly = false,
                state = "DEFAULT",
                tooltip = "The number of items in this specific stack. Affects only the item in your hand.",
                effectDescription = null,
                isNotSet = false,
                sourceMod = null,
                globalCapable = false,
                localCapable = true,
                localValue = ctx.quantity
            )
        )

        // Durability only applies to items that actually have a durability bar.
        if (ctx.isDurable()) {
            fields.add(
                SerializedFieldDefinition(
                    id = FIELD_DURABILITY,
                    displayName = "Durability",
                    category = "General",
                    valueType = "DOUBLE",
                    currentValue = null,
                    calculationType = null,
                    originalValue = null,
                    min = 0.0,
                    // Current durability can't exceed this stack's own max.
                    max = ctx.maxDurability,
                    step = null,
                    maxDecimals = null,
                    options = null,
                    readOnly = false,
                    state = "DEFAULT",
                    tooltip = "Current durability of the item in your hand (0 = broken). Affects only this item.",
                    effectDescription = null,
                    isNotSet = false,
                    sourceMod = null,
                    globalCapable = false,
                    localCapable = true,
                    localValue = ctx.durability
                )
            )
        }

        return fields
    }

    /**
     * Builds the editable NAME field shown pinned in the editor header. Editable in BOTH scopes:
     * [currentValue]/[globalCapable] drive the GLOBAL (item-type) name; [localValue]/[localCapable]
     * drive the LOCAL (this held instance) name. [globalText] is the item's current resolved/overridden
     * type name (prefill for Global). [localText] is this instance's `ItemDisplay` name, or null when it
     * inherits the type name — in which case the Local field prefills from [globalText] so editing
     * "this item" starts from what the player currently sees.
     *
     * @param localAvailable whether per-instance editing is possible this session (inspect open).
     */
    fun buildNameField(globalText: String, localText: String?, localAvailable: Boolean): SerializedFieldDefinition =
        SerializedFieldDefinition(
            id = FIELD_CUSTOM_NAME,
            displayName = "Name",
            category = "General",
            valueType = "STRING",
            currentValue = globalText,
            calculationType = null,
            originalValue = null,
            min = null, max = null, step = null, maxDecimals = null,
            options = null,
            readOnly = false,
            state = "DEFAULT",
            tooltip = "The item's display name. \"All copies\" renames every copy of this item type; " +
                "\"This item\" renames only the one in your hand.",
            effectDescription = null,
            isNotSet = false,
            sourceMod = null,
            globalCapable = true,
            localCapable = localAvailable,
            localValue = localText ?: globalText,
            multiline = false
        )

    /**
     * Builds the editable LORE/description field shown pinned in the editor header (multiline). Same
     * dual-scope model as [buildNameField]. [globalText] is the item's current type description (may be
     * empty when the item has none); [localText] is this instance's `ItemDisplay` description or null
     * (then prefills from [globalText]).
     */
    fun buildLoreField(globalText: String, localText: String?, localAvailable: Boolean): SerializedFieldDefinition =
        SerializedFieldDefinition(
            id = FIELD_CUSTOM_LORE,
            displayName = "Lore",
            category = "General",
            valueType = "STRING",
            currentValue = globalText,
            calculationType = null,
            originalValue = null,
            min = null, max = null, step = null, maxDecimals = null,
            options = null,
            readOnly = false,
            state = "DEFAULT",
            tooltip = "The item's description / lore shown in its tooltip. \"All copies\" sets it for the " +
                "whole item type; \"This item\" sets it only for the one in your hand.",
            effectDescription = null,
            isNotSet = false,
            sourceMod = null,
            globalCapable = true,
            localCapable = localAvailable,
            localValue = localText ?: globalText,
            multiline = true
        )
}

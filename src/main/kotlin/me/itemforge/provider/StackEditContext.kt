package me.itemforge.provider

import me.itemforge.vuetale.EditorSource
import me.itemforge.vuetale.SerializedFieldDefinition

/**
 * Per-player state for an active **per-stack** (Inspect Mode) edit session — the pre-computed,
 * already-serialized editable fields for the inspected held item's metadata.
 *
 * Built ONCE on the **world thread** at inspect-open time (the only place a concrete
 * [com.hypixel.hytale.server.core.inventory.ItemStack] exists), and read by
 * [me.itemforge.vuetale.EditorBridge] when it assembles the editor payload.
 *
 * Why pre-serialize and cache: [me.itemforge.vuetale.EditorBridge] may rebuild the editor
 * payload on the **V8 thread** (e.g. the reset re-push), so payload assembly must never read a
 * live `ItemStack` or call back into world-thread-only code. Everything per-stack is resolved
 * here, on the world thread, and cached as plain serialized data. Save-time re-reads the live
 * stack independently (see `EditorBridge.executeStackSave`), so no stack snapshot is retained.
 *
 * Each per-stack metadata namespace (e.g. `SocketReforge`) becomes one [EditorSource] in the
 * editor's existing source dropdown — the same dropdown that already lists BASE, auto-detected
 * `MOD:` stat sources, and API extension panels. Per-stack source ids use the [STACK_PREFIX].
 *
 * @property baseItemId  The held stack's item id — titles the editor and matches the session to
 *                       the opened item.
 * @property sources     Per-stack selectable sources, one per metadata namespace
 *                       (id = `"STACK:<namespace>"`, name = the namespace label).
 * @property fields      Source id → its serialized editable fields (already mapped from the
 *                       live metadata on the world thread).
 * @property categories  Source id → ordered category headings (for grouped rendering).
 * @property quantity    The held stack's quantity, snapshotted on the world thread at open
 *                       Engine-native per-stack value → drives the
 *                       "This Item" free-tier Quantity field.
 * @property durability  The held stack's current durability, snapshotted at open. Engine-native
 *                       per-stack value (the durability bar) → drives the free-tier Durability field.
 * @property maxDurability The held stack's max durability, snapshotted at open. Engine-native
 *                       per-stack value → the per-instance value shown for MaxDurability in
 *                       "This Item" scope (distinct from the item asset's default max).
 */
data class StackEditContext(
    val baseItemId: String,
    val sources: List<EditorSource>,
    val fields: Map<String, List<SerializedFieldDefinition>>,
    val categories: Map<String, List<String>>,
    val quantity: Int = 1,
    val durability: Double = 0.0,
    val maxDurability: Double = 0.0,
    /**
     * The held stack's CURRENT per-item damage multipliers (combat tier),
     * keyed by `DamageCause` id (e.g. `"Physical" -> 1.5`). Parsed from the stack's `ItemForge`
     * metadata at inspect-open so the editor can show this weapon's current per-item damage (asset
     * base × multiplier) in "This Item" scope. Empty when the item carries no per-item damage edits.
     */
    val damageMultipliers: Map<String, Double> = emptyMap(),
    /**
     * The held stack's CURRENT per-item armor RESISTANCE fractions (combat
     * tier), keyed by `DamageCause` id (e.g. `"Physical" -> 0.25` = 25% extra reduction). Parsed from
     * `ItemForge.def` at inspect-open so the editor shows this armor piece's current per-item
     * resistance in "This Item" scope. Empty when none.
     */
    val damageResist: Map<String, Double> = emptyMap(),
    /**
     * The held stack's CURRENT per-item ENTITY-STAT bonuses (attribute
     * tier), keyed by `EntityStatType` id (e.g. `"Health" -> 50.0` = +50 max health while worn).
     * Parsed from `ItemForge.stat` at inspect-open so the editor shows this piece's current per-item
     * stat bonuses in "This Item" scope. Empty when none.
     */
    val statBonuses: Map<String, Double> = emptyMap(),
    /**
     * This held instance's CURRENT custom display NAME, if one is set on the stack via the engine's
     * `ItemDisplay` metadata (a literal `Message`). null = no per-instance name (the item shows its
     * type name). Snapshotted on the world thread at inspect-open so the "This item" Name field can
     * prefill with the instance's own value (distinct from the global/type name).
     */
    val customName: String? = null,
    /**
     * This held instance's CURRENT custom LORE/description, if set via `ItemDisplay` metadata.
     * null = no per-instance lore. Snapshotted at inspect-open for the "This item" Lore field.
     */
    val customLore: String? = null
) {
    /** True if this session actually contributes any per-stack metadata source (a mod namespace). */
    fun hasSources(): Boolean = sources.isNotEmpty()

    /** True if this is a durable item (has a max-durability bar) — gates the Durability free field. */
    fun isDurable(): Boolean = maxDurability > 0.0

    companion object {
        /**
         * Source-id prefix for a per-stack metadata namespace, e.g. `"STACK:SocketReforge"`.
         * Distinct from `"MOD:"` (asset stat sources → asset save) and extension ids
         * (→ extension save) so [me.itemforge.vuetale.EditorBridge] can route a save from a
         * per-stack source to the held-item write path. See `EditorBridge.executeSave`.
         */
        const val STACK_PREFIX = "STACK:"
    }
}

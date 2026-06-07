package me.itemforge.provider

import com.hypixel.hytale.server.core.asset.type.item.config.Item

/**
 * Lets a mod plug its **own panel** into the ItemForge editor for items it cares about.
 *
 * ## Why this exists
 *
 * ItemForge edits an item's fields by reading them off the `Item` asset (the dynamic codec
 * scan), and that already covers the vast majority of mods — anything whose data lives in the
 * item. What it *can't* reach is data a mod keeps **elsewhere**: the mod's own storage/config,
 * a separate registry, computed values — anything ItemForge has no automatic access to. An
 * `EditorExtension` is the bridge: the mod tells ItemForge what to show and how to apply
 * changes, and ItemForge renders it as a selectable **stat/source panel** in the editor.
 *
 * ## Model — everything is per-item-id (global)
 *
 * Extensions operate on the **`Item`** (i.e. the item id / definition), not on a single
 * physical stack. An edit applies to that item id for everyone, just like every other
 * ItemForge edit. There is no "this one copy" concept. The mod owns persistence and any live
 * re-sync of its own data.
 *
 * ## How a mod builds its UI
 *
 * The mod returns a list of [EditorComponent]s from [buildPanel] — text/number/toggle/dropdown
 * fields, buttons, sections, labels — composing its own panel. ItemForge renders them with its
 * themed widgets (consistent look; the mod chooses *what*, ItemForge owns *how it looks*). The
 * mod does not ship raw UI.
 *
 * ## Lifecycle / events
 *
 *  - [manages] — does this extension have a panel for this item? Cheap, side-effect-free.
 *  - [buildPanel] — the components to render for this item (re-called after actions/saves to
 *    reflect new state).
 *  - [applyChanges] — called when the admin hits Save, with `fieldId → newValue` for every
 *    field the admin changed. Apply them globally + persist.
 *  - [onAction] — called when a button is pressed (its `actionId`). Do whatever (regenerate,
 *    reset, randomise…); the panel is rebuilt afterwards so the UI reflects the result.
 *
 * ## Threading & robustness
 *
 *  - [manages]/[buildPanel] are reads invoked while building the editor payload — keep them
 *    cheap and thread-safe (treat as callable off the world thread).
 *  - [applyChanges]/[onAction] are invoked on a background worker thread (not the V8/UI
 *    thread); if they must touch the entity store, dispatch to the world thread themselves.
 *  - Every call ItemForge makes is exception-isolated: a throwing extension is logged and its
 *    panel/save/action is skipped — it can never crash the editor.
 *
 * Register once at startup via [me.itemforge.ItemForgeAPI.registerExtension]. All methods are
 * required (no Kotlin default methods) so the contract is identical from Java.
 */
interface EditorExtension {

    /** Stable unique id (e.g. `"simpleenchants"`). The selection/persistence key for this panel. */
    fun getExtensionId(): String

    /** Human-readable label shown in the editor's source dropdown (e.g. `"Enchantments"`). */
    fun getDisplayName(): String

    /** True if this extension contributes a panel for [item]. Cheap, side-effect-free. */
    fun manages(item: Item): Boolean

    /**
     * The components making up this extension's panel for [item], in display order.
     * Empty → the panel/source is omitted. Re-invoked after [applyChanges]/[onAction].
     */
    fun buildPanel(item: Item): List<EditorComponent>

    /**
     * Apply the admin's field edits for [item], globally. [changes] maps each changed field's
     * id to its new value (coerced to the field's type: Int for `integer`, Double for `number`,
     * Boolean for `toggle`, String for `text`/`dropdown`). The mod persists + re-syncs its data.
     */
    fun applyChanges(item: Item, changes: Map<String, Any?>)

    /**
     * Handle a button press for [item]. [actionId] is the button's id. The mod does its thing;
     * ItemForge rebuilds the panel afterwards so the UI reflects any resulting changes.
     */
    fun onAction(item: Item, actionId: String)
}

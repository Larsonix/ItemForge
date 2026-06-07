# ItemForge editor extensions

ItemForge edits an item by reading its fields straight off the item asset. For most mods that means there is nothing to do: if your mod's stats live on the item, they already appear in the editor and are editable with no code from you.

An extension is for the other case. When a mod keeps item-related data somewhere ItemForge cannot see (its own config, a separate registry, computed values), an `EditorExtension` lets the mod add its own panel to the editor. The mod decides what to show and what to do when the admin saves or presses a button; ItemForge draws it with the editor's own widgets and routes the events back.

Everything an extension edits is per item id (global), the same as every other ItemForge edit. There is no "this one copy" concept.

## At a glance

1. Implement `me.itemforge.provider.EditorExtension`.
2. Register it once at startup: `ItemForgeAPI.registerExtension(new MyExtension())`.
3. Your panel appears as an entry in the editor's "Stat Source" dropdown, next to "Base Item".
4. The admin edits your fields and presses Save; ItemForge calls your `applyChanges`. Buttons call your `onAction`.
5. You own the data. ItemForge never stores it and never writes to the item asset on your behalf.

## Depending on ItemForge

Put the ItemForge jar on your compile classpath as a compile-only dependency. It is present at runtime because the server has ItemForge installed. You need exactly three types: `me.itemforge.ItemForgeAPI`, `me.itemforge.provider.EditorExtension`, and `me.itemforge.provider.EditorComponent`.

`registerExtension` is safe to call before ItemForge has finished starting. Early registrations are buffered and applied once it is ready, so load order does not matter. If you want ItemForge to be optional (your mod runs with or without it), keep every reference to these classes behind one guarded call so the classes are never touched when ItemForge is absent.

## The interface

`EditorExtension` has six methods. All are required (there are no default methods, so it reads the same from Java and Kotlin).

| Method | When ItemForge calls it | What you do |
|--------|-------------------------|-------------|
| `String getExtensionId()` | on every lookup | Return a stable, unique id, e.g. `"soulbound"`. It is the selection and persistence key. |
| `String getDisplayName()` | building the dropdown | Return the label shown to the admin, e.g. `"Soulbound"`. |
| `boolean manages(Item item)` | building the editor | Return true if you have a panel for this item. Keep it cheap and side-effect free. |
| `List<EditorComponent> buildPanel(Item item)` | on open, on save, and after an action | Return the components to show, in order, with current values. Return an empty list to hide the panel. |
| `void applyChanges(Item item, Map<String,Object> changes)` | the admin presses Save | Apply and persist the changed fields. |
| `void onAction(Item item, String actionId)` | a button is pressed | Do whatever the button means. The panel is rebuilt afterwards. |

`manages` and `buildPanel` are reads, and ItemForge calls them often: on every editor open, again on save (to read your field types), and again after each button press. Make them fast and free of side effects.

## Building the panel

You do not ship UI. You return a list of `EditorComponent`, and ItemForge renders each one with the editor's themed widgets. Build them with the static factory methods on `EditorComponent`:

| Factory | Renders |
|---------|---------|
| `section(label)` | A heading with a divider line. |
| `label(text)` | A line of read-only text. |
| `spacer()` | Vertical space. |
| `textField(id, label, value)` | A text input. |
| `numberField(id, label, value, min, max, step)` | A decimal input. |
| `integerField(id, label, value, min, max)` | A whole-number input. |
| `toggle(id, label, value)` | A Yes/No button. |
| `dropdown(id, label, value, options)` | A selector over the given options. |
| `button(actionId, label, style)` | A clickable button. `style` is `"primary"` or `"secondary"`. |

Every factory also has a shorter overload and a variant taking a trailing `tooltip`. Read "Current limits" below before relying on `tooltip`, `min`, `max`, or `step`.

The `id` you give a field is the same id you receive in `applyChanges`. The `actionId` you give a button is what you receive in `onAction`. Sections, labels, and spacers carry no id.

## A complete example (Java)

A mod that tracks, per item id, a bound owner and whether the item is tradeable, plus a button to clear the owner. The storage and persistence are the mod's own; ItemForge only renders the panel and delivers the edits.

```java
package com.example.soulbound;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import me.itemforge.ItemForgeAPI;
import me.itemforge.provider.EditorComponent;
import me.itemforge.provider.EditorExtension;

public final class SoulboundExtension implements EditorExtension {

    // The mod's own storage, keyed by item id. ItemForge never reads or persists this.
    private final Map<String, String>  ownerByItem     = new ConcurrentHashMap<>();
    private final Map<String, Boolean> tradeableByItem = new ConcurrentHashMap<>();

    @Override public String getExtensionId() { return "soulbound"; }
    @Override public String getDisplayName() { return "Soulbound"; }

    @Override public boolean manages(Item item) {
        return item.getId().startsWith("soulbound_");
    }

    @Override public List<EditorComponent> buildPanel(Item item) {
        String id = item.getId();
        return List.of(
            EditorComponent.section("Soulbound"),
            EditorComponent.textField("owner", "Bound owner", ownerByItem.getOrDefault(id, "")),
            EditorComponent.toggle("tradeable", "Tradeable", tradeableByItem.getOrDefault(id, Boolean.TRUE)),
            EditorComponent.button("clear", "Clear owner", "secondary")
        );
    }

    @Override public void applyChanges(Item item, Map<String, Object> changes) {
        String id = item.getId();
        if (changes.containsKey("owner")) {
            ownerByItem.put(id, (String) changes.get("owner"));
        }
        if (changes.containsKey("tradeable")) {
            tradeableByItem.put(id, (Boolean) changes.get("tradeable"));
        }
        persist(); // write your own config to disk
    }

    @Override public void onAction(Item item, String actionId) {
        if ("clear".equals(actionId)) {
            ownerByItem.remove(item.getId());
            persist();
        }
    }

    private void persist() { /* your save */ }
}
```

Register it once, in your plugin's startup:

```java
ItemForgeAPI.registerExtension(new SoulboundExtension());
```

## How values come back

`applyChanges` receives only the fields the admin actually changed, keyed by your field id. Values arrive already coerced to the field's declared type:

* `integerField` gives an `Integer`
* `numberField` gives a `Double`
* `toggle` gives a `Boolean`
* `textField` and `dropdown` give a `String`

A value for an id that is not in your current panel is passed through unchanged.

## Threading

This part matters. The six methods run on two different kinds of thread.

`manages` and `buildPanel` run while ItemForge builds the editor payload. That can be the server's world thread or the UI (V8) thread, because a reset or a button press rebuilds the panel on the UI thread. Treat them as callable from any thread: read your own state, do not block, and do not touch the entity store or world from inside them.

`applyChanges` and `onAction` run on a background worker thread. That is neither the world thread nor the UI thread. If you need to read or write the entity store or anything else that requires the world thread, dispatch to it yourself.

Every call ItemForge makes into your extension is wrapped. If a method throws, ItemForge logs it and skips that panel, save, or action; a broken extension cannot crash the editor. A hung call is caught by the editor's watchdog. Keep these methods quick regardless.

## Buttons and live updates

When a button is pressed, ItemForge calls your `onAction`, then rebuilds the panel by calling `buildPanel` again and pushes the fresh result to the client. So a button that changes state (reroll, regenerate, reset) follows one pattern: change your state in `onAction`, and return the new values from `buildPanel`. The UI updates on its own.

## What ItemForge does and does not do

It does: draw your panel, collect the admin's edits, hand them to you, route button presses, and rebuild the panel after actions and saves.

It does not: store your data, write to the item asset, call `loadAssets`, or persist anything for you. Persistence and any live re-sync of your data are your responsibility.

## Permissions

Editing an extension panel counts as editing item stats. The admin needs the same permission that allows editing item stats. A player without it sees your panel read-only, and the server rejects any save or action they attempt anyway.

## Current limits

A few `EditorComponent` properties are accepted by the factories but not yet acted on by the panel renderer. They are safe to set, but do not depend on them:

* `min`, `max`, and `step` on number and integer fields are not enforced by the input. Validate ranges yourself in `applyChanges`.
* `tooltip` is not shown in extension panels.
* `readOnly` applies to text, number, and integer fields. Dropdowns and toggles ignore it. For a value the admin should not change, use a `label` or leave the component out.

Two editor behaviours to know about:

* The editor's Reset button only reverts base-item overrides. It does nothing to your panel. If you want a revert, add your own button and handle it in `onAction`.
* Switching the source dropdown away from your panel discards unsaved edits in it.

## Verifying it works

Install your mod and ItemForge on the same server, then run:

```
/itemforge extensions
```

It lists every registered extension by name and id. Then open any item your extension manages, from `/itemforge`, `/itemforge <itemId>`, or by inspecting it in-world. A "Stat Source" dropdown appears at the top of the editor; select your panel's name to see it. With no extension registered, the dropdown does not appear and the editor behaves exactly as it did before.

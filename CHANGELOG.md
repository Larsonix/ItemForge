# Changelog

## 1.0.1 - Runs On More Hosts

Small but important one. On some cheap, locked-down server hosts (PebbleHost and the like), ItemForge could fail to start with an error about a missing `libatomic.so.1` file, and there was nothing you could do about it from your side, since those hosts don't let you install system packages or touch the console. This release fixes it : ItemForge now carries that file itself, so it just works, with nothing for you to set up.

---

### The Fix
- **ItemForge starts on locked-down hosts now.** ItemForge's editor runs on a JavaScript engine (Vuetale) that needs a small system library called libatomic. Most servers already have it, but minimal hosts like PebbleHost don't ship it and won't let you add it, so the mod would crash on boot with `libatomic.so.1: cannot open shared object file`. ItemForge now bundles that file and loads it at startup, only on the hosts that actually need it. If your server was already running ItemForge fine, nothing changes for you.

Thanks to Guaz for reporting it.

## 1.0.0 - Initial Release

The initial public release of ItemForge. Edit any item's properties live, in game, no JSON and no restarts.

- Live editing for armor, weapons, tools, food, potions, gliders, and containers, applied instantly to every copy already in the world
- Works with items from any mod : reads each item's real schema through codec introspection instead of hardcoding known types
- Dashboard browser : grid or table view, search, filters (type, mod, quality, slot, status), sortable columns
- Editor tabs : Properties, Defense, Damage, Recipe, General
- Recipes : edit, create from scratch, or remove. Bench and panel selection, input materials with item search
- Custom name and lore, editable globally (the item type) or on the single stack a player is holding
- Batch operations across many items at once, with undo
- Modded-stat detection : picks up stats other mods register, weapons and armor, each labelled with the mod it came from
- Per-item and per-stack editing : the shared base item, or just the stack in someone's hand
- Inspect mode : crouch and right-click a held item to open it straight in the editor
- Editor extension API : other mods can add their own panel for data ItemForge can't read on its own (see docs/EXTENSIONS.md)
- Audit log of every change : who, what, when
- Permissions : per-section gates for stats, recipes, general properties, and reset
- Instant dashboard and editor open, even on servers running 50+ mods
- Mod credits page : `/credits` lists every installed mod and its authors, bundling Creditor (by Lordimass) as a library so creators always get credited

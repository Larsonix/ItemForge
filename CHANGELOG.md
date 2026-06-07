# Changelog

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

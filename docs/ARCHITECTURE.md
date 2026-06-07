# ItemForge — Architecture Specification

Version: 3.1
Date: 2026-05-25
Status: IMPLEMENTED — Phases 1–13 complete: engine, full editor tabs (Properties/Defense/Damage/Recipe/General), dashboard browser, batch operations, audit log, inspect mode, per-stack and per-item editing, modded-stat detection, custom name/lore, and anonymous metrics. ~60 Kotlin files. Verified live on a 50+-mod server.

Synthesizes: All 13 research/design docs + 9-agent ecosystem sweep (60+ vendor mods, 36 builtin plugins, TrailOfOrbis, Vuetale, decompiled server core).

Changes from v2.0: Fundamental architectural shift. Reflection-based field access replaced by Hytale's own BuilderCodec public API (encode/decode on fields). Manual packet sync replaced by AssetStore.loadAssets() (engine handles broadcasting). Hardcoded field scanner replaced by BuilderCodec.getEntries() introspection. Config format changed to Gson+JSON for overrides. 3-tier atomic writes and CAS debounce adopted from vendor mod patterns.

> **RUNTIME CORRECTIONS (from Phase 1 probes + SDK source read + production audit):**
> The pseudocode below illustrates architectural concepts. For production patterns proven at runtime, see **SDK_FINDINGS.md §9.8**. Key corrections:
> - Use `selectField(fieldList, version)` instead of `fieldList.last()` — version-aware selection
> - No unsafe cast needed on BuilderField — `codec.entries` is properly typed `Map<String, List<BuilderField<Item, *>>>`
> - Use `field.codec.childCodec is PrimitiveCodec` instead of `field.isPrimitive` — the field is protected
> - Use `assetStore.decode()` (not `codec.decode()`) for items with Interactions/InteractionVars — ContainedAssetCodec requires AssetExtraInfo
> - Item codec has **54 fields** (not ~40). Armor has **11 sub-fields**. Container sub-field is `ItemTag` (not `tag`).
> - Server tested with **5,523 items** across **11 mods**. Tag sentinel = `Integer.MIN_VALUE`.
> - **Path A (direct decode) is ONLY safe for top-level primitives/strings.** For compound fields (Armor, Weapon, Tool, etc.), `BuilderField.decode()` creates a NEW sub-object from partial BSON, wiping non-overridden sub-fields to defaults. All component overrides MUST use Path B (full encode → merge → decode).
> - **Path B calls `loadAssets()` internally** — the only public API to insert a decoded item into the asset map. Callers must NOT also call `commitItem()` for Path B items.
> - **HytaleLogger (Google Flogger)**, NOT `java.util.logging.Logger`. API: `logger.atInfo().log()`, not `logger.info()`.
> - **Event registration in `setup()`**, not `start()`. Evidence: CraftingPlugin.java:141-144.
> - **`LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>>`** — fully typed handler, no `Any` cast needed in Kotlin.

---

## 1. Core Design Principle: Use The Engine, Don't Fight It

Hytale's built-in Asset Editor modifies items by:
1. Discovering fields via `BuilderCodec.getEntries()` — the codec knows every JSON field
2. Reading/writing values via `BuilderField.encode()/decode()` — the codec's own public API
3. Committing changes via `AssetStore.loadAssets()` — the engine handles sync, events, caching

ItemForge uses the **exact same pipeline**. Zero reflection on game classes. Zero manual packet construction. The engine does the heavy lifting.

**Evidence**: `AssetStoreTypeHandler.java:47-61` (Hytale's editor), `AssetStore.java:420` (public loadAssets), `BuilderCodec.java:154` (public getEntries), `BuilderField.java:130,141` (public decode/encode). Verified accessible from plugins by SimpleEnchantments, LifeCrops, CraftingPlugin.

---

## 2. System Architecture Overview

```
+-----------------------------------------------------------------------+
|                        ITEMFORGE PLUGIN                                |
|                                                                        |
|  +----------------+    +------------------+    +--------------------+  |
|  |  ENTRY LAYER   |    |   VUETALE UI     |    |  PERSISTENCE LAYER |  |
|  |                |    |   (Vue 3 + TS)   |    |                    |  |
|  | Commands       |    | Dashboard.vue    |    | ConfigManager      |  |
|  | Interaction    |--->| Editor.vue       |--->| AtomicFileWriter   |  |
|  | Listener       |    | BatchEdit.vue    |    | MigrationRunner    |  |
|  | (inspect mode) |    | Overlays         |    | BackupManager      |  |
|  +----------------+    +--------+---------+    +--------------------+  |
|                                 |                                      |
|  +------------------------------+------------------------------------+ |
|  |                    CORE ENGINE                                    | |
|  |                              |                                    | |
|  |  +-----------------+  +------+------+  +------------------------+ | |
|  |  | Codec Scanner   |  | Override    |  | Asset Sync             | | |
|  |  |                 |  | Engine      |  |                        | | |
|  |  | BuilderCodec    |  |             |  | AssetStore.loadAssets() | | |
|  |  |  .getEntries()  |  | Apply       |  | (engine handles:       | | |
|  |  | BuilderField    |  | Revert      |  |  - UpdateItems packet  | | |
|  |  |  .encode/decode |  | Validate    |  |  - broadcast to all    | | |
|  |  | Zero reflection |  | Batch apply |  |  - cache invalidation  | | |
|  |  | on game classes |  |             |  |  - LoadedAssetsEvent)  | | |
|  |  +-----------------+  +-------------+  +------------------------+ | |
|  |                                                                    | |
|  |  +--------------------------------------------------------------+ | |
|  |  | ORIGINALS CACHE                                               | | |
|  |  | Pre-override BSON snapshots captured during LoadedAssetsEvent  | | |
|  |  +--------------------------------------------------------------+ | |
|  |                                                                    | |
|  |  +--------------------------------------------------------------+ | |
|  |  | ASSET METADATA                                                | | |
|  |  | ModSourceTracker — AssetMap.getAssetPack() per item           | | |
|  |  | TagCache — getRawTags() scan + getKeysForTag() O(1) filtering | | |
|  |  +--------------------------------------------------------------+ | |
|  +--------------------------------------------------------------------+ |
|                                                                        |
|  +------------------------------------------------------------------+  |
|  | EXTENSION API                                                     |  |
|  | EditorExtension interface + Registry (mod-built editor panels)    |  |
|  +------------------------------------------------------------------+  |
|                                                                        |
|  +------------------------------------------------------------------+  |
|  | CROSS-CUTTING                                                     |  |
|  | AuditLogger | PermissionChecker | HealthMonitor | InspectToggle  |  |
|  +------------------------------------------------------------------+  |
+------------------------------------------------------------------------+
```

**Two-process architecture**: Server-side plugin (Kotlin) handles game state mutation, config persistence, codec operations, and asset sync. UI (Vue 3 + TypeScript via Vuetale's V8 engine) handles rendering, client-side sorting/filtering, and reactive state. Communication: `setData()` (server → UI), event callbacks (UI → server).

---

## 3. Package Structure

### 3.1 Server-Side (Kotlin)

```
me.itemforge/
├── ItemForgePlugin.kt               # Plugin entry point (extends JavaPlugin)
├── ItemForgeAPI.kt                  # Public API (EditorExtension registration)
│
├── core/
│   ├── OverrideEngine.kt            # Apply/revert overrides via BuilderField.decode()
│   ├── OriginalsCache.kt            # Pre-override BSON snapshots
│   ├── AssetCommitter.kt            # AssetStore.loadAssets() wrapper + pitfall guards
│   ├── HealthMonitor.kt             # Startup codec health check
│   ├── BatchEngine.kt               # Batch stat operations (scale, set, add, subtract)
│   └── BatchRecipeEngine.kt         # Batch recipe operations (scale qty, replace ingredient, scale time)
│
├── scanner/
│   ├── CodecScanner.kt              # BuilderCodec.getEntries() field discovery
│   ├── FieldDefinition.kt           # Discovered field: name, type, value, constraints, docs
│   ├── FieldConstraints.kt          # From BuilderField.getValidators()
│   └── FieldState.kt                # Enum: DEFAULT/MODIFIED/INVALID/READ_ONLY
│
├── metadata/
│   ├── ModSourceTracker.kt          # AssetMap.getAssetPack() — item → mod name
│   ├── TagCache.kt                  # getRawTags() scan + getKeysForTag() filtering
│   └── ItemNameResolver.kt          # I18nModule.getMessage() + formatting fallback
│
├── provider/
│   ├── EditorExtension.kt           # Public API: mod-built editor panel interface
│   ├── EditorComponent.kt           # Public API: themed UI component palette (factories)
│   ├── ExtensionRegistry.kt         # Registration + lookup (exception-isolated)
│   ├── MetadataStackReader.kt       # Per-stack metadata namespaces read off a held item
│   └── StackEditContext.kt          # Per-player held-stack edit session (inspect mode)
│
├── config/
│   ├── ConfigManager.kt             # Load/save/reload all config files
│   ├── ItemOverrideStore.kt         # Gson+JSON override storage (nullable overlay pattern)
│   ├── RecipeOverrideStore.kt       # Gson+JSON recipe override storage
│   ├── PluginConfig.kt              # YAML main config (human-readable settings)
│   ├── AtomicFileWriter.kt          # 3-tier: ATOMIC_MOVE → REPLACE_EXISTING → copy+delete
│   ├── DebouncedWriter.kt           # AtomicLong + CAS debounce (2s for overrides)
│   ├── BackupManager.kt             # Pre-migration backups + periodic snapshots
│   ├── SchemaValidator.kt           # Per-field validation on load
│   └── migration/
│       ├── MigrationRunner.kt       # Sequential migration executor
│       └── ConfigMigration.kt       # Interface for migrations
│
├── vuetale/
│   ├── VuetaleIntegration.kt        # Module registration, page opening, V8 init
│   ├── DashboardBridge.kt           # Prepares + pushes dashboard data via setData()
│   ├── EditorBridge.kt              # Prepares + pushes editor data, handles save/reset
│   ├── BatchBridge.kt               # Batch preview, apply, undo events
│   └── DataContracts.kt             # Serializable payloads for setData()
│
├── entry/
│   ├── ItemForgeCommand.kt          # /itemforge command tree
│   ├── InteractionListener.kt       # Crouch + right-click (opt-in inspect mode)
│   ├── InspectToggle.kt             # Per-player toggle state
│   └── AssetLoadListener.kt         # LoadedAssetsEvent — apply overrides, rebuild caches
│
├── audit/
│   ├── AuditLogger.kt               # Change logging to file + console
│   └── AuditEntry.kt                # Data class: who/when/what/old/new
│
└── util/
    ├── PermissionChecker.kt         # Centralized permission nodes (GroupManager pattern)
    ├── BsonHelper.kt                # BSON document manipulation utilities
    └── ValueFormatter.kt            # Display formatting for UI labels
```

### 3.2 Client-Side UI (Vue 3 + TypeScript + Tailwind CSS)

```
src/ui/
├── package.json
├── vite.config.ts
├── vuetale-plugin.json
│
└── lib/
    ├── pages/
    │   ├── Dashboard.vue            # Grid view, table view, filters, batch actions
    │   └── Editor.vue               # Properties/recipe/general tabs
    │
    ├── components/
    │   ├── FieldEditor.vue          # Universal field renderer by valueType
    │   ├── StatRow.vue              # Label + input + original value
    │   ├── RecipeInput.vue          # Material dropdown + quantity
    │   ├── TableView.vue            # Sortable headers, checkboxes, row click
    │   ├── GridView.vue             # Icon grid with quality backgrounds
    │   ├── FilterBar.vue            # Search + type/mod/status dropdowns
    │   ├── ActionBar.vue            # Batch edit, reset, close, summary
    │   ├── TabBar.vue               # Dynamic tabs per item type
    │   ├── BatchEditOverlay.vue     # Batch operation with live preview
    │   ├── BatchResetOverlay.vue    # Batch reset confirmation
    │   ├── UnsavedOverlay.vue       # Save & Close / Discard / Keep Editing
    │   ├── ConflictOverlay.vue      # Their changes vs your changes
    │   ├── ExtremeValueOverlay.vue  # Multiplicative warning
    │   ├── PermissionOverlay.vue    # Permission denied
    │   └── BatchUndoToast.vue       # "Batch applied — [Undo]" 10s timeout
    │
    ├── composables/
    │   ├── useItemData.ts           # Reactive item list from setData()
    │   ├── useFilters.ts            # Filter state + computed filteredItems
    │   ├── useEditorState.ts        # Pending changes, dirty tracking
    │   ├── useBatchState.ts         # Selection, preview, undo buffer
    │   └── useOverlays.ts           # Overlay visibility management
    │
    └── types/
        ├── ItemData.ts              # Dashboard item interface
        ├── FieldDefinition.ts       # Matches server FieldDefinition
        ├── EditorPayload.ts         # Editor setData payload
        └── BatchPreview.ts          # Batch preview rows
```

**Total: ~35 Kotlin classes + ~15 Vue components + ~5 composables.** Fewer classes than v2.0 because the codec API eliminates ReflectionAccessor, InteractionChainWalker, and simplifies PacketSyncService into AssetCommitter.

---

## 4. Core Engine: The Codec Pipeline

### 4.1 CodecScanner — Dynamic Field Discovery

Discovers every editable field on any item using the engine's own codec introspection. No hardcoded field names.

```
class CodecScanner {
    // Cached at startup — the Item BuilderCodec and its field map
    private lateinit var itemCodec: BuilderCodec<Item>
    private lateinit var recipeCodec: BuilderCodec<CraftingRecipe>

    fun init() {
        // Get the Item codec from the asset store
        val itemStore = Item.getAssetStore()    // AssetStore<String, Item>
        itemCodec = itemStore.codec as BuilderCodec<Item>  // The codec that parses Item JSON

        val recipeStore = CraftingRecipe.getAssetStore()
        recipeCodec = recipeStore.codec as BuilderCodec<CraftingRecipe>
    }

    // Discover ALL editable fields on an item
    fun scan(item: Item, config: PluginConfig): List<FieldDefinition> {
        val fields = mutableListOf<FieldDefinition>()
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        // Walk every entry in the Item codec
        for ((jsonKey, fieldList) in itemCodec.getEntries()) {
            val field = fieldList.last()  // Use latest version of the field

            // Read current value via the codec's own encode path
            val doc = BsonDocument()
            field.encode(doc, item, extraInfo)
            val currentValue = doc.get(jsonKey)  // BsonValue

            // Skip display-only fields (Icon, Model, Texture, etc.)
            if (isDisplayField(jsonKey)) continue

            // For primitive fields (MaxDurability, ItemLevel, MaxStack, etc.)
            if (field.isPrimitive) {
                fields.add(buildPrimitiveField(jsonKey, field, currentValue, config))
                continue
            }

            // For component sub-objects (Armor, Weapon, Tool, Glider, Utility, etc.)
            val childCodec = field.codec.childCodec
            if (childCodec is BuilderCodec<*> && currentValue != null) {
                // Recursively scan the component's fields
                val component = readComponentFromItem(item, jsonKey)
                if (component != null) {
                    scanComponent(fields, jsonKey, childCodec, component, extraInfo, config)
                }
            }
        }

        return fields
    }

    // Recursively scan a component (Armor, Weapon, Tool, etc.)
    private fun scanComponent(
        fields: MutableList<FieldDefinition>,
        parentKey: String,
        codec: BuilderCodec<*>,
        component: Any,
        extraInfo: ExtraInfo,
        config: PluginConfig
    ) {
        for ((key, fieldList) in codec.getEntries()) {
            val field = fieldList.last()
            val doc = BsonDocument()
            // Use unchecked cast — the codec's own encode is type-safe internally
            @Suppress("UNCHECKED_CAST")
            (field as BuilderField<Any, Any>).encode(doc, component, extraInfo)
            val value = doc.get(key)

            val displayName = formatFieldName(parentKey, key)  // "Armor.StatModifiers" -> "Armor Stats"
            val category = formatCategoryName(parentKey)       // "Armor" -> "Armor Stats"

            if (field.isPrimitive && value != null) {
                fields.add(buildPrimitiveField(
                    id = "$parentKey.$key",
                    displayName = displayName,
                    category = category,
                    field = field,
                    value = value,
                    config = config
                ))
            } else if (value is BsonDocument) {
                // Map fields (StatModifiers, DamageResistance, etc.)
                // Iterate the BSON document's entries — each key is a stat/damage type
                for (entry in value.entries) {
                    fields.add(buildMapEntryField(
                        id = "$parentKey.$key.${entry.key}",
                        displayName = "${entry.key} ($displayName)",
                        category = category,
                        bsonValue = entry.value,
                        config = config
                    ))
                }
            }
            // Deeper nesting (e.g., InteractionVars) handled recursively
        }
    }

    // Build a FieldDefinition from codec metadata
    private fun buildPrimitiveField(
        id: String, displayName: String, category: String,
        field: BuilderField<*, *>, value: BsonValue, config: PluginConfig
    ): FieldDefinition {
        val validators = field.validators ?: emptyList()
        val constraints = extractConstraints(validators, config)
        val tooltip = field.documentation  // Built into the codec!
        val effectDescription = computeEffectDescription(id, value, constraints)

        return FieldDefinition(
            id = id,
            displayName = displayName,
            category = category,
            valueType = bsonTypeToClass(value),
            currentValue = bsonToKotlin(value),
            constraints = constraints,
            readOnly = false,
            tooltip = tooltip,
            effectDescription = effectDescription
        )
    }

    // Which fields to skip (display/rendering only, not gameplay)
    private fun isDisplayField(key: String): Boolean {
        return key in setOf(
            "Icon", "Model", "Texture", "Scale", "Animation",
            "PlayerAnimationsId", "DroppedItemAnimation",
            "Particles", "FirstPersonParticles", "Trails", "Light",
            "SoundEventId", "ItemSoundSetId", "Reticle",
            "ItemAppearanceConditions", "IconProperties"
        )
    }
}
```

**Why this is better than v2.0's reflection scanner**: The codec KNOWS every field. `getEntries()` returns them all. `field.isPrimitive` tells us the type. `field.getValidators()` gives us min/max/step. `field.getDocumentation()` gives us tooltips. New fields added in Hytale updates appear automatically. Zero hardcoded field names for game classes.

**Evidence**: `BuilderCodec.java:154` (getEntries), `BuilderField.java:141` (encode), `BuilderField.java:126` (getDocumentation), `BuilderField.java:117` (getValidators), `AssetRegistryLoader.java:313-373` (Hytale's editor uses this exact approach).

### 4.2 OverrideEngine — Two Modification Paths

The Item codec reveals a critical distinction: most fields are simple data on the Item object, but `InteractionVars` uses `RootInteraction.CHILD_ASSET_CODEC` which **creates entire child asset objects** (RootInteraction → DamageEntityInteraction → DamageCalculator). Modifying weapon damage, food healing, or potion effects requires rebuilding those child assets — not just setting a field.

**Path A (Simple fields)**: `BuilderField.decode()` directly on the existing Item. Covers: MaxDurability, StatModifiers, DamageResistance, Tool.speed, Glider physics, Container.capacity, Quality, ItemLevel, MaxStack, FuelQuality, etc.

**Path B (Interaction chain fields)**: Full BSON re-decode via `assetStore.decode()`. The CHILD_ASSET_CODEC rebuilds the entire interaction chain with modified damage/healing values. Covers: InteractionVars (weapon damage, food healing, potion effects).

**Evidence for why Path B is needed**: Item.java:264 — `appendInherited(new KeyedCodec("InteractionVars", new MapCodec(RootInteraction.CHILD_ASSET_CODEC, ...)))`. The CHILD_ASSET_CODEC creates RootInteraction assets from JSON. Simply setting the `interactionVars` Map<String,String> on an existing Item does NOT rebuild the DamageCalculator objects.

```
class OverrideEngine {
    private val codecScanner: CodecScanner
    private val originals: OriginalsCache
    private val committer: AssetCommitter
    private val itemStore: AssetStore<String, Item> = Item.getAssetStore()

    // Apply an override — routes to Path A or Path B based on what's being changed
    fun applyItemOverride(itemId: String, overrideBson: BsonDocument) {
        val hasInteractionOverrides = overrideBson.containsKey("InteractionVars")
        val hasSimpleOverrides = overrideBson.keys.any { it != "InteractionVars" }

        if (hasInteractionOverrides) {
            // PATH B: Full re-decode for interaction chain modifications
            applyViaFullRedecode(itemId, overrideBson)
        } else if (hasSimpleOverrides) {
            // PATH A: Direct field decode for simple properties
            applyViaDirectDecode(itemId, overrideBson)
        }
    }

    // PATH A: Direct BuilderField.decode() on existing item
    // Fast — only modifies the fields that changed, no full re-decode
    private fun applyViaDirectDecode(itemId: String, overrideBson: BsonDocument) {
        val item = Item.getAssetMap().getAsset(itemId) ?: return
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        for ((jsonKey, fieldList) in codecScanner.itemCodec.getEntries()) {
            if (!overrideBson.containsKey(jsonKey)) continue
            val field = fieldList.last()
            @Suppress("UNCHECKED_CAST")
            (field as BuilderField<Any, Any>).decode(overrideBson, item, extraInfo)
        }

        clearCachedPacket(item)
    }

    // PATH B: Full BSON re-decode via assetStore.decode()
    // Creates a fresh Item from merged BSON — CHILD_ASSET_CODEC rebuilds
    // the entire interaction chain (RootInteraction → DamageCalculator)
    private fun applyViaFullRedecode(itemId: String, overrideBson: BsonDocument) {
        val item = Item.getAssetMap().getAsset(itemId) ?: return
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        // Step 1: Encode the CURRENT item to a full BSON document
        val fullBson = BsonDocument()
        for ((_, fieldList) in codecScanner.itemCodec.getEntries()) {
            val field = fieldList.last()
            @Suppress("UNCHECKED_CAST")
            (field as BuilderField<Any, Any>).encode(fullBson, item, extraInfo)
        }

        // Step 2: Merge override values into the full BSON
        for ((key, value) in overrideBson) {
            if (value is BsonDocument && fullBson.containsKey(key) && fullBson[key] is BsonDocument) {
                // Deep merge for nested objects (e.g., InteractionVars, Armor)
                BsonHelper.deepMerge(fullBson[key] as BsonDocument, value)
            } else {
                fullBson.put(key, value)
            }
        }

        // Step 3: Decode a fresh Item from the merged BSON
        // This runs CHILD_ASSET_CODEC for InteractionVars, rebuilding
        // RootInteraction → DamageEntityInteraction → DamageCalculator
        val packKey = Item.getAssetMap().getAssetPack(itemId) ?: "ItemForge:overrides"
        val freshItem = itemStore.decode(packKey, itemId, fullBson)

        // Step 4: The fresh item replaces the old one via loadAssets
        // (commitItem will be called by the caller — applyAndSync)
    }

    // Revert an item to its original values
    fun revertItem(itemId: String) {
        val originalBson = originals.get(itemId) ?: return
        // Full re-decode to restore interaction chains
        applyViaFullRedecode(itemId, originalBson)
        val item = Item.getAssetMap().getAsset(itemId) ?: return
        committer.commitItem(item)
        originals.remove(itemId)
    }

    // Apply + commit (sync to all players)
    fun applyAndSync(itemId: String, overrideBson: BsonDocument) {
        val hasInteractionOverrides = overrideBson.containsKey("InteractionVars")

        if (hasInteractionOverrides) {
            // Path B already creates a fresh item via decode — commit it
            applyViaFullRedecode(itemId, overrideBson)
            val freshItem = Item.getAssetMap().getAsset(itemId) ?: return
            committer.commitItem(freshItem)
        } else {
            // Path A modifies in-place — commit the modified item
            applyViaDirectDecode(itemId, overrideBson)
            val item = Item.getAssetMap().getAsset(itemId) ?: return
            committer.commitItem(item)
        }
    }

    // Clear Item.cachedPacket SoftReference so toPacket() regenerates
    // This is the ONLY reflection on a game class — one field, stable across versions
    private fun clearCachedPacket(item: Item) {
        cachedPacketField.set(item, null)
    }

    companion object {
        private val cachedPacketField: Field = Item::class.java
            .getDeclaredField("cachedPacket")
            .also { it.isAccessible = true }
    }
}
```

**Reflection budget**: Exactly **1 field** on a game class (`Item.cachedPacket`). Compare to v2.0's 25+ reflected fields. Everything else uses the codec's public API.

**Evidence**: `BuilderField.java:130` (decode is public, writes value from BSON to object), SimpleEnchantments `ScrollItemGenerator.java:336-339` (cachedPacket clear pattern, proven in production).

### 4.3 AssetCommitter — Engine-Handled Sync

Replaces the manual `PacketSyncService` from v2.0. One method call — the engine handles UpdateItems packets, broadcast, event dispatch, and cache invalidation.

```
class AssetCommitter {
    private var flushing = false  // Feedback loop guard

    // Commit a modified item — engine handles ALL sync
    fun commitItem(item: Item) {
        if (flushing) return  // Prevent feedback loop (EC-pitfall from ToO)

        flushing = true
        try {
            // This single call:
            // 1. Replaces the item in the asset map
            // 2. Fires LoadedAssetsEvent (CraftingPlugin rebuilds recipe registries)
            // 3. Generates UpdateItems packet via ItemPacketGenerator
            // 4. Broadcasts to ALL connected players via Universe.broadcastPacketNoCache()
            // 5. Invalidates cached Init packets (new players get fresh data)
            Item.getAssetStore().loadAssets("ItemForge:overrides", listOf(item))
        } finally {
            flushing = false
        }
    }

    // Commit a modified recipe
    fun commitRecipe(recipe: CraftingRecipe) {
        if (flushing) return
        flushing = true
        try {
            CraftingRecipe.getAssetStore().loadAssets("ItemForge:overrides", listOf(recipe))
        } finally {
            flushing = false
        }
    }

    // Batch commit multiple items (single loadAssets call for efficiency)
    fun commitItems(items: List<Item>) {
        if (flushing) return
        flushing = true
        try {
            Item.getAssetStore().loadAssets("ItemForge:overrides", items)
        } finally {
            flushing = false
        }
    }
}
```

**Why `flushing` guard**: `loadAssets()` fires `LoadedAssetsEvent` synchronously, which triggers our own `AssetLoadListener`, which could call `applyOverride`, which could call `commitItem` again — infinite loop. ToO's `ItemSyncCoordinator.java:481-491` discovered this the hard way (11+ cycles, 1903 client warnings). The boolean guard breaks the cycle.

**Evidence**: `AssetStore.java:420` (loadAssets is public), `HytaleAssetStore.java:93-122` (handleRemoveOrUpdate broadcasts automatically), `ItemPacketGenerator.java:37-48` (generates UpdateItems with AddOrUpdate). Used by SimpleEnchantments, LifeCrops, CraftingPlugin, FunctionalTargetDummy.

### 4.4 OriginalsCache — BSON Snapshots

Stores pre-override values as BSON documents — the same format the codec uses. No custom serialization.

```
class OriginalsCache {
    // itemId -> BSON snapshot of ALL fields before any override
    private val cache = HashMap<String, BsonDocument>()

    // Capture current values BEFORE applying override
    fun capture(itemId: String, item: Item) {
        if (cache.containsKey(itemId)) return  // Already captured

        val doc = BsonDocument()
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()

        // Encode the entire item to BSON using the codec
        for ((_, fieldList) in codecScanner.itemCodec.getEntries()) {
            val field = fieldList.last()
            @Suppress("UNCHECKED_CAST")
            (field as BuilderField<Any, Any>).encode(doc, item, extraInfo)
        }

        cache[itemId] = doc
    }

    fun get(itemId: String): BsonDocument? = cache[itemId]
    fun remove(itemId: String) { cache.remove(itemId) }
    fun has(itemId: String): Boolean = cache.containsKey(itemId)
}
```

### 4.5 HealthMonitor

Verifies the codec pipeline works at startup. Since we use the codec's own API instead of reflecting on 25+ game fields, the health check is much simpler.

```
class HealthMonitor {
    fun check(): HealthReport {
        val report = HealthReport()

        // Verify Item codec is accessible
        report.checkNotNull("Item AssetStore", Item.getAssetStore())
        report.checkNotNull("Item codec", Item.getAssetStore().codec)

        // Verify key fields exist in the codec
        val entries = (Item.getAssetStore().codec as BuilderCodec<Item>).getEntries()
        report.checkContains("MaxDurability field", entries, "MaxDurability")
        report.checkContains("Armor field", entries, "Armor")
        report.checkContains("Weapon field", entries, "Weapon")
        report.checkContains("Tool field", entries, "Tool")
        report.checkContains("Glider field", entries, "Glider")
        report.checkContains("Utility field", entries, "Utility")
        report.checkContains("Recipe field", entries, "Recipe")
        report.checkContains("InteractionVars field", entries, "InteractionVars")

        // Verify cachedPacket field exists (our only reflection target)
        report.checkField("Item.cachedPacket", Item::class.java, "cachedPacket")

        // Verify CraftingRecipe codec
        report.checkNotNull("CraftingRecipe codec", CraftingRecipe.getAssetStore().codec)

        // Verify AssetStore.loadAssets() is callable
        report.checkMethod("AssetStore.loadAssets", AssetStore::class.java, "loadAssets")

        // Verify I18nModule for name resolution
        report.checkNotNull("I18nModule", I18nModule.get())

        return report
    }
}
```

---

## 5. Asset Metadata

### 5.1 ModSourceTracker

```
class ModSourceTracker {
    private val itemModMap = HashMap<String, String>()
    private val knownMods = LinkedHashSet<String>()

    fun scan() {
        val itemMap = Item.getAssetMap()
        for (itemId in itemMap.assetMap.keys) {
            val packKey = itemMap.getAssetPack(itemId)
            val modName = resolveModName(packKey)
            itemModMap[itemId] = modName
            knownMods.add(modName)
        }
    }

    fun getModName(itemId: String): String = itemModMap[itemId] ?: "Unknown"
    fun getAllModNames(): Set<String> = knownMods

    private fun resolveModName(packKey: String?): String {
        if (packKey == null) return "Unknown"
        val pack = AssetModule.get().getAssetPack(packKey)
        return pack?.manifest?.name ?: packKey.substringAfter(":", packKey)
    }
}
```

**Evidence**: `AssetMap.java:27`, `DefaultAssetMap.java:147-172`, `AssetModule.java:349-355`, `PluginIdentifier.java:57-58`.

### 5.2 TagCache

```
class TagCache {
    private val tagValues = HashMap<String, LinkedHashSet<String>>()

    fun scan() {
        tagValues.clear()
        for (item in Item.getAssetMap().assetMap.values) {
            val rawTags = item.data?.rawTags ?: continue
            for ((key, values) in rawTags) {
                tagValues.getOrPut(key) { LinkedHashSet() }.addAll(values)
            }
        }
    }

    fun getValuesForKey(key: String): Set<String> = tagValues[key] ?: emptySet()

    fun getItemsWithTag(key: String, value: String): Set<String> {
        val tagIndex = AssetRegistry.getTagIndex("$key=$value")
        if (tagIndex == Integer.MIN_VALUE) return emptySet()
        return Item.getAssetMap().getKeysForTag(tagIndex)
    }
}
```

### 5.3 ItemNameResolver

```
class ItemNameResolver {
    fun resolve(item: Item): String {
        try {
            val i18n = I18nModule.get()
            val translated = i18n?.getMessage("en-US", item.translationKey)
            if (translated != null && translated.isNotEmpty() && translated != item.translationKey) {
                return translated.trim()
            }
        } catch (_: Exception) {}

        return formatItemId(item.id)
    }

    private fun formatItemId(id: String): String {
        return id.replaceFirst(Regex("^(Armor|Weapon|Tool|Food|Potion|Glider|Container|Ingredient|Bench|Utility)_"), "")
            .replace('_', ' ')
    }
}
```

**Evidence**: ToO `ItemNameFormatter.java:264-286` — `I18nModule.get().getMessage("en-US", translationKey)`.

---

## 6. Config Persistence

### 6.1 Format Decisions

| File | Format | Library | Rationale |
|------|--------|---------|-----------|
| `config.yml` | YAML | SnakeYAML 2.5 | Human-edited settings need comments. Ecosystem precedent (ToO). |
| `overrides/items.json` | JSON | Gson | Machine-generated data. Ecosystem standard (BetterMap, Aetherhaven, EndgameAndQoL). Gson provided by Hytale at runtime. |
| `overrides/recipes.json` | JSON | Gson | Same rationale. |

**Evidence**: BetterMap, Aetherhaven, ModernStorage, EndgameAndQoL all use Gson+JSON for config. SnakeYAML used only by ToO and EndlessLeveling for human-edited configs.

### 6.2 Override Storage — Nullable Overlay Pattern

Inspired by rpgmobs `ConfigOverlay` — only store overridden values, null = use original.

```
// items.json schema
{
  "schema_version": 1,
  "overrides": {
    "Armor_Iron_Chest": {
      "MaxDurability": 150,
      "Armor": {
        "StatModifiers": {
          "Health": [{"Amount": 25, "CalculationType": "Additive"}]
        },
        "DamageResistance": {
          "Physical": [{"Amount": 0.12, "CalculationType": "Multiplicative"}]
        }
      }
    },
    "Weapon_Battleaxe_Adamantite": {
      "InteractionVars": {
        "Swing_Down_Damage": {
          "Interactions": [{"DamageCalculator": {"BaseDamage": {"Physical": 65}}}]
        }
      }
    }
  }
}
```

**Critical design**: Override JSON keys match the **exact JSON field names** from the Item codec (PascalCase: `"MaxDurability"`, `"StatModifiers"`, `"DamageResistance"`). This means override documents can be directly fed to `BuilderField.decode()` — the codec reads them natively. No key translation needed.

**Evidence**: rpgmobs `ConfigOverlay.java` (nullable overlay pattern), EndgameAndQoL `RecipeOverrideConfig.java` (recipe override storage).

### 6.3 AtomicFileWriter — 3-Tier Fallback

```
class AtomicFileWriter {
    fun write(path: Path, content: String) {
        val tmpPath = path.resolveSibling("${path.fileName}.tmp")

        // Phase 1: Write to temp file
        try {
            Files.newBufferedWriter(tmpPath).use { writer ->
                writer.write(content)
            }
        } catch (e: IOException) {
            try { Files.deleteIfExists(tmpPath) } catch (_: IOException) {}
            throw e
        }

        // Phase 2: Atomic move (best case)
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall through to tier 2
        }

        // Phase 3: Non-atomic move (Windows cloud sync may block atomic)
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: IOException) {
            // Fall through to tier 3
        }

        // Phase 4: Copy + delete fallback (last resort)
        try {
            Files.copy(tmpPath, path, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(tmpPath)
        } catch (e: IOException) {
            try { Files.deleteIfExists(tmpPath) } catch (_: IOException) {}
            throw e
        }
    }
}
```

**Evidence**: BetterMap `ModConfig.java:506-530` (3-tier pattern), Aetherhaven `TownWorldFile.java:42-82` (Windows AccessDeniedException fallback). No other mod in the ecosystem has true atomic writes — Hytale's own backup renames old then writes new (NOT atomic).

### 6.4 DebouncedWriter — CAS Debounce

```
class DebouncedWriter(
    private val writer: AtomicFileWriter,
    private val delayMs: Long = 2000
) {
    private val lastWriteTime = AtomicLong(0)
    private var pendingContent: Pair<Path, String>? = null
    private var scheduledTask: ScheduledFuture<*>? = null

    fun scheduleWrite(path: Path, content: String) {
        pendingContent = Pair(path, content)
        val now = System.currentTimeMillis()
        val last = lastWriteTime.get()

        if (now - last < delayMs) return  // Within debounce window, skip

        if (!lastWriteTime.compareAndSet(last, now)) return  // CAS failed, another thread won

        scheduledTask?.cancel(false)
        scheduledTask = scheduler.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun flush() {
        val pending = pendingContent ?: return
        pendingContent = null
        writer.write(pending.first, pending.second)
    }

    fun flushSync() {
        scheduledTask?.cancel(false)
        flush()
    }
}
```

**Evidence**: EndgameAndQoL `CraftingTab.java:38-56` (AtomicLong + compareAndSet, 2s debounce), `ConfigSaveManager.java` (dirty flag + debounce). ToO `ItemSyncCoordinator.java:472-503` (cancel-reschedule debounce, 100ms).

---

## 6.5 Shared Data Classes

```
// === Batch operations ===

data class BatchResult(val items: List<BatchItemResult>)

data class BatchItemResult(
    val itemId: String,
    val success: Boolean,
    val error: String? = null,
    val oldValue: Double? = null,
    val newValue: Double? = null
)

data class BatchPreviewRow(
    val itemId: String,
    val itemName: String,
    val oldValue: Double?,
    val newValue: Double?,
    val skipped: Boolean,
    val reason: String? = null
)

data class BatchUndoBuffer(
    val itemIds: List<String>,
    val targetField: String,
    val preValues: Map<String, Double>,
    val timestamp: java.time.Instant
)

// === Recipe data (for editor) ===

data class RecipeData(
    val recipeId: String,
    val inputs: List<RecipeInputData>,
    val timeSeconds: Float,
    val knowledgeRequired: Boolean,
    val benchRequirements: List<BenchRequirementData>,
    val allItemIds: List<String>         // For material dropdown population
)

data class RecipeInputData(
    val itemId: String,
    val quantity: Int
)

data class BenchRequirementData(
    val benchId: String?,
    val requiredTier: Int
)
```

### 6.6 BatchRecipeEngine

Handles recipe-specific batch operations (scale quantities, replace ingredients, scale time). Uses the CraftingRecipe codec for field access, same pattern as item overrides.

```
class BatchRecipeEngine {
    private val codecScanner: CodecScanner
    private val committer: AssetCommitter
    private val configManager: ConfigManager
    private val auditLogger: AuditLogger

    fun scaleInputQuantities(itemIds: List<String>, scalePercent: Double): BatchResult {
        val results = mutableListOf<BatchItemResult>()
        for (itemId in itemIds) {
            val recipeId = "${itemId}_Recipe_Generated_0"
            val recipe = CraftingRecipe.getAssetStore().getAsset(recipeId)
            if (recipe == null) {
                results.add(BatchItemResult(itemId, false, "No recipe found"))
                continue
            }
            // Read current inputs via codec encode
            val doc = BsonDocument()
            val inputField = codecScanner.recipeCodec.getEntries()["Input"]?.last() ?: continue
            @Suppress("UNCHECKED_CAST")
            (inputField as BuilderField<Any, Any>).encode(doc, recipe, ExtraInfo.THREAD_LOCAL.get())

            // Scale quantities in BSON, decode back
            val inputArray = doc.getArray("Input")
            for (input in inputArray) {
                val inputDoc = input.asDocument()
                val qty = inputDoc.getInt32("Quantity").value
                inputDoc.put("Quantity", BsonInt32(Math.max(1, Math.round(qty * scalePercent / 100.0).toInt())))
            }
            inputField.decode(doc, recipe, ExtraInfo.THREAD_LOCAL.get())

            committer.commitRecipe(recipe)
            results.add(BatchItemResult(itemId, true))
        }
        return BatchResult(results)
    }

    fun replaceIngredient(itemIds: List<String>, oldId: String, newId: String): BatchResult {
        // Same pattern: encode inputs → find/replace itemId in BSON → decode back → commit
        // Items without the old ingredient are skipped with explanation
    }

    fun scaleCraftingTime(itemIds: List<String>, scalePercent: Double): BatchResult {
        // Same pattern: encode TimeSeconds → scale → decode back → commit
    }
}
```

---

## 7. Vuetale Integration

### 7.1 Data Contracts (Server → UI)

```
// Dashboard payload
data class DashboardPayload(
    val items: List<DashboardItem>,
    val typeFilterOptions: List<String>,    // From TagCache.getValuesForKey("Type")
    val modFilterOptions: List<String>,     // From ModSourceTracker.getAllModNames()
    val overrideCount: Int,
    val totalCount: Int
)

data class DashboardItem(
    val id: String,
    val name: String,                       // From ItemNameResolver
    val type: String,                       // From TagCache
    val mod: String,                        // From ModSourceTracker
    val quality: String?,
    val level: Int,
    val durability: Double,
    val hasOverride: Boolean,
    val overrideCount: Int,
    val iconIndex: Int,
    val qualityIndex: Int,
    val health: Float?,                     // Type-specific summary stats
    val damage: Float?,
    val speed: Float?,
    val capacity: Int?,
    val healAmount: Float?
)

// Editor payload
data class EditorPayload(
    val itemId: String,
    val itemName: String,
    val itemMod: String,
    val itemType: String,
    val hasOverride: Boolean,
    val iconIndex: Int,
    val qualityIndex: Int,
    val fields: List<SerializedFieldDefinition>,
    val originalValues: Map<String, Any>,
    val tabs: List<String>,
    val recipeData: RecipeData?
)

data class SerializedFieldDefinition(
    val id: String,                         // "Armor.StatModifiers.Health"
    val displayName: String,                // "Health (Armor Stats)"
    val category: String,                   // "Armor Stats"
    val valueType: String,                  // "Double", "Integer", "Boolean", "String"
    val currentValue: Any,
    val originalValue: Any?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val maxDecimals: Int?,
    val options: List<String>?,
    val readOnly: Boolean,
    val state: String,
    val tooltip: String?,                   // From BuilderField.getDocumentation()
    val effectDescription: String?          // Computed: "+25 Health" / "12% resistance"
)
```

### 7.2 Event Handling (UI → Server)

```
class EditorBridge {
    fun handleEvent(event: String, data: Map<String, Any>, playerRef: EntityRef) {
        when (event) {
            "save" -> {
                val changes = data["changes"] as Map<String, Any>  // fieldId -> newValue
                val itemId = data["itemId"] as String
                saveFlowHandler.execute(playerRef, itemId, changes)
            }
            "reset" -> resetItem(playerRef, data["itemId"] as String)
            "resetField" -> resetSingleField(playerRef, data["itemId"] as String, data["fieldId"] as String)
        }
    }
}

class BatchBridge {
    private val undoBuffers = HashMap<EntityRef, BatchUndoBuffer>()

    fun handleEvent(event: String, data: Map<String, Any>, playerRef: EntityRef) {
        when (event) {
            "batchPreview" -> {
                val preview = batchEngine.preview(/* ... */)
                pushData(playerRef, "batchPreview", preview)
            }
            "batchApply" -> {
                val preValues = capturePreBatchValues(/* ... */)
                val result = batchEngine.apply(/* ... */)
                undoBuffers[playerRef] = BatchUndoBuffer(/* ..., */ Instant.now())
                pushData(playerRef, "batchResult", result)
            }
            "batchUndo" -> {
                val buffer = undoBuffers.remove(playerRef) ?: return
                if (Duration.between(buffer.timestamp, Instant.now()).seconds > 10) return
                batchEngine.revert(buffer)
            }
            "batchRecipeScale" -> batchRecipeEngine.scaleInputQuantities(/* ... */)
            "batchRecipeReplace" -> batchRecipeEngine.replaceIngredient(/* ... */)
            "batchReset" -> {
                val itemIds = data["itemIds"] as List<String>
                itemIds.forEach { overrideEngine.revertItem(it) }
                itemIds.forEach { configManager.removeOverride(it) }
            }
        }
    }
}
```

---

## 8. Save Flow

```
class SaveFlowHandler {
    fun execute(playerRef: EntityRef, itemId: String, changes: Map<String, Any>) {
        // Gate 1: Permission
        if (!permissionChecker.canEdit(playerRef.player)) {
            pushEvent(playerRef, "permissionLost"); return
        }

        // Gate 2: Validation
        val errors = validateAll(itemId, changes)
        if (errors.isNotEmpty()) {
            pushEvent(playerRef, "validationFailed", errors); return
        }

        // Gate 3: Conflict detection
        val conflicts = detectConflicts(itemId, changes)
        if (conflicts.isNotEmpty()) {
            pushEvent(playerRef, "conflictDetected", conflicts); return
        }

        // Execute
        try {
            // 1. Build override BSON from changes
            val overrideBson = buildOverrideBson(changes)

            // 2. Capture originals if first override
            val item = Item.getAssetMap().getAsset(itemId) ?: throw IllegalStateException()
            if (!originals.has(itemId)) originals.capture(itemId, item)

            // 3. Apply via codec
            overrideEngine.applyItemOverride(itemId, overrideBson)

            // 4. Commit — engine syncs to all players
            committer.commitItem(item)

            // 5. Persist to JSON (debounced)
            configManager.saveOverride(itemId, overrideBson)

            // 6. Audit
            auditLogger.record(playerRef.player, itemId, changes)

            pushEvent(playerRef, "saveSuccess")
        } catch (e: Exception) {
            pushEvent(playerRef, "saveFailed", e.message)
        }
    }
}
```

---

## 9. Known Pitfalls (From Production Experience)

Discovered across 60+ vendor mods and TrailOfOrbis:

| Pitfall | Source | Impact | Guard |
|---------|--------|--------|-------|
| **cachedPacket stale** | SimpleEnchantments:336 | toPacket() returns old data after field mutation | Null out via reflection (our 1 reflected field) |
| **Feedback loop** | ToO ItemSyncCoordinator:481 | loadAssets → LoadedAssetsEvent → applyOverride → loadAssets (11+ cycles) | `flushing` boolean guard in AssetCommitter |
| **World transition crash** | ToO ItemSyncCoordinator:245 | UpdateItems during JoinWorldPacket → client NullReferenceException | Not applicable — loadAssets() broadcasts once, engine handles timing |
| **Weapon stat reset** | ToO StatMapBridge | UpdateItems causes client to re-apply weapon.statModifiers | Only relevant for weapon mechanic stats (SignatureEnergy), not for ItemForge's use case |
| **processConfig()** | SimpleEnchantments:350 | New items need quality/interaction resolution | Only for CREATING items — ItemForge modifies existing, processConfig already ran |
| **Windows ATOMIC_MOVE fails** | Aetherhaven TownWorldFile:65 | Cloud sync folders (OneDrive) block atomic moves | 3-tier fallback in AtomicFileWriter |

---

## 10. Plugin Lifecycle

### 10.1 setup()

```
Register LoadedAssetsEvent<Item> → AssetLoadListener::onItemsLoaded
Register LoadedAssetsEvent<CraftingRecipe> → AssetLoadListener::onRecipesLoaded
Register RemovedAssetsEvent<Item> → AssetLoadListener::onItemsRemoved
Register PlayerInteractEvent → InteractionListener::onInteract
Register player disconnect → cleanup sessions
```

### 10.2 start()

```
1. Load configs (config.yml via SnakeYAML, overrides/*.json via Gson)
2. Initialize CodecScanner (cache Item/Recipe BuilderCodecs)
3. Run HealthMonitor (verify codec pipeline accessible)
4. Build metadata caches (ModSourceTracker, TagCache, ItemNameResolver)
5. Apply any missed overrides (belt & suspenders)
6. Register commands
7. Initialize Vuetale module (register "itemforge" module, init V8 engine)
8. Log startup summary
```

### 10.3 AssetLoadListener

```
onItemsLoaded(event):
    for item in event.loadedAssets.values:
        modSourceTracker.track(item.id, event.assetMap.getAssetPack(item.id))
        tagCache.index(item.id, item.data?.rawTags)

        if committer.flushing: continue  // Our own commit — don't re-apply

        if configManager.hasOverride(item.id):
            originals.capture(item.id, item)
            overrideEngine.applyItemOverride(item.id, configManager.getOverrideBson(item.id))
            clearCachedPacket(item)
```

### 10.4 Shutdown

```
1. debouncedWriter.flushSync()  // Ensure all pending writes complete
2. Destroy all UI sessions
3. If config.revertOnDisable: revert all overrides
4. Flush audit log
```

---

## 11. Extension API

Mods extend the editor through the `EditorExtension` interface, registered via `ItemForgeAPI.registerExtension`. An extension contributes its own panel (a list of `EditorComponent`s) for the items it manages, surfaced as a selectable source in the editor next to the base item. Field edits route to the extension's `applyChanges`; button presses route to `onAction`, after which the panel is rebuilt and re-pushed. The extension owns its persistence: ItemForge never writes extension data to the item asset. Panels are additive and never replace the codec-scanned fields. Every call into an extension is exception-isolated, so a faulty extension cannot crash the editor. Full guide for mod authors: [EXTENSIONS.md](EXTENSIONS.md).

---

## 12. Implementation Phases

### Phase 1: Core Engine (Foundation)

**Delivers**: Codec-based override engine that applies JSON config on startup. No UI.

```
Build:
  - ItemForgePlugin (lifecycle)
  - CodecScanner (BuilderCodec.getEntries() introspection)
  - OverrideEngine (BuilderField.decode() for writes)
  - OriginalsCache (BuilderField.encode() for snapshots)
  - AssetCommitter (AssetStore.loadAssets() wrapper with flushing guard)
  - HealthMonitor (verify codec pipeline)
  - ConfigManager + AtomicFileWriter (3-tier) + DebouncedWriter (CAS)
  - AssetLoadListener
  - ModSourceTracker, TagCache, ItemNameResolver
  - AuditLogger

Test:
  - Write overrides/items.json manually with PascalCase keys
  - Start server → verify item stats changed via codec pipeline
  - Connect client → verify tooltip shows new value (engine-synced)
  - /itemforge status → health check, mod sources, tag types

Exit criteria:
  - Codec-driven overrides work for all item types
  - AssetStore.loadAssets() syncs to all players automatically
  - Zero reflection on game classes (except cachedPacket clear)
  - Health check confirms all codec fields accessible
```

### Phase 2: Vuetale + Editor (General Tab)

**Delivers**: `/itemforge <id>` opens editor via Vuetale. General tab editable. Save flow works.

```
Build: VuetaleIntegration, Editor.vue, FieldEditor.vue, EditorBridge, DataContracts, SaveFlowHandler
Test: Open editor, change durability, save, verify persisted + synced
Exit: Full save flow with validation, conflict detection, audit
```

### Phase 3: Properties Tab (All Components)

**Delivers**: Dynamic property editing for all item types discovered by codec.

```
Build: Properties tab in Editor.vue, StatRow.vue, recursive codec scanning for sub-objects
Test: Armor stats, weapon damage, tool speed, glider physics, utility effects, container capacity
Exit: Codec auto-discovers ALL fields. Multi-component items show all sections.
```

### Phase 4: Dashboard

**Delivers**: `/itemforge` opens balance dashboard with grid/table view, filters, sorting.

```
Build: Dashboard.vue, DashboardBridge, FilterBar, GridView, TableView, ActionBar
Test: 851 items render, sort by health, filter by type/mod, search by name
Exit: Client-side Vue computed sort/filter is instant. State preserved across navigation.
```

### Phase 5: Recipe Tab

**Delivers**: Recipe editing via CraftingRecipe codec.

### Phase 6: Batch Operations

**Delivers**: Multi-select, batch stat/recipe editing, preview, undo toast.

### Phase 7: Overlays + Polish

**Delivers**: All overlays, per-field undo, computed effect descriptions, tooltips.

### Phase 8: Inspect Mode + Editor Extension API

**Delivers**: Crouch+right-click toggle, extension API for mods.

### Phase 9: Armory Integration

**Delivers**: ItemForge embedded in The Armory. Vuetale bundled in both.

---

## 13. Build System

### 13.1 Dual Build Pipeline

```
Gradle (Kotlin)                          Vite (Vue/TypeScript)
+---------------------------+            +---------------------------+
| src/main/kotlin/           |            | src/ui/                   |
| me.itemforge/...           |            | lib/pages/*.vue           |
|                           |            | lib/components/*.vue      |
| Compiles to:              |            |                           |
| build/classes/kotlin/main |            | Compiles to:              |
+---------------------------+            | build/vuetale/itemforge/  |
                                         +---------------------------+
                     → Gradle assemblePlugin combines both →
                        +-----------------+
                        | ItemForge.jar   |
                        +-----------------+
```

### 13.2 Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| Hytale Server SDK | compileOnly | Plugin API, asset types, codecs, packets |
| Vuetale | implementation (bundled) | UI framework — server owners install one JAR |
| SnakeYAML 2.5 | implementation (bundled) | Main config parsing |
| Gson | compileOnly (provided by Hytale) | Override JSON parsing |
| Kotlin 2.3.0 | implementation | Plugin language |
| Vue 3 / TypeScript / Tailwind | devDependencies (UI build) | UI framework |
| Vite | devDependencies (UI build) | Vue build tool |

---

## 14. Security Checklist

| Threat | Mitigation |
|--------|-----------|
| Unauthorized modification | Permission check on every write (open AND save) |
| Forged UI events | Server-side session tracking; reject events without session |
| Config tampering | Validation on load; 3-tier atomic writes |
| Reflection abuse | Only 1 reflected field (cachedPacket). All else is public API. |
| DoS via rapid saves | CAS debounce (2s). Rate limit: max 10 saves/s/admin |
| Information disclosure | Permission-gated editor |
| Batch abuse | Mandatory preview. 10s undo window. |
| Audit tampering | Append-only log file |

---

## 15. Success Criteria

ItemForge is DONE when:

1. Any admin can modify any item's stats, damage, durability, quality, recipe, tool speed, glider physics, container capacity, and food healing through the in-game UI with immediate effect.
2. Changes persist across restarts.
3. No hardcoded mod knowledge. Dashboard shows source mod and type via engine APIs.
4. The Editor Extension API lets any mod add its own editor panel without ItemForge changes.
5. All edge cases from EDGE_CASES.md handled gracefully.
6. Server owner installs ItemForge, types `/itemforge`, immediately understands it.
7. Batch operations rebalance entire tiers with mandatory preview and undo.
8. Vuetale bundled — full dashboard UI always available.
9. **Zero reflection on game classes** (except `Item.cachedPacket`). All field access through the codec's public API.
10. **New Hytale fields auto-discovered** — codec introspection means ItemForge never needs updating for new item properties.

---

## Appendix A: Ecosystem Evidence Summary

| Decision | Evidence Source |
|----------|---------------|
| AssetStore.loadAssets() for sync | Hytale AssetEditor, SimpleEnchantments, LifeCrops, CraftingPlugin |
| BuilderCodec.getEntries() for scanning | Hytale AssetEditor (AssetRegistryLoader.generateSchemas) |
| BuilderField.encode/decode for read/write | BuilderField.java:130,141 (public methods) |
| cachedPacket clear needed | SimpleEnchantments ScrollItemGenerator:336-339 |
| Flushing guard for feedback loop | ToO ItemSyncCoordinator:481-491 (11+ cycles without guard) |
| 3-tier atomic write | BetterMap ModConfig:506-530, Aetherhaven TownWorldFile:42-82 |
| CAS debounce | EndgameAndQoL CraftingTab:38-56 (AtomicLong + compareAndSet) |
| Gson+JSON for config | BetterMap, Aetherhaven, ModernStorage, EndgameAndQoL |
| Nullable overlay pattern | rpgmobs ConfigOverlay (null = not overridden) |
| ModSourceTracker via getAssetPack | AssetMap.java:27, DefaultAssetMap.java:147-172 |
| TagCache via getRawTags + getKeysForTag | AssetExtraInfo.java:170, DefaultAssetMap.java:241 |
| ItemNameResolver via I18nModule | ToO ItemNameFormatter.java:264-286 |
| Vuetale session model | PlayerUiManager (ConcurrentHashMap<UUID, PlayerUi>) |
| Permission per action | Hytale AssetEditorPacketHandler:395-421 (every handler checks) |

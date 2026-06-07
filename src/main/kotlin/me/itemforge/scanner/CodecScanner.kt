package me.itemforge.scanner

import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.builder.BuilderField
import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.PrimitiveCodec
import com.hypixel.hytale.codec.Codec as HytaleCodec
import com.hypixel.hytale.codec.codecs.EnumCodec
import com.hypixel.hytale.codec.validation.validator.RangeValidator
import com.hypixel.hytale.logger.HytaleLogger
import me.itemforge.metadata.ModSourceTracker
import me.itemforge.util.BsonHelper
import me.itemforge.util.ValueFormatter
import org.bson.BsonDocument
import org.bson.BsonType
import org.bson.BsonValue

/**
 * Discovers all editable fields on any item via BuilderCodec introspection.
 *
 * This is the class that makes ItemForge work with any mod, any item, any field —
 * without hardcoded knowledge. It walks the codec's entries, reads current values
 * via encode(), classifies each field, and produces [FieldDefinition] objects.
 *
 * ## How it works
 *
 * 1. Encode the full item via `BuilderCodec.encode()` → BSON snapshot (Probe 1.2 verified)
 * 2. Walk `BuilderCodec.getEntries()` for field metadata (type, validators, docs)
 * 3. For each entry, classify and create [FieldDefinition]:
 *    - Primitive/String with value → leaf field with currentValue
 *    - Primitive/String without value → leaf field with currentValue=null, isNotSet=true
 *    - Document + BuilderCodec child → recurse into component sub-fields
 *    - InteractionVars → mark as Path B (not recursed — requires assetStore.decode)
 *    - Everything else → skip (arrays, maps, contained assets)
 * 4. Inside components, handle map sub-fields (StatModifiers, DamageResistance)
 *    by iterating BSON entries AND enumerating missing valid keys
 *
 * ## Thread safety
 *
 * Uses `ExtraInfo.THREAD_LOCAL` — must be called from the server thread.
 * The codecs and item data are read-only during scanning (no mutations).
 *
 * ## Lifecycle
 *
 * Call [init] once during plugin start (after assets are loaded).
 * Then call [scan] for each item the admin opens in the editor.
 */
class CodecScanner {

    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()

    /** The Item codec — cached at init. */
    lateinit var itemCodec: BuilderCodec<Item>
        private set

    /** The CraftingRecipe codec — cached at init. */
    lateinit var recipeCodec: BuilderCodec<CraftingRecipe>
        private set

    /**
     * Live snapshot of the `EntityStatType` registry: stat key → source mod.
     *
     * Drives [StatModifiers] discovery so it covers EVERY stat type registered on the
     * server — vanilla AND any mod's (e.g. Hexcode's `Magic_Power`, `Volatility`) — with
     * zero hardcoding. Value is the source mod display name, or null for vanilla ("Hytale").
     * Keys preserve registry order. Stats flagged `HideFromTooltip` are excluded (internal).
     *
     * Empty until [initStatTypes] runs (it needs [ModSourceTracker], created after the
     * codec init). Until then [validStatKeys]/[statSourceMod] fall back to the curated
     * vanilla set in [MAP_VALID_KEYS], so the scanner is always safe to call.
     */
    @Volatile
    private var statTypeRegistry: Map<String, String?> = emptyMap()

    /** True once [initStatTypes] populated [statTypeRegistry] with at least one entry. */
    @Volatile
    private var statTypesReady: Boolean = false

    /**
     * Initialize by caching the codecs. Call once during plugin start.
     * Must be called after assets are loaded (in start(), not setup()).
     */
    @Suppress("UNCHECKED_CAST") // Safe: AssetBuilderCodec extends BuilderCodec (SDK_FINDINGS.md §1)
    fun init() {
        itemCodec = Item.getAssetStore().codec as BuilderCodec<Item>
        recipeCodec = CraftingRecipe.getAssetStore().codec as BuilderCodec<CraftingRecipe>
    }

    /**
     * Snapshots the live `EntityStatType` registry so [StatModifiers] discovery covers
     * every registered stat (vanilla + modded), attributing each to its source mod.
     *
     * Call once during plugin start AFTER [ModSourceTracker.scan] (the codec [init] runs
     * earlier, before the tracker exists, so this is a separate phase). Re-callable: a
     * runtime asset reload that adds stat types can refresh the snapshot.
     *
     * Resolution mirrors item attribution: `EntityStatType.getAssetMap().getAssetPack(key)`
     * → [ModSourceTracker.resolvePackToModName]. Vanilla ("Hytale") and unresolvable
     * ("Unknown") map to null sourceMod. Stats with `HideFromTooltip=true` are skipped so
     * internal/scratch stats never clutter the editor (the same signal the game uses).
     *
     * Failure-isolated: any exception leaves [statTypesReady] false → scanner falls back to
     * the curated vanilla key set. The feature degrades, it never breaks item editing.
     */
    fun initStatTypes(modSourceTracker: ModSourceTracker) {
        val snapshot = LinkedHashMap<String, String?>()
        try {
            val assetMap = EntityStatType.getAssetMap()
            for ((key, statType) in assetMap.assetMap) {
                if (key == EntityStatType.UNKNOWN?.id) continue
                if (isHiddenFromTooltip(statType)) continue
                val packKey = try { assetMap.getAssetPack(key) } catch (_: Exception) { null }
                val mod = modSourceTracker.resolvePackToModName(packKey)
                snapshot[key] = if (isVanillaSource(mod)) null else mod
            }
            statTypeRegistry = snapshot
            statTypesReady = snapshot.isNotEmpty()
            val modded = snapshot.values.count { it != null }
            logger.atInfo().log(
                "CodecScanner: stat-type registry ready — %d stats (%d modded)",
                snapshot.size, modded
            )
        } catch (e: Exception) {
            // Leave statTypesReady=false → fall back to MAP_VALID_KEYS vanilla set.
            logger.atWarning().withCause(e)
                .log("CodecScanner: stat-type registry init failed — using vanilla fallback")
        }
    }

    /**
     * Discovers all editable fields on the given item.
     *
     * Returns a list of [FieldDefinition] covering:
     * - General properties (MaxDurability, ItemLevel, Quality, etc.) — including null fields
     * - Component stats (Armor.StatModifiers, Weapon.StatModifiers, etc.)
     * - Component properties (Tool.Speed, Glider.TerminalVelocity, Container.Capacity)
     * - Missing map entries (all valid keys for StatModifiers, DamageResistance, etc.)
     * - Interaction vars as Path B markers (InteractionVars entries)
     *
     * Fields are ordered by category: General first, then component sections
     * in the order they appear in the codec.
     *
     * @param item The live Item object from the asset map
     * @param hasOverrides A set of field IDs that have active ItemForge overrides
     *                     (from ConfigManager), used to set [FieldState.MODIFIED]
     */
    fun scan(item: Item, hasOverrides: Set<String> = emptySet()): List<FieldDefinition> {
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        val version = extraInfo.version

        // Step 1: Encode the full item to BSON. Crash-safe against cyclic items (see below).
        val fullBson = encodeItemSafely(item, version, extraInfo)

        // Step 2: Walk entries and build FieldDefinitions
        val fields = mutableListOf<FieldDefinition>()

        for ((key, fieldList) in itemCodec.entries) {
            // Skip display/rendering/non-editable fields
            if (key in SKIP_FIELDS) continue

            val field = selectField(fieldList, version) ?: continue
            val bsonValue = fullBson[key]

            // Null field — exists in codec but not populated on this item.
            // Show simple types (primitive/string/enum) as "not set" so admins can add values.
            // Components (BuilderCodec) stay hidden when null — can't add components via override.
            if (bsonValue == null) {
                val childCodec = field.codec.childCodec
                val inferredType = inferValueType(childCodec)
                if (inferredType != null) {
                    fields.add(FieldDefinition(
                        id = key,
                        jsonKey = key,
                        displayName = ValueFormatter.formatFieldName(key),
                        category = CATEGORY_GENERAL,
                        valueType = inferredType,
                        currentValue = null,
                        constraints = extractConstraints(field, key),
                        state = if (key in hasOverrides) FieldState.MODIFIED else FieldState.DEFAULT,
                        tooltip = field.documentation,
                        componentKey = null,
                        requiresPathB = false,
                        isNotSet = true
                    ))
                }
                continue
            }

            val childCodec = field.codec.childCodec

            when {
                // InteractionVars — Path B marker. Don't recurse (needs AssetExtraInfo).
                key == "InteractionVars" && bsonValue.bsonType == BsonType.DOCUMENT -> {
                    scanInteractionVars(fields, bsonValue.asDocument(), hasOverrides)
                }

                // Primitive leaf (DOUBLE, INT32, BOOLEAN)
                childCodec is PrimitiveCodec -> {
                    val valueType = BsonHelper.bsonToValueType(bsonValue) ?: continue
                    fields.add(buildLeafField(
                        id = key,
                        jsonKey = key,
                        category = CATEGORY_GENERAL,
                        componentKey = null,
                        field = field,
                        bsonValue = bsonValue,
                        valueType = valueType,
                        requiresPathB = false,
                        hasOverrides = hasOverrides
                    ))
                }

                // String leaf (Quality, etc. — StringCodec, not PrimitiveCodec)
                bsonValue.bsonType == BsonType.STRING -> {
                    fields.add(buildLeafField(
                        id = key,
                        jsonKey = key,
                        category = CATEGORY_GENERAL,
                        componentKey = null,
                        field = field,
                        bsonValue = bsonValue,
                        valueType = ValueType.STRING,
                        requiresPathB = false,
                        hasOverrides = hasOverrides
                    ))
                }

                // Component (Armor, Weapon, Tool, Glider, Utility, Container, etc.)
                childCodec is BuilderCodec<*> && bsonValue.bsonType == BsonType.DOCUMENT -> {
                    scanComponent(fields, key, childCodec, bsonValue.asDocument(), version, hasOverrides)
                }

                // Everything else: skip (arrays, maps, contained assets, enum maps)
            }
        }

        return fields
    }

    /**
     * Encodes the full item to BSON, surviving items whose codec graph is **cyclic**.
     *
     * A handful of vanilla items (multi-state containers like `Deco_Bucket`, whose `State`
     * variants are themselves contained `Item` assets that loop back) form a cycle in the
     * in-memory object graph. A normal `itemCodec.encode(item)` walks that cycle until the
     * JVM stack overflows — verified from the crash trace: the repeating
     * `ContainedAssetCodec.encode → ACodecMapCodec.encode → BuilderCodec.encode0 → BuilderField.encode`
     * loop. The engine itself never hits this because it ships items to the client via
     * `toPacket()` / partial encodes, not a full structural encode like our introspection does.
     *
     * Strategy:
     * 1. Try the normal full encode (the fast, complete path for the 99.9% of well-formed items).
     * 2. On [StackOverflowError], degrade to a **per-field encode**: encode each top-level codec
     *    field independently into a fresh document, catching the overflow on the one cyclic field
     *    and skipping only it. Every other field still encodes, so the item opens with all of its
     *    non-cyclic properties editable — only the recursive field is dropped (treated as "not
     *    present" by [scan], which already tolerates missing keys).
     *
     * Safety (verified from `BuilderField.encode` / `BuilderCodec.encode0` source, 0.5.3):
     * - `field.encode(doc, item, extraInfo)` performs NO `pushKey`/`popKey` and no other
     *   `ExtraInfo` mutation, so an overflow thrown mid-field cannot corrupt the shared
     *   thread-local `ExtraInfo` — the next field (and the next scan on this pooled thread)
     *   starts clean.
     * - We iterate `itemCodec.entries` (this codec's own fields) with the SAME `version`
     *   [scan] uses, so the degraded document carries exactly the keys [scan]'s field walk
     *   reads. The version-wrapper [BuilderCodec.encode] adds is irrelevant here ("Version" is
     *   not a scanned field).
     */
    private fun encodeItemSafely(item: Item, version: Int, extraInfo: ExtraInfo): BsonDocument {
        return try {
            itemCodec.encode(item, extraInfo)
        } catch (e: StackOverflowError) {
            logger.atWarning().log(
                "CodecScanner: full encode of '%s' overflowed the stack (cyclic contained asset, " +
                    "e.g. a multi-state container). Degrading to a per-field encode; the recursive " +
                    "field(s) will be skipped and shown as not editable for this item.",
                item.id ?: "<unknown>"
            )
            encodeItemPerField(item, version)
        }
    }

    /**
     * Per-field fallback for [encodeItemSafely]. Encodes each top-level field on its own,
     * isolating a [StackOverflowError] to the single cyclic field so the rest survives.
     */
    private fun encodeItemPerField(item: Item, version: Int): BsonDocument {
        val doc = BsonDocument()
        val extraInfo = ExtraInfo.THREAD_LOCAL.get()
        // Iterate the codec's own entries directly (NOT via selectField, which widens to
        // BuilderField<*, *> and would block passing `item` into encode's Type parameter).
        // itemCodec.entries preserves BuilderField<Item, *>, so field.encode(doc, item, …) type-checks.
        for ((key, fieldList) in itemCodec.entries) {
            val field = fieldList.firstOrNull { it.supportsVersion(version) } ?: continue
            try {
                field.encode(doc, item, extraInfo)
            } catch (e: StackOverflowError) {
                doc.remove(key) // drop any partial value the overflow left behind
                logger.atWarning().log(
                    "CodecScanner: field '%s' on '%s' skipped — encode recursion (cyclic asset). " +
                        "This field is not editable for this item.",
                    key, item.id ?: "<unknown>"
                )
            }
        }
        return doc
    }

    // ── Schema-Only Field Discovery (catalog warm) ───────────────────────

    /**
     * Discovers EVERY possible editable field from the Item codec SCHEMA alone — no item
     * instance, no `encode`, no asset-map read. Returns the union of all field shapes any
     * item could have: top-level primitives/strings/enums, every component's sub-fields, and
     * every valid map key (StatModifiers from the live registry, others from [MAP_VALID_KEYS]).
     *
     * This is the value-free core of [scan] — it reuses the SAME classification helpers
     * ([selectField], [inferValueType], [extractConstraints], [scanMissingMapEntries]) but
     * never reads a value, so it costs a single codec-structure walk instead of one full
     * `itemCodec.encode` per item. It is the reason the dashboard field catalog (which discards
     * values anyway) drops from ~21s/1200-item-scan to a few ms.
     *
     * `InteractionVars` is skipped here — the weapon-damage `@wdmg.*` fields are data-derived
     * (no `DamageType` enum exists) and are collected separately during the payload warm.
     *
     * Thread-safe: pure codec metadata read, no `ExtraInfo`, no item, no asset map.
     */
    fun discoverAllFields(): List<FieldDefinition> {
        val fields = mutableListOf<FieldDefinition>()
        // ExtraInfo.getVersion() is hardcoded to Integer.MAX_VALUE in 0.5.3 (verified from the
        // decompiled source via the Hytale Workshop MCP), so scan()'s `extraInfo.version` is ALWAYS
        // MAX_VALUE on every thread — passing it here selects fields identically to scan().
        val version = Int.MAX_VALUE

        for ((key, fieldList) in itemCodec.entries) {
            if (key in SKIP_FIELDS) continue
            if (key == "InteractionVars") continue // data-derived; handled by the payload warm
            val field = selectField(fieldList, version) ?: continue
            val childCodec = field.codec.childCodec

            if (childCodec is BuilderCodec<*>) {
                // Component (Armor, Weapon, Tool, …) — enumerate its sub-fields unconditionally,
                // regardless of whether any particular item populates this component.
                discoverComponentFields(fields, key, childCodec, version)
            } else {
                val inferredType = inferValueType(childCodec) ?: continue
                fields.add(buildSchemaLeaf(key, key, CATEGORY_GENERAL, null, field, inferredType))
            }
        }
        return fields
    }

    /**
     * Schema-only counterpart to [scanComponent]: enumerates EVERY sub-field of a component
     * codec (leaf primitives/strings/enums + all valid map keys), with no BSON value. Mirrors
     * the "not set" branches of [scanComponent] (which already build value-free definitions).
     */
    private fun discoverComponentFields(
        fields: MutableList<FieldDefinition>,
        componentKey: String,
        codec: BuilderCodec<*>,
        version: Int
    ) {
        val category = ValueFormatter.formatCategoryName(componentKey)
        for ((subKey, subFieldList) in codec.entries) {
            // Equipment props (ArmorSlot) stay in Properties, not Defense — same split as scan().
            val fieldCategory = if (componentKey == "Armor" && subKey in EQUIPMENT_FIELDS) {
                CATEGORY_EQUIPMENT
            } else {
                category
            }
            val subField = selectField(subFieldList, version) ?: continue
            val subChildCodec = subField.codec.childCodec

            // Simple leaf (primitive / string / enum)
            val inferredType = inferValueType(subChildCodec)
            if (inferredType != null) {
                fields.add(buildSchemaLeaf("$componentKey.$subKey", subKey, fieldCategory, componentKey, subField, inferredType))
            }

            // Map sub-field (StatModifiers, DamageResistance, …) — enumerate ALL valid keys.
            if (subKey in MAP_FIELDS) {
                scanMissingMapEntries(
                    fields, componentKey, subKey, category,
                    existingKeys = emptySet(),
                    hasOverrides = emptySet()
                )
            }
        }
    }

    // ── Component Scanning ───────────────────────────────────────────────

    /**
     * Recursively scans a component's sub-fields (Armor, Weapon, Tool, etc.).
     *
     * Uses the child codec's entries for field metadata and the BSON document
     * for current values. Shows ALL sub-fields — including those with null
     * values and missing map entries — so admins can add values to any field.
     */
    private fun scanComponent(
        fields: MutableList<FieldDefinition>,
        componentKey: String,
        codec: BuilderCodec<*>,
        bsonDoc: BsonDocument,
        version: Int,
        hasOverrides: Set<String>
    ) {
        val category = ValueFormatter.formatCategoryName(componentKey)

        for ((subKey, subFieldList) in codec.entries) {
            // Equipment property fields (ArmorSlot, etc.) get a distinct category
            // so the UI can separate them from defense stats in the Defense tab.
            // These are item properties, not stat modifiers — they belong in Properties.
            val fieldCategory = if (componentKey == "Armor" && subKey in EQUIPMENT_FIELDS) {
                CATEGORY_EQUIPMENT
            } else {
                category
            }
            val subField = selectField(subFieldList, version) ?: continue
            val subValue = bsonDoc[subKey]

            // Null sub-field — exists in codec but not populated on this item.
            if (subValue == null) {
                val subChildCodec = subField.codec.childCodec

                // Simple types: show as "not set"
                val inferredType = inferValueType(subChildCodec)
                if (inferredType != null) {
                    fields.add(FieldDefinition(
                        id = "$componentKey.$subKey",
                        jsonKey = subKey,
                        displayName = ValueFormatter.formatFieldName(subKey),
                        category = fieldCategory,
                        valueType = inferredType,
                        currentValue = null,
                        constraints = extractConstraints(subField, subKey),
                        state = if ("$componentKey.$subKey" in hasOverrides) FieldState.MODIFIED else FieldState.DEFAULT,
                        tooltip = subField.documentation,
                        componentKey = componentKey,
                        requiresPathB = false,
                        isNotSet = true
                    ))
                }

                // Map sub-fields that are entirely null: show all valid keys as "not set"
                if (subKey in MAP_FIELDS) {
                    scanMissingMapEntries(
                        fields, componentKey, subKey, category,
                        existingKeys = emptySet(),
                        hasOverrides = hasOverrides
                    )
                }
                continue
            }

            val subChildCodec = subField.codec.childCodec

            when {
                // Primitive sub-field (Speed, TerminalVelocity, Capacity, etc.)
                subChildCodec is PrimitiveCodec -> {
                    val valueType = BsonHelper.bsonToValueType(subValue) ?: continue
                    fields.add(buildLeafField(
                        id = "$componentKey.$subKey",
                        jsonKey = subKey,
                        category = fieldCategory,
                        componentKey = componentKey,
                        field = subField,
                        bsonValue = subValue,
                        valueType = valueType,
                        requiresPathB = false,
                        hasOverrides = hasOverrides
                    ))
                }

                // String sub-field (ArmorSlot, GlobalFilter, etc.)
                subValue.bsonType == BsonType.STRING -> {
                    fields.add(buildLeafField(
                        id = "$componentKey.$subKey",
                        jsonKey = subKey,
                        category = fieldCategory,
                        componentKey = componentKey,
                        field = subField,
                        bsonValue = subValue,
                        valueType = ValueType.STRING,
                        requiresPathB = false,
                        hasOverrides = hasOverrides
                    ))
                }

                // Map-as-document (StatModifiers, DamageResistance, DamageEnhancement, etc.)
                // Scan existing entries, then add missing valid keys
                subValue.bsonType == BsonType.DOCUMENT -> {
                    val mapDoc = subValue.asDocument()
                    scanMapEntries(
                        fields, componentKey, subKey, category,
                        mapDoc, hasOverrides
                    )
                    // Add entries for valid keys NOT present in the BSON
                    scanMissingMapEntries(
                        fields, componentKey, subKey, category,
                        existingKeys = mapDoc.keys,
                        hasOverrides = hasOverrides
                    )
                }

                // Boolean sub-field (Regenerating, Usable, Compatible, RenderDualWielded)
                subValue.bsonType == BsonType.BOOLEAN -> {
                    fields.add(buildLeafField(
                        id = "$componentKey.$subKey",
                        jsonKey = subKey,
                        category = fieldCategory,
                        componentKey = componentKey,
                        field = subField,
                        bsonValue = subValue,
                        valueType = ValueType.BOOLEAN,
                        requiresPathB = false,
                        hasOverrides = hasOverrides
                    ))
                }

                // Everything else: skip (arrays like EntityStatsToClear, Specs, etc.)
            }
        }
    }

    /**
     * Scans entries of a map-encoded-as-document sub-field.
     *
     * StatModifiers, DamageResistance, DamageEnhancement, and similar fields
     * encode as BSON documents where each key is a stat/type name (e.g., "Health",
     * "Physical") and each value is the modifier data.
     *
     * For stat modifier entries, the value is typically an ARRAY of modifier objects:
     * `[{"Amount": 17.0, "CalculationType": "Additive"}]`
     * We extract the first modifier's Amount as the editable value and the
     * CalculationType as modifier metadata (for UI display and BSON reconstruction).
     *
     * For simple map entries (key → DOUBLE), we use the value directly.
     */
    private fun scanMapEntries(
        fields: MutableList<FieldDefinition>,
        componentKey: String,
        mapKey: String,
        parentCategory: String,
        mapDoc: BsonDocument,
        hasOverrides: Set<String>
    ) {
        val category = mapCategory(mapKey, parentCategory)

        for ((entryKey, entryValue) in mapDoc) {
            val fieldId = "$componentKey.$mapKey.$entryKey"

            when (entryValue.bsonType) {
                // Direct numeric value (KnockbackResistances/Enhancements use plain Float)
                BsonType.DOUBLE -> {
                    fields.add(FieldDefinition(
                        id = fieldId,
                        jsonKey = entryKey,
                        displayName = entryKey,
                        category = category,
                        valueType = ValueType.DOUBLE,
                        currentValue = BsonHelper.bsonToKotlin(entryValue),
                        constraints = FieldConstraints.NONE,
                        state = if (fieldId in hasOverrides) FieldState.MODIFIED else FieldState.DEFAULT,
                        tooltip = null,
                        componentKey = componentKey,
                        requiresPathB = false,
                        sourceMod = statSourceMod(mapKey, entryKey)
                    ))
                }

                // Modifier array: [{Amount: 17.0, CalculationType: "Additive"}]
                BsonType.ARRAY -> {
                    val arr = entryValue.asArray()
                    if (arr.isEmpty()) continue

                    val firstModifier = arr[0]
                    if (firstModifier.bsonType != BsonType.DOCUMENT) continue

                    val modDoc = firstModifier.asDocument()
                    val amount = modDoc["Amount"]
                    if (amount == null || amount.bsonType != BsonType.DOUBLE) continue

                    val calcType = modDoc["CalculationType"]
                    val calcTypeStr = if (calcType != null && calcType.bsonType == BsonType.STRING) {
                        calcType.asString().value
                    } else {
                        null
                    }

                    fields.add(FieldDefinition(
                        id = fieldId,
                        jsonKey = entryKey,
                        displayName = entryKey,
                        category = category,
                        valueType = ValueType.DOUBLE,
                        currentValue = BsonHelper.bsonToKotlin(amount),
                        calculationType = calcTypeStr,
                        constraints = FieldConstraints.NONE,
                        state = if (fieldId in hasOverrides) FieldState.MODIFIED else FieldState.DEFAULT,
                        tooltip = null,
                        componentKey = componentKey,
                        requiresPathB = false,
                        sourceMod = statSourceMod(mapKey, entryKey)
                    ))
                }

                // Other types: skip
                else -> {}
            }
        }
    }

    /**
     * Adds FieldDefinitions for valid map keys NOT present in the BSON document.
     *
     * This ensures admins can ADD new entries (e.g., add Fire resistance to armor
     * that only has Physical resistance). Valid keys come from the game's asset
     * registries and enums, hardcoded from decompiled 0.5.0 source.
     *
     * Missing entries are created with `currentValue = null, isNotSet = true`
     * and the appropriate default CalculationType for the map type.
     */
    private fun scanMissingMapEntries(
        fields: MutableList<FieldDefinition>,
        componentKey: String,
        mapKey: String,
        parentCategory: String,
        existingKeys: Set<String>,
        hasOverrides: Set<String>
    ) {
        val allKeys = validStatKeys(mapKey) ?: return
        val missing = allKeys - existingKeys
        if (missing.isEmpty()) return

        val category = mapCategory(mapKey, parentCategory)
        val defaultCalcType = MAP_DEFAULT_CALC_TYPE[mapKey]

        for (entryKey in missing.sorted()) {
            val fieldId = "$componentKey.$mapKey.$entryKey"
            fields.add(FieldDefinition(
                id = fieldId,
                jsonKey = entryKey,
                displayName = entryKey,
                category = category,
                valueType = ValueType.DOUBLE,
                currentValue = null,
                calculationType = defaultCalcType,
                constraints = FieldConstraints.NONE,
                state = if (fieldId in hasOverrides) FieldState.MODIFIED else FieldState.DEFAULT,
                tooltip = null,
                componentKey = componentKey,
                requiresPathB = false,
                isNotSet = true,
                sourceMod = statSourceMod(mapKey, entryKey)
            ))
        }
    }

    // ── InteractionVars Scanning ─────────────────────────────────────────

    /**
     * Scans InteractionVars entries, parsing deep into DamageCalculator for
     * editable weapon damage fields.
     *
     * InteractionVars uses RootInteraction.CHILD_ASSET_CODEC which requires
     * assetStore.decode() with AssetExtraInfo — all InteractionVars fields are
     * marked `requiresPathB = true` (Probe 1.5, SDK_FINDINGS.md §9.7).
     *
     * For DamageEntity interactions, extracts:
     * - BaseDamage entries (Physical, Fire, etc.) → editable DOUBLE fields
     * - DamageCalculator.Class (Light, Charged, Signature) → STRING dropdown
     * - RandomPercentageModifier → editable DOUBLE field
     *
     * For other interaction types (ApplyEffect for food/potions) and STRING
     * references (external RootInteraction assets), creates READ_ONLY labels.
     *
     * ## Override detection
     *
     * Uses prefix matching: `hasOverrides.any { it.startsWith("InteractionVars.$varName") }`.
     * The stored override JSON contains the full var entry as a nested document.
     * `collectFieldIds()` walks the JSON and produces `InteractionVars.Swing_Left_Damage`
     * (Interactions is a JsonArray → recursion stops). Prefix matching catches this.
     */
    private fun scanInteractionVars(
        fields: MutableList<FieldDefinition>,
        varsDoc: BsonDocument,
        hasOverrides: Set<String>
    ) {
        if (varsDoc.isEmpty()) return

        for ((varName, varValue) in varsDoc) {
            when (varValue.bsonType) {
                // External RootInteraction asset reference — not parseable
                BsonType.STRING -> {
                    fields.add(FieldDefinition(
                        id = "InteractionVars.$varName",
                        jsonKey = varName,
                        displayName = ValueFormatter.formatInteractionVarName(varName),
                        category = "Interactions",
                        valueType = ValueType.STRING,
                        currentValue = varValue.asString().value,
                        constraints = FieldConstraints.NONE,
                        state = FieldState.READ_ONLY,
                        tooltip = "External interaction reference (not editable inline)",
                        componentKey = "InteractionVars",
                        requiresPathB = true
                    ))
                }

                // Inline interaction definition — parse for damage fields
                BsonType.DOCUMENT -> {
                    scanInteractionVarEntry(fields, varName, varValue.asDocument(), hasOverrides)
                }

                else -> {} // Skip other types
            }
        }
    }

    /**
     * Scans a single inline InteractionVars entry for editable fields.
     *
     * Navigates to `Interactions[0]` and dispatches by interaction type:
     * - DamageEntity → [scanDamageCalculator] extracts editable damage values
     * - Other types (ApplyEffect, etc.) → READ_ONLY label
     */
    private fun scanInteractionVarEntry(
        fields: MutableList<FieldDefinition>,
        varName: String,
        varDoc: BsonDocument,
        hasOverrides: Set<String>
    ) {
        val interactionsVal = varDoc["Interactions"]
        if (interactionsVal == null || interactionsVal.bsonType != BsonType.ARRAY) {
            // No Interactions array — degenerate case
            return
        }

        val interactions = interactionsVal.asArray()
        if (interactions.isEmpty()) return

        val firstInteraction = interactions[0]
        if (firstInteraction.bsonType != BsonType.DOCUMENT) return

        val interactionDoc = firstInteraction.asDocument()
        val interactionType = interactionDoc["Type"]?.let {
            if (it.bsonType == BsonType.STRING) it.asString().value else null
        }

        if (interactionType == "DamageEntity") {
            val damageCalc = interactionDoc["DamageCalculator"]
            if (damageCalc != null && damageCalc.bsonType == BsonType.DOCUMENT) {
                scanDamageCalculator(fields, varName, damageCalc.asDocument(), hasOverrides)
            }
        } else {
            // Non-damage interaction (ApplyEffect for food/potions, etc.)
            fields.add(FieldDefinition(
                id = "InteractionVars.$varName",
                jsonKey = varName,
                displayName = ValueFormatter.formatInteractionVarName(varName),
                category = "Interactions",
                valueType = ValueType.STRING,
                currentValue = "Effect: ${interactionType ?: "Unknown"}",
                constraints = FieldConstraints.NONE,
                state = FieldState.READ_ONLY,
                tooltip = "Non-damage interaction - editing not supported in this version",
                componentKey = "InteractionVars",
                requiresPathB = true
            ))
        }
    }

    /**
     * Extracts editable fields from a DamageCalculator BSON document.
     *
     * Produces FieldDefinitions for:
     * 1. Each BaseDamage entry (Physical, Fire, etc.) as DOUBLE
     * 2. Missing BaseDamage types as isNotSet=true (admin can add new damage types)
     * 3. DamageCalculator.Class as STRING dropdown
     * 4. RandomPercentageModifier as DOUBLE
     *
     * BSON structure (SDK_FINDINGS.md §9.7):
     * ```
     * DamageCalculator: {
     *   Type: "Absolute",
     *   Class: "Light" | "Charged" | "Signature",
     *   BaseDamage: { "Physical": 42.0 },
     *   SequentialModifierStep: 0.0,
     *   SequentialModifierMinimum: 0.0,
     *   RandomPercentageModifier: 0.2
     * }
     * ```
     */
    private fun scanDamageCalculator(
        fields: MutableList<FieldDefinition>,
        varName: String,
        calcDoc: BsonDocument,
        hasOverrides: Set<String>
    ) {
        val category = ValueFormatter.formatInteractionVarName(varName)
        // Prefix match: if any override key starts with "InteractionVars.{varName}",
        // the entire var entry is overridden (stored as a complete clone).
        val isVarOverridden = hasOverrides.any { it.startsWith("InteractionVars.$varName") }

        // 1. BaseDamage entries — the primary edit targets
        val baseDamageVal = calcDoc["BaseDamage"]
        val existingDmgKeys = mutableSetOf<String>()

        if (baseDamageVal != null && baseDamageVal.bsonType == BsonType.DOCUMENT) {
            val baseDamage = baseDamageVal.asDocument()
            for ((dmgType, dmgValue) in baseDamage) {
                if (dmgValue.bsonType != BsonType.DOUBLE) continue
                existingDmgKeys.add(dmgType)

                val fieldId = "InteractionVars.$varName.BaseDamage.$dmgType"
                fields.add(FieldDefinition(
                    id = fieldId,
                    jsonKey = dmgType,
                    displayName = dmgType,
                    category = category,
                    valueType = ValueType.DOUBLE,
                    currentValue = BsonHelper.bsonToKotlin(dmgValue),
                    constraints = FieldConstraints(min = 0.0),
                    state = if (isVarOverridden) FieldState.MODIFIED else FieldState.DEFAULT,
                    tooltip = "Base $dmgType damage for this attack",
                    componentKey = "InteractionVars",
                    requiresPathB = true
                ))
            }
        }

        // Missing damage types — admin can add Fire damage to a Physical-only weapon
        scanMissingDamageTypes(fields, varName, category, existingDmgKeys, isVarOverridden)

        // 2. Damage Class dropdown (Light, Charged, Signature)
        val classVal = calcDoc["Class"]
        if (classVal != null && classVal.bsonType == BsonType.STRING) {
            val fieldId = "InteractionVars.$varName.Class"
            fields.add(FieldDefinition(
                id = fieldId,
                jsonKey = "Class",
                displayName = "Damage Class",
                category = category,
                valueType = ValueType.STRING,
                currentValue = classVal.asString().value,
                constraints = FieldConstraints(options = DAMAGE_CLASSES),
                state = if (isVarOverridden) FieldState.MODIFIED else FieldState.DEFAULT,
                tooltip = "Attack class: Light (normal), Charged (hold), Signature (special)",
                componentKey = "InteractionVars",
                requiresPathB = true
            ))
        }

        // 3. RandomPercentageModifier — damage variance
        val randModVal = calcDoc["RandomPercentageModifier"]
        if (randModVal != null && randModVal.bsonType == BsonType.DOUBLE) {
            val fieldId = "InteractionVars.$varName.RandomPercentageModifier"
            fields.add(FieldDefinition(
                id = fieldId,
                jsonKey = "RandomPercentageModifier",
                displayName = "Damage Variance",
                category = category,
                valueType = ValueType.DOUBLE,
                currentValue = BsonHelper.bsonToKotlin(randModVal),
                constraints = FieldConstraints(min = 0.0, max = 1.0),
                state = if (isVarOverridden) FieldState.MODIFIED else FieldState.DEFAULT,
                tooltip = "Random damage spread. 0.2 = ±20% variance per hit",
                componentKey = "InteractionVars",
                requiresPathB = true
            ))
        }
    }

    /**
     * Adds "not set" FieldDefinitions for valid damage types not present on this attack.
     *
     * Reuses the same damage type set as DamageResistance/DamageEnhancement
     * (both use DamageCause from the same enum). Allows admins to add Fire
     * damage to a weapon that currently only does Physical, for example.
     */
    private fun scanMissingDamageTypes(
        fields: MutableList<FieldDefinition>,
        varName: String,
        category: String,
        existingKeys: Set<String>,
        isVarOverridden: Boolean
    ) {
        val missing = INTERACTION_DAMAGE_TYPES - existingKeys
        if (missing.isEmpty()) return

        for (dmgType in missing.sorted()) {
            val fieldId = "InteractionVars.$varName.BaseDamage.$dmgType"
            fields.add(FieldDefinition(
                id = fieldId,
                jsonKey = dmgType,
                displayName = dmgType,
                category = category,
                valueType = ValueType.DOUBLE,
                currentValue = null,
                constraints = FieldConstraints(min = 0.0),
                state = if (isVarOverridden) FieldState.MODIFIED else FieldState.DEFAULT,
                tooltip = "Add $dmgType damage to this attack",
                componentKey = "InteractionVars",
                requiresPathB = true,
                isNotSet = true
            ))
        }
    }

    // ── Field Building ───────────────────────────────────────────────────

    /**
     * Builds a leaf [FieldDefinition] from codec metadata and BSON value.
     */
    private fun buildLeafField(
        id: String,
        jsonKey: String,
        category: String,
        componentKey: String?,
        field: BuilderField<*, *>,
        bsonValue: BsonValue,
        valueType: ValueType,
        requiresPathB: Boolean,
        hasOverrides: Set<String>
    ): FieldDefinition {
        return FieldDefinition(
            id = id,
            jsonKey = jsonKey,
            displayName = ValueFormatter.formatFieldName(jsonKey),
            category = category,
            valueType = valueType,
            currentValue = BsonHelper.bsonToKotlin(bsonValue),
            constraints = extractConstraints(field, jsonKey),
            state = if (id in hasOverrides) FieldState.MODIFIED else FieldState.DEFAULT,
            tooltip = field.documentation,
            componentKey = componentKey,
            requiresPathB = requiresPathB
        )
    }

    /**
     * Builds a value-free leaf [FieldDefinition] from codec metadata only (no BSON value).
     *
     * Identical to [buildLeafField]'s output for the "not set" case (currentValue=null,
     * isNotSet=true, state=DEFAULT). Used by the schema-only [discoverAllFields] catalog walk
     * so the catalog never needs to encode an item. Shares [extractConstraints] /
     * [ValueFormatter.formatFieldName] with the value-bearing path so the two never diverge.
     */
    private fun buildSchemaLeaf(
        id: String,
        jsonKey: String,
        category: String,
        componentKey: String?,
        field: BuilderField<*, *>,
        valueType: ValueType
    ): FieldDefinition = FieldDefinition(
        id = id,
        jsonKey = jsonKey,
        displayName = ValueFormatter.formatFieldName(jsonKey),
        category = category,
        valueType = valueType,
        currentValue = null,
        constraints = extractConstraints(field, jsonKey),
        state = FieldState.DEFAULT,
        tooltip = field.documentation,
        componentKey = componentKey,
        requiresPathB = false,
        isNotSet = true
    )

    /**
     * Extracts [FieldConstraints] from a BuilderField's codec and validators.
     *
     * Three extraction strategies, checked in order:
     *
     * 1. **EnumCodec** — childCodec is EnumCodec → getEnumKeys() returns all valid values.
     *    Covers real Java enums like ItemArmorSlot (Head/Chest/Hands/Legs).
     *
     * 2. **Known string options** — hardcoded values for non-enum string fields where
     *    the valid set is known from game assets but not discoverable via codec
     *    (ItemQuality is a data class, not a Java enum — EnumCodec doesn't apply).
     *
     * 3. **RangeValidator** — getValidators() returns RangeValidator instances with
     *    private min/max fields. Extracted via reflection (fields are private, no getters).
     *
     * @param field The BuilderField to extract constraints from
     * @param jsonKey The PascalCase JSON key (e.g., "Quality", "ArmorSlot") for known-options lookup
     */
    private fun extractConstraints(field: BuilderField<*, *>, jsonKey: String? = null): FieldConstraints {
        val childCodec = field.codec.childCodec

        // Strategy 1: EnumCodec — extract all valid enum keys
        if (childCodec is EnumCodec<*>) {
            val options = childCodec.getEnumKeys().toList()
            if (options.isNotEmpty()) {
                return FieldConstraints(options = options)
            }
        }

        // Strategy 2: Known string options for non-enum fields
        if (jsonKey != null) {
            val knownOptions = KNOWN_STRING_OPTIONS[jsonKey]
            if (knownOptions != null) {
                return FieldConstraints(options = knownOptions)
            }
        }

        // Strategy 3: RangeValidator — extract min/max via reflection
        var min: Double? = null
        var max: Double? = null
        val validators = field.getValidators()
        if (validators != null) {
            for (validator in validators) {
                if (validator is RangeValidator<*>) {
                    try {
                        val minField = RangeValidator::class.java.getDeclaredField("min")
                        minField.isAccessible = true
                        val minVal = minField.get(validator)
                        if (minVal is Number) min = minVal.toDouble()

                        val maxField = RangeValidator::class.java.getDeclaredField("max")
                        maxField.isAccessible = true
                        val maxVal = maxField.get(validator)
                        if (maxVal is Number) max = maxVal.toDouble()
                    } catch (_: Exception) {
                        // Reflection failed — field structure changed, skip gracefully
                    }
                }
            }
        }

        if (min != null || max != null) {
            return FieldConstraints(min = min, max = max)
        }

        return FieldConstraints.NONE
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Infers [ValueType] from a codec's childCodec when no BSON value is available.
     *
     * Uses identity comparison with Hytale's codec singletons (Codec.FLOAT, etc.)
     * verified from `Codec.java` — these are static final instances.
     *
     * Returns null for compound types (BuilderCodec, MapCodec, ArrayCodec) that
     * cannot be represented as a single editable leaf field.
     */
    private fun inferValueType(childCodec: Any): ValueType? = when (childCodec) {
        HytaleCodec.FLOAT, HytaleCodec.DOUBLE -> ValueType.DOUBLE
        HytaleCodec.INTEGER, HytaleCodec.LONG, HytaleCodec.SHORT, HytaleCodec.BYTE -> ValueType.INTEGER
        HytaleCodec.BOOLEAN -> ValueType.BOOLEAN
        HytaleCodec.STRING -> ValueType.STRING
        is EnumCodec<*> -> ValueType.STRING  // Enum fields displayed as string dropdowns
        else -> null  // BuilderCodec, MapCodec, ArrayCodec → compound, skip
    }

    /**
     * Version-aware field selection. Replicates BuilderCodec.findField()
     * (which is protected — SDK_FINDINGS.md §3.3).
     */
    private fun selectField(
        fields: List<BuilderField<*, *>>?,
        version: Int
    ): BuilderField<*, *>? {
        if (fields.isNullOrEmpty()) return null
        for (field in fields) {
            if (field.supportsVersion(version)) return field
        }
        return null
    }

    /**
     * Valid keys for a map sub-field's "addable" entries.
     *
     * For `StatModifiers` this returns the LIVE stat-type registry (vanilla + every mod's
     * registered stat) once [initStatTypes] has run — so any item with a StatModifiers-bearing
     * component can receive any registered stat. Before the registry is ready, or for all other
     * map fields (DamageResistance etc., which are enum-keyed and NOT mod-extensible), it falls
     * back to the curated [MAP_VALID_KEYS] set.
     */
    private fun validStatKeys(mapKey: String): Set<String>? =
        if (mapKey == "StatModifiers" && statTypesReady) statTypeRegistry.keys
        else MAP_VALID_KEYS[mapKey]

    /**
     * Source mod for a stat entry, or null for vanilla / non-stat map fields.
     *
     * Only `StatModifiers` entries carry mod attribution — they're keyed by the extensible
     * [EntityStatType] registry. DamageResistance/Enhancement keys come from a fixed enum
     * (`DamageCause`), so they're always vanilla.
     */
    private fun statSourceMod(mapKey: String, entryKey: String): String? =
        if (mapKey == "StatModifiers") statTypeRegistry[entryKey] else null

    /**
     * Reads `EntityStatType.hideFromTooltip` (protected field, no public getter) via reflection.
     * These are internal/scratch stats the game itself omits from tooltips — we omit them from
     * the editor too. Reflection failure (field renamed/removed) defaults to NOT hidden, so a
     * structure change degrades to showing the stat rather than silently dropping it.
     */
    private fun isHiddenFromTooltip(statType: EntityStatType): Boolean {
        return try {
            val f = EntityStatType::class.java.getDeclaredField("hideFromTooltip")
            f.isAccessible = true
            f.getBoolean(statType)
        } catch (_: Exception) {
            false
        }
    }

    /** True if a resolved mod name is vanilla or unattributable (→ null sourceMod, no grouping). */
    private fun isVanillaSource(modName: String): Boolean =
        modName == VANILLA_MOD_NAME || modName == UNKNOWN_MOD_NAME

    /** Returns the display category for a map sub-field. */
    private fun mapCategory(mapKey: String, parentCategory: String): String = when (mapKey) {
        "StatModifiers" -> parentCategory
        "DamageResistance" -> "Damage Resistance"
        "DamageEnhancement" -> "Damage Enhancement"
        "DamageClassEnhancement" -> "Damage Class Enhancement"
        "KnockbackResistances" -> "Knockback Resistance"
        "KnockbackEnhancements" -> "Knockback Enhancement"
        "Regenerating" -> "Regeneration"
        else -> parentCategory
    }

    companion object {
        /** Category name for top-level item properties. */
        const val CATEGORY_GENERAL = "General"

        /** Resolved mod name for vanilla assets (Hytale's own pack). null sourceMod → no grouping. */
        private const val VANILLA_MOD_NAME = "Hytale"

        /** Resolved mod name when a pack key can't be attributed (matches [ModSourceTracker]). */
        private const val UNKNOWN_MOD_NAME = "Unknown"

        /** Category name for equipment property fields (ArmorSlot, etc.) that belong
         *  in the Properties tab, not the Defense tab. */
        const val CATEGORY_EQUIPMENT = "Equipment"

        /**
         * Armor component sub-fields that are equipment properties, not defense stats.
         * These get [CATEGORY_EQUIPMENT] instead of "Armor Stats" so the UI can
         * keep them in the Properties tab while defense stats go to the Defense tab.
         */
        private val EQUIPMENT_FIELDS = setOf("ArmorSlot")

        /**
         * Fields to skip during scanning — display/rendering/non-gameplay fields.
         *
         * Built from the architecture's skip list + new fields discovered at runtime
         * (Probe 1.1: 54 fields, many visual/structural).
         */
        val SKIP_FIELDS = setOf(
            // Visual/rendering
            "Icon", "IconProperties", "Model", "Texture", "Scale", "Animation",
            "PlayerAnimationsId", "DroppedItemAnimation", "UsePlayerAnimations",
            "Particles", "FirstPersonParticles", "Trails", "Light",
            "ClipsGeometry", "RenderDeployablePreview", "PullbackConfig",
            "HudUI",

            // Audio
            "SoundEventId", "ItemSoundSetId",

            // Classification/metadata (not gameplay)
            "Tags", "Categories", "SubCategory", "Set", "ResourceTypes",
            "DisplayEntityStatsHUD", "Reticle",

            // Localization
            "TranslationProperties",

            // Complex/structural (out of scope for v1)
            "BlockType", "State", "ItemAppearanceConditions",
            "Interactions", "InteractionConfig",
            "ItemEntity", "Variant",

            // Specialized tools (not general item editing)
            "BlockSelectorTool", "BuilderTool",

            // Recipe (handled separately by RecipeScanner)
            "Recipe"
        )

        /**
         * Map fields that contain modifier arrays or plain floats.
         * Used to detect null maps that should still show valid key entries.
         */
        private val MAP_FIELDS = setOf(
            "StatModifiers", "DamageResistance", "DamageEnhancement",
            "DamageClassEnhancement", "KnockbackResistances", "KnockbackEnhancements",
            "Regenerating"
        )

        /**
         * Valid keys for each map field.
         *
         * Source: decompiled 0.5.0 game assets + DamageCause/EntityStatType/DamageClass.
         * Excludes environmental damage causes (Command, Drowning, Fall, etc.) and
         * non-balance stats (GlidingActive, DeployablePreview) that aren't meaningful
         * for item modification.
         */
        private val MAP_VALID_KEYS = mapOf(
            "StatModifiers" to setOf(
                "Health", "Stamina", "Mana", "Oxygen", "Ammo",
                "MagicCharges", "SignatureCharges", "SignatureEnergy", "Immunity"
            ),
            "DamageResistance" to setOf(
                "Physical", "Projectile", "Bludgeoning", "Slashing",
                "Fire", "Ice", "Elemental", "Poison"
            ),
            "DamageEnhancement" to setOf(
                "Physical", "Projectile", "Bludgeoning", "Slashing",
                "Fire", "Ice", "Elemental", "Poison"
            ),
            "DamageClassEnhancement" to setOf("Light", "Charged", "Signature"),
            "KnockbackResistances" to setOf(
                "Physical", "Projectile", "Bludgeoning", "Slashing",
                "Fire", "Ice", "Elemental", "Poison"
            ),
            "KnockbackEnhancements" to setOf(
                "Physical", "Projectile", "Bludgeoning", "Slashing",
                "Fire", "Ice", "Elemental", "Poison"
            ),
            "Regenerating" to setOf("Health", "Stamina", "Mana", "Oxygen")
        )

        /**
         * Default CalculationType for newly created modifier entries in each map type.
         *
         * Two distinct modifier systems in Hytale (ResistanceModifier.java, StaticModifier.java):
         * - StaticModifier: Additive / Multiplicative (StatModifiers, DamageEnhancement, DamageClassEnhancement)
         * - ResistanceModifier: Flat / Percent (DamageResistance)
         *
         * null = plain float (KnockbackResistances/Enhancements, Regenerating has different structure).
         */
        private val MAP_DEFAULT_CALC_TYPE = mapOf(
            "StatModifiers" to "Additive",
            "DamageResistance" to "Percent",
            "DamageEnhancement" to "Additive",
            "DamageClassEnhancement" to "Additive",
            "Regenerating" to "Additive"
            // KnockbackResistances/Enhancements = plain float, no calcType
        )

        /**
         * Known valid options for non-enum string fields.
         *
         * These fields use asset-backed codecs (not Java enums), so EnumCodec
         * doesn't apply. Values extracted from vanilla 0.5.0 game assets.
         *
         * ItemQuality is a data class (protocol/ItemQuality.java), not a Java enum.
         * Quality IDs extracted from Assets/Server/Item JSON files.
         */
        private val KNOWN_STRING_OPTIONS = mapOf(
            "Quality" to listOf(
                "Common", "Uncommon", "Rare", "Epic", "Legendary",
                "Tool", "Junk",
                "Debug", "Developer", "Technical", "Template"
            )
        )

        // ── InteractionVars constants ────────────────────────────────────

        /**
         * Valid DamageCalculator.Class values from decompiled DamageClass enum.
         * Used as dropdown options for the Damage Class field.
         */
        private val DAMAGE_CLASSES = listOf("Light", "Charged", "Signature")

        /**
         * Valid damage types for BaseDamage entries.
         * Same DamageCause set used by DamageResistance/DamageEnhancement
         * (both map damage type → amount). Allows admins to add damage types
         * that aren't present on the original weapon.
         */
        private val INTERACTION_DAMAGE_TYPES = setOf(
            "Physical", "Projectile", "Bludgeoning", "Slashing",
            "Fire", "Ice", "Elemental", "Poison"
        )
    }
}

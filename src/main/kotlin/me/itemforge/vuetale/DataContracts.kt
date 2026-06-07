package me.itemforge.vuetale

import me.itemforge.scanner.FieldDefinition
import me.itemforge.scanner.FieldState
import me.itemforge.scanner.ValueType
import me.itemforge.util.ValueFormatter

/**
 * Serializable data contracts for Vuetale setData() payloads.
 *
 * These mirror ARCHITECTURE.md §7.1 exactly. Gson serializes them to JSON
 * for the Vue UI. All fields use types Gson handles natively (String, Number,
 * Boolean, List, Map) — no custom serializers needed.
 *
 * ## Data Flow
 *
 * ```
 * CodecScanner.scan() → List<FieldDefinition>
 *     ↓ (EditorBridge.serializeField)
 * SerializedFieldDefinition (adds originalValue, effectDescription)
 *     ↓ (Gson.toJson)
 * JSON string → setData("editor", json) → Vue useData("editor")
 * ```
 */

// ── Editor Payload ──────────────────────────────────────────────────────

/**
 * Complete payload for the editor page.
 * Pushed via `setData("editor", json)` when the editor opens,
 * and returned by bridge methods after mutations (save, reset).
 *
 * ARCHITECTURE.md §7.1
 */
data class EditorPayload(
    val itemId: String,
    val itemName: String,
    val itemMod: String,
    val itemType: String?,
    val hasOverride: Boolean,
    val fields: List<SerializedFieldDefinition>,
    /**
     * Fields the item could have but currently does NOT (`isNotSet`, no override) — the
     * "addable" universe (e.g. every registered `StatModifiers` stat). These are
     * NOT rendered as rows. The UI feeds them to the searchable "+ Add a stat" picker and
     * mounts a row only when the admin picks one. Splitting them out of [fields] is what keeps
     * the editor's element count (and therefore its mount time) bounded regardless of how many
     * mods are installed — the registry-driven `StatModifiers` set is the only unbounded source.
     */
    val addableFields: List<SerializedFieldDefinition>,
    val originalValues: Map<String, Any?>,
    val tabs: List<String>,
    val recipeData: RecipeData?,
    /**
     * What the viewing player is allowed to edit on this item.
     * Computed at open time from the player's permissions. The UI renders
     * restricted tabs read-only with a banner; the bridge re-checks on every
     * write as defense-in-depth (permission can be revoked mid-session).
     */
    val permissions: EditorPermissions,

    // ── Editor extensions ──────────────────────────────────
    // Mod-built panels for this item id (global). Defaulted so the base editor path is
    // unchanged when no extension manages the item: the dropdown only appears when an
    // extension contributes a panel, and it works from every entry point (dashboard,
    // command, inspect) since it keys off the item id.

    /**
     * Selectable editor sources. Empty → no dropdown (plain base editor). When an extension
     * contributes a panel, this is `[{"BASE","Base Item"}, ...one per managing extension]`.
     */
    val editorSources: List<EditorSource> = emptyList(),
    /**
     * Extension id → its declarative component panel, rendered by the Vue extension renderer.
     * Components are [me.itemforge.provider.EditorComponent] (Gson-serialized directly).
     */
    val extensionPanels: Map<String, List<me.itemforge.provider.EditorComponent>> = emptyMap(),

    /**
     * Whether per-item ("This Item") editing is available for this editor session.
     * True only when the editor was opened by inspecting a held item (a concrete
     * `ItemStack` exists to write back to). The UI enables the Global/Local scope toggle when set;
     * dashboard/command opens (item id only — no instance) leave it false and lock scope to Global.
     */
    val localScopeAvailable: Boolean = false,

    // ── Custom Name + Lore (the last pre-release feature) ──────────────────────────────
    // The item's display name and lore/description, editable in BOTH scopes (the only fields that
    // are): GLOBAL = the item type's translation, pushed to every client via the i18n broadcast
    // (TranslationOverrideEngine); LOCAL = this held instance's `ItemDisplay` metadata (a literal
    // Message). They are rendered BESPOKE in the editor header (pinned above the tabs, always
    // visible regardless of active tab) — hence dedicated payload slots rather than tab fields.
    // Both carry currentValue = the GLOBAL/type text and localValue = the LOCAL (ItemDisplay) text;
    // the header picks per [localScopeAvailable]/scope. null when the viewer can't edit General.

    /** The item's NAME as an editable header field (single-line). null → name not editable here. */
    val nameField: SerializedFieldDefinition? = null,
    /** The item's LORE/description as an editable header field (multiline). null → not editable. */
    val loreField: SerializedFieldDefinition? = null
)

/**
 * One selectable source in the editor's source dropdown.
 * [id] is `"BASE"` for the base item, or an extension id; [name] is the display label.
 */
data class EditorSource(
    val id: String,
    val name: String
)

/**
 * Per-player edit permissions for the editor, resolved from PermissionChecker
 * at payload-build time. Maps to editor tabs:
 * - [canEditStats]   → Properties / Defense / Damage tabs
 * - [canEditRecipes] → Recipe tab
 * - [canEditGeneral] → General tab
 * - [canReset]       → Reset button
 */
data class EditorPermissions(
    val canEditStats: Boolean,
    val canEditRecipes: Boolean,
    val canEditGeneral: Boolean,
    val canReset: Boolean
)

/**
 * A single editable field serialized for the Vue UI.
 *
 * Extends [FieldDefinition] with UI-specific computed properties:
 * - [originalValue]: from OriginalsCache (for "was: X" indicators)
 * - [effectDescription]: from ValueFormatter ("+25 Health", "12% resistance")
 * - [readOnly]: derived from [FieldState.READ_ONLY]
 *
 * ARCHITECTURE.md §7.1
 */
data class SerializedFieldDefinition(
    /** Dot-separated BSON path: "MaxDurability", "Armor.StatModifiers.Health" */
    val id: String,
    /** Human-readable name: "Max Durability", "Health" */
    val displayName: String,
    /** Group heading: "General", "Armor Stats", "Damage Resistance" */
    val category: String,
    /** Data type: "DOUBLE", "INTEGER", "BOOLEAN", "STRING" */
    val valueType: String,
    /** Current value (after overrides if any). Type matches valueType. */
    val currentValue: Any?,
    /**
     * Calculation type for stat modifier entries: "Additive" or "Multiplicative".
     * null for non-modifier fields. Used by the UI to render a dropdown next to
     * the amount input and by the save flow to reconstruct modifier array BSON.
     */
    val calculationType: String?,
    /** Original value before ItemForge override. null if never overridden. */
    val originalValue: Any?,
    /** Minimum allowed value (inclusive). null = no minimum. */
    val min: Double?,
    /** Maximum allowed value (inclusive). null = no maximum. */
    val max: Double?,
    /** Step increment for number inputs. null = any value. */
    val step: Double?,
    /** Max decimal places. 0 for integers, null for unconstrained. */
    val maxDecimals: Int?,
    /** Valid options for enum-like string fields (Quality). null = free text. */
    val options: List<String>?,
    /** Whether the field is read-only (display only, input disabled). */
    val readOnly: Boolean,
    /** Modification state: "DEFAULT", "MODIFIED", "STALE", "READ_ONLY" */
    val state: String,
    /** Tooltip from BuilderField.getDocumentation(). null if no docs. */
    val tooltip: String?,
    /** Computed description: "+25 Health", "12% Physical Resistance". null if N/A. */
    val effectDescription: String?,
    /** Whether the field currently has no value on the item (null/unset). */
    val isNotSet: Boolean,
    /**
     * Source mod that registered this stat type, or null for vanilla / non-stat fields.
     * Set for modded `StatModifiers` entries (e.g. Hexcode's Magic_Power). The editor groups
     * these under their mod in the source dropdown — the same place API extensions appear.
     */
    val sourceMod: String?,

    // ── Per-item ("This Item") scope capability ────────────────
    // Two flags + a per-instance value let ONE field model serve both edit scopes. A field can be
    // editable globally (asset override), locally (per-instance override), or both — and the UI
    // greys the field in whichever scope it can't be edited. As each runtime applier ships
    // (combat/attribute tiers), more fields flip [localCapable] true.

    /**
     * Whether this field can be edited at GLOBAL scope (asset override, affects all copies).
     * True for every codec-scanned asset field. False for instance-only synthetic fields
     * (the held stack's Quantity / current Durability — they have no item-type equivalent and are
     * hidden entirely in Global scope).
     */
    val globalCapable: Boolean = true,
    /**
     * Whether this field can be edited at LOCAL scope (per-instance, affects only the held item).
     * Step 1 (free tier): true for the engine-native per-stack fields (MaxDurability + the
     * synthetic Quantity / Durability). All other fields are greyed in Local scope until their
     * runtime applier ships (combat/attribute tiers). Default false.
     */
    val localCapable: Boolean = false,
    /**
     * The field's CURRENT per-instance value on the held stack, shown when editing in Local scope
     * (e.g. this specific sword's MaxDurability, distinct from [currentValue] = the item asset's
     * default). null when there is no instance value to show (non-inspect open, or a field with no
     * per-instance form yet). Display falls back to [currentValue] when null.
     */
    val localValue: Any? = null,

    /**
     * Render this STRING field as a MULTILINE text box (lore/description) rather than a single-line
     * input. Drives `FieldEditor` / the header to use Vuetale's `MultilineTextField`. Default false
     * (single line). Only meaningful for `valueType == "STRING"`.
     */
    val multiline: Boolean = false
)

// ── Save/Reset Response ─────────────────────────────────────────────────

/**
 * Response from the save bridge method.
 * Either contains the updated payload (success) or an error with details.
 */
data class SaveResponse(
    /** Whether the save succeeded. */
    val success: Boolean,
    /** Updated editor payload (null on error). */
    val payload: EditorPayload?,
    /** Error message (null on success). */
    val error: String?,
    /** Per-field validation errors (empty on success). */
    val validationErrors: List<FieldValidationError>,
    /**
     * True when the save was rejected because the player lacks permission
     * The UI shows the Permission Denied overlay rather than a
     * generic error status. Distinct from [validationErrors] (bad values).
     */
    val permissionDenied: Boolean = false
)

/**
 * A validation error for a specific field.
 */
data class FieldValidationError(
    val fieldId: String,
    val message: String
)

// ── Dashboard Payload ──────────────────────────────────────────────────

/**
 * Complete payload for the dashboard page.
 * Pushed via `setData("dashboard", json)` when `/itemforge` opens.
 *
 * Contains a summary row for every item in the asset map — the Vue UI
 * handles filtering, sorting, and display capping client-side for instant
 * responsiveness. At ~150 bytes/item × 5,523 items ≈ 800KB, this is
 * acceptable as a one-time push.
 *
 * ARCHITECTURE.md §7.1, UX_DESIGN.md §4
 */
data class DashboardPayload(
    /** Summary data for every item in the asset map. */
    val items: List<DashboardItem>,
    /** Available type filter options from TagCache (e.g., "Armor", "Weapon", "Tool"). */
    val typeOptions: List<String>,
    /** Available mod filter options from ModSourceTracker (e.g., "Vanilla", "The Armory"). */
    val modOptions: List<String>,
    /** Total number of items with active ItemForge overrides. */
    val overrideCount: Int,
    /** Total number of items in the asset map. */
    val totalCount: Int
)

/**
 * Summary data for a single item in the dashboard.
 *
 * Contains just enough data for browsing, filtering, sorting, and comparison.
 * Key stats are extracted from BSON via targeted field reads — NOT via full
 * CodecScanner.scan() (which processes all 54 fields). Stats are null when
 * the item doesn't have that component (e.g., a Tool has no health stat).
 */
data class DashboardItem(
    /** Asset ID (e.g., "Armor_Iron_Chest"). Used as unique key. */
    val id: String,
    /** Human-readable display name from [ItemNameResolver]. */
    val name: String,
    /** Primary type tag (Armor, Weapon, Tool, etc.) from [TagCache]. */
    val type: String,
    /** Source mod name from [ModSourceTracker]. */
    val mod: String,
    /** Whether this item has active ItemForge overrides. */
    val hasOverride: Boolean,
    /** Number of overridden fields (for dashboard "3 overrides" badge). */
    val overrideCount: Int,
    // ── Key summary stats (null = item doesn't have this stat) ──────
    /** MaxDurability field value. */
    val durability: Int?,
    /** First Armor.StatModifiers.Health modifier Amount (Additive). */
    val health: Double?,
    /** Sum of BaseDamage values from first InteractionVar entry. */
    val damage: Double?,
    /** Tool.Speed field value. */
    val speed: Double?,
    /**
     * Armor.DamageResistance.Physical — displayed as percentage.
     * Raw Multiplicative values (0.09) are multiplied by 100 at extraction → 9.0.
     * Additive values (flat damage reduction) are kept as-is.
     * This makes the filter intuitive: "Defense >= 9" matches 9% Physical resistance.
     */
    val defense: Double?,
    /** ItemLevel field value. */
    val level: Int?,
    /** Quality enum name (e.g., "Rare", "Legendary"). null if no quality set. */
    val quality: String?,
    /** First Armor.StatModifiers.Stamina modifier Amount (Additive). */
    val stamina: Double?,
    /**
     * Armor.DamageResistance.Fire — displayed as percentage (same ×100 as defense).
     * Highlights fire-specialized armor like Cindercloth (75% fire resistance).
     */
    val fireRes: Double?,
    /** Armor.ArmorSlot value (Head, Chest, Hands, Legs). null for non-armor items. */
    val slot: String?,
    /** MaxStack field value (default null for non-stackable or standard items). */
    val maxStack: Int?
)

/**
 * Per-player dashboard state preserved during Dashboard → Editor navigation.
 *
 * Stored server-side in [DashboardBridge] when the admin clicks an item.
 * Pushed back via setData("dashboardState", json) when navigating back.
 * The Vue composable restores these values on mount.
 */
data class DashboardState(
    val searchText: String = "",
    val typeFilter: String = "All",
    val modFilter: String = "All",
    val qualityFilter: String = "All",
    val slotFilter: String = "All",
    val statusFilter: String = "All",
    val viewMode: String = "grid",
    val sortColumn: String = "name",
    val sortAscending: Boolean = true,
    // Advanced stat filter — preserved across Dashboard→Editor→Dashboard navigation
    val statFilter: String = "none",
    val statOperator: String = ">=",
    val statValue: String = ""
)

// ── Serialization Helpers ───────────────────────────────────────────────

/**
 * Builds a minimal error payload for when item lookup or scanning fails.
 *
 * Shared by the payload assembler (when a build fails) and the editor bridge's
 * reset/resetField error paths. Pure — only literal map construction.
 */
fun buildErrorPayload(itemId: String, error: String): Map<String, Any?> {
    return mapOf(
        "itemId" to itemId,
        "itemName" to itemId,
        "itemMod" to "Unknown",
        "itemType" to null,
        "hasOverride" to false,
        "fields" to emptyList<Any>(),
        "originalValues" to emptyMap<String, Any>(),
        "tabs" to listOf("General"),
        "recipeData" to null,
        // Fail-closed: an errored item is not editable. Matches the UI's
        // default for a missing `permissions` block.
        "permissions" to mapOf(
            "canEditStats" to false,
            "canEditRecipes" to false,
            "canEditGeneral" to false,
            "canReset" to false
        ),
        "error" to error
    )
}

/**
 * Converts a [FieldDefinition] (from CodecScanner) to a [SerializedFieldDefinition]
 * (for the Vue UI), enriching it with the original value and effect description.
 *
 * @param field The scanned field definition
 * @param originalValue The pre-override value from OriginalsCache (null if never overridden)
 * @return The serialized field ready for JSON
 */
fun serializeField(
    field: FieldDefinition,
    originalValue: Any?
): SerializedFieldDefinition {
    // Compute effect description for numeric fields
    val effectDescription = computeEffectDescription(field)

    // Infer maxDecimals from value type if not set in constraints
    val effectiveMaxDecimals = field.constraints.maxDecimals ?: when (field.valueType) {
        ValueType.INTEGER -> 0
        else -> null
    }

    // Infer step from value type if not set in constraints
    val effectiveStep = field.constraints.step ?: when (field.valueType) {
        ValueType.INTEGER -> 1.0
        else -> null
    }

    return SerializedFieldDefinition(
        id = field.id,
        displayName = field.displayName,
        category = field.category,
        valueType = field.valueType.name,
        currentValue = field.currentValue,
        calculationType = field.calculationType,
        originalValue = originalValue,
        min = field.constraints.min,
        max = field.constraints.max,
        step = effectiveStep,
        maxDecimals = effectiveMaxDecimals,
        options = field.constraints.options,
        readOnly = field.state == FieldState.READ_ONLY,
        state = field.state.name,
        tooltip = field.tooltip,
        effectDescription = effectDescription,
        isNotSet = field.isNotSet,
        sourceMod = field.sourceMod
    )
}

/**
 * Computes a human-readable effect description for a field.
 *
 * Uses [ValueFormatter.formatEffectDescription] which handles:
 * - Additive stats: "+25 Health"
 * - Multiplicative stats: "12% Health"
 * - Damage: "Deals 42 Physical damage"
 * - Resistance: "12% Physical damage reduction"
 * - Speed/multiplier: "2x base speed"
 *
 * Returns null for fields where no description applies (booleans, strings,
 * non-stat numeric fields like MaxStack).
 */
private fun computeEffectDescription(field: FieldDefinition): String? {
    val value = field.currentValue as? Number ?: return null

    // General tab fields (MaxDurability, ItemLevel, MaxStack) are direct values,
    // not stat modifiers — no effect description applies.
    if (field.componentKey == null) return null

    // InteractionVars fields — custom descriptions per sub-field type.
    // Without this, the category (e.g., "Swing Left Damage") contains "Damage"
    // and triggers the generic "Deals X damage" branch for ALL sub-fields,
    // producing wrong descriptions for RandomPercentageModifier ("Deals 0.2 Damage Variance damage").
    if (field.componentKey == "InteractionVars") {
        val parts = field.id.split(".")
        return when {
            parts.size >= 4 && parts[2] == "BaseDamage" ->
                "Deals ${ValueFormatter.formatNumber(value.toDouble())} ${parts[3]} damage"
            field.id.endsWith(".RandomPercentageModifier") ->
                "${ValueFormatter.formatPercent(value.toDouble())} damage variance"
            else -> null // Class (STRING) — no numeric description
        }
    }

    return ValueFormatter.formatEffectDescription(
        value = value.toDouble(),
        calculationType = field.calculationType,
        statOrTypeName = field.displayName,
        fieldCategory = field.category
    )
}

// ── Recipe Data ─────────────────────────────────────────────────────────

/**
 * Complete recipe data for the editor Recipe tab.
 *
 * Populated by [RecipeScanner.scan] and included in [EditorPayload.recipeData].
 * Contains the current recipe state plus original values for "was:" indicators.
 *
 * ## Output Patterns
 *
 * Recipes use one of three mutually exclusive output patterns:
 * - "quantity" (695 recipes): [outputQuantity] copies of the parent item
 * - "array" (62 recipes): Multiple different items in [outputs]
 * - "primary" (1 recipe): Rare, co-exists with Output
 *
 * In v1, only [outputQuantity] is editable. Output arrays are read-only.
 */
data class RecipeData(
    /** Recipe ID (e.g., "Armor_Iron_Chest_Recipe_Generated_0"). */
    val recipeId: String,
    /** Input materials. Typically 1-6 entries. */
    val inputs: List<RecipeInputData>,
    /** Which output pattern: "quantity", "array", or "primary". */
    val outputPattern: String,
    /** For "quantity" pattern: how many of the parent item are produced. Editable. */
    val outputQuantity: Int?,
    /** For "array"/"primary" patterns: list of output items. Read-only in v1. */
    val outputs: List<RecipeOutputData>,
    /** Crafting time in seconds. 0 = instant (structural recipes). */
    val timeSeconds: Double,
    /** Whether the player must learn this recipe first. */
    val knowledgeRequired: Boolean,
    /** Required memories level (>= 1). */
    val requiredMemoriesLevel: Int,
    /** Bench requirements. Typically 1-2 entries. */
    val benchRequirements: List<RecipeBenchData>,
    /** Whether this recipe has active ItemForge overrides. */
    val hasOverride: Boolean,
    // ── Original values for "was:" indicators (null if never overridden) ──
    val originalInputs: List<RecipeInputData>?,
    val originalOutputQuantity: Int?,
    val originalTimeSeconds: Double?,
    val originalKnowledgeRequired: Boolean?,
    val originalRequiredMemoriesLevel: Int?,
    val originalBenchRequirements: List<RecipeBenchData>?,
    /** Bench registry for dropdown population. Attached by EditorBridge. */
    val benchRegistry: Map<String, me.itemforge.metadata.BenchInfo>,
    /**
     * True when this item has NO recipe asset yet and the payload is a blank,
     * creatable template (output pre-bound to this item, empty inputs/benches).
     * The editor renders the same form but treats the first save as a CREATE:
     * it registers a brand-new CraftingRecipe asset. False for existing recipes.
     */
    val isNew: Boolean = false
)

/**
 * A single input material in a recipe.
 *
 * Either [itemId] or [resourceTypeId] is set (mutually exclusive).
 * [displayName] is resolved server-side for immediate display.
 */
data class RecipeInputData(
    /** Index in the Input array (0-based). Used as identity key. */
    val index: Int,
    /** Specific item ID (e.g., "Ingredient_Bar_Iron"). null if using resourceTypeId. */
    val itemId: String?,
    /** Resource type wildcard (e.g., "Wood_All", "Fuel"). null if using itemId. */
    val resourceTypeId: String?,
    /** Amount required. Always >= 1. */
    val quantity: Int,
    /** Resolved display name (from ItemNameResolver or formatted ResourceTypeId). */
    val displayName: String
)

/**
 * An output item in a recipe (read-only in v1).
 *
 * Only populated for "array" pattern recipes (salvage, byproduct).
 */
data class RecipeOutputData(
    /** Output item ID. */
    val itemId: String,
    /** Quantity produced. */
    val quantity: Int,
    /** Resolved display name. */
    val displayName: String
)

/**
 * A single bench requirement entry in a recipe.
 *
 * Specifies where this recipe can be crafted: which bench type, which bench,
 * which panel (category), and what tier level is required.
 */
data class RecipeBenchData(
    /** Index in the BenchRequirement array (0-based). */
    val index: Int,
    /** Bench type: "Crafting", "Processing", "DiagramCrafting", "StructuralCrafting". */
    val type: String,
    /** Bench ID (e.g., "Workbench", "Weapon_Bench"). */
    val benchId: String,
    /** Human-readable bench name. */
    val benchDisplayName: String,
    /** Panel categories (e.g., ["Weapon_Sword"]). null for Processing benches. */
    val categories: List<String>?,
    /** Minimum bench tier level (0 = base). */
    val requiredTierLevel: Int
)

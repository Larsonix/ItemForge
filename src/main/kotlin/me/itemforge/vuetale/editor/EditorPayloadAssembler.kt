package me.itemforge.vuetale.editor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import me.itemforge.ItemForgePlugin
import me.itemforge.metadata.ItemNameResolver
import me.itemforge.scanner.CodecScanner
import me.itemforge.util.PermissionChecker
import me.itemforge.vuetale.EditorPayload
import me.itemforge.vuetale.EditorPermissions
import me.itemforge.vuetale.EditorSource
import me.itemforge.vuetale.RecipeData
import me.itemforge.vuetale.SerializedFieldDefinition
import me.itemforge.vuetale.buildErrorPayload
import me.itemforge.vuetale.serializeField
import java.util.UUID

/**
 * Assembles the [EditorPayload] for an item — the editor's read path (scanning, serialization,
 * tab computation, source/extension discovery, name/lore + per-item scope fields).
 *
 * Pure reads of plugin subsystems; the only shared-state write is `runtime.fieldCache[itemId]`
 * (codec metadata cached for the V8-thread save), done on the same thread as today (the game thread
 * during open, or the V8 thread on a reset/action re-push). Reuses the shared
 * [EditorRuntime.asyncExecutor] for the off-thread build so it stays serialized with saves/resets.
 */
class EditorPayloadAssembler(
    private val plugin: ItemForgePlugin,
    private val runtime: EditorRuntime
) {
    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    /**
     * Builds the [EditorPayload] for the given item.
     *
     * Called by [me.itemforge.vuetale.VuetaleIntegration.openEditor] for initial data push,
     * and by bridge methods (save, reset) to return updated state.
     *
     * Flow:
     * 1. Look up item in asset map
     * 2. Scan fields via [CodecScanner] (discovers ALL editable fields)
     * 3. Serialize all fields (General + Properties) for the Vue UI
     * 4. Look up original values from [OriginalsCache] for "was: X" indicators
     * 5. Compute dynamic tabs based on which components the item has
     * 6. Build [EditorPayload] and serialize to JSON
     *
     * @param itemId The item's asset ID
     * @param playerUuid The viewing player's UUID — used to compute per-tab
     *        edit permissions embedded in the payload.
     * @return JSON string of [EditorPayload]
     */
    fun buildEditorPayload(itemId: String, playerUuid: UUID): String {
        val item = Item.getAssetMap().getAsset(itemId)
        if (item == null) {
            logger.atWarning().log("EditorBridge: Item '%s' not found", itemId)
            return gson.toJson(buildErrorPayload(itemId, "Item not found"))
        }

        return try {
            val payload = buildPayloadForItem(itemId, item, playerUuid)
            gson.toJson(payload)
        } catch (e: Exception) {
            logger.atSevere().withCause(e).log("EditorBridge: Failed to build payload for '%s'", itemId)
            gson.toJson(buildErrorPayload(itemId, "Failed to scan item: ${e.message}"))
        }
    }

    /**
     * Builds the editor payload for [itemId] OFF the world/V8 thread (the single-item codec scan
     * is ~17ms, worse on deep InteractionVars/recipe chains) and invokes [onReady] with the JSON
     * on the worker thread. Callers open/navigate the page with this data already buffered, so the
     * editor mounts in one render (no empty->full re-render — the Hytale client can crash on that).
     *
     * Safe off-thread: the scan uses `ExtraInfo.THREAD_LOCAL` (per-thread default) and read-only
     * asset lookups — the same guarantee that lets [me.itemforge.vuetale.DashboardBridge] encode off
     * the tick. The `fieldCache` write happens here and is consumed by V8 saves long after this ~17ms
     * build, so it is always ready in time. Reuses [EditorRuntime.asyncExecutor] (serialized with
     * saves, no contention).
     */
    fun buildEditorPayloadAsync(itemId: String, playerUuid: UUID, onReady: (String) -> Unit) {
        runtime.asyncExecutor.execute {
            try {
                onReady(buildEditorPayload(itemId, playerUuid))
            } catch (e: Throwable) {
                logger.atSevere().withCause(e).log("async editor payload build failed for '%s'", itemId)
            }
        }
    }

    /**
     * Builds the [EditorPayload] data structure for an item.
     * Separated from JSON serialization for testability.
     */
    private fun buildPayloadForItem(itemId: String, item: Item, playerUuid: UUID): EditorPayload {
        val codecScanner = plugin.codecScanner
        val configManager = plugin.configManager
        val originalsCache = plugin.originalsCache
        val modSourceTracker = plugin.modSourceTracker
        val tagCache = plugin.tagCache

        // Scan all editable fields (CodecScanner discovers everything via codec introspection)
        val overriddenFieldIds = configManager.getOverriddenFieldIds(itemId)
        val allFields = codecScanner.scan(item, overriddenFieldIds)

        // Cache field definitions for executeSave() — avoids re-scanning on V8 thread.
        // This runs on the game thread (called from openEditor via world.execute),
        // so the heavy scan happens where it doesn't block the V8 event pipeline.
        runtime.fieldCache[itemId] = allFields.associateBy { it.id }

        // Serialize ALL fields — General + Properties. The Vue UI filters by
        // tab (category === "General" vs componentKey != null) client-side.
        val originalBson = originalsCache.get(itemId)
        val originalValues = mutableMapOf<String, Any?>()
        val serializedFields = allFields.map { field ->
            val originalValue = BsonOriginalsLookup.forField(field, originalBson)
            if (originalValue != null) {
                originalValues[field.id] = originalValue
            }
            serializeField(field, originalValue)
        }

        // ── Per-item ("This Item") scope capability ──────────────
        // Inspect mode caches a StackEditContext (the held stack, read on the world thread). When
        // present for THIS item, per-instance editing is available: annotate each codec field with
        // its Local-scope capability + current instance value, and synthesize the instance-only
        // fields (Quantity / Durability). buildPayloadForItem can re-run on the V8 thread (reset
        // re-push) — it only READS the cached context here, never a live ItemStack.
        val stackSession = runtime.stackSessions[playerUuid]
        val localScopeAvailable = stackSession != null && stackSession.baseItemId == itemId
        val capFields = if (localScopeAvailable)
            serializedFields.map { me.itemforge.local.LocalScopeFields.withLocalCapability(it, stackSession) }
        else serializedFields
        val instanceFields = if (localScopeAvailable)
            me.itemforge.local.LocalScopeFields.buildInstanceFields(stackSession)
        else emptyList()
        // Combat tier: one synthetic per-cause percentage knob per damage type the weapon deals
        // (e.g. "Physical (%)"). Per-item damage can only be a per-cause scale, so this REPLACES the
        // per-attack BaseDamage rows in local mode (see LocalScopeFields.buildDamageFields).
        val damagePctFields = if (localScopeAvailable) {
            val causes = capFields.asSequence()
                .filter { me.itemforge.local.LocalScopeFields.isDamageField(it.id) }
                .map { me.itemforge.local.LocalScopeFields.causeOf(it.id) }
                .distinct().toList()
            if (causes.isEmpty()) emptyList()
            else me.itemforge.local.LocalScopeFields.buildDamageFields(causes, stackSession)
        } else emptyList()
        // Combat tier: one synthetic per-cause resistance knob per damage type this armor piece
        // resists (set resistances only, so the knob set stays bounded). REPLACES the per-cause asset
        // DamageResistance rows in local mode (see LocalScopeFields.buildDefenseFields).
        val defenseFields = if (localScopeAvailable) {
            val causes = capFields.asSequence()
                .filter { me.itemforge.local.LocalScopeFields.isResistanceField(it.id) && !it.isNotSet }
                .map { me.itemforge.local.LocalScopeFields.causeOfResistance(it.id) }
                .distinct().toList()
            if (causes.isEmpty()) emptyList()
            else me.itemforge.local.LocalScopeFields.buildDefenseFields(causes, stackSession)
        } else emptyList()
        // Attribute tier: one synthetic per-stat bonus knob per EntityStat the armor grants
        // (Armor.StatModifiers.*, set only). REPLACES the per-stat asset rows in local mode and is
        // applied while worn by LocalStatSystem (see LocalScopeFields.buildStatFields).
        val statBonusFields = if (localScopeAvailable) {
            val stats = capFields.asSequence()
                .filter { me.itemforge.local.LocalScopeFields.isStatModifierField(it.id) && !it.isNotSet }
                .map { me.itemforge.local.LocalScopeFields.statIdOfModifier(it.id) }
                .distinct().toList()
            if (stats.isEmpty()) emptyList()
            else me.itemforge.local.LocalScopeFields.buildStatFields(stats, stackSession)
        } else emptyList()

        // Dynamic tabs based on what this item has (UX_DESIGN.md §5.3).
        // Properties tab: non-Armor, non-InteractionVars component fields (weapon, tool, etc.)
        //   + Armor equipment fields (ArmorSlot — category "Equipment", not defense stats).
        // Defense tab: Armor defense stats (StatModifiers, DamageResistance, etc.)
        // Damage tab: InteractionVars (weapon attack damage types).
        // General tab: always shown.
        // Tab order: Properties first, then Defense, then Damage, then General.
        val tabs = mutableListOf<String>()
        val hasProperties = allFields.any {
            it.componentKey != null && it.componentKey != "InteractionVars" && it.componentKey != "Armor"
        } || allFields.any {
            it.componentKey == "Armor" && it.category == CodecScanner.CATEGORY_EQUIPMENT
        }
        val hasDefense = allFields.any {
            it.componentKey == "Armor" && it.category != CodecScanner.CATEGORY_EQUIPMENT
        }
        val hasDamage = allFields.any { it.componentKey == "InteractionVars" }
        if (hasProperties) tabs.add("Properties")
        if (hasDefense) tabs.add("Defense")
        if (hasDamage) tabs.add("Damage")

        // Recipe tab: shown when the item has a craftable recipe
        val recipeData = buildRecipeData(itemId, item)
        if (recipeData != null) tabs.add("Recipe")

        tabs.add("General")

        // Editor extensions. Any registered EditorExtension that manages this item
        // id contributes a selectable source + its declarative panel. Global per item id and
        // available from every entry point (this builds the same payload for dashboard,
        // command, and inspect). Each extension call is exception-isolated (a throwing/empty
        // extension is simply omitted). buildPanel is a read — extensions are contracted to be
        // thread-safe here, since this can run on the world or V8 thread (reset/action re-push).
        val editorSources = mutableListOf<EditorSource>()
        val extensionPanelsMap = mutableMapOf<String, List<me.itemforge.provider.EditorComponent>>()
        for (extension in plugin.extensionRegistry.findManaging(item)) {
            val extId = try { extension.getExtensionId() } catch (e: Exception) { continue }
            val panel = try {
                extension.buildPanel(item)
            } catch (e: Exception) {
                logger.atWarning().withCause(e)
                    .log("EditorBridge: extension '%s'.buildPanel() threw — omitting its source", extId)
                continue
            }
            if (panel.isEmpty()) continue
            val name = try { extension.getDisplayName() } catch (e: Exception) { extId }
            editorSources.add(EditorSource(extId, name))
            extensionPanelsMap[extId] = panel
        }

        // Auto-detected mod stat sources. Any mod that registered an EntityStatType present
        // on this item (as a set value OR an addable "Not set" slot) becomes a dropdown entry,
        // surfaced by name alongside API extensions — zero per-mod code. Selecting one focuses
        // the editor on that mod's stats; they still save to the base item (StatModifiers are
        // base overrides), so the UI routes a "MOD:" source through the BASE save/reset path.
        // Sorted for stable dropdown order across opens.
        val modStatSources = capFields
            .mapNotNull { it.sourceMod }
            .distinct()
            .sorted()
        for (mod in modStatSources) {
            editorSources.add(EditorSource("$MOD_SOURCE_PREFIX$mod", mod))
        }

        // Per-stack metadata sources. When this editor was opened by
        // inspecting a held item, [setStackEdit] cached a session holding the live stack's
        // metadata — already read + serialized on the world thread (the only place a concrete
        // ItemStack exists), so we ONLY READ the cache here. This is critical: buildPayloadForItem
        // can re-run on the V8 thread (reset/action re-push), where reading a live ItemStack is
        // unsafe. Each metadata namespace (e.g. "SocketReforge") is a "STACK:<ns>" source whose
        // fields are appended to the same field list, tagged with sourceMod so the Vue editor
        // groups + routes them exactly like auto-detected MOD: stat sources.
        val stackFields: List<SerializedFieldDefinition> =
            if (stackSession != null && stackSession.baseItemId == itemId && stackSession.hasSources()) {
                editorSources.addAll(stackSession.sources)
                stackSession.fields.values.flatten()
            } else emptyList()

        if (editorSources.isNotEmpty()) {
            editorSources.add(0, EditorSource("BASE", "Base Item"))
        }

        // Split the codec-scanned fields into what's actually SET on the item
        // (rendered inline) vs what the item COULD have but doesn't (the "addable" universe,
        // fed to the "+ Add a stat" picker and mounted only on demand). The addable set is
        // dominated by the registry-driven `StatModifiers` "Not set" entries, which scale with
        // installed mods — pre-rendering them is what made the editor take ~20s to open on heavy
        // modpacks. A field is addable when it has no value AND no override (`isNotSet` and not
        // MODIFIED); an overridden-but-empty slot stays inline so the admin sees their override.
        val inlineFields = ArrayList<SerializedFieldDefinition>(capFields.size)
        val addableFields = ArrayList<SerializedFieldDefinition>()
        for (f in capFields) {
            if (f.isNotSet && f.state != me.itemforge.scanner.FieldState.MODIFIED.name) {
                addableFields.add(f)
            } else {
                inlineFields.add(f)
            }
        }
        // Synthetic per-instance fields (Quantity / Durability) are always "set" — render inline.
        // They carry globalCapable=false so the UI hides them in Global scope and shows them only
        // when editing "This Item".
        if (instanceFields.isNotEmpty()) inlineFields.addAll(instanceFields)
        if (damagePctFields.isNotEmpty()) inlineFields.addAll(damagePctFields)
        if (defenseFields.isNotEmpty()) inlineFields.addAll(defenseFields)
        if (statBonusFields.isNotEmpty()) inlineFields.addAll(statBonusFields)

        // Per-stack fields ride the same inline `fields` list as codec/asset fields; the Vue editor
        // filters them by their STACK: source id (they carry sourceMod = the namespace). They are a
        // small, bounded per-item metadata set (a single namespace), so they are never split into
        // the addable picker — they always render inline.
        val allInlineFields =
            if (stackFields.isEmpty()) inlineFields else inlineFields + stackFields

        // Inline field count drives client mount time; addable fields are mounted on demand
        // behind the picker (not pre-rendered), so this ratio bounds the editor's open cost.
        logger.atFine().log(
            "[TIMING] editor payload '%s': %d inline fields rendered, %d addable behind picker",
            itemId, allInlineFields.size, addableFields.size
        )

        // Custom Name + Lore (the last pre-release feature) — editable in BOTH scopes, rendered
        // pinned in the header (not a tab). Global current = the active i18n override or the stock
        // text; local current = this held stack's ItemDisplay (or null → inherits the global text).
        // Built only when the viewer can edit General; otherwise null → the header shows a static name.
        val canEditGen = PermissionChecker.canEditGeneral(playerUuid)
        val nameField = if (canEditGen)
            me.itemforge.local.LocalScopeFields.buildNameField(
                globalText = plugin.translationOverrides.currentName(itemId, item),
                localText = if (localScopeAvailable) stackSession.customName else null,
                localAvailable = localScopeAvailable
            ) else null
        val loreField = if (canEditGen)
            me.itemforge.local.LocalScopeFields.buildLoreField(
                globalText = plugin.translationOverrides.currentLore(itemId, item),
                localText = if (localScopeAvailable) stackSession.customLore else null,
                localAvailable = localScopeAvailable
            ) else null

        return EditorPayload(
            itemId = itemId,
            itemName = ItemNameResolver.resolve(item),
            itemMod = modSourceTracker.getModName(itemId),
            itemType = tagCache.getItemType(item),
            hasOverride = configManager.hasItemOverride(itemId),
            fields = allInlineFields,
            addableFields = addableFields,
            originalValues = originalValues,
            tabs = tabs,
            recipeData = recipeData,
            permissions = EditorPermissions(
                canEditStats = PermissionChecker.canEditStats(playerUuid),
                canEditRecipes = PermissionChecker.canEditRecipes(playerUuid),
                canEditGeneral = PermissionChecker.canEditGeneral(playerUuid),
                canReset = PermissionChecker.canReset(playerUuid)
            ),
            editorSources = editorSources,
            extensionPanels = extensionPanelsMap,
            localScopeAvailable = localScopeAvailable,
            nameField = nameField,
            loreField = loreField
        )
    }

    /**
     * Builds recipe data for an item's generated recipe.
     *
     * Recipe IDs follow the pattern "{ItemId}_Recipe_Generated_0"
     * (from [CraftingRecipe.generateIdFromItemRecipe]).
     *
     * @return RecipeData if the item has a recipe, null otherwise
     */
    private fun buildRecipeData(itemId: String, item: Item): RecipeData? {
        val recipeId = CraftingRecipe.generateIdFromItemRecipe(item, 0)
        val recipe = CraftingRecipe.getAssetMap().getAsset(recipeId)

        // No recipe asset → offer a blank, creatable recipe. The crafting system's source
        // of truth is the CraftingRecipe asset map, NOT the item's RecipesToGenerate field
        // (verified: CraftingPlugin.onItemAssetLoad only READS that field once at item-load to
        // seed the map). So registering a fresh recipe asset for an item that never had one
        // makes it craftable — the identical loadAssets pipeline we use to edit recipes.
        // Returning a non-null template here is what makes the Recipe tab appear for these items.
        if (recipe == null) {
            return buildBlankRecipeTemplate(recipeId)
        }

        val hasOverride = plugin.configManager.recipeOverrides.hasOverride(recipeId)
        val originalBson = plugin.recipeOriginalsCache.get(recipeId)

        // Defense-in-depth: a recipe-scan failure must never prevent the whole editor
        // from opening. Inspect mode lets admins open the editor for ANY held item,
        // including ones with recipe shapes we haven't seen — degrade gracefully to
        // "no Recipe tab" rather than failing buildEditorPayload entirely. (We deliberately
        // do NOT offer a creatable template here: the asset exists, so a blank "create" form
        // would risk overwriting a real recipe we simply couldn't read.)
        return try {
            plugin.recipeScanner.scan(recipe, hasOverride, originalBson).copy(
                benchRegistry = plugin.benchRegistry.toPayload()
            )
        } catch (e: Exception) {
            logger.atWarning().withCause(e).log(
                "EditorBridge: recipe scan failed for '%s' (recipe '%s') — opening editor without the Recipe tab",
                itemId, recipeId
            )
            null
        }
    }

    /**
     * Builds a blank, creatable [RecipeData] for an item that has no recipe asset.
     *
     * Mirrors the shape of a vanilla generated recipe (see Item#processConfig): the output
     * is pre-bound to this item at quantity 1 via the "quantity" pattern. Inputs and benches
     * start empty — the admin adds at least one input (required by the codec's nonEmptyArray
     * validator) and, optionally, a bench (no bench → uncraftable until one is added) before
     * the first save creates the recipe. [isNew] = true tells the editor to render the
     * create affordance and route the save through the create path.
     */
    private fun buildBlankRecipeTemplate(recipeId: String): RecipeData = RecipeData(
        recipeId = recipeId,
        inputs = emptyList(),
        outputPattern = "quantity",
        outputQuantity = 1,
        outputs = emptyList(),
        timeSeconds = 0.0,
        knowledgeRequired = false,
        requiredMemoriesLevel = 1,
        benchRequirements = emptyList(),
        hasOverride = false,
        originalInputs = null,
        originalOutputQuantity = null,
        originalTimeSeconds = null,
        originalKnowledgeRequired = null,
        originalRequiredMemoriesLevel = null,
        originalBenchRequirements = null,
        benchRegistry = plugin.benchRegistry.toPayload(),
        isNew = true
    )

    companion object {
        /**
         * Prefix for auto-detected mod stat-source ids in [EditorPayload.editorSources]
         * (e.g. "MOD:Hexcode"). Distinguishes them from "BASE" and from API extension ids
         * so the UI can route their save/reset through the base-item path and render a focused
         * stat view. Must match the `MOD:` check in Editor.vue.
         */
        const val MOD_SOURCE_PREFIX = "MOD:"
    }
}

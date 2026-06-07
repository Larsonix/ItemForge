package me.itemforge.provider

import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata
import com.hypixel.hytale.server.core.inventory.ItemStack
import me.itemforge.util.BsonHelper
import me.itemforge.vuetale.EditorSource
import me.itemforge.vuetale.SerializedFieldDefinition
import org.bson.BsonType
import org.bson.BsonValue

/**
 * Generic, mod-agnostic reader for a held [ItemStack]'s **per-instance metadata**.
 *
 * Hytale stores per-stack data (other mods' sockets/essences/rolled-gear stats, etc.) as a raw
 * `BsonDocument` on the `ItemStack` — there is no engine registry of which keys exist or what
 * they mean (verified: `ItemStack` exposes only `getMetadata()` → raw doc; no key/schema
 * accessor). So instead of hardcoding any mod, this reader simply **enumerates whatever keys are
 * actually present** and groups them by their namespace prefix (`SocketReforge.Socket.Max` →
 * namespace `SocketReforge`). Each namespace becomes one [EditorSource] in the editor's existing
 * source dropdown — the same dropdown that lists BASE, auto-detected `MOD:` stat sources, and
 * API extension panels.
 *
 * This is **tier-2 "live observation"** of the key-discovery union:
 * it reports keys that are present with their true BSON types. Tier-0 (codec introspection) and
 * tier-1 (JAR constant-harvest) feed *additional* "Not set" rows for keys the item *could* carry
 * but doesn't yet — layered in a later step; this reader is the authoritative source for what is
 * currently on the stack.
 *
 * Threading: pure read of the (cloned) metadata document — no SDK state mutation — but it is only
 * ever called from the world thread at inspect-open (where the live stack exists), and its result
 * is cached in a [StackEditContext] so the editor never re-reads a live stack off-thread.
 *
 * No correctness gatekeeping (deliberate): every present key is surfaced verbatim,
 * including a mod's internal bookkeeping. Admins own validity; ItemForge only guarantees a
 * byte-faithful read/write mechanism.
 */
object MetadataStackReader {

    /**
     * Reads [held] into a [StackEditContext] for an Inspect-Mode edit session.
     *
     * ALWAYS returns a context (never null) — even on an item with no per-stack metadata — because
     * the context now ALSO carries the held stack's engine-native per-instance scalars
     * (quantity / durability / maxDurability). Those enable the "This Item"
     * free-tier editing on ANY held item, independent of whether any mod wrote metadata. When the
     * stack DOES carry metadata, each namespace additionally becomes a `STACK:` source exactly as
     * before. A returned context with no [StackEditContext.hasSources] simply means
     * "no mod metadata, but per-instance base editing is available."
     *
     * @param baseItemId the held stack's item id (titles the editor, matches the session)
     */
    @Suppress("DEPRECATION") // getMetadata() is the only accessor that enumerates ALL keys;
    // the KeyedCodec accessors require knowing the key+codec ahead of time, which is exactly
    // what a generic reader cannot assume. Reading the cloned document is safe (no mutation).
    fun read(baseItemId: String, held: ItemStack): StackEditContext {
        // The instance scalars are captured for EVERY inspect open — they are the keystone of
        // per-item base editing and must be snapshotted here on the world thread (the only place a
        // concrete ItemStack exists), never re-read off-thread during payload assembly.
        val quantity = held.quantity
        val durability = held.durability
        val maxDurability = held.maxDurability

        val metadata = held.metadata
        if (metadata == null || metadata.isEmpty()) {
            return StackEditContext(
                baseItemId, emptyList(), emptyMap(), emptyMap(),
                quantity = quantity, durability = durability, maxDurability = maxDurability
            )
        }

        // ItemForge's OWN per-item overrides (combat/attribute tiers) live under the "ItemForge"
        // metadata key — parse them for the editor's display, and EXCLUDE the key from the mod
        // namespace grouping below so it never shows as a foreign "STACK:" source.
        val damageMultipliers = parseForgeSub(metadata, me.itemforge.local.LocalScopeFields.DMG_SUBKEY)
        val damageResist = parseForgeSub(metadata, me.itemforge.local.LocalScopeFields.DEF_SUBKEY)
        val statBonuses = parseForgeSub(metadata, me.itemforge.local.LocalScopeFields.STAT_SUBKEY)

        // The engine's own per-instance custom name/lore lives under the "ItemDisplay" metadata key
        // (a literal Message each). Read them for the "This item" Name/Lore fields, and (below)
        // exclude the key from the foreign-mod `STACK:` grouping — it is first-class, not a mod source.
        val display = held.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC)
        val customName = display?.name?.rawText
        val customLore = display?.description?.rawText

        // namespace → ordered editable fields, preserving first-seen key order for stable UI.
        val grouped = LinkedHashMap<String, MutableList<SerializedFieldDefinition>>()

        for ((key, value) in metadata) {
            val namespace = namespaceOf(key)
            if (namespace == me.itemforge.local.LocalScopeFields.METADATA_KEY) continue
            if (namespace == ItemDisplayMetadata.KEY) continue // engine custom name/lore — first-class, not a mod source
            val field = serializeMetadataEntry(namespace, key, value)
            grouped.getOrPut(namespace) { mutableListOf() }.add(field)
        }

        val sources = mutableListOf<EditorSource>()
        val fields = mutableMapOf<String, List<SerializedFieldDefinition>>()
        val categories = mutableMapOf<String, List<String>>()

        // Namespaces sorted for a stable dropdown order; BASE is prepended by the payload
        // assembler (it already owns BASE ordering for MOD:/extension sources).
        for (namespace in grouped.keys.sorted()) {
            val sourceId = "${StackEditContext.STACK_PREFIX}$namespace"
            val nsFields = grouped.getValue(namespace)
            sources.add(EditorSource(sourceId, namespace))
            fields[sourceId] = nsFields
            categories[sourceId] = nsFields.map { it.category }.distinct()
        }

        return StackEditContext(
            baseItemId, sources, fields, categories,
            quantity = quantity, durability = durability, maxDurability = maxDurability,
            damageMultipliers = damageMultipliers, damageResist = damageResist, statBonuses = statBonuses,
            customName = customName, customLore = customLore
        )
    }

    /**
     * Parses one of ItemForge's per-item override sub-documents from a held stack's metadata —
     * `ItemForge.<subKey>` → `{ <DamageCause id>: <number> }` (e.g. `dmg` = multipliers, `def` =
     * resistance fractions). Returns empty when absent/malformed (never throws).
     */
    private fun parseForgeSub(metadata: org.bson.BsonDocument, subKey: String): Map<String, Double> {
        val forge = metadata.get(me.itemforge.local.LocalScopeFields.METADATA_KEY)
            ?.takeIf { it.isDocument }?.asDocument() ?: return emptyMap()
        val sub = forge.get(subKey)?.takeIf { it.isDocument }?.asDocument() ?: return emptyMap()
        val map = LinkedHashMap<String, Double>(sub.size)
        for ((cause, v) in sub) {
            if (v.isNumber) map[cause] = v.asNumber().doubleValue()
        }
        return map
    }

    /**
     * The namespace prefix of a metadata key — the segment before the first `.`
     * (`SocketReforge.Socket.Max` → `SocketReforge`). Keys with no dot are their own namespace
     * (e.g. engine `ToolData`, `BlockHolder`).
     */
    private fun namespaceOf(key: String): String {
        val dot = key.indexOf('.')
        return if (dot <= 0) key else key.substring(0, dot)
    }

    /**
     * Maps one top-level metadata entry to the editor's existing field-row contract
     * ([SerializedFieldDefinition]), reusing [BsonHelper] for type/value conversion so per-stack
     * fields render through the exact same `FieldEditor` UI as codec-scanned asset fields.
     *
     * Scalar values (number / bool / string) become normal editable fields. Compound values
     * (arrays, nested documents) are surfaced **read-only** in v1 with a compact preview — a
     * dedicated array editor is a later step; showing them read-only keeps the data
     * visible and faithful without risking a malformed write.
     */
    private fun serializeMetadataEntry(
        namespace: String,
        key: String,
        value: BsonValue
    ): SerializedFieldDefinition {
        val valueType = BsonHelper.bsonToValueType(value)

        if (valueType != null) {
            // Scalar — fully editable.
            val current = BsonHelper.bsonToKotlin(value)
            return SerializedFieldDefinition(
                id = key,
                displayName = displayNameOf(namespace, key),
                category = categoryOf(namespace, key),
                valueType = valueType.name,
                currentValue = current,
                calculationType = null,
                originalValue = current,
                min = null,
                max = null,
                step = if (valueType.name == "INTEGER") 1.0 else null,
                maxDecimals = if (valueType.name == "INTEGER") 0 else null,
                options = null,
                readOnly = false,
                state = "DEFAULT",
                tooltip = "Per-item metadata key: $key",
                effectDescription = null,
                isNotSet = false,
                sourceMod = namespace
            )
        }

        // Compound (array / document) — read-only preview in v1.
        return SerializedFieldDefinition(
            id = key,
            displayName = displayNameOf(namespace, key),
            category = categoryOf(namespace, key),
            valueType = "STRING",
            currentValue = compoundPreview(value),
            calculationType = null,
            originalValue = compoundPreview(value),
            min = null,
            max = null,
            step = null,
            maxDecimals = null,
            options = null,
            readOnly = true,
            state = "READ_ONLY",
            tooltip = "Per-item metadata key: $key (${value.bsonType.name.lowercase()} - read-only in this version)",
            effectDescription = null,
            isNotSet = false,
            sourceMod = namespace
        )
    }

    /**
     * Human-ish label for a key: drop the namespace prefix, keep the remaining dotted path
     * (`SocketReforge.Socket.Max` → `Socket.Max`). Deliberately faithful to the real key so an
     * admin who knows the mod recognizes it; we don't invent prettier names that could mislead.
     */
    private fun displayNameOf(namespace: String, key: String): String {
        val prefix = "$namespace."
        return if (key.startsWith(prefix)) key.substring(prefix.length) else key
    }

    /**
     * Category heading within a namespace — the second path segment when present
     * (`SocketReforge.Socket.Max` → `Socket`; `SocketReforge.Lore.Socket.XP` → `Lore`), else
     * a generic bucket. Groups related keys under one heading in the source panel.
     */
    private fun categoryOf(namespace: String, key: String): String {
        val prefix = "$namespace."
        if (!key.startsWith(prefix)) return namespace
        val rest = key.substring(prefix.length)
        val dot = rest.indexOf('.')
        return if (dot <= 0) "General" else rest.substring(0, dot)
    }

    /** Compact, read-only preview of an array/document value for display. */
    private fun compoundPreview(value: BsonValue): String = when (value.bsonType) {
        BsonType.ARRAY -> {
            val arr = value.asArray()
            "[${arr.size} item${if (arr.size == 1) "" else "s"}]"
        }
        BsonType.DOCUMENT -> {
            val doc = value.asDocument()
            "{${doc.size} field${if (doc.size == 1) "" else "s"}}"
        }
        else -> value.bsonType.name.lowercase()
    }
}

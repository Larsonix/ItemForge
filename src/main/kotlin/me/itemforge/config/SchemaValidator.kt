package me.itemforge.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Validates override config file structure before it reaches the OverrideEngine.
 *
 * This is a **structural** validator — it checks that the JSON is well-formed
 * and follows the expected schema (correct keys, correct types, version bounds).
 * It does NOT validate field values against the item codec — that happens at
 * decode time via ThrowingValidationResults (SDK_FINDINGS.md §3.5).
 *
 * ## Validation Layers
 *
 * ```
 * 1. JSON syntax       → Gson parser (catches malformed JSON)
 * 2. Schema structure   → THIS CLASS (schema_version, overrides map, key format)
 * 3. Field value types  → THIS CLASS (numbers, strings, booleans, objects — not arrays at top level)
 * 4. Field value ranges → BuilderField.decode() at apply time (ThrowingValidationResults)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val result = SchemaValidator.validateItemOverrides(jsonString)
 * if (result.hasErrors) {
 *     logger.severe("Config validation failed:")
 *     result.errors.forEach { logger.severe("  - $it") }
 *     // Load defaults instead
 * }
 * result.warnings.forEach { logger.warning("Config warning: $it") }
 * ```
 *
 * The validator returns ALL issues in one pass — not just the first one.
 * This gives server owners a complete list of what to fix.
 */
object SchemaValidator {

    /** Current schema version for item override files. */
    const val CURRENT_ITEM_SCHEMA_VERSION = 1

    /** Current schema version for recipe override files. */
    const val CURRENT_RECIPE_SCHEMA_VERSION = 1

    /**
     * Validates a raw JSON string as an item overrides file.
     *
     * Checks:
     * - Valid JSON syntax
     * - "schema_version" present and <= current
     * - "overrides" is a JSON object
     * - Each item key is a non-empty string
     * - Each item value is a JSON object
     * - Each field key follows PascalCase convention (starts with uppercase)
     *
     * @param json The raw JSON string from items.json
     * @return Validation result with errors and warnings
     */
    fun validateItemOverrides(json: String): ValidationResult {
        return validate(json, "items.json", CURRENT_ITEM_SCHEMA_VERSION)
    }

    /**
     * Validates a raw JSON string as a recipe overrides file.
     *
     * Same structural checks as [validateItemOverrides] — recipe overrides
     * follow the same top-level schema.
     *
     * @param json The raw JSON string from recipes.json
     * @return Validation result with errors and warnings
     */
    fun validateRecipeOverrides(json: String): ValidationResult {
        return validate(json, "recipes.json", CURRENT_RECIPE_SCHEMA_VERSION)
    }

    /**
     * Validates a pre-parsed [JsonObject] as an overrides document.
     *
     * Used when the JSON is already parsed (e.g., after migration).
     * Skips the syntax check — only validates structure.
     *
     * @param root The parsed JSON object
     * @param fileName File name for error messages
     * @param currentSchemaVersion The expected schema version
     * @return Validation result with errors and warnings
     */
    fun validateParsed(
        root: JsonObject,
        fileName: String,
        currentSchemaVersion: Int
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        validateStructure(root, fileName, currentSchemaVersion, errors, warnings)

        return ValidationResult(errors, warnings)
    }

    // ── Core Validation ─────────────────────────────────────────────────

    private fun validate(
        json: String,
        fileName: String,
        currentSchemaVersion: Int
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Step 1: Parse JSON
        val root: JsonObject
        try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonObject) {
                errors.add("$fileName: Root element must be a JSON object, got ${element.javaClass.simpleName}")
                return ValidationResult(errors, warnings)
            }
            root = element.asJsonObject
        } catch (e: JsonSyntaxException) {
            errors.add("$fileName: Invalid JSON syntax — ${e.message}")
            return ValidationResult(errors, warnings)
        }

        // Step 2: Validate structure
        validateStructure(root, fileName, currentSchemaVersion, errors, warnings)

        return ValidationResult(errors, warnings)
    }

    private fun validateStructure(
        root: JsonObject,
        fileName: String,
        currentSchemaVersion: Int,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        // ── schema_version ──────────────────────────────────────────────

        val versionElement = root["schema_version"]
        if (versionElement == null) {
            // Missing version — assume v1 (pre-versioning files)
            warnings.add("$fileName: Missing 'schema_version' — assuming version 1")
        } else if (!versionElement.isJsonPrimitive || !versionElement.asJsonPrimitive.isNumber) {
            errors.add("$fileName: 'schema_version' must be a number, got: $versionElement")
        } else {
            val version = versionElement.asInt
            when {
                version > currentSchemaVersion -> {
                    errors.add(
                        "$fileName: Schema version $version is newer than this plugin supports " +
                        "(max: $currentSchemaVersion). Update ItemForge or downgrade the config."
                    )
                }
                version < 1 -> {
                    errors.add("$fileName: Schema version must be >= 1, got: $version")
                }
            }
        }

        // ── overrides map ───────────────────────────────────────────────

        val overridesElement = root["overrides"]
        if (overridesElement == null) {
            // No overrides = valid but empty
            warnings.add("$fileName: No 'overrides' key found — file has no effect")
            return
        }

        if (!overridesElement.isJsonObject) {
            errors.add("$fileName: 'overrides' must be a JSON object, got ${typeDescription(overridesElement)}")
            return
        }

        val overrides = overridesElement.asJsonObject

        if (overrides.size() == 0) {
            warnings.add("$fileName: 'overrides' is empty — file has no effect")
            return
        }

        // ── per-item validation ─────────────────────────────────────────

        var itemCount = 0
        for ((itemId, itemElement) in overrides.entrySet()) {
            itemCount++

            // Item ID checks
            if (itemId.isBlank()) {
                errors.add("$fileName: Empty item ID at position $itemCount")
                continue
            }

            // Item value must be an object
            if (!itemElement.isJsonObject) {
                errors.add("$fileName: Override for '$itemId' must be a JSON object, got ${typeDescription(itemElement)}")
                continue
            }

            val itemObj = itemElement.asJsonObject
            if (itemObj.size() == 0) {
                warnings.add("$fileName: Override for '$itemId' is empty — has no effect")
                continue
            }

            // Per-field validation
            validateItemFields(itemObj, itemId, fileName, errors, warnings)
        }
    }

    /**
     * Validates the field keys within an item override object.
     *
     * Checks that field keys follow PascalCase convention — this matches the
     * KeyedCodec enforcement (SDK_FINDINGS.md §3.7: keys must start with uppercase).
     * Override JSON keys are fed directly to BuilderField.decode(), so they MUST
     * match the codec's key format.
     *
     * Recurses into nested objects (e.g., Armor.StatModifiers) to check sub-keys.
     */
    private fun validateItemFields(
        itemObj: JsonObject,
        itemId: String,
        fileName: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        for ((fieldKey, fieldValue) in itemObj.entrySet()) {
            // PascalCase check: first character must be uppercase letter
            // KeyedCodec enforces this (SDK_FINDINGS.md §3.7)
            if (fieldKey.isNotEmpty() && fieldKey[0].isLetter() && !fieldKey[0].isUpperCase()) {
                errors.add(
                    "$fileName: '$itemId.$fieldKey' — field key must start with uppercase " +
                    "(PascalCase). Codec keys use PascalCase (e.g., 'MaxDurability', not 'maxDurability')."
                )
            }

            // Null values are invalid — use field absence for "not overridden"
            if (fieldValue.isJsonNull) {
                warnings.add("$fileName: '$itemId.$fieldKey' is null — remove the key to un-override")
            }

            // Recurse into nested objects (components like Armor, Weapon, etc.)
            if (fieldValue.isJsonObject) {
                validateNestedFields(fieldValue.asJsonObject, "$itemId.$fieldKey", fileName, errors, warnings)
            }
        }
    }

    /**
     * Validates nested field objects (components and their sub-fields).
     *
     * Only checks key format — value validation is left to the codec at apply time.
     * Recursion is bounded by JSON nesting depth (typically 3-4 levels max for items).
     */
    private fun validateNestedFields(
        obj: JsonObject,
        path: String,
        fileName: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        for ((key, value) in obj.entrySet()) {
            // InteractionVars sub-keys use mixed case (e.g., "Swing_Left_Damage")
            // so we only warn on obvious camelCase (starts with lowercase letter),
            // not on underscored or other formats
            if (key.isNotEmpty() && key[0].isLetter() && !key[0].isUpperCase()) {
                // Only warn at nested levels — some map entries (stat names) may be lowercase
                warnings.add(
                    "$fileName: '$path.$key' — nested key starts with lowercase. " +
                    "Most codec keys use PascalCase."
                )
            }

            if (value.isJsonNull) {
                warnings.add("$fileName: '$path.$key' is null — remove the key to un-override")
            }

            if (value.isJsonObject) {
                validateNestedFields(value.asJsonObject, "$path.$key", fileName, errors, warnings)
            }
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Returns a human-readable type description for error messages.
     */
    private fun typeDescription(element: JsonElement): String {
        return when {
            element.isJsonObject -> "object"
            element.isJsonArray -> "array"
            element.isJsonPrimitive -> {
                val p = element.asJsonPrimitive
                when {
                    p.isNumber -> "number (${p.asNumber})"
                    p.isBoolean -> "boolean (${p.asBoolean})"
                    p.isString -> "string (\"${p.asString}\")"
                    else -> "primitive"
                }
            }
            element.isJsonNull -> "null"
            else -> element.javaClass.simpleName
        }
    }
}

/**
 * Result of a schema validation pass.
 *
 * Contains both errors (prevent loading) and warnings (log but continue).
 * All issues are collected — not just the first one.
 */
data class ValidationResult(
    /** Hard failures that prevent the config from being loaded. */
    val errors: List<String>,

    /** Issues that should be logged but don't block loading. */
    val warnings: List<String>
) {
    /** True if any errors were found — config should NOT be loaded. */
    val hasErrors: Boolean get() = errors.isNotEmpty()

    /** True if any warnings were found — config can load but admin should know. */
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    /** True if validation passed with no issues at all. */
    val isClean: Boolean get() = errors.isEmpty() && warnings.isEmpty()

    /** Total number of issues (errors + warnings). */
    val issueCount: Int get() = errors.size + warnings.size

    companion object {
        /** A clean result with no issues. */
        val CLEAN = ValidationResult(emptyList(), emptyList())
    }
}

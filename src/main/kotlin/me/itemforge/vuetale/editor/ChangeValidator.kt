package me.itemforge.vuetale.editor

import com.hypixel.hytale.logger.HytaleLogger
import me.itemforge.scanner.FieldDefinition
import me.itemforge.scanner.FieldState
import me.itemforge.scanner.ValueType
import me.itemforge.vuetale.FieldValidationError

/**
 * Validates a map of field changes against each field's type and constraints.
 *
 * Pure — no plugin access, no threading, no side effects beyond a single warning log on an
 * unknown field. Operates entirely on the supplied `fieldMap` (codec metadata) + `changes`.
 */
object ChangeValidator {

    private val logger: HytaleLogger = HytaleLogger.forEnclosingClass()

    /**
     * All valid CalculationType values across ALL modifier codecs.
     *
     * Two distinct modifier systems in Hytale:
     * - StaticModifier (StatModifiers, DamageEnhancement, DamageClassEnhancement):
     *   CalculationType enum = { Additive, Multiplicative }
     * - ResistanceModifier (DamageResistance, and potentially KnockbackResistances):
     *   ResistanceCalculationType enum = { Flat, Percent }
     *
     * Source: StaticModifier.java:107-119, ResistanceModifier.java:80-83
     */
    private val VALID_CALC_TYPES = setOf("Additive", "Multiplicative", "Flat", "Percent")

    /**
     * Validates each field change against its type and constraints.
     *
     * Returns a list of validation errors (empty = all valid).
     * Uses FieldDefinition metadata from the field cache.
     *
     * For modifier fields (calculationType != null), the UI sends composite values:
     * `{"amount": 25.0, "calculationType": "Additive"}` — Gson deserializes as Map.
     * We validate the amount as a number and the calculationType as a known string.
     */
    fun validate(
        fieldMap: Map<String, FieldDefinition>,
        changes: Map<String, Any>
    ): List<FieldValidationError> {
        val errors = mutableListOf<FieldValidationError>()

        for ((fieldId, newValue) in changes) {
            val field = fieldMap[fieldId]
            if (field == null) {
                logger.atWarning().log(
                    "Validation: field '%s' not found in cache (cache has %d fields: %s)",
                    fieldId, fieldMap.size, fieldMap.keys.take(10).joinToString(", ")
                )
                errors.add(FieldValidationError(fieldId, "Unknown field"))
                continue
            }

            if (field.state == FieldState.READ_ONLY) {
                errors.add(FieldValidationError(fieldId, "Field is read-only"))
                continue
            }

            // Type validation: ensure the value matches the expected type
            when (field.valueType) {
                ValueType.DOUBLE, ValueType.INTEGER -> {
                    val numValue: Double

                    if (field.calculationType != null && newValue is Map<*, *>) {
                        // Modifier field: composite value {amount, calculationType}
                        val amount = newValue["amount"]
                        if (amount !is Number) {
                            errors.add(FieldValidationError(fieldId, "Modifier amount must be a number"))
                            continue
                        }
                        numValue = amount.toDouble()

                        val calcType = newValue["calculationType"]
                        if (calcType != null && calcType !is String) {
                            errors.add(FieldValidationError(fieldId, "CalculationType must be a string"))
                            continue
                        }
                        if (calcType is String && calcType !in VALID_CALC_TYPES) {
                            errors.add(FieldValidationError(fieldId, "'$calcType' is not a valid calculation type"))
                            continue
                        }
                    } else if (newValue is Number) {
                        // Simple numeric field (or modifier with just an amount)
                        numValue = newValue.toDouble()
                    } else {
                        errors.add(FieldValidationError(fieldId, "Expected a number, got ${newValue::class.simpleName}"))
                        continue
                    }

                    // Constraint validation (applies to both simple and modifier amounts)
                    val min = field.constraints.min
                    val max = field.constraints.max
                    if (min != null && numValue < min) {
                        errors.add(FieldValidationError(fieldId, "Value $numValue is below minimum $min"))
                    }
                    if (max != null && numValue > max) {
                        errors.add(FieldValidationError(fieldId, "Value $numValue exceeds maximum $max"))
                    }
                }
                ValueType.BOOLEAN -> {
                    if (newValue !is Boolean) {
                        errors.add(FieldValidationError(fieldId,
                            "Expected a boolean, got ${newValue::class.java.name} ($newValue)"))
                    }
                }
                ValueType.STRING -> {
                    if (newValue !is String) {
                        errors.add(FieldValidationError(fieldId,
                            "Expected a string, got ${newValue::class.java.name} ($newValue)"))
                    }
                    val options = field.constraints.options
                    if (options != null && newValue is String && newValue !in options) {
                        errors.add(FieldValidationError(fieldId, "'$newValue' is not a valid option"))
                    }
                }
            }
        }

        return errors
    }
}

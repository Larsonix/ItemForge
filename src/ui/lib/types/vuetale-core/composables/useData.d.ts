import { ComputedRef } from 'vue'

/**
 * Access a reactive data value pushed from the Kotlin/JVM side via
 * `playerUi.setData("key", value)`.
 *
 * The returned ComputedRef updates automatically whenever Kotlin pushes a new
 * value for the same key. If no value has been set yet, defaultValue is returned.
 */
export declare function useData<T>(key: string, defaultValue?: T): ComputedRef<T>

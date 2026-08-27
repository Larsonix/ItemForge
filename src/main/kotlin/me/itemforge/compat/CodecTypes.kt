package me.itemforge.compat

import com.hypixel.hytale.codec.Codec as HytaleCodec
import com.hypixel.hytale.codec.codecs.EnumCodec
import com.hypixel.hytale.codec.codecs.simple.BooleanCodec
import com.hypixel.hytale.codec.codecs.simple.ByteCodec
import com.hypixel.hytale.codec.codecs.simple.DoubleCodec
import com.hypixel.hytale.codec.codecs.simple.FloatCodec
import com.hypixel.hytale.codec.codecs.simple.IntegerCodec
import com.hypixel.hytale.codec.codecs.simple.LongCodec
import com.hypixel.hytale.codec.codecs.simple.NullableBooleanCodec
import com.hypixel.hytale.codec.codecs.simple.ShortCodec
import com.hypixel.hytale.codec.codecs.simple.StringCodec
import com.hypixel.hytale.logger.HytaleLogger
import me.itemforge.scanner.ValueType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps a Hytale leaf codec to the [ValueType] ItemForge edits it as.
 *
 * ## Why this class exists
 *
 * This is the single most dangerous piece of coupling in ItemForge, and the danger is that
 * it fails **silently**.
 *
 * The scanner needs a [ValueType] for fields that are absent from an item's encoded BSON —
 * the "not set" fields an admin can add. With no BSON value there is nothing to infer a type
 * from, so the only remaining signal is the field's own codec. The original implementation
 * compared that codec by **reference identity** against the singletons on Hytale's `Codec`
 * interface (`Codec.FLOAT`, `Codec.INTEGER`, …), and returned null on no match.
 *
 * Returning null means "compound type, skip". So if identity ever stops matching, every
 * "not set" field disappears from the editor and from the dashboard field catalog, and
 * **nothing reports it**: no exception, no log line, no failed build. The editor simply shows
 * fewer fields than it should, which looks like a design choice rather than a defect.
 *
 * Identity is a fair fast path — verified against `Codec.java` at 0.5.9 and 0.6.0-pre.9, those
 * really are shared `static final` instances. But the codec classes are public and
 * instantiable (`new FloatCodec()`), so nothing in the engine's contract *promises* a field
 * codec is the shared instance. One engine refactor that builds per-field codecs would break
 * identity everywhere at once.
 *
 * So resolution is layered, cheapest first, and every layer below the first is recorded so the
 * health report can say the fast path stopped working before a user notices missing fields.
 *
 * ## The layers
 *
 * 1. **Identity** against the `Codec` singletons. Current behaviour, one reference compare.
 * 2. **Type** (`is FloatCodec`). Survives the engine constructing fresh codec instances.
 * 3. **Class simple name**. Survives the codec classes moving package, which a type check
 *    cannot, because a moved class fails to resolve at compile time and an `is` check against
 *    the old one silently stops matching.
 * 4. **[EnumCodec]** — rendered as a string dropdown.
 *
 * Anything still unresolved is recorded in [unmappedCodecs] rather than silently dropped.
 * That set is the thing to read after a Hytale update: it names exactly which codec classes
 * appeared on real fields that ItemForge does not know how to edit.
 *
 * ## Fixed while writing this
 *
 * `Codec.NULLABLE_BOOLEAN` is a real singleton (`NullableBooleanCodec`) that the original
 * `when` never listed, so any field using it was invisible in the editor. It is handled here.
 */
object CodecTypes {

    private val logger = HytaleLogger.forEnclosingClass()

    /** How many resolutions each layer answered. Read by the health report / EngineProbe. */
    private val layerHits = Array(4) { AtomicLong() }

    /**
     * Codec classes seen on a real field that no layer could map, with an occurrence count.
     *
     * Non-empty is not automatically a defect — genuinely compound codecs (`ArrayCodec`,
     * `MapCodec`, `BuilderCodec`) land here by design and are correctly non-editable. It
     * matters when a *leaf* type shows up, which is what a Hytale update is likely to add.
     */
    private val unmapped = ConcurrentHashMap<String, AtomicLong>()

    /** Snapshot of per-layer resolution counts, index 0 = identity … index 3 = enum. */
    val layerCounts: List<Long> get() = layerHits.map { it.get() }

    /** Snapshot of unresolved codec class names → how often each was seen. */
    val unmappedCodecs: Map<String, Long> get() = unmapped.mapValues { it.value.get() }

    /** True when every resolution so far was answered by the identity fast path. */
    val identityFastPathIntact: Boolean
        get() = layerHits[1].get() == 0L && layerHits[2].get() == 0L

    /**
     * Infers the editable [ValueType] for a leaf codec, or null when the codec is compound
     * (`BuilderCodec`, `MapCodec`, `ArrayCodec`) and therefore not a single editable field.
     *
     * Never throws. A codec that cannot be classified is recorded and reported as null, which
     * preserves the caller's existing contract.
     */
    fun infer(childCodec: Any?): ValueType? {
        if (childCodec == null) return null

        identity(childCodec)?.let { layerHits[0].incrementAndGet(); return it }
        byType(childCodec)?.let { layerHits[1].incrementAndGet(); warnOnFallback(childCodec, "type"); return it }
        bySimpleName(childCodec)?.let { layerHits[2].incrementAndGet(); warnOnFallback(childCodec, "class-name"); return it }

        if (childCodec is EnumCodec<*>) {
            layerHits[3].incrementAndGet()
            return ValueType.STRING
        }

        unmapped.computeIfAbsent(childCodec.javaClass.name) { AtomicLong() }.incrementAndGet()
        return null
    }

    /** Layer 1 — reference identity against the `Codec` interface singletons. */
    private fun identity(c: Any): ValueType? = when (c) {
        HytaleCodec.FLOAT, HytaleCodec.DOUBLE -> ValueType.DOUBLE
        HytaleCodec.INTEGER, HytaleCodec.LONG, HytaleCodec.SHORT, HytaleCodec.BYTE -> ValueType.INTEGER
        HytaleCodec.BOOLEAN, HytaleCodec.NULLABLE_BOOLEAN -> ValueType.BOOLEAN
        HytaleCodec.STRING -> ValueType.STRING
        else -> null
    }

    /** Layer 2 — concrete codec type, so a freshly constructed instance still resolves. */
    private fun byType(c: Any): ValueType? = when (c) {
        is FloatCodec, is DoubleCodec -> ValueType.DOUBLE
        is IntegerCodec, is LongCodec, is ShortCodec, is ByteCodec -> ValueType.INTEGER
        is BooleanCodec, is NullableBooleanCodec -> ValueType.BOOLEAN
        is StringCodec -> ValueType.STRING
        else -> null
    }

    /**
     * Layer 3 — class simple name, the only layer that survives these classes being moved to
     * another package. Deliberately does not use the imported types, so it keeps working when
     * layers 1 and 2 have been silently invalidated by a relocation.
     */
    private fun bySimpleName(c: Any): ValueType? = when (c.javaClass.simpleName) {
        "FloatCodec", "DoubleCodec" -> ValueType.DOUBLE
        "IntegerCodec", "IntCodec", "LongCodec", "ShortCodec", "ByteCodec" -> ValueType.INTEGER
        "BooleanCodec", "NullableBooleanCodec" -> ValueType.BOOLEAN
        "StringCodec" -> ValueType.STRING
        else -> null
    }

    /**
     * Logs once per codec class when resolution falls past the identity fast path.
     *
     * Once per class, not per call: this runs inside the field-discovery loop, which executes
     * thousands of times per editor open. A per-call log would be a flood, and a flood is
     * ignored, which would defeat the point of noticing at all.
     */
    private val warned = ConcurrentHashMap.newKeySet<String>()
    private fun warnOnFallback(c: Any, layer: String) {
        val name = c.javaClass.name
        if (warned.add(name)) {
            logger.atWarning().log(
                "CodecTypes: %s resolved by %s fallback, not identity. The engine's codec " +
                    "singletons no longer match the codecs on real fields. ItemForge still works, " +
                    "but this is the early warning that a Hytale update moved the codec layer.",
                name, layer
            )
        }
    }

    /** Test hook: clears counters and the warn-once set. Not used in production paths. */
    fun resetDiagnostics() {
        layerHits.forEach { it.set(0) }
        unmapped.clear()
        warned.clear()
    }
}

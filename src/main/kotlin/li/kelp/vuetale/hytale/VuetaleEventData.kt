package li.kelp.vuetale.hytale

import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.codecs.simple.IntegerCodec
import com.hypixel.hytale.codec.codecs.simple.StringCodec

/**
 * Event payload sent from the Hytale client back to the server when a UI event fires.
 *
 * ── ITEMFORGE PATCH ──────────────────────────────────────────────────────────
 * This class lives in ItemForge's source (package `li.kelp.vuetale.hytale`) and
 * **overrides** the version bundled in `lib/Vuetale.jar`. The build excludes the
 * original `VuetaleEventData*.class` from the shadow jar (see build.gradle.kts) so
 * only this patched copy ships.
 *
 * Why: Hytale's `ItemGrid` reports clicks via a `SlotClicking` event whose payload
 * carries a `SlotIndex` field (which slot was clicked). Stock Vuetale only decodes
 * `RoutingKey` + `@Value`, silently dropping `SlotIndex`, so a `@slot-clicking`
 * handler can fire but never learn which item was clicked.
 *
 * The fix is intentionally surgical — ONLY this class changes; `EventBinding` and
 * `VuetaleUIPage` (still loaded from the jar) are untouched. We decode `SlotIndex`
 * and surface it through the existing [value] field, which stock
 * `EventBinding.invoke(value)` already forwards to the JS callback. So a Vue
 * `@slot-clicking="(i) => …"` receives the slot index as a string (parse with
 * `Number(i)`), with zero changes to the rest of Vuetale.
 *
 * Crash-safety: the client includes `SlotIndex` automatically for slot events — it
 * is NOT requested in the binding's EventData (verified against HyUI's
 * `ItemGridBuilder`, which binds only `Action`/`Target` yet still receives
 * `SlotIndex`). So we only DECODE it; we never request an element property that
 * doesn't exist (which is what triggers the client's "Could not gather property
 * value" crash).
 *
 * Fields
 * ------
 * - [routingKey] — Stable key `"<rawElementId>__<bindingTypeName>"` used to look up
 *   the Vue callback in `handleDataEvent`.
 * - [value]      — Element value for ValueChanged; empty for plain activations; the
 *   **slot index (as a string)** for ItemGrid slot events (see patch note above).
 * - [slotIndex]  — Decoded slot index, or -1 when the event carries none.
 */
class VuetaleEventData {
    var routingKey: String = ""
    var value: String = ""
    var slotIndex: Int = -1

    companion object {
        private val STRING = StringCodec()
        private val INT = IntegerCodec()

        @JvmField
        val CODEC: BuilderCodec<VuetaleEventData> =
            BuilderCodec.builder(VuetaleEventData::class.java) { VuetaleEventData() }
                .append(
                    KeyedCodec("RoutingKey", STRING),
                    { d, v: String? -> d.routingKey = v ?: "" },
                    { d -> d.routingKey }
                ).add()
                .append(
                    KeyedCodec("@Value", STRING),
                    { d, v: String? -> d.value = v ?: "" },
                    { d -> d.value }
                ).add()
                // Decoded last so it overrides `value` for slot events (where @Value is
                // absent). Absent for non-slot events → setter receives null → no-op,
                // leaving `value` as decoded from @Value.
                .append(
                    KeyedCodec("SlotIndex", INT),
                    { d, v: Int? -> if (v != null) { d.slotIndex = v; d.value = v.toString() } },
                    { d -> d.slotIndex }
                ).add()
                .build()
    }
}

package me.itemforge.compat

import com.hypixel.hytale.codec.Codec as HytaleCodec
import com.hypixel.hytale.codec.ExtraInfo
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.validation.validator.RangeValidator
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe
import com.hypixel.hytale.server.core.asset.type.item.config.Item
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType
import me.itemforge.scanner.ValueType

/** Outcome of a single engine-shape probe. */
enum class ProbeStatus {
    /** The assumption holds. */
    OK,

    /** The assumption no longer holds, but ItemForge has a working fallback. */
    DEGRADED,

    /** The assumption no longer holds and something the admin uses is broken. */
    FAILED
}

/**
 * One probe outcome. [detail] is written for a server admin reading a log at 2am, so it says
 * what broke and what the visible consequence is, not just which symbol moved.
 */
data class ProbeResult(
    val name: String,
    val status: ProbeStatus,
    val detail: String
)

/**
 * Field-discovery sample used by the yield probe. Supplied by the caller so this package does
 * not need to depend on the scanner beyond [ValueType].
 */
data class FieldYield(
    val itemsSampled: Int,
    val totalFields: Int,
    val notSetFields: Int
)

/**
 * Boot-time self-test for every assumption ItemForge makes about the shape of the Hytale engine.
 *
 * ## Why this exists
 *
 * ItemForge is not a normal mod. It does not call a handful of documented APIs — it introspects
 * Hytale's codec system to discover, at runtime, what fields every item has. That is what lets
 * it edit items from mods nobody has ever seen. It is also what makes it uniquely fragile in a
 * way a compiler cannot see.
 *
 * The audit ahead of Hytale Update 6 checked all 83 engine classes ItemForge touches against
 * the 0.6 pre-release: **none had been removed, and none of the engine's 436 removed entities
 * touched anything we use.** ItemForge compiles clean against an SDK nine versions newer than
 * the one it was pinned to. In other words, the compiler will keep saying yes.
 *
 * The real risk is the other kind: the code still runs, and quietly does less. A codec that no
 * longer matches shows up as an editor with fewer fields. A stat registry that changed shape
 * shows up as a missing dropdown entry. A reflected field that was renamed shows up as a
 * setting that silently stops applying. None of those throw. None fail a build. The admin's
 * only symptom is that ItemForge seems to have gotten worse.
 *
 * This class turns each of those into a line in the log, on boot, before anyone opens the
 * editor. It is deliberately cheap: no probe allocates meaningfully, and the whole run is
 * bounded by the caller's sample size.
 *
 * ## How to read the output
 *
 * `FAILED` means an admin-visible feature is broken now. `DEGRADED` means a fallback is
 * carrying it and the next engine change may not be survivable. `OK` means the assumption
 * measured true on this server, on this engine build, today — which is the only sense in which
 * any of this is ever known.
 */
object EngineProbe {

    private val logger = HytaleLogger.forEnclosingClass()

    /**
     * Runs every probe and returns the results in report order.
     *
     * Never throws: a probe that blows up is reported as [ProbeStatus.FAILED] with the
     * exception message, because a self-test that can take the server down with it is worse
     * than the problem it was added to detect.
     *
     * @param itemCodec   the cached item codec, or null if `CodecScanner.init()` has not run
     * @param recipeCodec the cached recipe codec, or null
     * @param yield       a field-discovery sample, or null to skip the yield probe
     */
    fun runAll(
        itemCodec: BuilderCodec<Item>?,
        recipeCodec: BuilderCodec<CraftingRecipe>?,
        yield: FieldYield?
    ): List<ProbeResult> = listOf(
        guard("item-codec-shape") { assetStoreCodecShape("Item", Item.getAssetStore().codec, itemCodec) },
        guard("recipe-codec-shape") { assetStoreCodecShape("CraftingRecipe", CraftingRecipe.getAssetStore().codec, recipeCodec) },
        guard("codec-type-mapping") { codecTypeMapping() },
        guard("field-discovery-yield") { fieldDiscoveryYield(yield) },
        guard("extra-info-version") { extraInfoVersion() },
        guard("range-validator-bounds") { reflectedField(RangeValidator::class.java, "min", "max", "field min/max constraints; sliders lose their bounds") },
        guard("entity-stat-hide-flag") { reflectedField(EntityStatType::class.java, "hideFromTooltip", null, "internal/scratch stats leak into the stat dropdowns") },
        guard("bson-document-codec") { bsonDocumentCodec() },
        guard("vuetale-internals") { vuetaleInternals() }
    )

    /**
     * The five Vuetale internals ItemForge reaches by reflection, checked by name.
     *
     * These are NOT engine symbols — no Hytale version index can vet them, and `apifloor.py`
     * deliberately excludes them for that reason. They move when *Vuetale* moves, which an engine
     * update forces indirectly.
     *
     * The reason this is worth a probe rather than a refactor: the four call sites that use these
     * are the page-lifecycle paths, and their `catch` blocks log a warning and continue. That is
     * worse than crashing here, because the visible symptom is a phantom event binding that
     * survives page close and eventually disconnects the client — a bug this project has already
     * chased twice. Naming the broken field at boot turns a disconnect report into a one-line
     * diagnosis.
     *
     * `getPlayerRef$Vuetale` carries Kotlin's `internal`-visibility name mangling, so it breaks if
     * Vuetale is rebuilt under a different module name even when the source is unchanged.
     */
    private fun vuetaleInternals(): ProbeResult {
        val missing = mutableListOf<String>()

        fun field(owner: Class<*>, name: String) {
            if (runCatching { owner.getDeclaredField(name) }.isFailure) {
                missing += "${owner.simpleName}.$name"
            }
        }

        field(li.kelp.vuetale.javascript.VueBridge::class.java, "v8Runtime")
        field(li.kelp.vuetale.javascript.JSEngine::class.java, "v8Executor")
        field(li.kelp.vuetale.app.PlayerUi::class.java, "page")
        field(li.kelp.vuetale.app.App::class.java, "isMounted")

        if (runCatching {
                li.kelp.vuetale.app.PlayerUi::class.java.getMethod("getPlayerRef\$Vuetale")
            }.isFailure
        ) {
            missing += "PlayerUi.getPlayerRef\$Vuetale()"
        }

        return if (missing.isEmpty()) {
            ProbeResult("vuetale-internals", ProbeStatus.OK, "all 5 reflected Vuetale members present")
        } else {
            ProbeResult(
                "vuetale-internals", ProbeStatus.FAILED,
                "${missing.size} reflected Vuetale member(s) missing: ${missing.joinToString(", ")}. " +
                    "Editor page cleanup will fail silently, leaving phantom event bindings that can " +
                    "disconnect the client on page close. lib/Vuetale.jar is probably not the expected build."
            )
        }
    }

    /** Wraps a probe so a throwing probe becomes a FAILED result rather than a boot crash. */
    private inline fun guard(name: String, body: () -> ProbeResult): ProbeResult =
        try {
            body()
        } catch (t: Throwable) {
            ProbeResult(name, ProbeStatus.FAILED, "probe threw ${t.javaClass.simpleName}: ${t.message}")
        }

    /**
     * The unchecked cast in `CodecScanner.init()` is the single load-bearing line in ItemForge:
     * if `AssetStore.codec` stops being a [BuilderCodec], that cast throws `ClassCastException`
     * during plugin start and the entire editor, dashboard, batch and recipe surface is dead.
     * Checking it here turns an obscure stack trace into a sentence.
     */
    private fun assetStoreCodecShape(label: String, live: Any?, cached: BuilderCodec<*>?): ProbeResult {
        val name = if (label == "Item") "item-codec-shape" else "recipe-codec-shape"
        if (live == null) {
            return ProbeResult(name, ProbeStatus.FAILED, "$label.getAssetStore().codec is null — assets not loaded yet?")
        }
        if (live !is BuilderCodec<*>) {
            return ProbeResult(
                name, ProbeStatus.FAILED,
                "$label.getAssetStore().codec is ${live.javaClass.name}, not a BuilderCodec. " +
                    "ItemForge cannot introspect fields at all; the editor will not open."
            )
        }
        if (cached == null) {
            return ProbeResult(name, ProbeStatus.DEGRADED, "$label codec is a BuilderCodec but CodecScanner.init() has not cached it yet")
        }
        return ProbeResult(name, ProbeStatus.OK, "$label codec is ${live.javaClass.simpleName}")
    }

    /**
     * Asserts every primitive codec singleton the engine publishes still maps to the
     * [ValueType] ItemForge edits it as.
     *
     * This is cheap and needs no assets, so it runs even on an empty server. It catches the
     * engine redefining what `Codec.FLOAT` *is*. It deliberately cannot catch the engine
     * handing out non-singleton codec instances on real fields — that failure is invisible
     * here and is exactly what [fieldDiscoveryYield] exists to see.
     */
    private fun codecTypeMapping(): ProbeResult {
        val expected = listOf(
            "STRING" to (HytaleCodec.STRING to ValueType.STRING),
            "BOOLEAN" to (HytaleCodec.BOOLEAN to ValueType.BOOLEAN),
            "NULLABLE_BOOLEAN" to (HytaleCodec.NULLABLE_BOOLEAN to ValueType.BOOLEAN),
            "DOUBLE" to (HytaleCodec.DOUBLE to ValueType.DOUBLE),
            "FLOAT" to (HytaleCodec.FLOAT to ValueType.DOUBLE),
            "BYTE" to (HytaleCodec.BYTE to ValueType.INTEGER),
            "SHORT" to (HytaleCodec.SHORT to ValueType.INTEGER),
            "INTEGER" to (HytaleCodec.INTEGER to ValueType.INTEGER),
            "LONG" to (HytaleCodec.LONG to ValueType.INTEGER)
        )
        val wrong = expected.mapNotNull { (label, pair) ->
            val (codec, want) = pair
            val got = CodecTypes.infer(codec)
            if (got == want) null else "$label→${got ?: "unmapped"} (expected $want)"
        }
        return when {
            wrong.isNotEmpty() -> ProbeResult(
                "codec-type-mapping", ProbeStatus.FAILED,
                "${wrong.size}/${expected.size} codec singletons no longer map correctly: ${wrong.joinToString(", ")}. " +
                    "Fields of those types will be missing from the editor."
            )
            !CodecTypes.identityFastPathIntact -> ProbeResult(
                "codec-type-mapping", ProbeStatus.DEGRADED,
                "all ${expected.size} singletons map correctly, but resolution fell past identity " +
                    "(layers ${CodecTypes.layerCounts}) — the engine is handing out non-singleton codecs"
            )
            else -> ProbeResult("codec-type-mapping", ProbeStatus.OK, "all ${expected.size} codec singletons map correctly")
        }
    }

    /**
     * The probe that catches the failure nothing else can see.
     *
     * "Not set" fields — the ones an admin can add to an item — exist only because ItemForge can
     * infer a type from a field's codec with no value present. If that inference silently starts
     * returning null, the count drops to zero, every add-a-field affordance disappears, and the
     * editor still opens and still works. No log, no exception, no failed build.
     *
     * So: sample real items, count what came back, and refuse to call zero normal.
     */
    private fun fieldDiscoveryYield(yield: FieldYield?): ProbeResult {
        if (yield == null) {
            return ProbeResult("field-discovery-yield", ProbeStatus.DEGRADED, "no sample supplied — probe skipped")
        }
        if (yield.itemsSampled == 0) {
            return ProbeResult("field-discovery-yield", ProbeStatus.DEGRADED, "no items available to sample")
        }
        if (yield.totalFields == 0) {
            return ProbeResult(
                "field-discovery-yield", ProbeStatus.FAILED,
                "scanned ${yield.itemsSampled} item(s) and discovered NO fields at all. " +
                    "The editor will be empty. Codec introspection is broken."
            )
        }
        if (yield.notSetFields == 0) {
            return ProbeResult(
                "field-discovery-yield", ProbeStatus.FAILED,
                "scanned ${yield.itemsSampled} item(s): ${yield.totalFields} fields found but ZERO are 'not set'. " +
                    "Admins cannot add any field that an item does not already have. This is the signature of " +
                    "codec type-inference failing silently — check the codec-type-mapping probe and " +
                    "CodecTypes.unmappedCodecs."
            )
        }
        val unmapped = CodecTypes.unmappedCodecs
        val detail = "${yield.totalFields} fields across ${yield.itemsSampled} item(s), ${yield.notSetFields} addable"
        return if (unmapped.isEmpty()) {
            ProbeResult("field-discovery-yield", ProbeStatus.OK, detail)
        } else {
            // Compound codecs land here by design, so this is information, not an alarm.
            ProbeResult(
                "field-discovery-yield", ProbeStatus.OK,
                "$detail; ${unmapped.size} unmapped codec class(es) seen (expected for compound types): " +
                    unmapped.keys.take(5).joinToString(", ")
            )
        }
    }

    /**
     * `CodecScanner.discoverAllFields` passes `Int.MAX_VALUE` as the codec version on the
     * recorded assumption that `ExtraInfo.getVersion()` is hardcoded to that value. If the
     * engine ever threads a real version through, the schema catalog and the live editor would
     * select different field revisions — a silent disagreement, not a crash.
     */
    private fun extraInfoVersion(): ProbeResult {
        val version = ExtraInfo.THREAD_LOCAL.get().version
        return if (version == Int.MAX_VALUE) {
            ProbeResult("extra-info-version", ProbeStatus.OK, "ExtraInfo.version is Int.MAX_VALUE as assumed")
        } else {
            ProbeResult(
                "extra-info-version", ProbeStatus.DEGRADED,
                "ExtraInfo.version is $version, not Int.MAX_VALUE. The engine now threads a real codec " +
                    "version, so the field catalog and the editor may select different field revisions. " +
                    "CodecScanner.discoverAllFields should use the live version instead of the constant."
            )
        }
    }

    /**
     * Confirms a field ItemForge reads reflectively still exists under the same name. These are
     * the references a clean compile can never check, because the name is a string.
     */
    private fun reflectedField(owner: Class<*>, first: String, second: String?, consequence: String): ProbeResult {
        val name = if (owner == RangeValidator::class.java) "range-validator-bounds" else "entity-stat-hide-flag"
        val missing = listOfNotNull(first, second).filter { fieldName ->
            runCatching { owner.getDeclaredField(fieldName) }.isFailure
        }
        return if (missing.isEmpty()) {
            ProbeResult(name, ProbeStatus.OK, "${owner.simpleName}.${listOfNotNull(first, second).joinToString("/")} present")
        } else {
            ProbeResult(
                name, ProbeStatus.DEGRADED,
                "${owner.simpleName} no longer declares ${missing.joinToString("/")} — ItemForge loses $consequence"
            )
        }
    }

    /**
     * `Codec.BSON_DOCUMENT` is deprecated with an explicit engine TODO to remove it, and three
     * ItemForge call sites depend on it — two of which are per-tick ECS systems, so its removal
     * would surface as an exception storm rather than a clean error. Leaving it in place is a
     * settled decision; knowing the day it disappears is not the same thing.
     */
    @Suppress("DEPRECATION")
    private fun bsonDocumentCodec(): ProbeResult =
        if (HytaleCodec.BSON_DOCUMENT != null) {
            ProbeResult("bson-document-codec", ProbeStatus.OK, "Codec.BSON_DOCUMENT present (deprecated upstream, in use by 3 call sites)")
        } else {
            ProbeResult(
                "bson-document-codec", ProbeStatus.FAILED,
                "Codec.BSON_DOCUMENT is gone. Per-item damage, per-item stats and held-stack saving are all broken."
            )
        }

    /**
     * Logs the probe table. One line per probe so a `grep` for `ItemForge probe` in a server log
     * answers "was the engine the same shape when this booted".
     */
    fun report(results: List<ProbeResult>) {
        val failed = results.count { it.status == ProbeStatus.FAILED }
        val degraded = results.count { it.status == ProbeStatus.DEGRADED }

        for (r in results) {
            val line = "ItemForge probe [%s] %s — %s"
            when (r.status) {
                ProbeStatus.OK -> logger.atInfo().log(line, "OK", r.name, r.detail)
                ProbeStatus.DEGRADED -> logger.atWarning().log(line, "DEGRADED", r.name, r.detail)
                ProbeStatus.FAILED -> logger.atSevere().log(line, "FAILED", r.name, r.detail)
            }
        }

        when {
            failed > 0 -> logger.atSevere().log(
                "ItemForge engine probe: %d FAILED, %d degraded, %d ok. ItemForge is running against an " +
                    "engine it does not fully understand — expect missing or non-applying fields. " +
                    "Please report this with your server version.",
                failed, degraded, results.size - failed - degraded
            )
            degraded > 0 -> logger.atWarning().log(
                "ItemForge engine probe: %d degraded, %d ok — fallbacks are carrying it, worth updating.",
                degraded, results.size - degraded
            )
            else -> logger.atInfo().log("ItemForge engine probe: all %d checks OK.", results.size)
        }
    }
}

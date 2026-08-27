// NOTE: this import is required, not stylistic. This script declares a `java { }` extension
// block below, so the identifier `java` resolves to Gradle's JavaPluginExtension — which means a
// fully-qualified `java.util.zip.ZipFile` fails to compile with "Unresolved reference 'util'".
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.1"
}

group = property("projectGroup") as String
version = property("projectVersion") as String

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()

    // Official Hytale Maven repository (SDK)
    maven {
        name = "hytale-release"
        url = uri("https://maven.hytale.com/release")
    }
}

val hytaleServerVersion = property("hytaleServerVersion") as String
val snakeyamlVersion = property("snakeyamlVersion") as String

// Captured here at configuration time on purpose. Reading it with property(...) inside the
// filesMatching { } closure resolves against the TASK, not the project, and fails with
// "Could not get unknown property 'serverVersionRange' for task ':processResources'".
val serverVersionRange = property("serverVersionRange") as String

// ── Creditor (bundled library, MIT — by Lordimass) ───────────────────────
// Creditor provides the /credits page that attributes mod creators. We embed
// it as a Java library (the author's intended "library mode"): ItemForge always
// exposes /credits even on servers that never installed Creditor standalone, so
// our attribution (Larsonix + LadyPaladra) is always visible. Library mode shows
// NO supporter checkmark by design — that badge is inlined in Creditor's own Main
// (anti-spoof) and only renders when Creditor is a standalone mod.
//
// The published Creditor JAR carries its own root manifest.json + icon-256.png,
// which would collide with ItemForge's manifest.json inside one shaded JAR (and a
// JAR may hold only one). We strip those two files (plus Creditor's bare META-INF)
// before shading, so OUR manifest wins. Creditor's classes, its Common/UI .ui
// markup, Server/Credits/creditor.json, and Server/Languages/.../creditor.lang are
// all kept — they land on fresh paths ItemForge doesn't use. The lang namespace is
// filename-based ("creditor."), so the markup's %creditor.* references resolve under
// our asset pack regardless of which mod ships the file (verified: Aetherhaven vendor).
//
// Vendored JAR lives in lib/ (gitignored, same pattern as Vuetale.jar). Full source +
// analysis under creditor/ (also gitignored).
val creditorJar = file("lib/Creditor-1.0.3.jar")
val stripCreditor = if (creditorJar.exists()) tasks.register<Jar>("stripCreditor") {
    description = "Repackages the Creditor library JAR without its colliding root manifest/icon."
    archiveFileName.set("Creditor-stripped.jar")
    destinationDirectory.set(layout.buildDirectory.dir("creditor"))
    from(zipTree(creditorJar)) {
        exclude("manifest.json", "icon-256.png", "META-INF/**")
    }
} else null

dependencies {
    // Hytale Server SDK — compile-only, provided by the server at runtime
    compileOnly("com.hypixel.hytale:Server:$hytaleServerVersion")

    // Vuetale — shaded into the JAR so ItemForge is self-contained.
    // IMPORTANT: Cannot coexist with other plugins that also shade Vuetale (e.g., TrailOfOrbis).
    // The Javet V8 native library can only load once per JVM. For production servers with
    // multiple Vuetale plugins, all must use compileOnly + standalone Vuetale.jar in mods/.
    val vuetaleJar = file("lib/Vuetale.jar")
    if (vuetaleJar.exists()) {
        implementation(files(vuetaleJar))
    }

    // Creditor — bundled library (see strip task above). Shaded into the JAR.
    stripCreditor?.let { implementation(files(it)) }

    // Gson — provided by Hytale at runtime (for override JSON parsing)
    compileOnly("com.google.code.gson:gson:2.10.1")

    // SnakeYAML — bundled for main config parsing (human-readable config.yml)
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")

    // Annotations — compile-time only
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Testing
    testImplementation("com.hypixel.hytale:Server:$hytaleServerVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

kotlin {
    jvmToolchain(25)
}

tasks.shadowJar {
    archiveClassifier.set("")

    // Relocate SnakeYAML to avoid conflicts with other mods that bundle it
    relocate("org.yaml.snakeyaml", "me.itemforge.libs.snakeyaml")

    // Exclude Hytale SDK classes (provided at runtime)
    dependencies {
        exclude(dependency("com.hypixel.hytale:.*"))
    }

    // Clean up JAR signatures from dependencies
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")

    mergeServiceFiles()
}

// ── Shaded-override verification ─────────────────────────────────────────
// ItemForge ships its own copies of two classes that ALSO exist inside lib/Vuetale.jar:
//   li/kelp/vuetale/hytale/VuetaleUIPage.class     — carries every freeze/deadlock fix
//   li/kelp/vuetale/hytale/VuetaleEventData.class  — decodes SlotIndex for @slot-clicking
// Shadow resolves that collision by first-writer-wins, which puts project output ahead of
// dependency jars. Verified 2026-08-27: our copies do win today. But the ordering is
// undocumented and the failure is silent — if it ever flips, the build stays green and
// ItemForge quietly reverts to stock Vuetale, losing the phantom-binding purge, the
// ACK-sendUpdate removal, and the slot index on every grid click.
//
// We assert on the OUTPUT rather than restructuring the merge. Stripping the stock copies out
// of Vuetale.jar first would mean repackaging a 26 MB jar that declares `Multi-Release: true`
// and holds 11 META-INF/versions entries — and that attribute does propagate to the final jar.
// That is a real risk taken to fix a hypothetical one. Reading the produced jar is ground truth
// and costs nothing.
//
// Discriminators measured against both jars on 2026-08-27:
//   VuetaleEventData — stock 4,615 B has no "SlotIndex";     ours 5,664 B does
//   VuetaleUIPage    — stock 22,496 B has no "tooltip-clear"; ours 30,580 B does
// The other upstream log strings ("phantom event binding", "may be stale after hot-reload") are
// NOT discriminators: our class is a fork of Vuetale's and inherits them.
val verifyShadedJar = tasks.register("verifyShadedJar") {
    description = "Asserts the shaded jar kept ItemForge's Vuetale overrides and both lib/Vuetale.jar patches."
    group = "verification"

    val jarProvider = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarProvider)

    doLast {
        val jar = jarProvider.get().asFile
        val problems = mutableListOf<String>()

        ZipFile(jar).use { zf ->
            // ISO-8859-1 maps bytes 1:1, so class bytes survive decoding intact. The markers are
            // ASCII; UTF-8 would risk mangling surrounding bytes into replacement characters.
            fun bodyOf(entry: String): String? = zf.getEntry(entry)?.let {
                zf.getInputStream(it).readBytes().toString(Charsets.ISO_8859_1)
            }

            fun mustContain(entry: String, marker: String, consequence: String) {
                val body = bodyOf(entry)
                when {
                    body == null -> problems += "$entry is MISSING from the jar"
                    !body.contains(marker) -> problems += "$entry lacks \"$marker\" — $consequence"
                }
            }

            mustContain(
                "li/kelp/vuetale/hytale/VuetaleEventData.class", "SlotIndex",
                "stock Vuetale won the shade, so @slot-clicking fires with no slot index"
            )
            mustContain(
                "li/kelp/vuetale/hytale/VuetaleUIPage.class", "tooltip-clear",
                "stock Vuetale won the shade, so the freeze/deadlock fixes are gone"
            )
            // Patch 2 from lib/README.md: without the idempotency guard the JVM dies with a
            // native EXCEPTION_ACCESS_VIOLATION on roughly the third editor open.
            mustContain(
                "li/kelp/vuetale/javascript/JSEngine.class", "preloadedComponents",
                "lib/Vuetale.jar is missing the preloadComponent idempotency guard"
            )

            // Patch 1 from lib/README.md: the V8 native splice. A stock download re-introduces the
            // Node natives and roughly doubles the shipped jar.
            val natives = zf.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex(".*javet.*\\.(so|dll)$")) }
                .toList()
            if (natives.none { it.contains("javet-v8") }) {
                problems += "no javet-v8 native present — lib/Vuetale.jar is not the V8-spliced build"
            }
            natives.filter { it.contains("javet-node") }.forEach {
                problems += "javet-node native present ($it) — a stock Vuetale was used"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("Shaded-jar verification FAILED (${problems.size} problem(s)) in ${jar.name}:")
                problems.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Most likely cause: lib/Vuetale.jar was replaced with a stock download.")
                appendLine("lib/README.md explains what the patched jar contains and how to check it.")
            })
        }
        logger.lifecycle("verifyShadedJar: Vuetale overrides + both lib/Vuetale.jar patches present in ${jar.name}")
    }
}

// Make shadowJar the default JAR output
tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
    dependsOn(verifyShadedJar)
}

tasks.test {
    useJUnitPlatform()
}

// ── Vue UI Build (ARCHITECTURE.md §13.1: Dual Build Pipeline) ──────────

// Build the Vue UI before processing resources — Vite outputs compiled
// .vue.js files to src/main/resources/vuetale/itemforge/, which are then
// bundled into the JAR by processResources + shadowJar.
val npmBuild = tasks.register<Exec>("npmBuild") {
    description = "Builds the Vuetale Vue UI (src/ui → resources/vuetale/itemforge/)"
    group = "build"

    val uiDir = file("src/ui")
    val nodeModules = uiDir.resolve("node_modules")
    val outputDir = file("src/main/resources/vuetale/itemforge/pages")

    // Only run if the UI project exists and has been npm-installed
    onlyIf { uiDir.resolve("package.json").exists() && nodeModules.exists() }

    inputs.dir(uiDir.resolve("lib"))
    inputs.file(uiDir.resolve("vite.config.ts"))
    inputs.file(uiDir.resolve("package.json"))
    outputs.dir(outputDir)

    workingDir = uiDir
    // Windows requires .cmd extension for npm
    val npmCmd = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
    commandLine(npmCmd, "run", "build")
}

tasks.processResources {
    dependsOn(npmBuild)

    // Expand placeholders in manifest.json.
    //
    // "ServerVersion" comes from the `serverVersionRange` property in gradle.properties, which is
    // its single home — see the long comment there. It is still NOT derived from
    // hytaleServerVersion (the compile-time SDK): coupling the two was a latent bug, because the
    // loader parses ServerVersion via SemverRange.fromString, which rejects a bare patch version
    // like "0.5.3" (bare ranges only parse when patch == 0). One is what we compiled against, the
    // other is what we are measured to run on. Keep the two concerns separate.
    filesMatching("**/manifest.json") {
        expand(
            "version" to project.version.toString(),
            "group" to project.group.toString(),
            "serverVersionRange" to serverVersionRange
        )
    }
}

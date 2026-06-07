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

// Make shadowJar the default JAR output
tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
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
    // NOTE: "ServerVersion" is NOT derived from hytaleServerVersion (the compile-time SDK
    // dependency). It is a SemverRange compatibility expression (">=0.5.0 <0.6.0") declared
    // literally in the manifest. Coupling the two was a latent bug: the loader parses
    // ServerVersion via SemverRange.fromString, which rejects a bare patch version like
    // "0.5.3" (bare ranges only parse when patch == 0). Keep the two concerns separate.
    filesMatching("**/manifest.json") {
        expand(
            "version" to project.version.toString(),
            "group" to project.group.toString()
        )
    }
}

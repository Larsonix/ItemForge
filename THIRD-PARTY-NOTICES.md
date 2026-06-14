# Third-Party Notices

ItemForge bundles, vendors, or depends on the third-party components listed
below. Each remains under its own license. This file is provided to satisfy the
attribution requirements of those licenses; it does not modify the terms of the
ItemForge source-code license (see [LICENSE](LICENSE)).

## Bundled into the distributed JAR

| Component | License | Source |
|-----------|---------|--------|
| [Vuetale](https://github.com/KelpyCode/Vuetale) (Vue 3 runtime + V8 host for Hytale UI) | MIT | github.com/KelpyCode/Vuetale |
| [Javet](https://github.com/caoccao/Javet) (Java ↔ V8/Node interop) | Apache-2.0 | github.com/caoccao/Javet |
| V8 (embedded in Javet's native libraries) | BSD-3-Clause | v8.dev |
| Node.js (embedded in Javet's native libraries) | MIT | nodejs.org |
| Kotlin standard library & kotlin-reflect | Apache-2.0 | github.com/JetBrains/kotlin |
| JetBrains / IntelliJ annotations | Apache-2.0 | github.com/JetBrains/java-annotations |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) (config parsing; relocated to `me.itemforge.libs.snakeyaml`) | Apache-2.0 | bitbucket.org/snakeyaml/snakeyaml |
| [Creditor](https://github.com/Lordimass/Creditor) (the bundled `/credits` mod-attribution page) by Lordimass | MIT | github.com/Lordimass/Creditor |
| libatomic (GCC runtime, `libatomic.so.1`, bundled for Javet's V8 native lib on minimal Linux hosts) | GPL-3.0-or-later WITH GCC-exception-3.1 | gcc.gnu.org |

The Apache-2.0 and BSD-3-Clause components above require their license text and
attribution to be preserved in redistributions. The corresponding `META-INF`
license files are carried inside the bundled Vuetale archive.

## Vendored source (copied into this repository)

### HStats (`src/main/java/me/itemforge/metrics/HStats.java`)

Anonymous metrics client for [hstats.dev](https://hstats.dev), created by
**Al3xWarrior**. It is copied verbatim per the service's integration policy and
is **not** covered by ItemForge's MIT license. Per its own terms, the file may
not be modified beyond its package declaration. It collects only anonymous,
aggregate data (see the file header and the README "Anonymous metrics" section)
and can be disabled by server owners via `metrics.enabled: false`.

## Not redistributed

The Hytale Server SDK (`com.hypixel.hytale:Server`) is a **compile-only**
dependency, provided by the Hytale server at runtime. It is explicitly excluded
from the distributed JAR and is **not** redistributed by this project.

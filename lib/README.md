# Dependencies

External dependency JARs live here and are bundled into the output JAR by the Shadow plugin.
The jars themselves are gitignored (`.gitignore:70-74`); only this file is tracked.

## Vuetale.jar — PATCHED. Do not replace it with a stock download.

**Required, and the copy in this folder is not the published one.** It carries two patches that
ItemForge depends on. Downloading Vuetale from GitHub / Modtale / CurseForge and renaming it to
`Vuetale.jar` will build successfully, pass every existing gate, and then behave differently at
runtime. That is why this warning is at the top.

| | Value |
|---|---|
| Base version | Vuetale 1.0.28 (`manifest.json` inside the jar) |
| Size | 26,971,797 bytes (25.72 MB) |
| SHA-256 | `E79DBE29D3A4331715E5A0B725DC525A2454D4E31CE956C877F311CBAE28A14E` |
| Entries | 3,953 |

### Patch 1 — Node to V8 natives (2026-06-07)

Roughly 90% of the stock jar is two Javet **`javet-node`** natives, a full Node.js runtime. ItemForge
uses none of it: timers are polyfilled onto the JVM by Vuetale's own `ktTimer`, promises are drained
by the 50 ms `v8Runtime.await(RunOnce)` tick, `console` comes from `ktConsole`, and the bundled
Vue builds reference no Node-only globals. Swapping to the **`javet-v8`** natives took the shipped
ItemForge jar from 49 MB to 26 MB while keeping the single-file install.

The change itself is one line in `JSEngine.kt`: `V8Host.getNodeInstance()` becomes
`V8Host.getV8Instance()`.

### Patch 2 — `preloadComponent` idempotency guard (2026-05-30)

`App.createApp()` calls `JSEngine.preloadComponent` on every page open, and stock `preloadComponent`
had no idempotency guard: it recompiled a fresh wrapper ES module and called `module.instantiate()`
every single time. Re-instantiating fresh wrappers against an already-instantiated module graph
corrupts V8's module records and kills the JVM outright with
`EXCEPTION_ACCESS_VIOLATION 0xc0000005` in `V8Native.moduleInstantiate`. It is a native crash, so
ItemForge's `V8Watchdog` cannot catch it (the watchdog handles hangs, not native crashes). The
observed threshold was the third editor open in a session.

The fix adds a `preloadedComponents` set, guards the top of the `runOnV8Thread` block on it, and
clears it in `close()`.

### Verifying the jar you have is the patched one

Both patches are checkable directly from the jar. `javap` from any recent JDK is enough.

```bash
# Patch 1: expect getV8Instance = 1, getNodeInstance = 0
javap -p -c -cp lib/Vuetale.jar li.kelp.vuetale.javascript.JSEngine | grep -c getV8Instance
javap -p -c -cp lib/Vuetale.jar li.kelp.vuetale.javascript.JSEngine | grep -c getNodeInstance

# Patch 1 (second signal): expect two javet-v8 natives and zero javet-node natives
unzip -l lib/Vuetale.jar | grep javet

# Patch 2: expect the preloadedComponents field to exist
javap -p -cp lib/Vuetale.jar li.kelp.vuetale.javascript.JSEngine | grep preloadedComponents
```

Last verified 2026-08-27: `getV8Instance` 1, `getNodeInstance` 0, natives
`libjavet-v8-linux-x86_64.v.4.1.2.so` and `libjavet-v8-windows-x86_64.v.4.1.2.dll`,
`preloadedComponents` present.

### Backups and restore

Both backups are the original 49 MB Node-native jar. Neither is tracked.

| File | Bytes | SHA-256 (first 16) |
|---|---|---|
| `Vuetale.jar.node-backup` | 50,341,890 | `9C310504F2EAAA6D` |
| `Vuetale.jar.bak` | 50,341,780 | `3ABE812F83B2951F` |

To roll back to stock Node natives:

```powershell
Copy-Item lib\Vuetale.jar.node-backup lib\Vuetale.jar -Force
./gradlew clean build
```

Note that rolling back restores the Node runtime **and drops patch 2 as well**, since the backups
predate or sit alongside it. A rollback is a debugging step, not a shipping configuration.

### Rebuilding from source — SOLVED 2026-08-27

**The full source is on disk and it builds.** `Vuetale/Vuetale-master (1)/Vuetale-master/` is not a
stock upstream copy: it is ItemForge's patched fork, with both patches (and the `SlotIndex` decode)
already applied in the Kotlin. 47 files, 6,852 lines.

Both patches originally went in by surgical splice because, at the time, the tree would not compile
against any *then-cached* SDK (0.5.0, 0.5.3, and a dated build). That is no longer true. Against
**SDK 0.5.9** the tree needs exactly four mechanical edits, all caused by ordinary API drift:

| File | Change | Why |
|---|---|---|
| `build.gradle.kts:26` | `force(...Server:0.5.0)` → `0.5.9` | Hytale pruned 0.5.0 from Maven; it no longer resolves at all |
| `hytale/VuetaleEventData.kt:5,32` | `IntCodec` → `IntegerCodec` | class renamed upstream |
| `hytale/VuetaleUIHud.kt:40` | `CustomUIHud(playerRef)` → `CustomUIHud(playerRef, appOwner)` | the 1-arg constructor is gone; 0.5.9 has `(PlayerRef, String)` and `(PlayerRef, String, int)` |
| `app/PlayerUi.kt:141` | `hudManager.setCustomHud(...)` → `addCustomHud(...)` | renamed; `resetHud` at `:158` is unchanged |

The exact commands, run from the fork root
(`Vuetale/Vuetale-master (1)/Vuetale-master/`). Deliberately not applied to the vendored tree, which
is kept as-is so it still documents what the shipped 1.0.28 jar was built from:

```bash
sed -i 's|force("com.hypixel.hytale:Server:0.5.0")|force("com.hypixel.hytale:Server:0.5.9")|' build.gradle.kts
sed -i 's/IntCodec/IntegerCodec/g'                       src/main/kotlin/li/kelp/vuetale/hytale/VuetaleEventData.kt
sed -i 's|) : CustomUIHud(playerRef) {|) : CustomUIHud(playerRef, appOwner) {|' src/main/kotlin/li/kelp/vuetale/hytale/VuetaleUIHud.kt
sed -i 's|\.setCustomHud(|.addCustomHud(|'                src/main/kotlin/li/kelp/vuetale/app/PlayerUi.kt
./gradlew shadowJar
```

Measured result: `./gradlew shadowJar` produces `Vuetale-1.0.28-all.jar`, **26,425,419 bytes, 3,953
entries** — the same entry count as the hand-spliced jar, with the same two `javet-v8` natives, no
`javet-node`, `preloadedComponents` present, and `SlotIndex` present (which the hand-spliced jar
lacks, since that decode currently comes from ItemForge's shade-time override instead).

ItemForge then builds clean against it, `verifyShadedJar` passes, and `apifloor.py` reports **0
owned misses with the shaded-only misses dropping from 3 to 1** — because those last two edits also
remove two real `NoSuchMethodError` landmines that stock Vuetale carries on every engine 0.5.0–0.5.9.

⚠ **The from-source jar has never been run in game.** It is proven to build, not to work. The jar in
this folder remains the hand-spliced one, which is the version with in-game history. A candidate
build is kept beside it as `Vuetale-1.0.28-fromsource.jar.candidate` (gitignored, not in use) for
whenever there is a server to test it on.

So a Hytale update that requires a Vuetale change is now ordinary work, not a dead end.

## Creditor-1.0.3.jar

Bundled library (MIT, by Lordimass) providing the always-on `/credits` page. Stock, unpatched.
SHA-256 `58954485FBDE8292673E28803DC402FDD9A338BE1E60C953F92A4F6B0431C1CB`.

`build.gradle.kts` strips its root `manifest.json`, `icon-256.png` and bare `META-INF` before
shading, so ItemForge's own manifest wins inside the merged jar. See the comment block at
`build.gradle.kts:30-57` for the full reasoning.

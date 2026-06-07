# ItemForge — Brand Base

The documented design system for all ItemForge presentation imagery (CurseForge page,
banners, social cards). Everything here is **derived from LadyPaladra's logo** so the page
stays 100% in sync with it. The logo is the single source of truth; this doc and the code
in `branding/` exist only to extend that logo consistently across a full page.

> **Principle:** never hand-invent a colour or vibe. Measure it from the logo, or sample it
> live at render time. If the logo is revised, re-run `generate.py` and the page re-themes.

---

## 1. The Logo

`branding/itemforge-logo.png` — 1024×1024, opaque RGB. A crystalline / voxel-beveled anvil
with an ice-blue sword, the "ITEM FORGE" wordmark below, on a white→azure gradient, with
faint **gears + circuit traces** as a watermark.

**Temperature:** cool, light, technical. This is the deliberate *opposite* of a dark-fantasy
mod page — a light/azure CurseForge page stands out hard against the typical dark crowd.
(For contrast: the Trail of Orbis brand is warm gold-on-black. ItemForge is its thermal inverse.)

---

## 2. Palette (measured from logo pixels)

Channel means across the logo: **R 154 / G 190 / B 228** — strongly blue-biased.

| Token | Hex | Role |
|-------|-----|------|
| **Forge Azure** `AZURE` | `#2687F4` | primary brand blue — the logo accent (~10% of all pixels) |
| Sky `SKY` | `#298DFA` | gradient bottom |
| Steel `STEEL` | `#245998` | depth, shadow, headline fill on light |
| Ice `ICE` | `#60B5F9` | mid tone, glows |
| Frost `FROST` | `#B3D0EC` | crystalline edge highlight |
| Glass `GLASS` | `#CEDAE8` | soft fill |
| Pale `PALE` | `#DFE6ED` | palest blue fill |
| White `WHITE` | `#FDFDFD` | gradient top, headline core, outline |
| Ink `INK` | `#14304D` | body text on light (derived; deep desaturated steel) |

Canonical values live in `brand.py` (class `C`). Use the names in composition code, not raw hex.

---

## 3. Signature Background

A **vertical gradient, white at the top → azure at the bottom** (`#FDFDFD` → `#298DFA`), with the
colour shift weighted into the lower ~60% (white dominates the upper third).

This is reproduced **literally**: `forgekit.logo_gradient_bg()` lifts a clean edge column from
the logo itself (x=20, full height) and stretches it across the banner — so the page background
is pixel-for-pixel the logo's own gradient, and self-updates if the logo changes.

---

## 4. Typography

| Use | Font | Notes |
|-----|------|-------|
| Headlines / wordmark | **RBNo3.1 Black** (Rene Bieder) | the exact face LadyPaladra used for "ITEM FORGE" |
| Body / captions | RBNo3.1 Black (fallback) | **recommend a dedicated body sans** — see constraints |

**Treatment:** headlines use a heavy white outline (`outlined_text`, stroke ~7px) exactly like
the logo wordmark; secondary text uses a soft white glow (`glow_text`) to lift off the gradient.

### ⚠️ Two font constraints (verified empirically — not assumptions)

1. **License — DEMO font.** The file in `assets/fonts/RBNo3.1-BlackDEMO.otf` is the *demo*
   (`"RBNo3.1 Black DEMO is a trademark of Rene Bieder"`), licensed for evaluation only — **not
   for commercial use**, and this CurseForge page is monetized. **Action:** buy the full
   **RBNo3.1 Black** before public launch. It is visually identical, so swapping is zero-change:
   update `FONT_DISPLAY` in `brand.py` to the licensed file. The demo font is gitignored (not
   redistributed) — re-place it locally to render.

2. **Glyph coverage in Pillow.** With this demo file in Pillow (no raqm), **only letters, digits,
   comma, period, and space render.** Every other punctuation mark (hyphen, slash, pipe, colon,
   asterisk, plus, bullet…) draws as a **tofu box** — even though the glyph exists in the font's
   CFF table (a cmap-subtable quirk of the demo; the full licensed font is complete). 
   **Mitigations in place:**
   - Marketing copy uses only safe glyphs (commas/periods as separators).
   - `forgekit.check_renderable()` flags unsafe glyphs; `generate.py` asserts on every copy string,
     so a stray hyphen can never silently ship as a box.
   - Likely resolved by the licensed font; otherwise pair a proper body sans for rich punctuation.

---

## 5. Motifs

Faint **gears** (`forgekit.gear_motif`) echo the logo's gear watermark — deterministic placement
(no RNG, reproducible builds), ~18/255 opacity in `STEEL`. Circuit traces are a future addition.

---

## 6. Canvas Conventions

- **Width 1700px** for all CurseForge images (2× the container width → retina-crisp).
- Rounded corners radius 14, content margin 64 (`brand.RADIUS`, `brand.MARGIN`).
- Export PNG, optimized, RGB.

---

## 7. The Pipeline (`branding/`)

```
itemforge-logo.png   single source of truth (committed)
brand.py             tokens: palette, fonts, paths, canvas constants  (run it: prints + self-checks)
forgekit.py          reusable primitives: gradients, logo-sync bg, glow/outline text,
                     fit_font, gear motif, vignette, rounded corners, crop-to-fill
generate.py          ItemForge page composition  ->  output/*.png
                     run:  python generate.py          (all)
                           python generate.py hero     (one)
assets/fonts/        RBNo3.1 (gitignored — demo, not redistributed)
output/              generated PNGs (gitignored)
```

**Status:** foundation + hero proof complete and verified in sync. Remaining banners
(feature cards, cinematic screenshots, install, credits) compose on top of the same primitives.

**No-AI-art rule** (inherited from the ToO pipeline): page imagery uses only the logo, in-game
screenshots, and these hand-built brand elements — no AI-generated artwork.

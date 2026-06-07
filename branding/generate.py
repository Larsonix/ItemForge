"""
ItemForge CurseForge presentation generator.

Composes brand tokens (brand.py) + primitives (forgekit.py) into the full page of
1700px-wide banners. All imagery is the logo, real in-game screenshots, and hand-built
brand elements — no AI art (inherited rule from the Trail of Orbis pipeline).

Run:  python generate.py                 (render the whole page)
      python generate.py hero filter     (render specific banners)

Headlines use RBNo3.1 Black (display-safe glyphs only, asserted); body copy uses Inter
(full punctuation).
"""

import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import brand
import forgekit as fk
from brand import C

# --- Real in-game screenshots -------------------------------------------------
# Author's capture folder; override with the ITEMFORGE_SHOTS env var to point at your own.
SHOTDIR = Path(os.environ.get("ITEMFORGE_SHOTS", os.path.expanduser("~/Pictures/Screenshots/ItemForge")))
SHOTS = {
    "grid":   SHOTDIR / "Capture d'écran 2026-05-30 210920.png",   # dashboard grid, 5040 items
    "filter": SHOTDIR / "Capture d'écran 2026-05-30 211153.png",   # filtered + native tooltip
    "table":  SHOTDIR / "Capture d'écran 2026-05-30 211326.png",   # table view, sortable
    "recipe": SHOTDIR / "Capture d'écran 2026-05-30 211620.png",   # recipe editor (Apple Pie)
    "damage": SHOTDIR / "Capture d'écran 2026-05-30 211710.png",   # damage editor (Crude Sword)
    "edit3":  SHOTDIR / "Capture d'écran 2026-05-30 211912.png",
    "edit4":  SHOTDIR / "Capture d'écran 2026-05-30 212010.png",
    "bench":  SHOTDIR / "Capture d'écran 2026-05-30 212121.png",   # in-game crafting bench (payoff)
    "batch1": SHOTDIR / "Capture d'écran 2026-05-30 215112.png",   # batch: scale Max Durability 110%
    "batch2": SHOTDIR / "Capture d'écran 2026-05-30 215250.png",   # batch: set Durability Loss to 0.7
}

W = brand.PAGE_WIDTH
M = brand.MARGIN


# =============================================================================
# Banners
# =============================================================================
def hero():
    """01_hero — the logo, flush at full height, blended seamlessly into the page gradient.

    The page background IS the logo's own (denoised) gradient, so when the square logo is
    placed full-height its background pixels match the page exactly at every row — the
    square edge dissolves and only the anvil/sword/wordmark read. No halo, no visible box."""
    H = 760
    img = fk.logo_gradient_bg((W, H)).convert("RGBA")   # plain gradient (no gear motif here)
    logo = Image.open(brand.LOGO).convert("RGBA").resize((H, H), Image.LANCZOS)
    img.alpha_composite(logo, (0, 0))                    # flush left, full height -> edges blend

    tx = H + 30
    avail = W - tx - M
    headline = "FORGE ANY ITEM"
    assert not fk.check_renderable(headline)
    hfont = fk.fit_font(headline, avail, max_size=96, stroke=10)
    fk.wordmark_text(img, (tx, 235), headline, hfont, anchor="lm")
    fk.body_text(img, (tx, 360), "The live, in-game item editor for Hytale",
                 size=46, color=C.INK, weight=brand.WEIGHT_BOLD, anchor="lm")
    fk.body_text(img, (tx, 460), "Armor · Weapons · Tools · Food · Recipes",
                 size=36, color=C.STEEL, weight=brand.WEIGHT_MEDIUM, anchor="lm")
    fk.body_text(img, (tx, 515), "No restart needed.",
                 size=36, color=C.STEEL, weight=brand.WEIGHT_MEDIUM, anchor="lm")
    return fk.save(img, "01_hero.png")


def divider():
    return fk.save(fk.divider(W, 230), "02_divider.png")


def overview():
    """03 — the dashboard grid, big."""
    H = 1040
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 60), "The Dashboard", "EVERY ITEM, ONE PLACE")
    fk.paragraph(img, (M, 200),
                 "Browse every item from every mod, over 5,000 of them. Search, filter, and jump "
                 "straight into the editor.", max_width=1200, size=32, weight=brand.WEIGHT_MEDIUM)
    shot = fk.framed_shot(SHOTS["grid"], (1360, 700))
    fk.paste_centered(img, shot, W // 2, 300 + shot.size[1] // 2 - 20)
    return fk.save(img, "03_overview.png")


def filter_():
    """04 — filter by any stat + native tooltip."""
    H = 1040
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 60), "Find Anything", "FILTER BY ANY STAT")
    fk.paragraph(img, (M, 200),
                 "Narrow down 5,000 items by type, mod, quality, or slot. You can filter by any "
                 "stat too, like health, defense, or durability. Hover an item to see its real "
                 "in-game tooltip.", max_width=1300, size=32, weight=brand.WEIGHT_MEDIUM)
    shot = fk.framed_shot(SHOTS["filter"], (1360, 700))
    fk.paste_centered(img, shot, W // 2, 300 + shot.size[1] // 2 - 20)
    return fk.save(img, "04_filter.png")


def views():
    """05 — grid OR table."""
    H = 1040
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 60), "Two Views", "GRID OR TABLE")
    fk.paragraph(img, (M, 200),
                 "A visual grid for browsing, a sortable table for the data. Sort by any column and "
                 "see what you've already changed.", max_width=1200, size=32, weight=brand.WEIGHT_MEDIUM)
    shot = fk.framed_shot(SHOTS["table"], (1360, 700))
    fk.paste_centered(img, shot, W // 2, 300 + shot.size[1] // 2 - 20)
    return fk.save(img, "05_views.png")


def editor():
    """06 — the editor: two panels side by side."""
    H = 1020
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 60), "The Editor", "TUNE EVERY PROPERTY")
    fk.paragraph(img, (M, 200),
                 "The editor adapts to whatever item you open, from armor and weapons to tools, food, "
                 "and recipes. Every field shows its real default value.", max_width=1300, size=32,
                 weight=brand.WEIGHT_MEDIUM)
    # Three editor panels: damage, properties (dropdown open), defense.
    cy = 320 + 580 // 2
    for shot, dx in ((SHOTS["damage"], -W // 3), (SHOTS["edit3"], 0), (SHOTS["edit4"], W // 3)):
        fk.paste_centered(img, fk.framed_shot(shot, (480, 580)), W // 2 + dx, cy)
    return fk.save(img, "06_editor.png")


def recipes():
    """07 — THE payoff: edit a recipe, it applies live in the real bench."""
    H = 980
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 60), "Live Recipes", "REWRITE CRAFTING")
    fk.paragraph(img, (M, 200),
                 "Change a recipe in the editor and it shows up in the real crafting bench right "
                 "away, without a restart.", max_width=1300, size=32, weight=brand.WEIGHT_MEDIUM)
    cy = 360 + 240
    left = fk.framed_shot(SHOTS["recipe"], (470, 490))
    right = fk.framed_shot(SHOTS["bench"], (760, 448))
    fk.paste_centered(img, left, M + 280, cy)
    fk.paste_centered(img, right, W - M - 430, cy)
    # azure arrow between the panels, with a white-on-azure "LIVE" badge above it
    ax = M + 560
    d = ImageDraw.Draw(img)
    d.line([(ax, cy), (ax + 120, cy)], fill=fk.rgba(C.AZURE), width=12)
    d.polygon([(ax + 120, cy - 26), (ax + 170, cy), (ax + 120, cy + 26)], fill=fk.rgba(C.AZURE))
    bx, by = ax + 85, cy - 56
    d.rounded_rectangle([bx - 56, by - 22, bx + 56, by + 22], radius=22, fill=fk.rgba(C.AZURE))
    fk.tracked_text(img, (bx, by), "LIVE", fk.font(26, body=True, weight=brand.WEIGHT_BLACK),
                    fill=C.WHITE, tracking=4, anchor="mm")
    return fk.save(img, "07_recipes.png")


def batch():
    """08 — batch editing, shown with the real before/after preview screens."""
    H = 980
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 60), "Batch Power", "EDIT THOUSANDS AT ONCE")
    fk.paragraph(img, (M, 200),
                 "Pick a field, select hundreds of items, and preview every before and after value "
                 "before you commit. Buff every sword or rebalance a whole mod in one pass.",
                 max_width=1400, size=32, weight=brand.WEIGHT_MEDIUM)
    cy = 320 + 290
    fk.paste_centered(img, fk.framed_shot(SHOTS["batch2"], (700, 584)),
                      W // 2 - 380, cy)
    fk.paste_centered(img, fk.framed_shot(SHOTS["batch1"], (700, 584)),
                      W // 2 + 380, cy)
    return fk.save(img, "08_batch.png")


def features():
    """09 — text feature grid."""
    H = 760
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 56), "Built Right", "WHY ITEMFORGE")
    feats = [
        ("Any item, any mod", "Reads items through codec introspection, so it works with mods it has never seen."),
        ("Live, no restart", "Edits apply to items immediately, in every single container · inventory · dropped item, and globally, to every single player."),
        ("Non-destructive", "Overrides layer on top, so you can reset any field back to its original value."),
        ("Inspect mode", "Crouch and right-click an item you're holding to open it right there in the editor, no command needed."),
    ]
    col_w = (W - 2 * M - 40) // 2
    for i, (title, desc) in enumerate(feats):
        cx = M + (i % 2) * (col_w + 40)
        cy = 230 + (i // 2) * 230
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([cx - 6, cy - 6, cx + 18, cy + 18], radius=6, fill=fk.rgba(C.AZURE))
        fk.body_text(img, (cx + 44, cy - 8), title, size=37, color=C.STEEL,
                     weight=brand.WEIGHT_BOLD)
        fk.paragraph(img, (cx + 44, cy + 52), desc, max_width=col_w - 60, size=brand.T_SMALL,
                     color=C.INK, weight=brand.WEIGHT_REGULAR)
    return fk.save(img, "09_features.png")


def install():
    """10 — installation."""
    H = 620
    img = fk.canvas(W, H)
    fk.section_header(img, (M, 56), "Get Started", "INSTALL IN SECONDS")
    steps = [
        "Drop ItemForge.jar into your server or client mod folder.",
        "Start the server. The UI is bundled in, so there's nothing else to install.",
        "Run /itemforge in-game to open the dashboard.",
    ]
    for i, s in enumerate(steps):
        cy = 240 + i * 100
        n = fk.font(46, body=True, weight=brand.WEIGHT_BLACK)
        ImageDraw.Draw(img).ellipse([M, cy - 6, M + 64, cy + 58], fill=fk.rgba(C.AZURE))
        fk.glow_text(img, (M + 32, cy + 26), str(i + 1), n, fill=C.WHITE, anchor="mm")
        fk.body_text(img, (M + 100, cy + 26), s, size=brand.T_BODY, color=C.INK,
                     weight=brand.WEIGHT_MEDIUM, anchor="lm")
    return fk.save(img, "10_install.png")


def credits():
    """11 — credits."""
    H = 460
    img = fk.canvas(W, H)
    # Circular anvil medallion (no square edges, consistent with the divider) — the
    # "ITEM FORGE" wordmark is rendered beside it, so we don't lose the name.
    fk.paste_centered(img, fk.anvil_medallion(240), M + 150, H // 2)
    x = M + 320
    fk.wordmark_text(img, (x, 130), "ITEM FORGE", fk.font(72), anchor="lm")
    fk.body_text(img, (x, 220), "Code & Engineering: Larsonix", size=brand.T_BODY, color=C.INK,
                 weight=brand.WEIGHT_SEMIBOLD, anchor="lm")
    fk.body_text(img, (x, 272), "Idea & Logo: LadyPaladra", size=brand.T_BODY, color=C.INK,
                 weight=brand.WEIGHT_SEMIBOLD, anchor="lm")
    fk.body_text(img, (x, 330), "For Hytale · find it on CurseForge", size=brand.T_SMALL,
                 color=C.STEEL, weight=brand.WEIGHT_MEDIUM, anchor="lm")
    return fk.save(img, "11_credits.png")


def swatches():
    """00 — internal palette reference (not a page banner)."""
    H = 360
    img = fk.canvas(W, H)
    fk.wordmark_text(img, (M, 40), "ITEMFORGE PALETTE", fk.font(54), anchor="la")
    swatch = [("AZURE", C.AZURE), ("SKY", C.SKY), ("STEEL", C.STEEL), ("ICE", C.ICE),
              ("FROST", C.FROST), ("GLASS", C.GLASS), ("PALE", C.PALE), ("WHITE", C.WHITE)]
    n = len(swatch)
    gap = 20
    bw = (W - 2 * M - gap * (n - 1)) // n
    d = ImageDraw.Draw(img)
    y0, bh = 150, 140
    for i, (name, hexv) in enumerate(swatch):
        x = M + i * (bw + gap)
        d.rounded_rectangle([x, y0, x + bw, y0 + bh], radius=12, fill=fk.hex_to_rgb(hexv),
                            outline=fk.hex_to_rgb(C.WHITE), width=2)
        d.text((x + bw // 2, y0 + bh + 18), name, font=fk.font(24, body=True),
               fill=fk.hex_to_rgb(C.INK if name in ("FROST", "GLASS", "PALE", "WHITE") else C.WHITE),
               anchor="ma")
        d.text((x + bw // 2, y0 + bh + 48), hexv.lstrip("#"), font=fk.font(20, body=True),
               fill=fk.hex_to_rgb(C.STEEL), anchor="ma")
    return fk.save(img, "00_palette.png")


BANNERS = {
    "hero": hero, "divider": divider, "overview": overview, "filter": filter_,
    "views": views, "editor": editor, "recipes": recipes, "batch": batch,
    "features": features, "install": install, "credits": credits, "swatches": swatches,
}

# Vertical order of the published page (the divider is inserted between each).
PAGE_ORDER = ["01_hero", "03_overview", "04_filter", "05_views", "06_editor",
              "07_recipes", "08_batch", "09_features", "10_install", "11_credits"]


LIMIT = 1_950_000          # CurseForge 2MB cap, with margin

# The page is split into PARTS so the three animated GIF banners (built by gifbanner.py) can be
# dropped into the breaks. Upload order:
#   part1 -> gif_editor -> part2 -> gif_weapon -> part3 -> gif_recipe -> part4
PARTS = [
    (["01_hero", "03_overview", "04_filter", "05_views"], "itemforge-part1"),
    (["06_editor"], "itemforge-part2"),
    (["07_recipes"], "itemforge-part3"),
    (["08_batch", "09_features", "10_install", "11_credits"], "itemforge-part4"),
]
UPLOAD_ORDER = ["itemforge-part1", "gif_editor.gif", "itemforge-part2", "gif_weapon.gif",
                "itemforge-part3", "gif_recipe.gif", "itemforge-part4"]


def _stitch(names, basename):
    """Stitch the given banner PNGs (divider between each) into one 848px-wide image. Saves
    lossless PNG if it fits under 2MB, else falls back to the highest JPEG quality that does."""
    div = Image.open(brand.OUTPUT / "02_divider.png").convert("RGB")
    strip = []
    for i, name in enumerate(names):
        if i > 0:
            strip.append(div)
        strip.append(Image.open(brand.OUTPUT / f"{name}.png").convert("RGB"))
    w = brand.EXPORT_WIDTH
    img = Image.new("RGB", (w, sum(p.size[1] for p in strip)), (253, 253, 253))
    y = 0
    for p in strip:
        img.paste(p, (0, y))
        y += p.size[1]
    png = brand.OUTPUT / f"{basename}.png"
    img.save(png, "PNG", optimize=True)
    if png.stat().st_size <= LIMIT:
        print(f"  {png.name}  ({img.size[0]}x{img.size[1]})  {png.stat().st_size/1e6:.2f}MB  PNG")
        return png
    png.unlink()
    out = brand.OUTPUT / f"{basename}.jpg"
    for q in (95, 93, 91, 88, 85):
        out.unlink(missing_ok=True)
        img.save(out, "JPEG", quality=q, subsampling=0, progressive=True, optimize=True)
        if out.stat().st_size <= LIMIT:
            break
    print(f"  {out.name}  ({img.size[0]}x{img.size[1]})  {out.stat().st_size/1e6:.2f}MB  JPEG q{q}")
    return out


def fullpage():
    """One tall image with every banner (no GIFs). Kept as an alternative to the split parts."""
    return _stitch(PAGE_ORDER, "itemforge-page")


def parts():
    """Split the page into the four uploadable image parts (GIFs slot into the breaks)."""
    for names, base in PARTS:
        _stitch(names, base)
    print("  upload order:  " + "  ->  ".join(UPLOAD_ORDER))


def main():
    composites = {"fullpage": fullpage, "parts": parts}
    targets = sys.argv[1:] or list(BANNERS) + ["parts"]
    print("Rendering:", ", ".join(targets))
    for name in targets:
        if name in composites:
            continue  # handled last, after all banners exist
        if name not in BANNERS:
            print(f"  ! unknown '{name}' (banners: {', '.join(BANNERS)}; composites: parts, fullpage)")
            continue
        BANNERS[name]()
    for name in ("fullpage", "parts"):
        if name in targets:
            composites[name]()


if __name__ == "__main__":
    main()

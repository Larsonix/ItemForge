"""
ItemForge brand tokens — the single source of truth for the presentation pipeline.

Design principle: stay 100% in sync with LadyPaladra's logo. Rather than hand-copying
hex codes (which drift the moment the logo is revised), the canonical palette below was
*measured* from the logo pixels, and the page background is sampled LIVE from the logo's
own vertical gradient at render time (see forgekit.logo_gradient_bg). Revise the logo and
the page re-themes itself.

Measured from branding/itemforge-logo.png (1024x1024, opaque RGB):
  channel means  R=154  G=190  B=228   -> strongly cool / blue-biased
  background     #FDFDFD (top) -> #298DFA (bottom), shift weighted into the lower ~60%
  accent         #2687F4 was 10% of all pixels (the signature brand blue)
"""

from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parent
LOGO = ROOT / "itemforge-logo.png"
FONTS = ROOT / "assets" / "fonts"
OUTPUT = ROOT / "output"

# Display/headline face — RBNo3.1 Black, the exact font LadyPaladra used for the
# "ITEM FORGE" wordmark. NOTE: this is the DEMO file (evaluation only, not licensed
# for commercial use). Swap to the full RBNo3.1 Black before public/monetized launch
# — it is visually identical, so only this path changes.
FONT_DISPLAY = FONTS / "RBNo3.1-BlackDEMO.otf"
# Body / caption face: Inter (OFL, fully redistributable — committed). Variable font,
# weight axis 100-900, full punctuation. Paired with the display font for legible body copy.
FONT_BODY = FONTS / "Inter-Regular.ttf"

# Variable-weight values for Inter (used via forgekit.font(..., weight=)).
WEIGHT_REGULAR = 400
WEIGHT_MEDIUM = 500
WEIGHT_SEMIBOLD = 600
WEIGHT_BOLD = 700
WEIGHT_BLACK = 900


# ---------------------------------------------------------------------------
# Palette — measured from the logo. Named so composition reads intent, not hex.
# ---------------------------------------------------------------------------
class C:
    # Core brand
    AZURE   = "#2687F4"   # primary brand blue — the logo accent
    SKY     = "#298DFA"   # gradient bottom
    STEEL   = "#245998"   # depth / shadow / headline-on-light
    ICE     = "#60B5F9"   # mid tone, glows
    FROST   = "#B3D0EC"   # crystalline edge highlight
    GLASS   = "#CEDAE8"   # soft fill
    PALE    = "#DFE6ED"   # palest blue fill
    WHITE   = "#FDFDFD"   # gradient top, headline core

    # Derived utility tones (kept in-family, not measured but harmonised)
    INK     = "#14304D"   # body text on light backgrounds (deep desaturated steel)
    OUTLINE = "#FFFFFF"   # heavy wordmark-style outline
    GLOW    = "#9CC2E9"   # soft halo around light text on azure

    # Background gradient endpoints (literal logo samples; live-sampled at render)
    BG_TOP    = "#FDFDFD"
    BG_BOTTOM = "#298DFA"

    # --- Wordmark palette: measured pixel-by-pixel from the logo's "ITEM FORGE" ---
    # The titles imitate LadyPaladra's wordmark exactly: a center-bright beveled face
    # wrapped in a thin dark-navy contour, over a soft dark-navy outer glow.
    WM_CENTER  = "#FDFEFE"   # brightest point, down the centre of each stroke (bevel peak)
    WM_MID     = "#E7EEF8"   # mid face
    WM_EDGE    = "#91B0DF"   # face colour at the stroke edge (light periwinkle)
    WM_OUTLINE = "#072B4D"   # thin dark-navy contour hugging the letters
    WM_GLOW    = "#0B2E50"   # soft dark-navy outer glow / shadow for separation


def hex_to_rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def rgba(h: str, a: int = 255) -> tuple[int, int, int, int]:
    r, g, b = hex_to_rgb(h)
    return (r, g, b, a)


# ---------------------------------------------------------------------------
# Canvas conventions (mirrors the proven ToO pipeline)
# ---------------------------------------------------------------------------
PAGE_WIDTH = 1700          # internal render width — everything is composed at 2x for crispness
EXPORT_WIDTH = 848         # CurseForge HARD-REJECTS uploads >=850px wide ("Image width is too big,
                           # should be less than 850px"). 848 is the safe ceiling. We render at
                           # PAGE_WIDTH (1700) and LANCZOS-downscale to this on save = supersampled,
                           # the sharpest possible image at the allowed width. (On a 2x retina screen
                           # the browser still upscales 848px to fit, so some softness there is CF's
                           # hard limit, not ours — there is no way to ship a wider file.)
RADIUS = 14                # default rounded-corner radius
MARGIN = 64                # default content inset

# --- Type scale (px) — one rhythm across every banner ---
T_KICKER  = 27             # eyebrow / kicker (tracked uppercase, Inter Black)
T_TITLE   = 72             # section title (wordmark style)
T_LEAD    = 38             # hero subhead / lead line
T_BODY    = 33             # body paragraphs
T_SMALL   = 27             # secondary body, feature descriptions
T_CAPTION = 23             # small labels
KICKER_TRACK = 6           # letter-spacing for kickers (px at T_KICKER)
LINE = 1.45                # body line-height multiplier

# Faint engineering motif (gears + circuit traces) echoing the logo watermark.
MOTIF_COLOR = C.STEEL
MOTIF_OPACITY = 18         # 0-255; very subtle


if __name__ == "__main__":
    # Quick self-check: print the palette and confirm the logo + font resolve.
    print("ItemForge brand tokens")
    print(f"  logo:  {LOGO}  exists={LOGO.exists()}")
    print(f"  font:  {FONT_DISPLAY.name}  exists={FONT_DISPLAY.exists()}")
    for name in ("AZURE", "SKY", "STEEL", "ICE", "FROST", "GLASS", "PALE", "WHITE", "INK"):
        h = getattr(C, name)
        print(f"  {name:8s} {h}  rgb{hex_to_rgb(h)}")

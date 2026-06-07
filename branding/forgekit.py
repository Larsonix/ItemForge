"""
forgekit — reusable image primitives for the ItemForge presentation pipeline.

These are the theme-agnostic building blocks (the parts that ported cleanly from the
Trail of Orbis pipeline), re-tuned cool/light for ItemForge. Composition scripts
(generate.py) import these and arrange them into banners. Keep this file free of any
ItemForge-specific layout — only generic, parameterised primitives live here.
"""

from __future__ import annotations

import math
from functools import lru_cache

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import brand
from brand import C, hex_to_rgb, rgba


# ---------------------------------------------------------------------------
# Fonts
# ---------------------------------------------------------------------------
@lru_cache(maxsize=128)
def font(size: int, body: bool = False, weight: int | None = None) -> ImageFont.FreeTypeFont:
    """Cached font. For body (Inter, a variable font) `weight` selects the weight axis
    (100-900). Each (size, body, weight) gets its own instance, so setting the variation
    here is safe — it never mutates a shared object across callers."""
    path = brand.FONT_BODY if body else brand.FONT_DISPLAY
    f = ImageFont.truetype(str(path), size)
    if body and weight is not None:
        try:
            f.set_variation_by_axes([weight])
        except Exception:
            pass  # non-variable fallback font — ignore
    return f


# RBNo3.1 Black DEMO renders ONLY these glyphs in Pillow (no raqm) — every other
# punctuation mark draws as tofu despite existing in the font's CFF table (a cmap
# quirk of the demo file; the licensed full RBNo3.1 is complete). Verified empirically.
# Use this guard so a stray hyphen/slash/bullet never silently ships as a box.
_SAFE = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 ,.")


def check_renderable(text: str) -> str:
    """Return the set of glyphs in `text` that the display font CANNOT render
    (would tofu). Empty string == safe. Composition code asserts on this."""
    return "".join(sorted({c for c in text if c not in _SAFE}))


def fit_font(text: str, max_width: int, max_size: int, body: bool = False,
             min_size: int = 12, stroke: int = 0) -> ImageFont.FreeTypeFont:
    """Largest font size at which `text` fits within `max_width` (accounting for
    stroke width). Prevents headline clipping regardless of copy length."""
    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    for size in range(max_size, min_size - 1, -2):
        fnt = font(size, body=body)
        l, t, r, b = probe.textbbox((0, 0), text, font=fnt, stroke_width=stroke)
        if (r - l) <= max_width:
            return fnt
    return font(min_size, body=body)


# ---------------------------------------------------------------------------
# Backgrounds
# ---------------------------------------------------------------------------
def vertical_gradient(size, top, bottom, ease: float = 1.0) -> Image.Image:
    """Smooth top->bottom gradient. `ease` > 1 keeps the top colour longer
    (matches the logo, where white dominates the upper third)."""
    w, h = size
    tr, tg, tb = hex_to_rgb(top) if isinstance(top, str) else top
    br, bg, bb = hex_to_rgb(bottom) if isinstance(bottom, str) else bottom
    col = Image.new("RGB", (1, h))
    px = col.load()
    for y in range(h):
        t = (y / max(1, h - 1)) ** ease
        px[0, y] = (
            round(tr + (br - tr) * t),
            round(tg + (bg - tg) * t),
            round(tb + (bb - tb) * t),
        )
    return col.resize((w, h))


@lru_cache(maxsize=1)
def _logo_gradient_column(samples: int = 1024):
    """The logo's vertical gradient, DENOISED into a single clean column.

    Sampling a thin strip and stretching it (the old approach) smeared the logo's
    paper-texture noise into visible horizontal banding. Instead we average a wide
    left-edge strip across X (kills horizontal noise), then Gaussian-smooth along Y
    (kills vertical banding) — yielding a clean column that still tracks the logo's
    exact colour curve, so backgrounds stay in sync with the logo and blend with it."""
    logo = Image.open(brand.LOGO).convert("RGB")
    lw, lh = logo.size
    strip = logo.crop((0, 0, max(48, lw // 16), lh))      # wide left strip (pure background)
    col = strip.resize((1, samples), Image.LANCZOS)        # average across X -> 1px wide
    col = col.filter(ImageFilter.GaussianBlur(7))          # smooth along Y -> no banding
    return col


def logo_gradient_bg(size, logo_path=None) -> Image.Image:
    """Clean page background: the logo's own (denoised) vertical gradient, expanded to
    `size`. Horizontal expansion is uniform, so there are no streaks/artifacts. Because
    it tracks the logo's curve, the logo itself blends seamlessly when placed flush."""
    w, h = size
    return _logo_gradient_column().resize((w, h), Image.LANCZOS)


# ---------------------------------------------------------------------------
# Text
# ---------------------------------------------------------------------------
def _text_size(draw, text, fnt):
    l, t, r, b = draw.textbbox((0, 0), text, font=fnt)
    return r - l, b - t, l, t


def glow_text(img, xy, text, fnt, fill, glow=None, glow_radius=8, anchor="la"):
    """Draw text with a soft outer glow. Returns nothing; mutates img in place."""
    if glow:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        d.text(xy, text, font=fnt, fill=rgba(glow, 230), anchor=anchor)
        layer = layer.filter(ImageFilter.GaussianBlur(glow_radius))
        img.alpha_composite(layer)
    d = ImageDraw.Draw(img)
    d.text(xy, text, font=fnt, fill=rgba(fill) if isinstance(fill, str) else fill, anchor=anchor)


def wordmark_text(img, xy, text, fnt, anchor="la", *, center=C.WM_CENTER, edge=C.WM_EDGE,
                  outline=C.WM_OUTLINE, glow=C.WM_GLOW, outline_w=None, glow_radius=None,
                  bevel=None, glow_alpha=170, bevel_gamma=0.45):
    """Render text in LadyPaladra's exact wordmark style (measured from the logo):

        soft dark-navy outer glow  ->  thin dark-navy contour  ->  center-bright bevel face

    The bevel (white down the centre of each stroke fading to light periwinkle at the edges)
    is reproduced by accumulating successive erosions of the glyph mask: pixels deep inside a
    stroke survive more erosions, so they map brighter. Scales with font size. Mutates `img`."""
    W, H = img.size
    asc, desc = fnt.getmetrics()
    fs = asc + desc
    ow = outline_w if outline_w is not None else max(2, round(fs * 0.05))
    gr = glow_radius if glow_radius is not None else max(5, round(fs * 0.11))
    steps = bevel if bevel is not None else max(3, round(fs * 0.11))

    # Glyph mask (anti-aliased), rendered once on a full-size L layer at the anchor point.
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).text(xy, text, font=fnt, fill=255, anchor=anchor)

    # 1. Outer glow — the dark navy halo that darkens the bg around the letters.
    glow_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow_layer).text(xy, text, font=fnt, fill=rgba(glow, glow_alpha), anchor=anchor,
                                    stroke_width=ow * 2, stroke_fill=rgba(glow, glow_alpha))
    img.alpha_composite(glow_layer.filter(ImageFilter.GaussianBlur(gr)))

    # 2. Dark contour — solid dark-navy text with a stroke; the face (drawn next, smaller)
    #    leaves this showing only as a thin rim.
    base = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(base).text(xy, text, font=fnt, fill=rgba(outline), anchor=anchor,
                              stroke_width=ow, stroke_fill=rgba(outline))
    img.alpha_composite(base)

    # 3. Beveled face — edge->center brightness from accumulated erosions of the glyph mask.
    acc = np.zeros((H, W), dtype=np.float32)
    cur = mask
    for _ in range(steps):
        acc += np.asarray(cur, dtype=np.float32) / 255.0
        cur = cur.filter(ImageFilter.MinFilter(3))
    peak = acc.max()
    t = (acc / peak) if peak > 0 else acc           # 0 at stroke edge -> 1 at stroke centre
    t = np.power(t, bevel_gamma)                     # gamma<1 expands the bright-white core
    t = t[..., None]
    ec = np.array(hex_to_rgb(edge), np.float32)
    cc = np.array(hex_to_rgb(center), np.float32)
    face_rgb = ec * (1 - t) + cc * t                 # HxWx3
    face_a = np.asarray(mask, dtype=np.float32)[..., None]
    face = np.concatenate([face_rgb, face_a], axis=2).astype(np.uint8)
    img.alpha_composite(Image.fromarray(face, "RGBA"))


def tracked_text(img, xy, text, fnt, fill, tracking=0, anchor="la", glow=None, glow_radius=3):
    """Draw text with letter-spacing (tracking) — PIL has no native tracking. Used for
    kickers/eyebrows, where tracked uppercase reads as intentional rather than cramped.
    Supports horizontal anchor l/m/r and vertical a/m."""
    d = ImageDraw.Draw(img)
    widths = [d.textlength(ch, font=fnt) for ch in text]
    total = sum(widths) + tracking * max(0, len(text) - 1)
    x, y = xy
    ha, va = anchor[0], (anchor[1] if len(anchor) > 1 else "a")
    if ha == "m":
        x -= total / 2
    elif ha == "r":
        x -= total
    if glow:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        gd = ImageDraw.Draw(layer)
        cx = x
        for ch, w in zip(text, widths):
            gd.text((cx, y), ch, font=fnt, fill=rgba(glow, 220), anchor="l" + va)
            cx += w + tracking
        img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(glow_radius)))
    fillc = rgba(fill) if isinstance(fill, str) else fill
    cx = x
    for ch, w in zip(text, widths):
        d.text((cx, y), ch, font=fnt, fill=fillc, anchor="l" + va)
        cx += w + tracking


def outlined_text(img, xy, text, fnt, fill, outline=C.OUTLINE, width=6, anchor="la"):
    """Heavy-outline text matching the logo wordmark treatment."""
    d = ImageDraw.Draw(img)
    d.text(
        xy, text, font=fnt,
        fill=rgba(fill) if isinstance(fill, str) else fill,
        stroke_width=width, stroke_fill=rgba(outline) if isinstance(outline, str) else outline,
        anchor=anchor,
    )


# ---------------------------------------------------------------------------
# Shaping / finishing
# ---------------------------------------------------------------------------
def rounded(img: Image.Image, radius: int = brand.RADIUS) -> Image.Image:
    """Round the corners of an RGBA image."""
    img = img.convert("RGBA")
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=radius, fill=255)
    img.putalpha(mask)
    return img


def vignette(img: Image.Image, strength: float = 0.35) -> Image.Image:
    """Darken edges radially. Subtle by default (light theme tolerates little)."""
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([-w * 0.25, -h * 0.25, w * 1.25, h * 1.25], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(min(w, h) // 8))
    dark = Image.new("RGB", (w, h), (0, 0, 0))
    base = img.convert("RGB")
    faded = Image.composite(base, Image.blend(base, dark, strength), mask)
    return faded


def fit_contain(img: Image.Image, max_size) -> Image.Image:
    """Scale an image to fit *within* max_size, preserving aspect — NEVER cropping.
    Returns the fitted image (which fills one dimension of max_size and is <= the other).
    Use for user screenshots: every pixel is preserved."""
    mw, mh = max_size
    sw, sh = img.size
    scale = min(mw / sw, mh / sh)
    return img.resize((max(1, round(sw * scale)), max(1, round(sh * scale))), Image.LANCZOS)


# ---------------------------------------------------------------------------
# Motif — faint engineering gears echoing the logo watermark
# ---------------------------------------------------------------------------
def _gear(draw, cx, cy, r, teeth, color, tooth=0.22, hole=0.4):
    pts = []
    for i in range(teeth * 2):
        ang = math.pi * i / teeth
        rr = r * (1 + tooth) if i % 2 == 0 else r
        pts.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang)))
    draw.polygon(pts, fill=color)
    hr = r * hole
    draw.ellipse([cx - hr, cy - hr, cx + hr, cy + hr], fill=(0, 0, 0, 0))


def gear_motif(size, color=brand.MOTIF_COLOR, opacity=brand.MOTIF_OPACITY) -> Image.Image:
    """A faint, fixed (deterministic) arrangement of gears for banner backgrounds —
    echoes the gear watermark in the logo. Returns an RGBA overlay."""
    w, h = size
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    col = rgba(color, opacity)
    # Deterministic placements (no RNG — reproducible builds): big gear lower-right,
    # smaller ones scattered, mirroring the logo's corner gears.
    for cx, cy, r, teeth in [
        (w * 0.86, h * 0.72, h * 0.30, 14),
        (w * 0.97, h * 0.40, h * 0.16, 11),
        (w * 0.10, h * 0.85, h * 0.12, 10),
    ]:
        # punch the hole by drawing onto a temp then compositing with transparency
        g = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        gd = ImageDraw.Draw(g)
        _gear(gd, cx, cy, r, teeth, col)
        hr = r * 0.4
        gd.ellipse([cx - hr, cy - hr, cx + hr, cy + hr], fill=(0, 0, 0, 0))
        layer.alpha_composite(g)
    return layer


def paste_centered(base: Image.Image, overlay: Image.Image, cx, cy):
    """Alpha-paste `overlay` centered at (cx, cy) on `base` (both RGBA)."""
    x = round(cx - overlay.size[0] / 2)
    y = round(cy - overlay.size[1] / 2)
    base.alpha_composite(overlay.convert("RGBA"), (x, y))


def canvas(width=brand.PAGE_WIDTH, height=700) -> Image.Image:
    """A fresh logo-synced banner background: logo gradient + faint gear motif."""
    bg = logo_gradient_bg((width, height)).convert("RGBA")
    bg.alpha_composite(gear_motif((width, height)))
    return bg


# ---------------------------------------------------------------------------
# Composition: panels, framed screenshots, headers, dividers, feature rows
# ---------------------------------------------------------------------------
def drop_shadow(img: Image.Image, blur: int = 24, offset=(0, 12),
                color=(20, 48, 77), opacity: int = 110) -> Image.Image:
    """Return a new RGBA canvas (padded for the blur) with `img` over its soft shadow."""
    pad = blur * 2
    w, h = img.size
    canvas_ = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", canvas_.size, (0, 0, 0, 0))
    alpha = img.split()[3] if img.mode == "RGBA" else Image.new("L", img.size, 255)
    silhouette = Image.new("RGBA", canvas_.size, (0, 0, 0, 0))
    tint = Image.new("RGBA", img.size, (*color, opacity))
    silhouette.paste(tint, (pad + offset[0], pad + offset[1]), alpha)
    shadow = silhouette.filter(ImageFilter.GaussianBlur(blur))
    canvas_.alpha_composite(shadow)
    canvas_.alpha_composite(img.convert("RGBA"), (pad, pad))
    return canvas_


def panel(size, fill=C.WHITE, radius=brand.RADIUS, border=None, border_w=2) -> Image.Image:
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius,
                        fill=rgba(fill) if isinstance(fill, str) else fill,
                        outline=rgba(border) if border else None, width=border_w)
    return img


def framed_shot(path, max_size, radius=brand.RADIUS, border=C.WHITE, border_w=4,
                shadow=True) -> Image.Image:
    """Load a screenshot, FIT it within `max_size` (contain — never cropped), round the
    corners, add a brand border and a soft drop shadow. Returns RGBA sized to the fitted
    screenshot (+border +shadow pad), so callers place it by centre with no letterboxing."""
    shot = fit_contain(Image.open(path).convert("RGB"), max_size)
    shot = rounded(shot, radius)
    if border_w:
        bw, bh = shot.size[0] + 2 * border_w, shot.size[1] + 2 * border_w
        b = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
        ImageDraw.Draw(b).rounded_rectangle([0, 0, bw - 1, bh - 1], radius=radius + border_w,
                                            fill=rgba(border))
        b.alpha_composite(shot, (border_w, border_w))
        shot = b
    return drop_shadow(shot) if shadow else shot


def section_header(img, xy, kicker, title, title_size=brand.T_TITLE, kicker_color=C.AZURE,
                   anchor_x="la"):
    """Tracked azure eyebrow (with a small azure accent tick) above a big title rendered
    in LadyPaladra's exact logo-wordmark style. `title` must use display-safe glyphs."""
    bad = check_renderable(title)
    assert not bad, f"section title has non-renderable glyphs {bad!r}: {title!r}"
    x, y = xy
    kf = font(brand.T_KICKER, body=True, weight=brand.WEIGHT_BLACK)
    if anchor_x[0] == "l":
        ImageDraw.Draw(img).rounded_rectangle([x, y + 3, x + 6, y + brand.T_KICKER + 1],
                                              radius=3, fill=rgba(kicker_color))
        tracked_text(img, (x + 22, y), kicker.upper(), kf, fill=kicker_color,
                     tracking=brand.KICKER_TRACK, anchor="la")
    else:
        tracked_text(img, (x, y), kicker.upper(), kf, fill=kicker_color,
                     tracking=brand.KICKER_TRACK, anchor=anchor_x[0] + "a")
    wordmark_text(img, (x, y + 46), title, font(title_size), anchor=anchor_x[0] + "a")


def anvil_medallion(diameter, crop=(196, 24, 824, 652), ring=C.WHITE):
    """Circular emblem of just the anvil+sword (cropped from the logo, above the wordmark).

    The white circle is the TRUE outer edge: we lay a full white disc, then inset the
    anvil content by the ring width and paste it on top. That leaves a clean white rim and
    makes it impossible for logo content to peek outside the ring (the previous bug)."""
    rw = max(5, diameter // 13)                          # white rim width
    disc = Image.new("RGBA", (diameter, diameter), (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse([0, 0, diameter - 1, diameter - 1], fill=rgba(ring))  # white disc
    inner = diameter - 2 * rw
    content = Image.open(brand.LOGO).convert("RGB").crop(crop).resize((inner, inner), Image.LANCZOS)
    cmask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(cmask).ellipse([0, 0, inner - 1, inner - 1], fill=255)
    disc.paste(content, (rw, rw), cmask)                 # anvil inset -> white rim = ring
    return drop_shadow(disc, blur=18, offset=(0, 7), color=hex_to_rgb(C.WM_GLOW), opacity=120)


def _taper_line(img, x_solid, x_fade, y, color=C.STEEL, thick=3, glow_radius=3):
    """A horizontal rule that is solid at x_solid and fades to transparent at x_fade
    (works in either direction), with a soft glow. Reads as a 'cool line', not a hard bar."""
    w, h = img.size
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(layer).line([(x_solid, y), (x_fade, y)], fill=rgba(color), width=thick)
    arr = np.asarray(layer).copy()
    xs = np.arange(w, dtype=np.float32)
    span = max(1, abs(x_fade - x_solid))
    ramp = np.clip((x_fade - xs) / span if x_fade >= x_solid else (xs - x_fade) / span, 0, 1)
    arr[:, :, 3] = (arr[:, :, 3].astype(np.float32) * ramp[None, :]).astype(np.uint8)
    faded = Image.fromarray(arr, "RGBA")
    img.alpha_composite(faded.filter(ImageFilter.GaussianBlur(glow_radius)))
    img.alpha_composite(faded)


def _diamond(img, cx, cy, r, color):
    ImageDraw.Draw(img).polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)],
                                fill=rgba(color))


def divider(width=brand.PAGE_WIDTH, height=190):
    """Section connector. Bridges stacked banners: the gradient runs azure (top, continuing
    the banner above) -> white (bottom, flowing into the banner below) — the page gradient
    reversed, so the seam disappears. No card background; just a tapered line with the
    anvil+sword medallion centred on it."""
    bg = logo_gradient_bg((width, height)).transpose(Image.FLIP_TOP_BOTTOM).convert("RGBA")
    cx, cy = width // 2, height // 2
    diameter = int(height * 0.60)        # leaves margin around the medallion so its drop shadow
    r = diameter // 2                    # fades fully inside the band (no cutoff at the seam)
    gap = 30
    _taper_line(bg, cx - r - gap, brand.MARGIN, cy)            # left: solid by medallion -> fade out
    _taper_line(bg, cx + r + gap, width - brand.MARGIN, cy)    # right
    _diamond(bg, cx - r - gap, cy, 7, C.AZURE)
    _diamond(bg, cx + r + gap, cy, 7, C.AZURE)
    paste_centered(bg, anvil_medallion(diameter), cx, cy)
    return bg


def body_text(img, xy, text, size=brand.T_BODY, color=C.INK, weight=None, anchor="la",
              glow=None, glow_radius=4):
    """Single-line body copy in Inter. Glow defaults OFF (crisp dark ink on the light page).
    Use paragraph() for anything that may wrap."""
    glow_text(img, xy, text, font(size, body=True, weight=weight),
              fill=color, glow=glow, glow_radius=glow_radius, anchor=anchor)


def wrap_text(text, fnt, max_width: int) -> list[str]:
    """Greedy word-wrap to a pixel width."""
    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    def w(s):
        l, t, r, b = probe.textbbox((0, 0), s, font=fnt)
        return r - l
    lines, cur = [], ""
    for word in text.split():
        trial = f"{cur} {word}".strip()
        if w(trial) <= max_width or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def paragraph(img, xy, text, max_width, size=brand.T_BODY, color=C.INK, weight=None,
              line_spacing=brand.LINE, anchor="la", glow=None, glow_radius=4) -> int:
    """Wrapped body copy in Inter. Glow defaults OFF — a white glow on the light page
    background haloes and washes the text; crisp dark ink reads far better. Returns the y
    after the block so callers can stack. anchor 'l*'=left, 'm*'=centered, 'r*'=right."""
    fnt = font(size, body=True, weight=weight)
    lines = wrap_text(text, fnt, max_width)
    x, y = xy
    lh = round(size * line_spacing)
    ha = anchor[0]
    for i, line in enumerate(lines):
        glow_text(img, (x, y + i * lh), line, fnt, fill=color, glow=glow,
                  glow_radius=glow_radius, anchor=ha + "a")
    return y + len(lines) * lh


def save(img: Image.Image, name: str):
    brand.OUTPUT.mkdir(parents=True, exist_ok=True)
    out = brand.OUTPUT / name
    rgb = img.convert("RGB")
    if rgb.size[0] > brand.EXPORT_WIDTH:                 # downscale 1700 -> 848 (supersampled)
        w, h = rgb.size
        rgb = rgb.resize((brand.EXPORT_WIDTH, round(h * brand.EXPORT_WIDTH / w)), Image.LANCZOS)
        # NOTE: deliberately NO unsharp mask. On the high-contrast wordmark it boosts edges that
        # JPEG then turns into visible ringing ("pixelated"), and it bloats PNG past 2MB. The
        # LANCZOS supersample is already crisp.
    rgb.save(out, "PNG", optimize=True)
    print(f"  wrote {out.relative_to(brand.ROOT)}  ({rgb.size[0]}x{rgb.size[1]})")
    return out

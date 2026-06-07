"""
Branded animated GIF banners from screen-recording clips.

CurseForge can't host raw video, but it embeds GIFs (same limits as images: width <850px,
file <2MB). This turns a clip into an on-brand animated banner — kicker + wordmark title +
the footage in a framed region, matching the static page banners.

Encoding uses **gifski** (the best GIF encoder — per-frame local palettes + temporal dithering),
not a hand-rolled single 256-color palette. That fixes colour fidelity: gradients, glow and fire
render true instead of being mapped to a wrong global palette. We composite each branded frame
(static template + current video frame), write the PNG sequence, and let gifski encode it,
stepping fps/quality down only as needed to fit under 2MB.

Usage:
    from gifbanner import build_gif_banner
    build_gif_banner(VIDEO, "See it live", "EDIT IN THE WORLD", "gif_editor.gif",
                     start=0.0, dur=999)
"""

import os
import shutil
import subprocess
import tempfile

import cv2
import numpy as np
from PIL import Image, ImageDraw

import brand
import forgekit as fk
from brand import C

LIMIT = 1_950_000          # CurseForge 2MB cap (with margin)
VIDEO_ASPECT = 2560 / 1370
FPS_BASE = 15              # decode/composite once at this rate; gifski steps fps DOWN to fit
GIFSKI = brand.ROOT / "tools" / "gifski.exe"


def _read_frames(video, start, dur, fps, size):
    """Decode `video` from start..start+dur at `fps`, each frame resized to `size` (RGB ndarray)."""
    cap = cv2.VideoCapture(video)
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(src_fps / fps))
    first = int(start * src_fps)
    last = int((start + dur) * src_fps)
    frames, i = [], 0
    cap.set(cv2.CAP_PROP_POS_FRAMES, first)
    while True:
        ok, fr = cap.read()
        if not ok or first + i > last:
            break
        if i % step == 0:
            rgb = cv2.cvtColor(fr, cv2.COLOR_BGR2RGB)
            frames.append(np.asarray(Image.fromarray(rgb).resize(size, Image.LANCZOS)))
        i += 1
    cap.release()
    return frames


def _build_template(kicker, title):
    """Static banner (rendered at 1700, downscaled to 848 like the other banners). Returns the
    848px template plus the inner video rect (x, y, w, h) in 848 coords + its rounded mask."""
    W, M = brand.PAGE_WIDTH, brand.MARGIN
    RX, RY, RW = 150, 360, 1400
    RH = round(RW / VIDEO_ASPECT)
    H = RY + RH + 70
    tpl = fk.canvas(W, H)
    fk.section_header(tpl, (M, 60), kicker, title)
    bw = 8
    border = fk.panel((RW + 2 * bw, RH + 2 * bw), fill=C.WHITE, radius=brand.RADIUS + bw)
    shadow = fk.drop_shadow(border, blur=20, offset=(0, 10),
                            color=fk.hex_to_rgb(C.WM_GLOW), opacity=120)
    fk.paste_centered(tpl, shadow, RX + RW // 2, RY + RH // 2)

    sc = brand.EXPORT_WIDTH / W
    tpl848 = tpl.convert("RGB").resize((brand.EXPORT_WIDTH, round(H * sc)), Image.LANCZOS)
    rx, ry, rw, rh = round(RX * sc), round(RY * sc), round(RW * sc), round(RH * sc)
    mask = Image.new("L", (rw, rh), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, rw - 1, rh - 1],
                                           radius=round(brand.RADIUS * sc), fill=255)
    return tpl848, (rx, ry, rw, rh), mask


def _gifski(names, tmp, out, fps, quality):
    """Run gifski on the relative PNG frame names in `tmp`. Returns the output size in bytes."""
    cmd = [str(GIFSKI), "-o", str(out), "-r", str(fps), "-Q", str(quality), "--quiet", "--", *names]
    subprocess.run(cmd, cwd=tmp, check=True, capture_output=True)
    return out.stat().st_size


def build_gif_banner(video, kicker, title, out_name, start=0.0, dur=999.0):
    """Build a branded animated GIF banner under CurseForge's 2MB cap, encoded with gifski.

    Composites every branded frame once (at FPS_BASE), then asks gifski to encode, stepping the
    frame-rate/quality down a ladder only until it fits. Long clips lose fps, never get trimmed."""
    brand.OUTPUT.mkdir(parents=True, exist_ok=True)
    out = brand.OUTPUT / out_name
    tpl, (rx, ry, rw, rh), mask = _build_template(kicker, title)

    # Composite branded frames once and write the PNG sequence to a temp dir.
    vids = _read_frames(video, start, dur, FPS_BASE, (rw, rh))
    tmp = tempfile.mkdtemp(prefix="ifgif_")
    names = []
    for i, v in enumerate(vids):
        f = tpl.copy()
        f.paste(Image.fromarray(v), (rx, ry), mask)
        nm = f"f{i:05d}.png"
        f.save(os.path.join(tmp, nm))
        names.append(nm)

    # gifski ladder: prefer smooth fps, trading quality before frame-rate. Targets are reachable
    # by subsampling the 15fps base (15, ~8, 5, ~4, 3). Stops at the first that fits under 2MB.
    ladder = [(15, 85), (15, 70), (8, 82), (8, 62), (5, 72), (5, 55), (4, 58), (3, 55)]
    chosen = None
    try:
        for fps, quality in ladder:
            stride = max(1, round(FPS_BASE / fps))
            sel = names[::stride]
            eff = round(FPS_BASE / stride)
            size = _gifski(sel, tmp, out, eff, quality)
            chosen = (eff, quality, len(sel), size)
            if size <= LIMIT:
                break
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    eff, quality, nf, size = chosen
    print(f"  wrote {out.name}  ({tpl.size[0]}x{tpl.size[1]})  {nf}f @ {eff}fps Q{quality}  "
          f"{size/1_000_000:.2f}MB  (gifski)")
    return out


# Author's capture folder; override with the ITEMFORGE_SHOTS env var to point at your own.
SHOTS = os.environ.get("ITEMFORGE_SHOTS", os.path.expanduser("~/Pictures/Screenshots/ItemForge"))
FULL = 9999.0   # use the entire clip (clamped to its real length) — the recordings are pre-trimmed
GIFS = [
    (f"{SHOTS}/Enregistrement 2026-05-30 212814.mp4", "See it live", "EDIT IN THE WORLD",
     "gif_editor.gif", 0.0, FULL),
    (f"{SHOTS}/Enregistrement 2026-05-30 213325.mp4", "Weapons & gear", "TUNE DAMAGE LIVE",
     "gif_weapon.gif", 0.0, FULL),
    (f"{SHOTS}/Enregistrement 2026-05-30 213822.mp4", "Live recipes", "CRAFT IT LIVE",
     "gif_recipe.gif", 0.0, FULL),
]

if __name__ == "__main__":
    for video, kicker, title, name, start, dur in GIFS:
        build_gif_banner(video, kicker, title, name, start=start, dur=dur)

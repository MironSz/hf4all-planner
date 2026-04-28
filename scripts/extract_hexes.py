"""
Extract hex corner coordinates for every site on the HF4A board image.

No siteSize prior is used — radius is inferred from the image itself.

Per-site pipeline:
  1. r_max = min(R_MAX_ABS, 0.45 × distance to nearest other site centre).
     r_min = R_MIN.
  2. Joint (r, θ) search with a SHARP-TRANSITION score. For every (r, θ),
     sample 360 points along the candidate hex boundary and at offsets
     0.85·r and 1.15·r along the same rays. The per-sample score is
       (g_boundary − ½ g_inside − ½ g_outside)
     i.e. gradient at the boundary minus the average gradient just inside
     and just outside. The candidate score is the MEDIAN of these
     per-sample scores. This punishes both oversized fits (whose boundary
     drifts through random features so g_inside/g_outside ≈ g_boundary)
     and 30°-rotated fits (which sample sparse strong features that don't
     show a true sharp transition). Only a real hex edge — high at the
     line and low on either side — scores positively in a robust way.
  3. Site-local colours, computed AFTER the fit:
       interior = median in disk of 0.4·a
       exterior = median in annulus [1.1·r, 1.3·r]
  4. Corners by closed-form trigonometry from (cx, cy, r, θ).
  5. Per-corner visibility: in a disk of 0.25·a, count pixels whose RGB is
     closer to interior than to exterior. Visible iff that ratio is in
     [0.10, 0.55] — captures both the geometric 1/3 expectation and tolerates
     in-corner artwork (cyan markers, label text).
  6. Quality = mean_boundary_gradient / 50, capped at 1.0.

Inputs
  src/main/resources/data-hf4-v2.json
  src/main/resources/static/hf4.jpg

Outputs
  src/main/resources/static/hexes.json
  target/hex-overlay.png  (skip with --no-overlay)

Usage
  python scripts/extract_hexes.py
  python scripts/extract_hexes.py --no-overlay
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
MAP_JSON = ROOT / "src/main/resources/data-hf4-v2.json"
MAP_IMG  = ROOT / "src/main/resources/static/hf4.jpg"
OUT_JSON = ROOT / "src/main/resources/static/hexes.json"
OUT_PNG  = ROOT / "target/hex-overlay.png"

R_MIN          = 22         # smallest plausible hex; below this we'd lock onto inner icons
R_MAX_FACTOR   = 0.45
R_MAX_ABS      = 100         # absolute upper bound on hex circumradius (px)
RAYS           = 360
THETA_STEP_DEG = 1
QUALITY_NORM   = 150.0       # boundary-median × sharp-median treated as "ideal" → quality 1.0
SHARP_DELTA_PX = 3.0         # absolute radial offset for the inner/outer reference samples
VIS_LO         = 0.10
VIS_HI         = 0.55


def load_sites():
    with open(MAP_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)
    sites = []
    for sid, p in data["points"].items():
        if p.get("type") != "site":
            continue
        sites.append({"id": sid, "x": float(p["x"]), "y": float(p["y"])})
    return sites


def nearest_neighbor_distances(sites, w, h):
    pts = np.array([[s["x"] * w, s["y"] * h] for s in sites], dtype=np.float64)
    n = len(pts)
    out = np.full(n, np.inf)
    for i in range(n):
        d = np.hypot(pts[:, 0] - pts[i, 0], pts[:, 1] - pts[i, 1])
        d[i] = np.inf
        out[i] = d.min()
    return out


def grad_magnitude(gray):
    f = gray.astype(np.float32)
    gy, gx = np.gradient(f)
    return np.hypot(gx, gy)


def fit_hex_joint(grad_mag, cx, cy, r_min, r_max):
    """Joint search over (r, θ). Score a candidate by sampling 360 points along
    the candidate hex boundary and taking the mean gradient magnitude."""
    H, W = grad_mag.shape
    phis = np.linspace(0, 2 * np.pi, RAYS, endpoint=False).astype(np.float32)
    cos_phi = np.cos(phis)
    sin_phi = np.sin(phis)
    rs = np.arange(int(r_min), int(r_max) + 1, dtype=np.float32)
    if len(rs) < 2:
        return None

    cos30 = math.cos(math.radians(30))
    best = None
    for theta_deg in range(0, 60, THETA_STEP_DEG):
        theta_rad = np.float32(math.radians(theta_deg))
        # phi_local = signed angular distance to nearest APOTHEM direction.
        # Apothem directions for a hex with corners at theta + k·60° are at
        # theta + 30° + k·60°. So phi_local = ((phi - theta) mod 60°) - 30°
        # (NOT (phi - theta - 30°) mod 60° — that gives the angle from a corner,
        # which rotates the scored boundary 30° from the emitted corners).
        phi_offset = ((phis - theta_rad) % (math.pi / 3.0)) - math.pi / 6.0
        # Boundary distance: r·cos(30°)/cos(phi_local)
        # - apothem direction (phi_local = 0)     -> r·cos(30°)
        # - corner  direction (phi_local = ±π/6)  -> r
        d_unit = cos30 / np.cos(phi_offset)            # (RAYS,)
        d_b = rs[:, None] * d_unit[None, :]            # (M, RAYS) boundary
        d_i = d_b - SHARP_DELTA_PX
        d_o = d_b + SHARP_DELTA_PX
        # Sample gradient at boundary, just inside, and just outside.
        def _sample(d):
            xs = (cx + d * cos_phi[None, :]).round().astype(np.int32)
            ys = (cy + d * sin_phi[None, :]).round().astype(np.int32)
            ib = (xs >= 0) & (xs < W) & (ys >= 0) & (ys < H)
            np.clip(xs, 0, W - 1, out=xs)
            np.clip(ys, 0, H - 1, out=ys)
            return grad_mag[ys, xs].astype(np.float32), ib
        g_b, ib_b = _sample(d_b)
        g_i, ib_i = _sample(d_i)
        g_o, ib_o = _sample(d_o)
        # Score per sample: combine median boundary gradient (overall strength)
        # with sharpness (boundary minus inner/outer). Median is robust against
        # sparse high-gradient features that mislead a pure-mean score.
        valid = ib_b & ib_i & ib_o
        g_b_m = np.where(valid, g_b, np.nan)
        sharp = g_b - 0.5 * (g_i + g_o)
        sharp_m = np.where(valid, sharp, np.nan)
        med_b      = np.nanmedian(g_b_m,   axis=1)
        med_sharp  = np.nanmedian(sharp_m, axis=1)
        # Combined: a fit must have both a respectable boundary gradient AND
        # a sharp transition. Multiplying penalises either being weak.
        scores = np.maximum(med_b, 0.0) * np.maximum(med_sharp, 0.0)
        scores = np.nan_to_num(scores, nan=0.0)
        m = int(scores.argmax())
        s = float(scores[m])
        if best is None or s > best["score"]:
            best = {"score": s, "r": float(rs[m]), "theta_deg": theta_deg,
                    "med_b": float(med_b[m]) if not np.isnan(med_b[m]) else 0.0,
                    "med_sharp": float(med_sharp[m]) if not np.isnan(med_sharp[m]) else 0.0}
    return best


def site_local_colors(rgb, cx, cy, r):
    H, W = rgb.shape[:2]
    apothem = r * math.cos(math.radians(30))
    crop_r = int(1.5 * r) + 5
    x0 = max(0, int(cx - crop_r))
    x1 = min(W, int(cx + crop_r))
    y0 = max(0, int(cy - crop_r))
    y1 = min(H, int(cy + crop_r))
    sub = rgb[y0:y1, x0:x1].astype(np.float32)
    sH, sW = sub.shape[:2]
    yy, xx = np.ogrid[:sH, :sW]
    dist = np.hypot(xx - (cx - x0), yy - (cy - y0))
    int_mask = dist < 0.4 * apothem
    ext_mask = (dist >= 1.1 * r) & (dist <= 1.3 * r)
    if not int_mask.any() or not ext_mask.any():
        return None, None
    interior = np.median(sub[int_mask].reshape(-1, 3), axis=0)
    exterior = np.median(sub[ext_mask].reshape(-1, 3), axis=0)
    return interior, exterior


def compute_corners(cx, cy, r, theta_deg):
    theta_rad = math.radians(theta_deg)
    return [
        (cx + r * math.cos(theta_rad + math.radians(60 * k)),
         cy + r * math.sin(theta_rad + math.radians(60 * k)))
        for k in range(6)
    ]


def corner_visibility(rgb, corner_xy, disk_r, interior, exterior):
    H, W = rgb.shape[:2]
    cx, cy = corner_xy
    rr = int(disk_r) + 1
    x0 = max(0, int(cx) - rr)
    x1 = min(W, int(cx) + rr + 1)
    y0 = max(0, int(cy) - rr)
    y1 = min(H, int(cy) + rr + 1)
    sub = rgb[y0:y1, x0:x1].astype(np.float32)
    if sub.size == 0:
        return False, 0.0
    sH, sW = sub.shape[:2]
    yy, xx = np.ogrid[:sH, :sW]
    dist = np.hypot(xx - (cx - x0), yy - (cy - y0))
    mask = dist <= disk_r
    if not mask.any():
        return False, 0.0
    pixels = sub[mask].reshape(-1, 3)
    d_int = np.linalg.norm(pixels - interior, axis=1)
    d_ext = np.linalg.norm(pixels - exterior, axis=1)
    interior_ratio = float((d_int < d_ext).sum()) / float(mask.sum())
    visible = bool(VIS_LO <= interior_ratio <= VIS_HI)
    return visible, interior_ratio


def detect_hex(rgb, grad_mag, w, h, site, r_max_px):
    cx = site["x"] * w
    cy = site["y"] * h
    r_min = R_MIN
    r_max = max(r_min + 5, min(int(r_max_px), R_MAX_ABS))

    fit = fit_hex_joint(grad_mag, cx, cy, r_min, r_max)
    if fit is None:
        return None

    r = fit["r"]
    theta_deg = fit["theta_deg"]
    apothem = r * math.cos(math.radians(30))

    interior, exterior = site_local_colors(rgb, cx, cy, r)
    if interior is None:
        interior = np.array([0.0, 0.0, 0.0])
        exterior = np.array([128.0, 128.0, 128.0])

    corners_px = compute_corners(cx, cy, r, theta_deg)

    disk_r = max(3.0, 0.25 * apothem)
    vis_flags, vis_ratios = [], []
    for corner in corners_px:
        v, ratio = corner_visibility(rgb, corner, disk_r, interior, exterior)
        vis_flags.append(v)
        vis_ratios.append(ratio)

    quality = min(1.0, fit["score"] / QUALITY_NORM)

    return {
        "id": site["id"],
        "cx_px": cx,
        "cy_px": cy,
        "r_px": float(r),
        "apothem_px": float(apothem),
        "theta_deg": int(theta_deg),
        "score": float(fit["score"]),
        "quality": float(quality),
        "corners_px": corners_px,
        "corners": [(x / w, y / h) for (x, y) in corners_px],
        "visible": vis_flags,
        "interior_ratios": vis_ratios,
    }


def write_overlay(map_img_path, results, out_path):
    base = Image.open(map_img_path).convert("RGB")
    drw = ImageDraw.Draw(base, "RGBA")
    for r in results:
        if r is None:
            continue
        q = r["quality"]
        if q >= 0.6:
            color = (60, 230, 110, 220)
        elif q >= 0.3:
            color = (255, 220, 0, 220)
        else:
            color = (255, 60, 60, 230)
        pts = list(r["corners_px"]) + [r["corners_px"][0]]
        drw.line(pts, fill=color, width=4)
        drw.ellipse(
            [r["cx_px"] - 4, r["cy_px"] - 4, r["cx_px"] + 4, r["cy_px"] + 4],
            fill=color,
        )
        for corner, vis in zip(r["corners_px"], r["visible"]):
            cx, cy = corner
            if vis:
                drw.ellipse([cx - 3, cy - 3, cx + 3, cy + 3],
                            outline=(255, 255, 255, 255), width=1)
            else:
                drw.line([(cx - 6, cy - 6), (cx + 6, cy + 6)],
                         fill=(255, 0, 0, 255), width=2)
                drw.line([(cx - 6, cy + 6), (cx + 6, cy - 6)],
                         fill=(255, 0, 0, 255), width=2)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    base.save(out_path, "PNG")


def main():
    no_overlay = "--no-overlay" in sys.argv

    sites = load_sites()
    print(f"Loaded {len(sites)} sites from {MAP_JSON.name}")

    img_pil = Image.open(MAP_IMG).convert("RGB")
    rgb = np.asarray(img_pil)
    H, W = rgb.shape[:2]
    print(f"Image: {W} x {H}")

    gray = np.asarray(img_pil.convert("L"))
    grad = grad_magnitude(gray)

    nearest = nearest_neighbor_distances(sites, W, H)

    results = []
    buckets = {"good": 0, "uncertain": 0, "bad": 0, "fail": 0}
    hidden_total = 0
    for i, s in enumerate(sites, 1):
        r_max_px = R_MAX_FACTOR * nearest[i - 1]
        out = detect_hex(rgb, grad, W, H, s, r_max_px)
        results.append(out)
        if out is None:
            buckets["fail"] += 1
        else:
            hidden_total += sum(1 for v in out["visible"] if not v)
            if out["quality"] >= 0.6:
                buckets["good"] += 1
            elif out["quality"] >= 0.3:
                buckets["uncertain"] += 1
            else:
                buckets["bad"] += 1
        if i % 25 == 0:
            print(f"  {i}/{len(sites)}")

    out_map = {}
    for r in results:
        if r is None:
            continue
        out_map[r["id"]] = {
            "r": r["r_px"] / W,
            "apothem": r["apothem_px"] / W,
            "theta": r["theta_deg"],
            "quality": r["quality"],
            "corners": [[x, y] for (x, y) in r["corners"]],
            "visible": r["visible"],
        }
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(out_map, f, indent=2)
    print(f"Wrote {OUT_JSON} ({len(out_map)} entries)")
    print(f"Quality: good={buckets['good']}  uncertain={buckets['uncertain']}  "
          f"bad={buckets['bad']}  fail={buckets['fail']}")
    print(f"Hidden corners flagged: {hidden_total}")

    if not no_overlay:
        print("Rendering debug overlay...")
        write_overlay(MAP_IMG, results, OUT_PNG)
        print(f"Wrote {OUT_PNG}")


if __name__ == "__main__":
    main()

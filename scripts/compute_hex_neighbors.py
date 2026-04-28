"""
For each hex, compute the list of neighbouring hexes and celestial bodies.

A hex is a NEIGHBOUR of another hex/body if, after the hex is enlarged from
its centroid by ENLARGE_FACTOR (30 %), the enlarged polygon intersects the
other hex/body's geometry.

Convex-polygon ↔ convex-polygon intersection: separating-axis theorem.
Convex-polygon ↔ circle intersection: point-in-poly OR min edge distance ≤ r.

Inputs (all merged in the same way the server does):
  src/main/resources/static/hexes.json           (fresh, from extract_hexes.py)
  src/main/resources/static/hexes-edited.json    (user overrides)
  src/main/resources/static/bodies.json
  src/main/resources/static/bodies-edited.json   ({removed:true} entries are
                                                   dropped, mirroring
                                                   CelestialBodyEditorHandler)

Output:
  src/main/resources/static/hex-neighbors.json

Format:
  { "<sid>": { "sites":  ["<sid>", ...],
               "bodies": ["Mars", ...] } , ... }
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
HEXES_FRESH    = ROOT / "src/main/resources/static/hexes.json"
HEXES_EDITED   = ROOT / "src/main/resources/static/hexes-edited.json"
BODIES_FRESH   = ROOT / "src/main/resources/static/bodies.json"
BODIES_EDITED  = ROOT / "src/main/resources/static/bodies-edited.json"
MAP_IMG        = ROOT / "src/main/resources/static/hf4.jpg"
OUT_PATH       = ROOT / "src/main/resources/static/hex-neighbors.json"

ENLARGE_FACTOR = 1.30   # hex grown 30 % from its centroid before testing


def load_image_dims():
    with Image.open(MAP_IMG) as im:
        return im.size  # (W, H)


def merge(fresh_path: Path, edited_path: Path, drop_removed: bool):
    with open(fresh_path, "r", encoding="utf-8") as f:
        merged = json.load(f)
    if edited_path.exists():
        with open(edited_path, "r", encoding="utf-8") as f:
            edits = json.load(f)
        for k, v in edits.items():
            if drop_removed and isinstance(v, dict) and v.get("removed"):
                merged.pop(k, None)
            else:
                merged[k] = v
    return merged


def scale_polygon(poly, factor):
    cx = sum(p[0] for p in poly) / len(poly)
    cy = sum(p[1] for p in poly) / len(poly)
    return [(cx + (x - cx) * factor, cy + (y - cy) * factor) for (x, y) in poly]


def polygons_intersect(poly_a, poly_b):
    """Separating-axis theorem for two convex polygons. Returns True if they
    overlap (sharing an interior). Touching at a point/edge counts as
    overlap."""
    for poly in (poly_a, poly_b):
        n = len(poly)
        for i in range(n):
            ax, ay = poly[i]
            bx, by = poly[(i + 1) % n]
            nx, ny = -(by - ay), (bx - ax)         # outward edge normal
            proj_a = [p[0] * nx + p[1] * ny for p in poly_a]
            proj_b = [p[0] * nx + p[1] * ny for p in poly_b]
            if max(proj_a) < min(proj_b) or max(proj_b) < min(proj_a):
                return False
    return True


def point_in_polygon(x, y, poly):
    n = len(poly)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and \
           (x < (xj - xi) * (y - yi) / ((yj - yi) or 1e-12) + xi):
            inside = not inside
        j = i
    return inside


def dist_point_segment(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def polygon_circle_intersect(poly, cx, cy, r):
    if point_in_polygon(cx, cy, poly):
        return True
    n = len(poly)
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % n]
        if dist_point_segment(cx, cy, ax, ay, bx, by) <= r:
            return True
    return False


def main():
    W, H = load_image_dims()
    print(f"Image: {W} × {H}  enlarge factor: {ENLARGE_FACTOR}")

    hexes  = merge(HEXES_FRESH, HEXES_EDITED, drop_removed=False)
    bodies = merge(BODIES_FRESH, BODIES_EDITED, drop_removed=True)
    print(f"Hexes:  {len(hexes)}")
    print(f"Bodies: {len(bodies)}")

    # Pixel-space geometry. Hex corners are stored as (x_frac_of_W, y_frac_of_H);
    # body radius is stored as a fraction of W (matches extract_bodies.py).
    hex_polys = {
        sid: [(c[0] * W, c[1] * H) for c in h["corners"]]
        for sid, h in hexes.items()
    }
    body_circles = [
        (name, b["cx"] * W, b["cy"] * H, b["r"] * W)
        for name, b in bodies.items()
    ]

    out = {}
    for sid, poly in hex_polys.items():
        enlarged = scale_polygon(poly, ENLARGE_FACTOR)
        nb_sites = [
            sid2 for sid2, poly2 in hex_polys.items()
            if sid2 != sid and polygons_intersect(enlarged, poly2)
        ]
        nb_bodies = [
            name for name, cx, cy, r in body_circles
            if polygon_circle_intersect(enlarged, cx, cy, r)
        ]
        # Stable ordering: numeric site ids ascending, body names alphabetic.
        nb_sites.sort(key=lambda s: (0, int(s)) if s.isdigit() else (1, s))
        nb_bodies.sort()
        out[sid] = {"sites": nb_sites, "bodies": nb_bodies}

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    print(f"Wrote {OUT_PATH}")

    total_sites  = sum(len(v["sites"])  for v in out.values())
    total_bodies = sum(len(v["bodies"]) for v in out.values())
    print(f"  total site links:  {total_sites}  (avg {total_sites / len(out):.1f}/hex)")
    print(f"  total body links:  {total_bodies} (avg {total_bodies / len(out):.2f}/hex)")

    # Sample of densest and sparsest hexes.
    by_site_count = sorted(out.items(), key=lambda kv: len(kv[1]["sites"]))
    print("\nFew-neighbour hexes:")
    for sid, v in by_site_count[:5]:
        print(f"  {sid:>4}  sites={len(v['sites']):>2}  bodies={v['bodies']}")
    print("\nMany-neighbour hexes:")
    for sid, v in by_site_count[-5:]:
        print(f"  {sid:>4}  sites={len(v['sites']):>2}  bodies={v['bodies']}")
    print("\nHexes touching a celestial body:")
    n_with_body = sum(1 for v in out.values() if v["bodies"])
    print(f"  {n_with_body}/{len(out)}")
    by_body = {}
    for v in out.values():
        for b in v["bodies"]:
            by_body[b] = by_body.get(b, 0) + 1
    for b in sorted(by_body):
        print(f"  {b:9}  {by_body[b]:>3} hex(es)")


if __name__ == "__main__":
    main()

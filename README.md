# HF4A Mission Planner

A multi-objective Pareto-optimal route planner for the **High Frontier 4 All** boardgame. Pick a ship configuration and a starting node on the printed map; the planner tells you every Pareto-optimal route to every reachable site, and lets you visualise and compare them on top of the original board image.

---

## What the planner offers

- **Plan from any node to any site.** Click a node on the map; the search runs and every reachable site lights up. Hover or click a site to see the route.
- **Multi-objective Pareto routes.** For each destination the planner keeps every route that isn't strictly worse than another — by remaining fuel, hazards endured, turns spent, radiation roll, etc. Cycle through them with the arrow keys or the `◄`/`►` buttons.
- **Per-turn segment readout.** Each leg of a route gets a small panel on the map showing turn number, engine in use, weight class, effective thrust, burns spent, fuel-steps consumed, and any fuel jettison.
- **Multiple engines per ship.** Add several thrusters with their own thrust, fuel-cost, pivot bonuses, and solar-power flags; the planner picks the best one for each leg.
- **Site filter.** Filter destinations by spectral type, hydration, modifiers, or synodic-comet flag. Matching sites get a bright green hex highlight; the rest of the map dims.
- **Toggle masks.** Hide unreachable sites, hide filter-mismatch sites, or both — the corresponding hexes are painted over with the surrounding map colour so the relevant set stands out.
- **Multi-tab plans.** Open several plans side-by-side and switch between them; each tab keeps its own ship config, start node, pinned route, and zoom transform.
- **Tooltips and an in-app "How to use" cheat-sheet** explain every input.

---

## Architecture: backend / frontend split

The project is two cleanly separated halves wired together by a small JSON HTTP API.

### Backend — Java 21 + JDK `com.sun.net.httpserver`

Everything in `src/main/java/com/hf4all/planner/`:

- **`pathfinder/`** — the core solver. A Pareto-optimal best-first search over `SearchState` (node, engine, fuel, partial fuel, weight class, hazards, radiation, turn, parent…) with a frontier keyed by full comparability context. Implements HF4A movement, lagrange free turns, hohmann pivots, force-turns, weight-class modifiers, solar zones, fuel jettison (lazy), and Pareto frontiers per (site, route).
- **`model/`** — domain primitives. `MapNode`, `Fraction` (exact rational fuel), `FuelStrip` (the non-linear wet-mass strip), `SolarMap` (the loaded graph).
- **`io/`** — `MapLoader` reads the JSON board into `SolarMap`.
- **`server/`** — minimal HTTP layer with one handler per endpoint:
  - `IndexHandler` — serves static assets (HTML, JS, JPG, …).
  - `MapHandler` — `GET /api/map` streams the cached map JSON.
  - `TraverseHandler` — `POST /api/traverse` runs the pathfinder and returns the search-tree response.
  - `ConfigHandler` — `GET /api/config` exposes UI/search tunables.
- **`config/`** — `Config` reads tunables from `application.properties` (cache TTLs, search limits, UI defaults).
- **`tools/`** — one-shot scripts for editing/migrating the map data (renaming node ids, injecting solar-zone modifiers, annotating landing burns, etc.).
- **`bench/`** — `BenchmarkRun` for repeatable performance measurements.

The backend is stateless per request: each `/api/traverse` call constructs a fresh `Pathfinder` instance, runs it, returns the tree.

### Frontend — single-page app, vanilla JS + D3

Everything in `src/main/resources/static/`:

- **`index.html`** — the entire UI: HTML, CSS, JS in one file, ~2 000 lines.
- **`d3-*.min.js`** — a few focused D3 modules (selection, zoom, drag, transitions). No build step, no framework.
- **`hf4.jpg`** — the printed HF4A board image (5400 × 3619), used as a backdrop.
- A `<canvas>` overlay on top of the board is where the planner paints route lines, segment-info panels, the start-node highlight, and the filter-match hex outlines.
- An SVG layer above the canvas is used for the hex-mask overlay (painting over unreachable / filter-mismatch sites with sampled local colours).

The frontend fetches `/api/config` and `/api/map` once at startup and `/hexes.json`, `/bodies.json`, `/hex-neighbors.json` for the geometric overlays. Every user input fires a fresh `/api/traverse` request and the response is cached per tab.

---

## JSON data files

### Backend resources (`src/main/resources/`)

- **`data-hf4-v2.json`** — current source-of-truth board data: every node (lagrange, burn, hohmann, site, decorative), the edges between them, site-specific metadata (name, size class, water rating, modifiers), hazards, solar-zone modifiers. Loaded by `MapLoader` into `SolarMap`. Served as-is via `/api/map`.
- **`data-hf4.json`** — the older v1 board (kept for reference and for the migration tools in `tools/`).
- **`id-mapping.json`** — node-id rename history from the v1 → v2 migration; consumed by `RenameNodeIds` so any external references to old ids can still be looked up.
- **`hexes.json`** — earlier intermediate output of the hex-extraction pipeline (superseded by `static/hexes.json`).

### Frontend resources (`src/main/resources/static/`)

- **`hexes.json`** — per-site hex geometry on the board image: circumradius, orientation, six corner positions in normalised coordinates, and a quality score. Produced by `scripts/extract_hexes.py` from `hf4.jpg`. Used by the canvas to draw filter-match outlines and by the SVG mask overlay to know exactly where each hex sits on the printed board.
- **`hexes-edited.json`** — manually corrected version of `hexes.json` for sites where automatic detection wasn't accurate enough.
- **`bodies.json`** — non-hex round bodies on the map (planet centres, large objects) — centre and radius in normalised coordinates. Used by the mask overlay to avoid painting over them.
- **`bodies-edited.json`** — manually corrected version of `bodies.json`.
- **`hex-neighbors.json`** — per-site list of which other hexes / round bodies are visually adjacent. Used by the mask overlay so when it paints over one site it doesn't bleed into a neighbour's hex.

### Build outputs

- **`target/hex-overlay.png`** — debug image produced by `scripts/extract_hexes.py`: the original board with every detected hex outline drawn over it, colour-coded by extraction quality. Used to spot-check the extraction.

---

## Running locally

```
./scripts/start-server.sh
```

Compiles via Maven, gracefully shuts down any running instance via `GET /stop-hf4-planner`, and starts a fresh server on `:8080`. Open `http://localhost:8080/` in a browser.

Tests:

```
./scripts/run-tests.sh
```

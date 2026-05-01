// =====================================================================
// Hex-mask overlay — paint over sites the planner shouldn't focus on.
// ---------------------------------------------------------------------
// Per-site opacity is computed from current filter + traverse + hover +
// route-endpoint state, on every draw cycle:
//
//   hovered site / start / current-route endpoint  → opacity 0
//   no traverse run yet (no reachableNodes)        → opacity 0
//   filter inactive:
//       reachable                                  → opacity 0
//       unreachable                                → opacity 0.5
//   filter active:
//       reachable, passes filter                   → opacity 0
//       reachable, filtered out                    → opacity 0.7
//       unreachable, passes filter                 → opacity 0.4
//       unreachable, filtered out                  → opacity 0.9
//
// Two independent toggles:
//   #hide-unreachable-sites — paint sites the planner can't reach from the
//                              current start (does nothing until a start is
//                              picked → reachableNodes exists).
//   #hide-filtered-sites    — paint sites that don't pass the active site
//                              filter (does nothing while the filter is off).
// Same colour-sampling logic as /hex-editor (6 triangles, neighbour-aware
// median, never paints over a precomputed neighbour hex/body).
// =====================================================================
import { state } from './state.js';
import { readSiteFilter, siteMatchesFilter } from './siteFilter.js';
import { getActiveEndpoint } from './routeTree.js';

const HM_SVG_NS = 'http://www.w3.org/2000/svg';
let HM_OVERLAY = null;
let HM_TOGGLE_UNREACHABLE = null;
let HM_TOGGLE_FILTERED = null;
let hmHexes = null, hmNeighbors = {}, hmBodies = {};
let hmImgPixels = null;
let hmMaskScale = 1.20, hmMaskBlur = 6;
let hmReady = false;
const hmGroups = new Map();    // siteId -> SVG group element
// Per-state opacities (overridden by /api/config keys ui.hex.mask.opacity.*).
let hmOpFilteredOut         = 0.5;  // no start, filter on, fails
// --- Search spotlight state ----------------------------------------------
// When the user picks a site from the search box we briefly dim every
// other site so the spotlight is visually obvious. searchSpotlightId is
// the picked node, searchSpotlightUntil is the wall-clock ms after which
// it ends. Both are cleared by a setTimeout that re-runs updateHexMasks().
let searchSpotlightId    = null;
let searchSpotlightUntil = 0;
let searchSpotlightTimer = null;
const SEARCH_SPOTLIGHT_MS      = 1000;
const SEARCH_SPOTLIGHT_OPACITY = 0.8;
let hmOpUnreachable         = 0.5;  // start, no filter, unreachable
let hmOpUnreachableMatch    = 0.4;
let hmOpReachableMismatch   = 0.7;
let hmOpUnreachableMismatch = 0.9;

function hmPointInPolygon(x, y, poly) {
    let inside = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
        const xi = poly[i][0], yi = poly[i][1];
        const xj = poly[j][0], yj = poly[j][1];
        if ((yi > y) !== (yj > y) &&
            (x < (xj - xi) * (y - yi) / ((yj - yi) || 1e-12) + xi)) inside = !inside;
    }
    return inside;
}

function hmGetNeighborShapes(siteId, W, H) {
    const nb = hmNeighbors[siteId];
    const polys = [], circles = [];
    if (!nb) return { polys, circles };
    for (const sid of nb.sites || []) {
        const nh = hmHexes[sid];
        if (!nh) continue;
        polys.push(nh.corners.map(([x, y]) => [x * W, y * H]));
    }
    for (const bname of nb.bodies || []) {
        const b = hmBodies[bname];
        if (!b || b.removed) continue;
        circles.push({ cx: b.cx * W, cy: b.cy * H, r2: (b.r * W) ** 2 });
    }
    return { polys, circles };
}

function hmInsideAnyNeighbor(x, y, shapes) {
    for (const poly of shapes.polys) if (hmPointInPolygon(x, y, poly)) return true;
    for (const c of shapes.circles) {
        const dx = x - c.cx, dy = y - c.cy;
        if (dx * dx + dy * dy <= c.r2) return true;
    }
    return false;
}

function hmSampleMedian(midX, midY, nx, ny, shapes, dStart, W, H) {
    const reds = [], greens = [], blues = [];
    for (let d = dStart; d <= dStart + 8; d += 2) {
        for (let off = -8; off <= 8; off += 4) {
            const tx = -ny, ty = nx;
            const x = Math.round(midX + nx * d + tx * off);
            const y = Math.round(midY + ny * d + ty * off);
            if (x < 0 || x >= W || y < 0 || y >= H) continue;
            if (hmInsideAnyNeighbor(x, y, shapes)) continue;
            const k = (y * W + x) * 4;
            reds.push(hmImgPixels[k]);
            greens.push(hmImgPixels[k + 1]);
            blues.push(hmImgPixels[k + 2]);
        }
    }
    if (reds.length === 0) return null;
    const med = a => { a.sort((p, q) => p - q); return a[Math.floor(a.length / 2)]; };
    return [med(reds), med(greens), med(blues)];
}

function hmEnsureDefs() {
    let defs = HM_OVERLAY.querySelector('defs');
    if (!defs) {
        defs = document.createElementNS(HM_SVG_NS, 'defs');
        HM_OVERLAY.appendChild(defs);
        const f = document.createElementNS(HM_SVG_NS, 'filter');
        f.id = 'hm-blur';
        f.setAttribute('x', '-10%'); f.setAttribute('y', '-10%');
        f.setAttribute('width', '120%'); f.setAttribute('height', '120%');
        const g = document.createElementNS(HM_SVG_NS, 'feGaussianBlur');
        g.setAttribute('stdDeviation', String(hmMaskBlur));
        f.appendChild(g);
        defs.appendChild(f);
    }
    return defs;
}

function hmBuildNeighborMask(siteId, W, H) {
    const nb = hmNeighbors[siteId];
    if (!nb || ((nb.sites || []).length === 0 && (nb.bodies || []).length === 0)) return null;
    const defs = hmEnsureDefs();
    const id = 'hm-nb-' + siteId;
    defs.querySelector('#' + CSS.escape(id))?.remove();
    const m = document.createElementNS(HM_SVG_NS, 'mask');
    m.id = id;
    m.setAttribute('maskUnits', 'userSpaceOnUse');
    const r = document.createElementNS(HM_SVG_NS, 'rect');
    r.setAttribute('x', 0); r.setAttribute('y', 0);
    r.setAttribute('width', W); r.setAttribute('height', H);
    r.setAttribute('fill', 'white');
    m.appendChild(r);
    for (const sid of nb.sites || []) {
        const nh = hmHexes[sid];
        if (!nh) continue;
        const p = document.createElementNS(HM_SVG_NS, 'polygon');
        p.setAttribute('points', nh.corners.map(([x, y]) => `${x * W},${y * H}`).join(' '));
        p.setAttribute('fill', 'black');
        m.appendChild(p);
    }
    for (const bname of nb.bodies || []) {
        const b = hmBodies[bname];
        if (!b || b.removed) continue;
        const c = document.createElementNS(HM_SVG_NS, 'circle');
        c.setAttribute('cx', b.cx * W); c.setAttribute('cy', b.cy * H);
        c.setAttribute('r',  b.r  * W); c.setAttribute('fill', 'black');
        m.appendChild(c);
    }
    defs.appendChild(m);
    return id;
}

function hmBuildMaskGroup(siteId, W, H) {
    const h = hmHexes[siteId];
    if (!h) return null;
    const pts = h.corners.map(([x, y]) => [x * W, y * H]);
    const cx = pts.reduce((s, p) => s + p[0], 0) / 6;
    const cy = pts.reduce((s, p) => s + p[1], 0) / 6;
    const ptsBig = pts.map(([x, y]) => [
        cx + (x - cx) * hmMaskScale,
        cy + (y - cy) * hmMaskScale,
    ]);
    const shapes = hmGetNeighborShapes(siteId, W, H);
    const colours = new Array(6).fill(null);
    for (let k = 0; k < 6; k++) {
        const c1 = pts[k], c2 = pts[(k + 1) % 6];
        const midX = (c1[0] + c2[0]) / 2, midY = (c1[1] + c2[1]) / 2;
        const dx = midX - cx, dy = midY - cy;
        const len = Math.hypot(dx, dy) || 1;
        const dStart = Math.max(2, len * (hmMaskScale - 1) + 2);
        colours[k] = hmSampleMedian(midX, midY, dx / len, dy / len, shapes, dStart, W, H);
    }
    for (let k = 0; k < 6; k++) if (colours[k] === null) colours[k] = colours[(k + 3) % 6];
    for (let k = 0; k < 6; k++) if (colours[k] === null) colours[k] = [128, 128, 128];

    const g = document.createElementNS(HM_SVG_NS, 'g');
    g.id = 'hm-group-' + siteId;
    g.setAttribute('class', 'hide-mask');
    g.setAttribute('opacity', 0);
    g.style.display = 'none';
    if (hmMaskBlur > 0) g.setAttribute('filter', 'url(#hm-blur)');
    const nbMaskId = hmBuildNeighborMask(siteId, W, H);
    if (nbMaskId) g.setAttribute('mask', 'url(#' + nbMaskId + ')');
    for (let k = 0; k < 6; k++) {
        const b1 = ptsBig[k], b2 = ptsBig[(k + 1) % 6];
        const [r, gC, bC] = colours[k];
        const tri = document.createElementNS(HM_SVG_NS, 'polygon');
        tri.setAttribute('points', cx + ',' + cy + ' ' + b1[0] + ',' + b1[1] + ' ' + b2[0] + ',' + b2[1]);
        tri.setAttribute('fill', 'rgb(' + r + ',' + gC + ',' + bC + ')');
        g.appendChild(tri);
    }
    HM_OVERLAY.appendChild(g);
    return g;
}

function hmEnsureImagePixels() {
    if (hmImgPixels) return true;
    const img = document.getElementById('bg');
    if (!img.complete || !img.naturalWidth) return false;
    const cv = document.createElement('canvas');
    cv.width = img.naturalWidth;
    cv.height = img.naturalHeight;
    const cx = cv.getContext('2d', { willReadFrequently: true });
    try {
        cx.drawImage(img, 0, 0);
        hmImgPixels = cx.getImageData(0, 0, img.naturalWidth, img.naturalHeight).data;
    } catch (e) {
        console.error('hex-mask: failed to read image pixels', e);
        return false;
    }
    return true;
}

function hmBuildAll() {
    if (!hmHexes) return;
    const img = document.getElementById('bg');
    if (!img.naturalWidth) return;
    const W = img.naturalWidth, H = img.naturalHeight;
    HM_OVERLAY.setAttribute('width', W);
    HM_OVERLAY.setAttribute('height', H);
    HM_OVERLAY.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    HM_OVERLAY.style.width  = W + 'px';
    HM_OVERLAY.style.height = H + 'px';
    if (!hmEnsureImagePixels()) return;
    hmEnsureDefs();
    for (const sid of Object.keys(hmHexes)) {
        if (hmGroups.has(sid)) continue;
        const g = hmBuildMaskGroup(sid, W, H);
        if (g) hmGroups.set(sid, g);
    }
    hmReady = true;
}

function hmOpacityFor(siteId, filter, activeEp) {
    if (siteId === state.selectedNode) return 0;
    if (siteId === state.hoveredNode)  return 0;
    if (siteId === activeEp)           return 0;
    const point = state.mapData && state.mapData.points && state.mapData.points[siteId];
    if (!point) return 0;
    const hideUnreachable = !!(HM_TOGGLE_UNREACHABLE && HM_TOGGLE_UNREACHABLE.checked);
    const hideFiltered    = !!(HM_TOGGLE_FILTERED    && HM_TOGGLE_FILTERED.checked);
    if (!hideUnreachable && !hideFiltered) return 0;
    const filterOk      = !filter.active || siteMatchesFilter(point, filter);
    const reachable     = !state.reachableNodes ? true : !!state.reachableNodes[siteId];
    const isUnreachable = !!state.reachableNodes && !reachable;
    const isFilteredOut = filter.active && !filterOk;
    const showUnreach   = hideUnreachable && isUnreachable;
    const showFiltered  = hideFiltered    && isFilteredOut;
    if (!showUnreach && !showFiltered) return 0;
    // Both categories apply → strongest overlay (heavy paint).
    if (showUnreach && showFiltered) return hmOpUnreachableMismatch;
    // Unreachable only — separate sub-tier when site still passes filter
    // (lighter paint so a matching-but-unreachable site stays partially
    // visible).
    if (showUnreach) {
        return (filter.active && filterOk) ? hmOpUnreachableMatch : hmOpUnreachable;
    }
    // showFiltered only — separate sub-tier for reachable-but-filtered-out.
    return reachable ? hmOpReachableMismatch : hmOpFilteredOut;
}

export function updateHexMasks() {
    if (!hmReady) return;
    // Search-spotlight mode (1 s after a search pick): dim every site to
    // SEARCH_SPOTLIGHT_OPACITY, leave the picked site fully visible. This
    // overrides the regular hide-unreachable / hide-filtered logic.
    const spotlightActive = searchSpotlightId
                         && performance.now() < searchSpotlightUntil;
    if (spotlightActive) {
        for (const [sid, g] of hmGroups) {
            if (sid === searchSpotlightId) {
                g.style.display = 'none';
            } else {
                g.style.display = '';
                g.setAttribute('opacity', SEARCH_SPOTLIGHT_OPACITY);
            }
        }
        return;
    }
    const enabled = (HM_TOGGLE_UNREACHABLE && HM_TOGGLE_UNREACHABLE.checked)
                 || (HM_TOGGLE_FILTERED    && HM_TOGGLE_FILTERED.checked);
    if (!enabled) {
        for (const g of hmGroups.values()) g.style.display = 'none';
        return;
    }
    const filter   = readSiteFilter();
    const activeEp = getActiveEndpoint();
    for (const [sid, g] of hmGroups) {
        const op = hmOpacityFor(sid, filter, activeEp);
        if (op > 0) {
            g.style.display = '';
            g.setAttribute('opacity', op);
        } else {
            g.style.display = 'none';
        }
    }
}

// Trigger the brief spotlight on a site picked from the search box.
// Re-callable: subsequent picks restart the timer.
export function startSearchSpotlight(siteId) {
    searchSpotlightId    = siteId;
    searchSpotlightUntil = performance.now() + SEARCH_SPOTLIGHT_MS;
    if (searchSpotlightTimer) clearTimeout(searchSpotlightTimer);
    searchSpotlightTimer = setTimeout(() => {
        searchSpotlightId    = null;
        searchSpotlightTimer = null;
        updateHexMasks();
    }, SEARCH_SPOTLIGHT_MS);
    updateHexMasks();
}

export function initHexMask() {
    HM_OVERLAY = document.getElementById('hex-mask-overlay');
    HM_TOGGLE_UNREACHABLE = document.getElementById('hide-unreachable-sites');
    HM_TOGGLE_FILTERED    = document.getElementById('hide-filtered-sites');

    // Refresh masks every time the planner redraws the canvas. Replaces
    // the original draw() monkey-patch (`_hmOrigDraw + updateHexMasks`).
    state.drawHooks.push(updateHexMasks);

    if (HM_OVERLAY) {
        Promise.all([
            fetch('/hex-editor/hexes',             { cache: 'no-store' }).then(r => r.ok ? r.json() : null),
            fetch('/hex-editor/neighbors',         { cache: 'no-store' }).then(r => r.ok ? r.json() : null),
            fetch('/celestial-body-editor/bodies', { cache: 'no-store' }).then(r => r.ok ? r.json() : null),
            fetch('/api/config',                   { cache: 'no-store' }).then(r => r.ok ? r.json() : null),
        ]).then(([hx, nb, bd, cfg]) => {
            if (!hx) { console.warn('hex-mask: no hex data'); return; }
            hmHexes = hx; hmNeighbors = nb || {}; hmBodies = bd || {};
            if (cfg) {
                const s = parseFloat(cfg['ui.hex.hide.mask.scale']);
                if (!isNaN(s) && s > 0) hmMaskScale = s;
                const bl = parseFloat(cfg['ui.hex.hide.mask.blur']);
                if (!isNaN(bl) && bl >= 0) hmMaskBlur = bl;
                // Per-state opacities. Each is read independently so any
                // missing/invalid key falls back to the JS default above.
                const setOp = (key, setter) => {
                    const v = parseFloat(cfg[key]);
                    if (!isNaN(v) && v >= 0 && v <= 1) setter(v);
                };
                setOp('ui.hex.mask.opacity.filtered.out',         v => hmOpFilteredOut         = v);
                setOp('ui.hex.mask.opacity.unreachable',          v => hmOpUnreachable         = v);
                setOp('ui.hex.mask.opacity.unreachable.match',    v => hmOpUnreachableMatch    = v);
                setOp('ui.hex.mask.opacity.reachable.mismatch',   v => hmOpReachableMismatch   = v);
                setOp('ui.hex.mask.opacity.unreachable.mismatch', v => hmOpUnreachableMismatch = v);
            }
            const img = document.getElementById('bg');
            if (img.complete && img.naturalWidth) {
                hmBuildAll(); updateHexMasks();
            } else {
                img.addEventListener('load', () => { hmBuildAll(); updateHexMasks(); }, { once: true });
            }
        }).catch(e => console.error('hex-mask: init failed', e));
    }

    if (HM_TOGGLE_UNREACHABLE) HM_TOGGLE_UNREACHABLE.addEventListener('change', updateHexMasks);
    if (HM_TOGGLE_FILTERED)    HM_TOGGLE_FILTERED.addEventListener('change',    updateHexMasks);
}

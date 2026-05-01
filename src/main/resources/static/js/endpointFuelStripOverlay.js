// =====================================================================
// Endpoint fuel-strip overlay
//
// Floating panel anchored to the upper-left corner of the map container.
// Shows the rulebook fuel-strip image with Dry + Wet chits drawn at the
// user-edited coordinates from /chit-editor/chits.
//
// Strip step indexing (matches the chit editor):
//   step  0 = Wet Mass 1
//   step  1 = 1+1/9
//   ...
//   step  9 = Wet Mass 2
//   ...
//   step 56 = Wet Mass 32
// Dry chit is at the integer-mass step (massToStripStep(dryMass)).
// Wet chit is fuelStepsRemaining further along the black line.
// =====================================================================
import { state } from './state.js';
import { cfgI } from './config.js';
import { STRIP_MAX_STEP, massToStripStep, parseFuelText, formatFuelSteps,
         INTEGER_MASS_STEPS } from './fuelStrip.js';
import { getTreeNodeIds, getPathToRoot } from './routeTree.js';
import { fireTraverse } from './traverse.js';
import { persistTabs } from './tabs.js';

// Drag state for chit dragging — null when not dragging.
let chitDrag = null;   // { which: 'dry'|'wet', startX, startY }

let stripChitCoords = null;          // { stepIndex: {x, y} }, x/y in [0,1]
let stripImgNaturalSize = null;      // { w, h } once the bg image loads

/**
 * Closest-point projection of (px,py) onto segment (ax,ay)-(bx,by).
 * Returns { x, y, distSq } where (x,y) is the projected point clamped
 * to the segment's endpoints, and distSq is the squared distance from
 * the input point to that projection.
 */
function projectPointOnSegment(px, py, ax, ay, bx, by) {
    const dx = bx - ax, dy = by - ay;
    const len2 = dx*dx + dy*dy;
    if (len2 === 0) {
        const ex = px - ax, ey = py - ay;
        return { x: ax, y: ay, distSq: ex*ex + ey*ey };
    }
    let t = ((px - ax) * dx + (py - ay) * dy) / len2;
    t = Math.max(0, Math.min(1, t));
    const cx = ax + t * dx, cy = ay + t * dy;
    const ex = px - cx, ey = py - cy;
    return { x: cx, y: cy, distSq: ex*ex + ey*ey };
}

/**
 * Looks for a route segment under the cursor. Returns
 *   { startId, projX, projY }
 * where startId is the mapNodeId at the segment's earlier end (closer
 * to startpoint) and (projX, projY) is the closest point ON THE
 * SEGMENT to the cursor, in image-pixel coords. Returns null when no
 * segment is within `maxDist`. Only meaningful when an endpoint is
 * pinned and a route exists; caller should check those preconditions.
 */
export function findHoveredRouteSegment(mouseX, mouseY, maxDist) {
    if (!state.pinnedEndpoint || !state.traverseResult || !state.mapData) return null;
    const treeNodeIds = getTreeNodeIds(state.pinnedEndpoint);
    if (!treeNodeIds || !treeNodeIds.length) return null;
    const idx = state.selectedRouteIndex % treeNodeIds.length;
    const pathNodes = getPathToRoot(treeNodeIds[idx]);
    if (!pathNodes || pathNodes.length < 2) return null;
    const points = state.mapData.points;
    const maxSq = maxDist * maxDist;
    let best = null;
    let bestSq = Infinity;
    // pathNodes is ordered root → endpoint, so [i] is the start of
    // segment i (closer to startpoint), [i+1] is its end.
    for (let i = 0; i < pathNodes.length - 1; i++) {
        const a = points[pathNodes[i].nodeId];
        const b = points[pathNodes[i+1].nodeId];
        if (!a || !b) continue;
        const ax = a.x * state.imgW, ay = a.y * state.imgH;
        const bx = b.x * state.imgW, by = b.y * state.imgH;
        const proj = projectPointOnSegment(mouseX, mouseY, ax, ay, bx, by);
        if (proj.distSq < bestSq && proj.distSq <= maxSq) {
            bestSq = proj.distSq;
            best = { startId: pathNodes[i].nodeId, projX: proj.x, projY: proj.y };
        }
    }
    return best;
}

export async function loadStripChitCoords() {
    try {
        const r = await fetch('/chit-editor/chits', { cache: 'no-store' });
        if (!r.ok) return;
        const j = await r.json();
        const arr = Array.isArray(j.chits) ? j.chits : [];
        const map = {};
        for (const c of arr) {
            // New schema: {step, x, y}. Old (auto-migrated by editor):
            // {mass, x, y} where `mass` was a 1-based click index, so
            // step = mass - 1.
            if (typeof c.step === 'number') map[c.step] = { x: c.x, y: c.y };
            else if (typeof c.mass === 'number') map[c.mass - 1] = { x: c.x, y: c.y };
        }
        stripChitCoords = map;
        updateEndpointFuelStrip();
    } catch (e) {
        console.warn('chit coords: load failed', e);
    }
}

/**
 * Captures the strip background's natural pixel dimensions once it's
 * loaded — we need them as the SVG viewBox so the chit positions
 * scale with the panel width. Called from the init flow; safe if the
 * image element doesn't exist (returns silently).
 */
export function bindStripImg() {
    const img = document.getElementById('endpoint-strip-bg');
    if (!img) return;
    const onReady = () => {
        stripImgNaturalSize = { w: img.naturalWidth, h: img.naturalHeight };
        const svg = document.getElementById('endpoint-strip-svg');
        if (svg) svg.setAttribute('viewBox', `0 0 ${img.naturalWidth} ${img.naturalHeight}`);
        updateEndpointFuelStrip();
    };
    if (img.complete && img.naturalWidth > 0) onReady();
    else img.addEventListener('load', onReady);
}

/** Show or hide the fuel-strip panel AND toggle the
 *  body.fuel-strip-hidden class so the rotation buttons can swap
 *  between "right of strip" (visible) and "upper-left corner"
 *  (hidden) via CSS. Always go through this helper rather than
 *  setting overlay.style.display directly. */
function setFuelStripVisible(visible) {
    const overlay = document.getElementById('endpoint-fuel-strip');
    if (overlay) overlay.style.display = visible ? 'block' : 'none';
    document.body.classList.toggle('fuel-strip-hidden', !visible);
}

/**
 * Hide / show / reposition the endpoint fuel-strip overlay. Cheap
 * (≈10 DOM writes) — over-calling is fine. Bails silently on any
 * unmet precondition.
 */
export function updateEndpointFuelStrip() {
    const overlay = document.getElementById('endpoint-fuel-strip');
    if (!overlay) return;

    // Reset the green-dot indicator at the top of every call. The
    // selection logic below sets it only for hover-driven targets.
    state.fuelStripHoverIndicatorId = null;

    // Master toggle (checkbox in #debug-toggle bar) — fastest hide path.
    const enableCb = document.getElementById('show-fuel-strip-cb');
    if (enableCb && !enableCb.checked) { setFuelStripVisible(false); return; }

    // Mandatory data for any rendering at all.
    if (!stripChitCoords || !stripImgNaturalSize) {
        setFuelStripVisible(false); return;
    }

    // Decide which PathNode's fuel state to display, or fall back
    // to the user's form-input initial state (dry + fuel) when no
    // route or hover gives us a specific target:
    //
    //   - Endpoint pinned + cursor on the selected route node: that node
    //   - Endpoint pinned + cursor on a route segment:          segment's earlier node
    //   - Endpoint pinned + cursor elsewhere:                   the endpoint
    //   - No pin + hovering a reachable node:                   that node's best-route endpoint
    //   - Otherwise:                                            FALLBACK — show
    //                                                           dryMass + fuel from the form
    let targetPathNode = null;

    if (state.traverseResult && state.reachableNodes && state.mapData) {
        if (state.pinnedEndpoint) {
            const treeNodeIds = getTreeNodeIds(state.pinnedEndpoint);
            if (treeNodeIds && treeNodeIds.length > 0) {
                const idx = state.selectedRouteIndex; // pinned uses the currently selected route
                const pathNodes = getPathToRoot(treeNodeIds[idx % treeNodeIds.length]);
                if (pathNodes && pathNodes.length > 0) {
                    const onRouteHover = state.hoveredNode
                            ? pathNodes.find(pn => pn.nodeId === state.hoveredNode) : null;
                    const segStartHover = (!onRouteHover && state.hoveredRouteSegmentStartId)
                            ? pathNodes.find(pn => pn.nodeId === state.hoveredRouteSegmentStartId) : null;

                    if (onRouteHover) {
                        targetPathNode = onRouteHover;
                        state.fuelStripHoverIndicatorId = onRouteHover.nodeId;
                    } else if (segStartHover) {
                        targetPathNode = segStartHover;
                        state.fuelStripHoverIndicatorId = segStartHover.nodeId;
                    } else {
                        targetPathNode = pathNodes[pathNodes.length - 1];
                    }
                }
            }
        } else if (state.hoveredNode && state.reachableNodes[state.hoveredNode]) {
            const treeNodeIds = getTreeNodeIds(state.hoveredNode);
            if (treeNodeIds && treeNodeIds.length > 0) {
                const pathNodes = getPathToRoot(treeNodeIds[0]); // best route
                targetPathNode = pathNodes && pathNodes[pathNodes.length - 1];
            }
        }
        // else: no specific target — fall through to form-input fallback
    }

    const dryMass = parseInt(document.getElementById('dry-mass').value)
                 || cfgI('ui.dry.mass.default', 4);
    const dryStep = massToStripStep(dryMass);
    let wetStep;
    if (targetPathNode) {
        wetStep = dryStep + (targetPathNode.fuelStepsRemaining || 0);
    } else {
        // Fallback: render the user's form-input initial state. The Fuel
        // field is now free-form ("5", "1+5/6", etc.) — parse it via the
        // shared helper. On parse failure (text invalid, denom mismatch),
        // treat as zero so we still render the Dry chit.
        const fuelText = document.getElementById('fuel').value;
        const parsed = parseFuelText(fuelText, dryMass);
        const fuelSteps = parsed.ok ? parsed.fuelSteps : 0;
        wetStep = dryStep + fuelSteps;
    }
    // Clamp wet step to MAX (TODO: replace with explicit input validation
    // — currently dryMass + fuel > 32 is rejected by the backend, so
    // this clamp is a safety net rather than a real path).
    if (wetStep > STRIP_MAX_STEP) wetStep = STRIP_MAX_STEP;

    const dryCoord = stripChitCoords[dryStep];
    const wetCoord = stripChitCoords[wetStep];
    if (!dryCoord || !wetCoord) {
        // User hasn't placed a coord for one of the steps in /chit-editor.
        // Silently hide rather than render with a missing chit.
        setFuelStripVisible(false); return;
    }

    // Build chit SVG: two filled circles + "Dry"/"Wet" text labels.
    // When chits coincide (zero fuel left), nudge Dry slightly left.
    const W = stripImgNaturalSize.w, H = stripImgNaturalSize.h;
    const r = Math.min(W, H) * 0.05;
    const fontSize = Math.round(r * 0.55);
    const dx = (dryStep === wetStep) ? -r * 0.6 : 0;
    const svg = document.getElementById('endpoint-strip-svg');
    if (!svg) { setFuelStripVisible(false); return; }
    svg.innerHTML =
        `<circle class="endpoint-chit-circle wet" data-chit="wet" data-step="${wetStep}"
                 cx="${wetCoord.x * W}" cy="${wetCoord.y * H}" r="${r}"/>
         <text class="endpoint-chit-label"
               x="${wetCoord.x * W}" y="${wetCoord.y * H}"
               font-size="${fontSize}">Wet</text>
         <circle class="endpoint-chit-circle dry" data-chit="dry" data-step="${dryStep}"
                 cx="${dryCoord.x * W + dx}" cy="${dryCoord.y * H}" r="${r}"/>
         <text class="endpoint-chit-label"
               x="${dryCoord.x * W + dx}" y="${dryCoord.y * H}"
               font-size="${fontSize}">Dry</text>`;

    // Panel is pinned to the upper-left corner via CSS — nothing to
    // compute per-frame. Just reveal it.
    setFuelStripVisible(true);
}

// ---- Drag-to-edit ----------------------------------------------------
//
// Both Dry and Wet chits are draggable. Dry snaps only to integer-mass
// steps (per HF4A F2 — Dry Mass is always integer). Wet snaps to any
// recorded chit coordinate. Constraints:
//
//   - Wet step ≥ Dry step (drag stops at the boundary; cursor can keep
//     moving but the chit doesn't).
//   - The snapped step must have a placed coordinate in chits-edited.json.
//
// Continuous chit motion during drag updates the form fields live AND
// re-renders the strip via updateEndpointFuelStrip. /api/traverse only
// fires once on mouseup, so the planner runs at most one search per
// drag interaction.

function panelToImageCoords(clientX, clientY) {
    if (!stripImgNaturalSize) return null;
    const overlay = document.getElementById('endpoint-fuel-strip');
    const img = document.getElementById('endpoint-strip-bg');
    if (!overlay || !img) return null;
    const rect = img.getBoundingClientRect();
    const sx = (clientX - rect.left) / rect.width  * stripImgNaturalSize.w;
    const sy = (clientY - rect.top)  / rect.height * stripImgNaturalSize.h;
    return { x: sx, y: sy };
}

function nearestSnapStep(imgX, imgY, which, currentDryStep, currentWetStep) {
    if (!stripChitCoords || !stripImgNaturalSize) return null;
    // Candidate steps: Dry can only land on integer-mass positions; Wet
    // on any of the 57. Constraint: dryStep ≤ wetStep always.
    const allSteps = [];
    if (which === 'dry') {
        for (const s of INTEGER_MASS_STEPS) {
            if (s <= currentWetStep) allSteps.push(s);
        }
    } else {
        for (let s = currentDryStep; s <= STRIP_MAX_STEP; s++) {
            allSteps.push(s);
        }
    }
    let bestStep = null, bestDistSq = Infinity;
    const W = stripImgNaturalSize.w, H = stripImgNaturalSize.h;
    for (const s of allSteps) {
        const c = stripChitCoords[s];
        if (!c) continue;
        const cx = c.x * W, cy = c.y * H;
        const dx = imgX - cx, dy = imgY - cy;
        const dSq = dx * dx + dy * dy;
        if (dSq < bestDistSq) { bestDistSq = dSq; bestStep = s; }
    }
    return bestStep;
}

/** dryStep that corresponds to a given integer-mass step. Reverse of
 *  massToStripStep. Walks INTEGER_MASS_STEPS to find the matching mass. */
function stepToIntegerMass(step) {
    for (let m = 1; m <= 32; m++) {
        if (INTEGER_MASS_STEPS[m - 1] === step) return m;
    }
    return null;
}

function onChitMouseDown(e) {
    const target = e.target;
    if (!target || !target.dataset || !target.dataset.chit) return;
    e.preventDefault();
    e.stopPropagation();
    chitDrag = { which: target.dataset.chit };
    document.addEventListener('mousemove', onChitMouseMove);
    document.addEventListener('mouseup', onChitMouseUp);
}

function onChitMouseMove(e) {
    if (!chitDrag) return;
    const img = panelToImageCoords(e.clientX, e.clientY);
    if (!img) return;

    // Read current state from the form (the source of truth between drags).
    const dryEl = document.getElementById('dry-mass');
    const fuelEl = document.getElementById('fuel');
    const dryMass = parseInt(dryEl.value) || 0;
    const dryStep = massToStripStep(dryMass);
    const parsedFuel = parseFuelText(fuelEl.value, dryMass);
    const wetStep = dryStep + (parsedFuel.ok ? parsedFuel.fuelSteps : 0);

    const snap = nearestSnapStep(img.x, img.y, chitDrag.which, dryStep, wetStep);
    if (snap == null) return;

    if (chitDrag.which === 'dry') {
        const newDry = stepToIntegerMass(snap);
        if (newDry == null || newDry === dryMass) return;
        // Preserve the wet STEP — that's the same dry-mass-change behavior
        // as typing a new value into the dry-mass input.
        const newDryStep = snap;
        const newFuelSteps = Math.max(0, wetStep - newDryStep);
        dryEl.value  = String(newDry);
        fuelEl.value = formatFuelSteps(newFuelSteps, newDry);
        state.lastDryMass = newDry;
    } else {
        // Wet drag — recompute fuelSteps relative to current Dry.
        const newFuelSteps = Math.max(0, snap - dryStep);
        fuelEl.value = formatFuelSteps(newFuelSteps, dryMass);
    }
    updateEndpointFuelStrip();
}

function onChitMouseUp() {
    if (!chitDrag) return;
    chitDrag = null;
    document.removeEventListener('mousemove', onChitMouseMove);
    document.removeEventListener('mouseup', onChitMouseUp);
    // Each drag-end fires a traverse — per the user's spec.
    fireTraverse();
    persistTabs();
}

// Single delegated handler on the SVG element — survives innerHTML
// rewrites by updateEndpointFuelStrip (which replaces the chit nodes).
(function bindChitDragOnce() {
    function tryBind() {
        const svg = document.getElementById('endpoint-strip-svg');
        if (!svg) return false;
        svg.addEventListener('mousedown', onChitMouseDown);
        return true;
    }
    if (!tryBind()) {
        // SVG element exists from page load, so this should be a no-op
        // delay — but in case the DOM isn't ready yet, retry on the
        // first updateEndpointFuelStrip call indirectly via a tiny
        // microtask.
        Promise.resolve().then(tryBind);
    }
})();

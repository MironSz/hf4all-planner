// Junky-flying-spacecraft animation along the pinned route.
//
// Triggered automatically when state.pinnedEndpoint changes (see hookup
// in canvas.js). Plays once start → endpoint at a fixed pace, then
// stops. The sprite is drawn procedurally — no asset files. Three
// scoped effects: a flickering thrust flame during burn segments,
// jettison particles trailing behind at jettison nodes, and a brief
// shake when crossing a hazard node.
//
// All animation state lives on state.flightAnim:
//   {
//     pathPoints:    [{x, y, pn}, ...]  // image-pixel coords + source PathNode
//     segments:      [{ kind, startMs, durMs, fromIdx, toIdx, fromFuel, toFuel,
//                       hazard, jettisoned, isBurn }]
//     totalMs:       int
//     startPerf:     performance.now() at flight start
//     particles:     [{ x, y, vx, vy, life, maxLife }]
//     hazardShake:   { until, mag } | null
//     wetStepNow:    number   (interpolated current wet step, for fuel-strip sync)
//     dryStep:       int
//     done:          bool
//   }
//
// Re-render cadence: a private rAF loop repaints the #flight-canvas overlay
// each frame while active (drawFlightLayer → drawFlight), leaving the base
// map / route / hex-mask layers untouched. Bows out cleanly on flight end /
// pin cancel.

import { state } from './state.js';
import { getTreeNodeIds, getPathToRoot } from './routeTree.js';
import { massToStripStep } from './fuelStrip.js';
import { updateEndpointFuelStrip } from './endpointFuelStripOverlay.js';

// Single dial. Multiply every wall-clock duration in this module so the
// motion AND its tied-in effects (flame lingers, hazard shake length,
// particle lifetimes) stretch together. 1 = original; 3 = 3× slower.
const ANIM_SPEED_FACTOR = 3;

const MS_PER_TURN_BASE = 700  * ANIM_SPEED_FACTOR;  // wall-clock per game turn
const MS_PER_BURN_KICK = 250  * ANIM_SPEED_FACTOR;  // burn flame lingers this long after entry
const HAZARD_SHAKE_MS  = 320  * ANIM_SPEED_FACTOR;
const HAZARD_SHAKE_MAG = 5;
const JETTISON_BURST   = 8;            // particle count (unscaled)
const PARTICLE_LIFE_MS = 700  * ANIM_SPEED_FACTOR;

let rafHandle = null;

/** Public entry: start a fresh flight animation along the pinned route.
 *  Idempotent — replaces any in-flight animation. No-op if data is
 *  incomplete (no pin / no route / no path). */
export function startFlightAnimation() {
    cancelFlightAnimation();
    if (!state.pinnedEndpoint || !state.traverseResult) return;
    const treeNodeIds = getTreeNodeIds(state.pinnedEndpoint);
    if (!treeNodeIds || !treeNodeIds.length) return;
    const idx = state.selectedRouteIndex % treeNodeIds.length;
    const pathNodes = getPathToRoot(treeNodeIds[idx]);
    if (!pathNodes || pathNodes.length < 2) return;

    const points = state.mapData && state.mapData.points;
    if (!points) return;

    // Project to (x, y) in image-pixel space; drop any nodes whose
    // map-node has no coords (shouldn't happen, but defensive).
    const pathPoints = [];
    for (const pn of pathNodes) {
        const p = points[pn.nodeId];
        if (!p) continue;
        pathPoints.push({ x: p.x * state.imgW, y: p.y * state.imgH, pn });
    }
    if (pathPoints.length < 2) return;

    // Derive Dry chit step from the form (matches what the fuel-strip
    // panel reads), so wet-chit interpolation lines up with what the
    // user sees as "Dry".
    const dryEl = document.getElementById('dry-mass');
    const dryMass = (dryEl && parseInt(dryEl.value)) || 4;
    const dryStep = massToStripStep(dryMass);

    // Build segments. Each segment animates the ship from pathPoints[i]
    // to pathPoints[i+1]. A "turn boundary" segment (sameNode + turn
    // increment) gets a small dwell instead of motion.
    const segments = [];
    let cumMs = 0;
    // Same-node turn-advancing edge = the search's turn-start bookkeeping
    // node, present at EVERY turn boundary — waiting or not. Within a run of
    // such edges, only the edges after the first are turns genuinely spent
    // waiting, unless the run is anchored at the route start (the root, or
    // the jettison/afterburn seed directly under it), which is itself a
    // turn-start — there every edge is a wait. Mirrors the wait-label logic
    // in draw.js.
    const isBoundaryEdge = (j) => {
        const u = pathPoints[j].pn, v = pathPoints[j + 1].pn;
        return u.nodeId === v.nodeId && v.turns > u.turns;
    };
    for (let i = 0; i < pathPoints.length - 1; i++) {
        const a = pathPoints[i], b = pathPoints[i+1];
        const sameNode = a.pn.nodeId === b.pn.nodeId;
        const turnDelta = b.pn.turns - a.pn.turns;
        const fuelDelta = b.pn.fuelSpent - a.pn.fuelSpent;
        const isBurn = fuelDelta > 0 && !sameNode;     // crossing a burn-space edge
        const isPivot = sameNode && fuelDelta > 0;     // same-node force-pivot
        const anchorIsRouteStart = i === 0
                || (i === 1 && pathPoints[0].pn.nodeId === a.pn.nodeId
                            && pathPoints[0].pn.turns === a.pn.turns);
        const isWait = sameNode && turnDelta > 0 && fuelDelta === 0
                && (anchorIsRouteStart || isBoundaryEdge(i - 1));

        let dur;
        if (isWait) {
            dur = MS_PER_TURN_BASE * 0.6;             // dwell during wait
        } else if (isPivot) {
            dur = 250 * ANIM_SPEED_FACTOR;
        } else {
            // Distance-weighted within a turn — long Hohmanns take more wall-clock.
            const dx = b.x - a.x, dy = b.y - a.y;
            const dist = Math.hypot(dx, dy);
            dur = Math.max(120 * ANIM_SPEED_FACTOR, dist * 1.2 * ANIM_SPEED_FACTOR);
        }

        segments.push({
            kind: isWait ? 'wait' : (isPivot ? 'pivot' : (isBurn ? 'burn' : 'coast')),
            startMs: cumMs,
            durMs: dur,
            fromIdx: i,
            toIdx: i+1,
            fromFuel: a.pn.fuelStepsRemaining || 0,
            toFuel:   b.pn.fuelStepsRemaining || 0,
            hazard:   b.pn.hazards > a.pn.hazards,
            jettisoned: b.pn.jettisonedHere || 0,
            isBurn,
        });
        cumMs += dur;
    }

    state.flightAnim = {
        pathPoints,
        segments,
        totalMs: cumMs,
        startPerf: performance.now(),
        particles: [],
        hazardShake: null,
        burnFlameUntil: 0,    // performance.now() epoch
        firedHazardOnSeg: new Set(),
        firedJettisonOnSeg: new Set(),
        wetStepNow: dryStep + (pathPoints[0].pn.fuelStepsRemaining || 0),
        dryStep,
        done: false,
    };
    scheduleFrame();
}

/** Cancel any active flight (idempotent). */
export function cancelFlightAnimation() {
    if (rafHandle != null) {
        cancelAnimationFrame(rafHandle);
        rafHandle = null;
    }
    state.flightAnim = null;
    clearFlightLayer();   // wipe any lingering sprite from the overlay
}

/** Repaint just the flight-sprite overlay: clear it, then redraw the junker +
 *  particles. Leaves the base map / route / hex-mask layers untouched, so a
 *  flight frame costs a small overlay clear+draw instead of a whole-map redraw.
 *  When state.flightAnim is null the drawFlight() call no-ops and this simply
 *  clears the layer. */
function drawFlightLayer() {
    const ctx = state.flightCtx;
    const canvas = state.flightCanvas;
    if (!ctx || !canvas) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    drawFlight(ctx);
}

/** Clear the overlay layer (on cancel / pin change). */
function clearFlightLayer() {
    const ctx = state.flightCtx;
    const canvas = state.flightCanvas;
    if (ctx && canvas) ctx.clearRect(0, 0, canvas.width, canvas.height);
}

function scheduleFrame() {
    if (rafHandle != null) return;
    rafHandle = requestAnimationFrame(tick);
}

function tick(now) {
    rafHandle = null;
    const anim = state.flightAnim;
    if (!anim) return;

    const elapsed = now - anim.startPerf;

    // Decay particles
    for (const p of anim.particles) p.life = p.maxLife - (now - p.spawnedAt);
    anim.particles = anim.particles.filter(p => p.life > 0);

    // Find current segment + interpolation t
    const seg = findSegmentAt(anim.segments, elapsed);
    if (seg) {
        const segT = (elapsed - seg.startMs) / seg.durMs;

        // Fire one-shot effects on segment ENTRY (not every frame).
        if (seg.hazard && !anim.firedHazardOnSeg.has(seg.toIdx)) {
            anim.firedHazardOnSeg.add(seg.toIdx);
            anim.hazardShake = { until: now + HAZARD_SHAKE_MS };
        }
        if (seg.jettisoned > 0 && !anim.firedJettisonOnSeg.has(seg.fromIdx)) {
            anim.firedJettisonOnSeg.add(seg.fromIdx);
            spawnJettisonParticles(anim, seg);
        }
        if (seg.isBurn) {
            anim.burnFlameUntil = now + MS_PER_BURN_KICK;
        }

        // Interpolate fuel-step for fuel-strip wet-chit sync (linear within seg).
        const t = Math.max(0, Math.min(1, segT));
        anim.wetStepNow = anim.dryStep + (seg.fromFuel + (seg.toFuel - seg.fromFuel) * t);
    }

    // End-of-flight check
    if (elapsed >= anim.totalMs && anim.particles.length === 0) {
        anim.done = true;
        state.flightAnim = null;
        // Clear the sprite from the overlay (drawFlight no-ops now), then
        // refresh the fuel strip from the now-static endpoint state.
        drawFlightLayer();
        updateEndpointFuelStrip();
        return;
    }

    // Drive the per-frame redraw — only the sprite overlay repaints; the base
    // map + route keep their own (now hex-mask-free) dash-animation cadence.
    drawFlightLayer();
    updateEndpointFuelStrip();
    scheduleFrame();
}

function findSegmentAt(segments, elapsed) {
    if (elapsed <= 0) return segments[0];
    if (elapsed >= segments[segments.length - 1].startMs + segments[segments.length - 1].durMs) {
        return segments[segments.length - 1];
    }
    // Linear scan — segments are short (≤ a few dozen).
    for (const s of segments) {
        if (elapsed >= s.startMs && elapsed < s.startMs + s.durMs) return s;
    }
    return segments[segments.length - 1];
}

function spawnJettisonParticles(anim, seg) {
    const a = anim.pathPoints[seg.fromIdx];
    const b = anim.pathPoints[seg.toIdx];
    const dx = b.x - a.x, dy = b.y - a.y;
    const len = Math.max(1, Math.hypot(dx, dy));
    // Particles drift backwards along the segment direction.
    const ux = dx / len, uy = dy / len;
    const now = performance.now();
    for (let i = 0; i < JETTISON_BURST; i++) {
        const back = -1 - Math.random() * 2;          // 1..3 px/ms backward
        const lat  = (Math.random() - 0.5) * 1.5;      // small lateral spread
        anim.particles.push({
            x: a.x, y: a.y,
            // Velocity in px/ms (we'll scale by frame dt at draw time).
            vx: ux * back + (-uy) * lat,
            vy: uy * back + ( ux) * lat,
            spawnedAt: now,
            maxLife: PARTICLE_LIFE_MS,
            life: PARTICLE_LIFE_MS,
        });
    }
}

/** Called from draw.js at the end of its render cycle. Cheap when
 *  state.flightAnim is null (early return). */
export function drawFlight(ctx) {
    const anim = state.flightAnim;
    if (!anim) return;
    const elapsed = performance.now() - anim.startPerf;
    const seg = findSegmentAt(anim.segments, elapsed);
    if (!seg) return;

    const a = anim.pathPoints[seg.fromIdx];
    const b = anim.pathPoints[seg.toIdx];
    const t = Math.max(0, Math.min(1, (elapsed - seg.startMs) / seg.durMs));

    // Position: linear lerp. Pivots/waits stay in place.
    let x = a.x + (b.x - a.x) * t;
    let y = a.y + (b.y - a.y) * t;

    // Shake — additive offset while a hazard timer is live.
    const now = performance.now();
    if (anim.hazardShake && now < anim.hazardShake.until) {
        const left = (anim.hazardShake.until - now) / HAZARD_SHAKE_MS;  // 1 → 0
        const mag = HAZARD_SHAKE_MAG * left;
        x += (Math.random() - 0.5) * 2 * mag;
        y += (Math.random() - 0.5) * 2 * mag;
    }

    // Facing direction: prefer the active segment vector, fall back to
    // the previous segment when stationary (pivot/wait).
    let dirX = b.x - a.x, dirY = b.y - a.y;
    if (dirX === 0 && dirY === 0 && seg.fromIdx > 0) {
        const prev = anim.pathPoints[seg.fromIdx - 1];
        dirX = a.x - prev.x; dirY = a.y - prev.y;
    }
    const angle = Math.atan2(dirY, dirX);

    // Particles (drawn behind the sprite).
    drawParticles(ctx, anim);

    // Burn flame, only while a burn was recently entered.
    const flameOn = now < anim.burnFlameUntil;
    drawJunker(ctx, x, y, angle, flameOn);
}

function drawParticles(ctx, anim) {
    ctx.save();
    for (const p of anim.particles) {
        const alpha = Math.max(0, p.life / p.maxLife);
        const age = (p.maxLife - p.life);
        const px = p.x + p.vx * age;
        const py = p.y + p.vy * age;
        ctx.fillStyle = `rgba(126, 200, 227, ${alpha.toFixed(2)})`;
        ctx.beginPath();
        ctx.arc(px, py, 2.5, 0, Math.PI * 2);
        ctx.fill();
    }
    ctx.restore();
}

// Sprite/effect sizes. SHIP_SCALE is applied via ctx.scale around the
// whole sprite. The flame is drawn inside that scaled space with an
// additional FLAME_EXTRA scale, so its EFFECTIVE size relative to the
// original sprite is SHIP_SCALE * FLAME_EXTRA. With 2 × 3 = 6, the flame
// is 6× bigger while the ship is 2× bigger.
const SHIP_SCALE  = 2;
const FLAME_EXTRA = 3;

/** Procedural junker: a stretched rectangle hull, two cargo tanks
 *  bolted on, a stub antenna, an engine bell at the rear, and a
 *  flickering thrust flame when flameOn. */
function drawJunker(ctx, x, y, angle, flameOn) {
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(angle);
    ctx.scale(SHIP_SCALE, SHIP_SCALE);

    // Hull (long rectangle, dirty grey)
    ctx.fillStyle = '#8a8d92';
    ctx.strokeStyle = '#1a1d22';
    ctx.lineWidth = 1.2;
    roundedRect(ctx, -14, -5, 24, 10, 2);
    ctx.fill();
    ctx.stroke();

    // Hull weld lines for "junky" texture
    ctx.strokeStyle = '#5a5d62';
    ctx.lineWidth = 0.6;
    ctx.beginPath();
    ctx.moveTo(-6, -5); ctx.lineTo(-6, 5);
    ctx.moveTo( 2, -5); ctx.lineTo( 2, 5);
    ctx.stroke();

    // Cargo tanks — bottom only. Top side is reserved for the solar
    // array, giving the sprite an ISS-style asymmetry.
    ctx.fillStyle = '#3aa1c4';
    ctx.strokeStyle = '#1a1d22';
    ctx.lineWidth = 0.8;
    [(-3), (5)].forEach((cx) => {
        ctx.beginPath(); ctx.arc(cx,  7, 2.5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    });

    // Solar array (ISS-style, top side only). A thin truss boom rises
    // from the hull centre and carries a long rectangular array divided
    // into a grid of photovoltaic cells. Drawn in local-sprite space so
    // it rotates with the ship.
    ctx.strokeStyle = '#1a1d22';
    ctx.lineWidth = 0.6;
    // Truss boom from hull top to panel underside.
    ctx.beginPath();
    ctx.moveTo(0, -5);
    ctx.lineTo(0, -10);
    ctx.stroke();
    // Panel substrate (dark navy — back-of-cells look).
    const panelX = -11, panelY = -14, panelW = 22, panelH = 4;
    ctx.fillStyle = '#0f1a32';
    ctx.fillRect(panelX, panelY, panelW, panelH);
    ctx.strokeStyle = '#000000';
    ctx.lineWidth = 0.4;
    ctx.strokeRect(panelX, panelY, panelW, panelH);
    // Photovoltaic cell grid (6×2).
    ctx.fillStyle = '#2d4878';
    const cols = 6, rows = 2;
    const cellW = panelW / cols, cellH = panelH / rows;
    const cellPad = 0.25;
    for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
            ctx.fillRect(
                panelX + c * cellW + cellPad,
                panelY + r * cellH + cellPad,
                cellW - 2 * cellPad,
                cellH - 2 * cellPad,
            );
        }
    }
    // Subtle gold sheen along the sun-facing edge.
    ctx.fillStyle = 'rgba(255, 220, 110, 0.18)';
    ctx.fillRect(panelX, panelY, panelW, cellH * 0.5);

    // Antenna (1-px line jutting up from the nose at an angle)
    ctx.strokeStyle = '#cccccc';
    ctx.lineWidth = 0.8;
    ctx.beginPath();
    ctx.moveTo( 8, -4);
    ctx.lineTo(13, -10);
    ctx.stroke();
    // Tiny dish at the antenna tip
    ctx.fillStyle = '#e94560';
    ctx.beginPath(); ctx.arc(13, -10, 1.2, 0, Math.PI * 2); ctx.fill();

    // Engine bell at rear (left of origin, since +x is forward)
    ctx.fillStyle = '#1d1f23';
    ctx.beginPath();
    ctx.moveTo(-14, -3);
    ctx.lineTo(-19,  -5);
    ctx.lineTo(-19,   5);
    ctx.lineTo(-14,  3);
    ctx.closePath();
    ctx.fill();

    // Thrust flame — drawn in its OWN local space anchored at the
    // engine-bell rear (−19, 0), then scaled by FLAME_EXTRA so the flame
    // ends up SHIP_SCALE × FLAME_EXTRA = 6× bigger than the original
    // (vs the ship's 2× scale). Translating BEFORE scaling keeps the
    // flame attached to the bell instead of flinging it across the map.
    if (flameOn) {
        ctx.save();
        ctx.translate(-19, 0);
        ctx.scale(FLAME_EXTRA, FLAME_EXTRA);

        const flicker = 6 + Math.random() * 5;
        ctx.beginPath();
        ctx.moveTo(0, -3);
        ctx.lineTo(-flicker, 0);
        ctx.lineTo(0,  3);
        ctx.closePath();
        ctx.fillStyle = '#ffeb6b'; ctx.fill();

        const flicker2 = flicker * 0.6;
        ctx.beginPath();
        ctx.moveTo(0, -2);
        ctx.lineTo(-flicker2, 0);
        ctx.lineTo(0,  2);
        ctx.closePath();
        ctx.fillStyle = '#f0851a'; ctx.fill();

        ctx.restore();
    }

    ctx.restore();
}

function roundedRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y,     x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x,     y + h, r);
    ctx.arcTo(x,     y + h, x,     y,     r);
    ctx.arcTo(x,     y,     x + w, y,     r);
    ctx.closePath();
}

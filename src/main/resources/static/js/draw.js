// Canvas painter. Runs every time the route, hover, filter, debug toggle,
// or map rotation changes — kept fast enough to call freely from event
// handlers. Other modules (currently just hexMask) push hooks into
// state.drawHooks; we flush them at the end so the previous behaviour of
// the monkey-patched draw() is preserved.
//
// While a route is shown, the dash pattern on each segment animates to
// indicate travel direction (forward = dashes flow start→end). This
// requires re-running draw() at frame rate, which is driven by a
// self-perpetuating rAF tick that auto-stops once no route is active.
import { state } from './state.js';
import { cfgI, cfgF } from './config.js';
import { weightClassMod, weightClassName } from './fuelStrip.js';
import { drawFlight } from './flightAnimation.js';
import { getActiveEndpoint, getActiveRouteIndex, getTreeNodeIds, getPathToRoot } from './routeTree.js';
import { readSiteFilter } from './siteFilter.js';
import { rotateAboutCentre } from './rotation.js';
import { seasonForYear } from './solarCycle.js';

// Season → CSS hex for the per-segment side strip. Mirrors the cycle
// widget's own colour palette so the user can match a label to the
// matching spot on the strip at a glance.
const SEASON_COLOR = { red: '#e94560', yellow: '#f1c40f', blue: '#3498db' };

function wrapYear(y) { return ((y - 1) % 12 + 12) % 12 + 1; }

// rAF-driven animation tick. Single-flight via animRafId; draw() arms it
// at the end of each render when a route is active, animTick re-arms via
// draw(); both stop the moment getActiveEndpoint() goes null.
let animRafId = null;
function ensureRouteAnimation() {
    if (animRafId !== null) return;
    animRafId = requestAnimationFrame(routeAnimTick);
}
function routeAnimTick() {
    animRafId = null;
    if (!getActiveEndpoint()) return; // route gone — let the chain end
    draw();                           // re-renders with new dash offset
}

export function draw() {
    drawInner();
    // Fire post-draw hooks (currently the hex-mask overlay refresh).
    // Mirrors the original monkey-patch which wrapped draw() and called
    // updateHexMasks unconditionally — including when the inner draw
    // bailed out via an early return.
    for (const hook of state.drawHooks) hook();
    // Keep the marching-dash animation running while a route is shown.
    if (getActiveEndpoint()) ensureRouteAnimation();
}

function drawInner() {
    if (!state.mapData || !state.imgW) return;
    const { points, edges } = state.mapData;
    const ctx = state.ctx;
    const canvas = state.canvas;
    const imgW = state.imgW;
    const imgH = state.imgH;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Draw edges
    ctx.strokeStyle = 'rgba(255,255,255,0.08)';
    ctx.lineWidth = 1;
    for (const edgeStr of edges) {
        const colon = edgeStr.indexOf(':');
        const a = points[edgeStr.substring(0, colon)];
        const b = points[edgeStr.substring(colon + 1)];
        if (!a || !b) continue;
        ctx.beginPath();
        ctx.moveTo(a.x * imgW, a.y * imgH);
        ctx.lineTo(b.x * imgW, b.y * imgH);
        ctx.stroke();
    }

    // Compile filter once per draw so each site is only evaluated once.
    // (Reachable / unreachable / filter-match state is now visualised by
    // the SVG hex-mask overlay, so we only need the compiled filter for
    // the route-highlight pass below.)
    const filter = readSiteFilter();

    // Draw highlighted route path (for active endpoint: pinned or hovered)
    const activeEp = getActiveEndpoint();
    const activeIdx = getActiveRouteIndex();
    const treeNodeIds = activeEp ? getTreeNodeIds(activeEp) : null;
    if (activeEp && treeNodeIds && treeNodeIds.length > 0) {
        const treeNodeId = treeNodeIds[activeIdx % treeNodeIds.length];
        const pathNodes = getPathToRoot(treeNodeId);
        if (pathNodes.length >= 2) {
            // Yellow and blue are reserved by the printed HF4A boardgame map
            // (used to highlight orbital routes), so they're omitted here.
            const turnColors = [
                'rgba(0,200,200,0.9)',    // turn 1 — cyan/teal
                'rgba(255,100,0,0.9)',    // turn 2 — orange
                'rgba(180,0,255,0.9)',    // turn 3 — purple
                'rgba(0,255,100,0.9)',    // turn 4 — green
                'rgba(255,50,50,0.9)',    // turn 5 — red
                'rgba(255,100,200,0.9)',  // turn 6 — pink
                'rgba(150,255,0,0.9)',    // turn 7 — lime
                'rgba(180,120,60,0.9)',   // turn 8 — brown
            ];
            // Group segments by undirected edge so that when the same edge
            // is traversed across multiple turns, each turn gets its own
            // parallel line (offset perpendicular to the edge) instead of
            // overdrawing a single colored line.
            //
            // Each turn entry is {turn, forward}: `forward` is true when the
            // player travelled from the entry's `a` (lex-smaller endpoint) to
            // `b`, false when they went the other way. The dash flow direction
            // below uses this to decide the sign of lineDashOffset.
            const segmentsByEdge = new Map();
            for (let i = 1; i < pathNodes.length; i++) {
                const from = pathNodes[i - 1].nodeId;
                const to   = pathNodes[i].nodeId;
                const key = from < to ? from + '|' + to : to + '|' + from;
                const turn = pathNodes[i].turns;
                if (!segmentsByEdge.has(key)) {
                    const a = from < to ? from : to;
                    const b = from < to ? to   : from;
                    segmentsByEdge.set(key, { a, b, turns: [] });
                }
                const entry = segmentsByEdge.get(key);
                const forward = (from === entry.a);
                // De-dupe identical (edge, turn) pairs — a single turn can
                // produce only one directed traversal per edge in a valid
                // path, but guard anyway.
                if (!entry.turns.find(t => t.turn === turn)) {
                    entry.turns.push({ turn, forward });
                }
            }

            const lineWidth = cfgI('ui.route.line.width', 8);
            const spacing = cfgI('ui.route.line.spacing', 9); // perpendicular gap between parallel lines
            ctx.lineWidth = lineWidth;

            // Marching-dash animation: dashes flow in the player's direction
            // of travel along each segment (start → end). Cyclic offset based
            // on performance.now() so the redraw frequency doesn't matter.
            // Convention: increasing lineDashOffset shifts the pattern toward
            // the line's start, so the visible flow is end → start. We want
            //   forward (a → b drawn a → b on screen): flow points to end → use NEGATIVE offset
            //   backward (b → a drawn a → b on screen): flow points to start → use POSITIVE offset
            const dashLen = cfgI('ui.route.dash.len', 12);
            const gapLen  = cfgI('ui.route.gap.len',  8);
            const flowSpeed = cfgF('ui.route.flow.px.per.sec', 30);
            const dashCycle = dashLen + gapLen;
            const flow = (performance.now() / 1000 * flowSpeed) % dashCycle;
            ctx.setLineDash([dashLen, gapLen]);

            // Capture every parallel-offset route line as we draw it, so
            // the segment-label placer below can avoid painting over them.
            const routeLines = [];
            for (const { a, b, turns } of segmentsByEdge.values()) {
                const pa = points[a];
                const pb = points[b];
                if (!pa || !pb) continue;
                const x1 = pa.x * imgW, y1 = pa.y * imgH;
                const x2 = pb.x * imgW, y2 = pb.y * imgH;
                const dx = x2 - x1, dy = y2 - y1;
                const len = Math.hypot(dx, dy) || 1;
                // Unit perpendicular: in screen coords (y growing down) this
                // points to the RIGHT of the canonical a → b direction.
                const px = -dy / len, py = dx / len;

                const drawOne = (t, signedOffset) => {
                    const ox = px * signedOffset, oy = py * signedOffset;
                    ctx.strokeStyle = turnColors[(t.turn - 1) % turnColors.length];
                    ctx.lineDashOffset = t.forward ? -flow : flow;
                    ctx.beginPath();
                    ctx.moveTo(x1 + ox, y1 + oy);
                    ctx.lineTo(x2 + ox, y2 + oy);
                    ctx.stroke();
                    routeLines.push({ x1: x1 + ox, y1: y1 + oy, x2: x2 + ox, y2: y2 + oy });
                };

                // Side-of-travel convention (right-hand traffic): each line
                // sits on the RIGHT of its own travel direction. So:
                //   - Forward turns (a → b in canonical orientation) use
                //     POSITIVE offsets along (px, py)  → right of canonical.
                //   - Backward turns (b → a) use NEGATIVE offsets → right of
                //     their own travel direction (= left of canonical).
                // This keeps a there-and-back pair consistently splayed
                // ("two cars passing on a road") regardless of whether the
                // outbound or return trip happened first in turn order.
                //
                // When the edge is traversed in only ONE direction (any
                // multiplicity) we centre the bundle on the canonical line
                // for readability — there's no opposing traffic to avoid.
                const forwards  = turns.filter(t => t.forward).sort((u, v) => u.turn - v.turn);
                const backwards = turns.filter(t => !t.forward).sort((u, v) => u.turn - v.turn);
                if (forwards.length > 0 && backwards.length > 0) {
                    forwards .forEach((t, i) => drawOne(t, +spacing * (0.5 + i)));
                    backwards.forEach((t, i) => drawOne(t, -spacing * (0.5 + i)));
                } else {
                    const all = forwards.length > 0 ? forwards : backwards;
                    const n = all.length;
                    all.forEach((t, k) => drawOne(t, (k - (n - 1) / 2) * spacing));
                }
            }
            // Reset dash state so connector lines below + any other strokes
            // outside this block stay solid.
            ctx.setLineDash([]);
            ctx.lineDashOffset = 0;

            // --- Engine-segment labels ---
            // A "segment" is a contiguous run of moves within a single turn
            // using the same engine. Each new turn starts a fresh segment
            // (the ship resets its burn budget), so a label is drawn at
            // every turn boundary AND at every engine switch, not only at
            // the start of the trip.
            // Snapshot engine config once per render so each segment label
            // can show the effective thrust (base + weight-class + solar).
            const engineCfgs = Array.from(document.querySelectorAll('.engine-block')).map(b => ({
                baseThrust:   parseInt(b.querySelector('.e-base-thrust').value) || 0,
                solarPowered: b.querySelector('.e-solar').checked,
            }));

            // Pre-pass: walk the path edges and split them into "blocks":
            //   - 'wait'   = consecutive wait edges at the same node
            //               (pn[i-1].nodeId === pn[i].nodeId && pn[i].turns > pn[i-1].turns).
            //               Rendered as a single "wait N turn(s)" label
            //               spanning the full turn range, regardless of how
            //               many turns the search broke them into.
            //   - 'move'   = consecutive move edges sharing engine+turn,
            //               which is the original "engine segment" notion.
            //               Rendered with engine / burn / fuel info.
            // Wait blocks are ALWAYS broken out separately so a player who
            // declines to move just sees "wait", not a misleading
            // "Engine 1 - Burns 0 - Fuel Steps 0" box.
            const blocks = [];
            {
                let i = 1;
                while (i < pathNodes.length) {
                    const prev = pathNodes[i - 1];
                    const cur = pathNodes[i];
                    const isWait = prev.nodeId === cur.nodeId && cur.turns > prev.turns;

                    if (isWait) {
                        let end = i + 1;
                        while (end < pathNodes.length) {
                            const p2 = pathNodes[end - 1];
                            const c2 = pathNodes[end];
                            if (p2.nodeId !== c2.nodeId || c2.turns <= p2.turns) break;
                            end++;
                        }
                        blocks.push({ kind: 'wait', anchorIdx: i - 1, startIdx: i, endIdx: end });
                        i = end;
                    } else {
                        const ei = cur.engineIndex;
                        const tn = cur.turns;
                        let end = i + 1;
                        while (end < pathNodes.length) {
                            const p2 = pathNodes[end - 1];
                            const c2 = pathNodes[end];
                            // Boundary: another wait, or engine/turn change.
                            if (p2.nodeId === c2.nodeId && c2.turns > p2.turns) break;
                            if (c2.engineIndex !== ei || c2.turns !== tn) break;
                            end++;
                        }
                        blocks.push({ kind: 'move', anchorIdx: i - 1, startIdx: i, endIdx: end });
                        i = end;
                    }
                }
            }

            ctx.save();
            const fontPx = cfgI('ui.engine.label.font.px', 14);
            ctx.font = 'bold ' + fontPx + 'px monospace';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'bottom';

            // PASS 1: build a label record per block. We don't draw
            // anything yet — we need to know every box's preferred bounds
            // before we can resolve overlaps.
            const labels = [];
            const drawnAt = new Set(); // dedupe labels with identical content+anchor
            const startYear = (state.activeTab && state.activeTab.state.solarYear) || 1;
            for (const block of blocks) {
                const anchor = pathNodes[block.anchorIdx];
                const p = points[anchor.nodeId];
                if (!p) continue;
                const ax = p.x * imgW;
                const ay = p.y * imgH;
                // Viewport-rotated anchor: where the anchor visually lands
                // after CSS map rotation. Layout (preferred slot, collision
                // avoidance) happens in this upright-viewport frame so the
                // segment labels appear axis-aligned regardless of rotation.
                const aRot = rotateAboutCentre(ax, ay, +1);
                const rax = aRot.x, ray = aRot.y;
                const lineH = fontPx + 4;

                let line1, line2 = null, line3 = null, labelTurn, key;
                if (block.kind === 'wait') {
                    // Wait block: skip engine info entirely. Each wait edge
                    // represents one full mission turn spent doing nothing
                    // (turn N → turn N+1 = "spent turn N waiting"), so the
                    // displayed range is anchor.turns ..= lastChild.turns - 1.
                    // The post-wait state's turn (lastChild.turns) is the
                    // NEXT active turn — that one is labelled by whichever
                    // segment starts there.
                    const fromTurn = anchor.turns;
                    const toTurn   = pathNodes[block.endIdx - 1].turns - 1;
                    const count    = block.endIdx - block.startIdx;
                    const range = (count === 1) ? String(fromTurn) : (fromTurn + '-' + toTurn);
                    line1 = range + '. wait ' + count + (count === 1 ? ' turn' : ' turns');
                    labelTurn = fromTurn;  // season strip uses the first wait turn
                    key = anchor.nodeId + '|wait|' + fromTurn + '-' + toTurn;
                } else {
                    // Move block (existing engine-label logic).
                    const i      = block.startIdx;
                    const segEnd = block.endIdx;
                    const ei     = pathNodes[i].engineIndex;
                    labelTurn    = pathNodes[i].turns;
                    key          = anchor.nodeId + '|' + ei + '|' + labelTurn;
                    // Use the post-jettison wet mass when this segment starts a
                    // turn that jettisoned fuel — otherwise the segment-start
                    // mass is just the anchor's mass.
                    const jettisoned   = pathNodes[i].jettisonedHere || 0;
                    const wmForSegment = jettisoned > 0 ? pathNodes[i].wetMass : anchor.wetMass;
                    const segStartFuelSpent = jettisoned > 0
                            ? pathNodes[i].fuelSpent
                            : anchor.fuelSpent;
                    const afterburns = pathNodes[i].afterburnedHere || 0;
                    const eng = engineCfgs[ei];
                    let thrustStr = '?';
                    if (eng) {
                        const solarMod = eng.solarPowered ? (p.solarMod || 0) : 0;
                        thrustStr = String(eng.baseThrust + weightClassMod(wmForSegment) + solarMod + afterburns);
                    }
                    line1 = labelTurn + '. Engine ' + (ei + 1) + ' - ' + weightClassName(wmForSegment) + ' - Thrust ' + thrustStr;

                    // Burns within this segment.
                    //   • Standard burn: move into a burn-type node from a
                    //     different node — counts as 1 (free or paid).
                    //   • Forced turn at a Hohmann intersection: same-node,
                    //     same-turn move that consumed fuel — counts as 2.
                    //   • Free pivots / cruise — count as 0.
                    // Skip the jettison-creation edge (pn[i-1] → pn[i]) when this
                    // segment starts with a jettison node — that edge changes
                    // fuelSpent but is NOT a burn.
                    const burnLoopStart = jettisoned > 0 ? i + 1 : i;
                    let burns = 0;
                    for (let k = burnLoopStart; k < segEnd; k++) {
                        const prev = pathNodes[k - 1];
                        const cur  = pathNodes[k];
                        const dst  = points[cur.nodeId];
                        if (!dst) continue;
                        const sameNode = (prev.nodeId === cur.nodeId);
                        const sameTurn = (prev.turns  === cur.turns);
                        if (!sameNode && dst.type === 'burn') {
                            burns++;
                        } else if (sameNode && sameTurn && cur.fuelSpent > prev.fuelSpent) {
                            burns += 2;
                        }
                    }
                    const fuelSteps = pathNodes[segEnd - 1].fuelSpent - segStartFuelSpent;
                    line2 = 'Burns ' + burns + ' - Fuel Steps ' + fuelSteps;
                    if (afterburns > 0) line2 += ' - Afterburn +' + afterburns;
                    line3 = jettisoned > 0 ? 'Jettison ' + jettisoned + ' fuel steps' : null;
                }
                if (drawnAt.has(key)) continue;
                drawnAt.add(key);
                const w1 = ctx.measureText(line1).width;
                const w2 = line2 ? ctx.measureText(line2).width : 0;
                const w3 = line3 ? ctx.measureText(line3).width : 0;
                const w  = Math.max(w1, w2, w3);
                const numLines = (line3 ? 3 : (line2 ? 2 : 1));
                // +12 = 2 px left border + 4 px left indent + 4 px right indent + 2 px right border.
                // 1.5× the tight-fit width so the season-coloured frame
                // reads at a glance even with a short label.
                const boxW = Math.round((w + 12) * 1.5);
                const boxH = 18 + (numLines - 1) * lineH;
                // Preferred placement is in VIEWPORT-rotated space (above-
                // right of where the anchor will visually land).
                const boxX = rax + 7;
                const preferredBoxY = ray - 10 - boxH + 2;
                // Season at this label's turn. Same arithmetic as
                // solarCycle.js: turn 1 is the user-set startingYear.
                const segSeason = seasonForYear(wrapYear(startYear + labelTurn - 1));
                labels.push({
                    ax, ay, rax, ray,
                    boxX, boxY: preferredBoxY,
                    preferredBoxY,
                    boxW, boxH,
                    line1, line2, line3,
                    numLines, lineH,
                    season: segSeason,
                });
            }

            // PASS 2: collision avoidance.
            //
            // For each label, try a fixed set of candidate offsets from
            // its preferred position (right side then left side, stepping
            // both downward and upward). Score each candidate by:
            //   • box-vs-box overlap with already-placed labels   (HARD: avoid)
            //   • box-vs-route-line crossing                       (SOFT: avoid if possible)
            // Keep the first candidate that's clear of BOTH; if every
            // candidate hits a route line, fall back to the first that's
            // at least free of other labels (best-effort, since the user
            // accepts route overlap when nothing else fits).
            const rectsOverlap = (a, b) => !(
                a.boxX + a.boxW + 2 < b.boxX ||
                b.boxX + b.boxW + 2 < a.boxX ||
                a.boxY + a.boxH + 2 < b.boxY ||
                b.boxY + b.boxH + 2 < a.boxY
            );
            // Cheap line-vs-rect intersection. True if the segment endpoints
            // sit inside the rect, or any rect edge intersects the segment.
            const lineCrossesRect = (ln, r) => {
                const inside = (x, y) =>
                    x >= r.boxX - 1 && x <= r.boxX + r.boxW + 1 &&
                    y >= r.boxY - 1 && y <= r.boxY + r.boxH + 1;
                if (inside(ln.x1, ln.y1) || inside(ln.x2, ln.y2)) return true;
                const segs = (ax1, ay1, ax2, ay2, bx1, by1, bx2, by2) => {
                    const d1x = ax2 - ax1, d1y = ay2 - ay1;
                    const d2x = bx2 - bx1, d2y = by2 - by1;
                    const den = d1x * d2y - d1y * d2x;
                    if (Math.abs(den) < 1e-9) return false;
                    const t = ((bx1 - ax1) * d2y - (by1 - ay1) * d2x) / den;
                    const u = ((bx1 - ax1) * d1y - (by1 - ay1) * d1x) / den;
                    return t >= 0 && t <= 1 && u >= 0 && u <= 1;
                };
                const x = r.boxX, y = r.boxY, X = r.boxX + r.boxW, Y = r.boxY + r.boxH;
                return segs(ln.x1, ln.y1, ln.x2, ln.y2, x, y, X, y)
                    || segs(ln.x1, ln.y1, ln.x2, ln.y2, X, y, X, Y)
                    || segs(ln.x1, ln.y1, ln.x2, ln.y2, X, Y, x, Y)
                    || segs(ln.x1, ln.y1, ln.x2, ln.y2, x, Y, x, y);
            };
            // Rotate route lines into viewport-aligned space so the
            // box-vs-line check is comparing apples to apples (boxes are in
            // viewport space, lines must be too).
            const routeLinesView = routeLines.map(ln => {
                const a = rotateAboutCentre(ln.x1, ln.y1, +1);
                const b = rotateAboutCentre(ln.x2, ln.y2, +1);
                return { x1: a.x, y1: a.y, x2: b.x, y2: b.y };
            });
            labels.sort((a, b) => a.boxY - b.boxY || a.boxX - b.boxX);
            const placedLabels = [];
            for (const cur of labels) {
                const right = cur.rax + 7;
                const left  = cur.rax - cur.boxW - 7;
                const above = cur.preferredBoxY;       // y of box top when above-anchor
                const below = cur.ray + 12;            // y of box top when below-anchor
                const dyStep = cur.boxH + 6;
                // Build candidate list, ordered by total displacement from preferred.
                const cands = [];
                for (let s = 0; s <= 8; s++) {
                    // Right-side first, then left, alternating up/down each step
                    // so we explore all 4 quadrants close to the anchor first.
                    cands.push({ boxX: right, boxY: above + s * dyStep });
                    if (s > 0) cands.push({ boxX: right, boxY: above - s * dyStep });
                    cands.push({ boxX: right, boxY: below + s * dyStep });
                    cands.push({ boxX: left,  boxY: above + s * dyStep });
                    if (s > 0) cands.push({ boxX: left,  boxY: above - s * dyStep });
                    cands.push({ boxX: left,  boxY: below + s * dyStep });
                }
                let cleanCand = null;     // no boxes AND no route-lines
                let fallbackCand = null;  // no boxes, lines acceptable
                for (const c of cands) {
                    const test = { boxX: c.boxX, boxY: c.boxY, boxW: cur.boxW, boxH: cur.boxH };
                    let boxHit = false;
                    for (const e of placedLabels) {
                        if (rectsOverlap(test, e)) { boxHit = true; break; }
                    }
                    if (boxHit) continue;
                    if (!fallbackCand) fallbackCand = c;
                    let lineHit = false;
                    for (const ln of routeLinesView) {
                        if (lineCrossesRect(ln, test)) { lineHit = true; break; }
                    }
                    if (!lineHit) { cleanCand = c; break; }
                }
                const chosen = cleanCand || fallbackCand
                                          || { boxX: cur.boxX, boxY: cur.preferredBoxY };
                cur.boxX = chosen.boxX;
                cur.boxY = chosen.boxY;
                placedLabels.push(cur);
            }

            // PASS 3: draw. Each label is laid out in viewport-rotated
            // space (boxX/boxY); we counter-rotate the canvas about the
            // image centre so that, after the CSS rotation applied by
            // applyMainTransform(), the label appears axis-aligned and
            // upright. If the box was shifted from its preferred slot, a
            // thin connector ties it back to its anchor.
            for (const L of labels) {
                const dx = L.boxX - (L.rax + 7);
                const dy = L.boxY - L.preferredBoxY;
                const moved = Math.abs(dx) > 4 || Math.abs(dy) > 4;
                if (moved) {
                    // Pick the box-edge midpoint nearest the (rotated)
                    // anchor — all in viewport space.
                    const cx = L.boxX + L.boxW / 2;
                    const cy = L.boxY + L.boxH / 2;
                    let tx, ty;
                    if (L.rax < L.boxX)               { tx = L.boxX;              ty = cy; }
                    else if (L.rax > L.boxX + L.boxW) { tx = L.boxX + L.boxW;     ty = cy; }
                    else if (L.ray < L.boxY)          { tx = cx;                  ty = L.boxY; }
                    else                               { tx = cx;                  ty = L.boxY + L.boxH; }
                    // Map the connector endpoint back to canvas space so it
                    // rotates with the rest of the canvas content.
                    const tCanvas = rotateAboutCentre(tx, ty, -1);
                    ctx.save();
                    ctx.strokeStyle = 'rgba(255,224,102,0.55)';
                    ctx.lineWidth = 1;
                    ctx.beginPath();
                    ctx.moveTo(L.ax, L.ay);
                    ctx.lineTo(tCanvas.x, tCanvas.y);
                    ctx.stroke();
                    ctx.restore();
                }
                // Translate to the canvas point that, after CSS rotation,
                // lands at (L.boxX, L.boxY). Then counter-rotate so the
                // box and text are upright after the CSS transform.
                const boxCanvas = rotateAboutCentre(L.boxX, L.boxY, -1);
                ctx.save();
                ctx.translate(boxCanvas.x, boxCanvas.y);
                ctx.rotate(-state.mapRotation);
                ctx.fillStyle = 'rgba(0,0,0,0.75)';
                ctx.fillRect(0, 0, L.boxW, L.boxH);
                // Per-turn season indicator: the entire box frame is
                // stroked in the season colour. More visible than a thin
                // left-edge strip, and unambiguous about which season the
                // segment belongs to. Colour mirrors the Solar Cycle widget
                // palette.
                const borderW = 2;
                ctx.lineWidth   = borderW;
                ctx.strokeStyle = SEASON_COLOR[L.season] || '#888';
                ctx.strokeRect(borderW / 2, borderW / 2,
                               L.boxW - borderW, L.boxH - borderW);
                // Inside the rotated frame the box top-left is (0,0).
                const textX = borderW + 4;
                const bottomY = L.boxH - 2; // textBaseline='bottom'
                ctx.fillStyle = '#ffe066';
                ctx.fillText(L.line1, textX, bottomY - (L.numLines - 1) * L.lineH);
                if (L.line2) {
                    ctx.fillStyle = '#eee';
                    ctx.fillText(L.line2, textX, bottomY - (L.line3 ? L.lineH : 0));
                }
                if (L.line3) {
                    ctx.fillStyle = '#ff6b6b';
                    ctx.fillText(L.line3, textX, bottomY);
                }
                ctx.restore();
            }
            ctx.restore();

            // Route info is rendered into the sidebar (above fuel input),
            // not onto the canvas. See updateRouteInfo().
        }
    }

    // Debug: show thrustRequired tooltip on landing burn hover
    if (state.debugMode && state.debugHoveredNode) {
        const dp = points[state.debugHoveredNode];
        if (dp && dp.type === 'burn' && dp.landing && dp.thrustRequired) {
            const tx = dp.x * imgW + 12;
            const ty = dp.y * imgH - 8;
            ctx.save();
            ctx.fillStyle = 'rgba(0,0,0,0.8)';
            ctx.fillRect(tx - 4, ty - 14, 160, 20);
            ctx.fillStyle = '#e94560';
            ctx.font = 'bold 13px monospace';
            ctx.textAlign = 'left';
            ctx.fillText('thrustReq: ' + dp.thrustRequired, tx, ty);
            ctx.restore();
        }
    }

    // (NOT REACHABLE badges and filter-matching rings used to be drawn
    // here; they were superseded by the SVG hex-mask overlay.)

    // Draw selected start node highlight
    if (state.selectedNode) {
        const p = points[state.selectedNode];
        if (p) {
            ctx.beginPath();
            ctx.arc(p.x * imgW, p.y * imgH, 10, 0, Math.PI * 2);
            ctx.fillStyle = 'red';
            ctx.fill();
            ctx.strokeStyle = 'white';
            ctx.lineWidth = 2;
            ctx.stroke();
        }
    }

    // Junker flying sprite — drawn over the route line and start
    // marker, under the hover indicator dot. No-op when no flight is
    // active (state.flightAnim == null).
    drawFlight(ctx);

    // Hover indicator dot: marks the route node whose fuel state the
    // panel is currently reading. Driven by fuelStripHoverIndicatorId,
    // which updateEndpointFuelStrip sets when the panel target was
    // chosen by an active hover (on-route node OR route segment) and
    // leaves null otherwise — so the dot only appears for explicit
    // hovers, never for the fall-back endpoint state.
    //
    // Position selection:
    //   - Segment-hover (indicator id == segment-start id, projection
    //     is set): draw at the projected point so the dot rides the
    //     cursor along the segment line.
    //   - Otherwise: draw at the indicator node's centre.
    if (state.fuelStripHoverIndicatorId) {
        let dotX, dotY;
        if (state.hoveredRouteSegmentStartId === state.fuelStripHoverIndicatorId
                && state.hoveredRouteSegmentProj) {
            dotX = state.hoveredRouteSegmentProj.x;
            dotY = state.hoveredRouteSegmentProj.y;
        } else {
            const p = points[state.fuelStripHoverIndicatorId];
            if (!p) return;
            dotX = p.x * imgW;
            dotY = p.y * imgH;
        }
        ctx.beginPath();
        ctx.arc(dotX, dotY, 6, 0, Math.PI * 2);
        ctx.fillStyle = '#2ecc71';
        ctx.fill();
        ctx.strokeStyle = 'white';
        ctx.lineWidth = 1.5;
        ctx.stroke();
    }
}

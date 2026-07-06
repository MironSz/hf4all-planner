// Solar-cycle year tracker. Renders a 12-spot horizontal strip and lets
// the user pick the STARTING year of the mission (1..12). The visible
// highlight then follows the *currently active* path node — same hover /
// pin / segment-hover logic as the fuel-strip overlay — so as the user
// scrubs through a route the highlighted spot advances around the cycle.
//
// State model:
//   state.activeTab.state.solarYear  = starting year (year of turn 1).
//                                       Set by clicking a spot.
//   displayedYear                    = derived = startingYear + (activeTurn - 1)
//                                       wrapped to 1..12. Driven by hover/pin
//                                       on every draw via the drawHooks queue.
//
// The starting year is consumed by the planner: fireTraverse sends it as
// `startingYear` in the /api/traverse request, driving the season-aware
// rules (Belt-Roll +2 in red K1, Venus-flyby blue gate H8c, synodic-comet
// accessibility). Clicking a spot therefore refires the traverse.
import { state } from './state.js';
import { persistTabs } from './tabs.js';
import { getTreeNodeIds, getPathToRoot } from './routeTree.js';
import { fireTraverse } from './traverse.js';

// HF4A Sunspot Cycle (K1) seasons. The 12 years partition into three
// 4-year colour bands in the order BLUE → YELLOW → RED, with year 1 at
// the cycle start.
const SEASON_FOR_YEAR = {
    1:'blue',   2:'blue',   3:'blue',   4:'blue',
    5:'yellow', 6:'yellow', 7:'yellow', 8:'yellow',
    9:'red',   10:'red',   11:'red',   12:'red',
};

export function seasonForYear(year) {
    return SEASON_FOR_YEAR[year] || 'blue';
}

/**
 * Wrap a year value into the [1, 12] range, accepting negative or
 * arbitrarily large inputs.
 */
function wrapYear(y) {
    return ((y - 1) % 12 + 12) % 12 + 1;
}

/**
 * Mirrors the fuel-strip overlay's target-picking logic in
 * endpointFuelStripOverlay.updateEndpointFuelStrip:
 *
 *   - Endpoint pinned + cursor on the selected route node:    that node
 *   - Endpoint pinned + cursor on a route segment:            segment's earlier node
 *   - Endpoint pinned + cursor elsewhere:                     the endpoint
 *   - No pin + hovering a reachable node:                     that node's best-route endpoint
 *   - Otherwise:                                              null (= idle)
 *
 * Returns the chosen PathNode or null. Caller treats null as "rest at
 * starting year" (= turn 1).
 */
function pickActivePathNode() {
    // Note: we deliberately don't require state.mapData here (the fuel-strip
    // overlay does, because it positions chits using point coordinates;
    // the cycle widget only reads PathNode.turns).
    if (!state.traverseResult || !state.reachableNodes) return null;

    if (state.pinnedEndpoint) {
        const treeNodeIds = getTreeNodeIds(state.pinnedEndpoint);
        if (!treeNodeIds || !treeNodeIds.length) return null;
        const idx = state.selectedRouteIndex;
        const pathNodes = getPathToRoot(treeNodeIds[idx % treeNodeIds.length]);
        if (!pathNodes || !pathNodes.length) return null;

        if (state.hoveredNode) {
            const onRouteHover = pathNodes.find(pn => pn.nodeId === state.hoveredNode);
            if (onRouteHover) return onRouteHover;
        }
        if (state.hoveredRouteSegmentStartId) {
            const segStartHover = pathNodes.find(
                    pn => pn.nodeId === state.hoveredRouteSegmentStartId);
            if (segStartHover) return segStartHover;
        }
        return pathNodes[pathNodes.length - 1];
    }

    if (state.hoveredNode && state.reachableNodes[state.hoveredNode]) {
        const treeNodeIds = getTreeNodeIds(state.hoveredNode);
        if (treeNodeIds && treeNodeIds.length) {
            const pathNodes = getPathToRoot(treeNodeIds[0]); // best route
            return pathNodes && pathNodes[pathNodes.length - 1];
        }
    }
    return null;
}

export function initSolarCycle() {
    const track = document.getElementById('solar-cycle-track');
    if (!track) return;
    track.innerHTML = '';
    for (let y = 1; y <= 12; y++) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'solar-cycle-spot solar-' + SEASON_FOR_YEAR[y];
        btn.textContent = String(y);
        btn.dataset.year = String(y);
        btn.title = `Year ${y} — ${SEASON_FOR_YEAR[y]}`;
        btn.addEventListener('click', () => setStartingYear(y));
        track.appendChild(btn);
    }
    // Refresh on every draw so hover/pin changes update the highlight
    // without each event handler having to remember to call us.
    state.drawHooks.push(updateSolarCycleDisplay);
    updateSolarCycleDisplay();
}

/**
 * Click handler — sets the starting year (year of turn 1).
 * The highlight then derives from this base + active path node's turn.
 */
export function setStartingYear(y) {
    if (state.activeTab) state.activeTab.state.solarYear = y;
    updateSolarCycleDisplay();
    persistTabs();
    // Season changes alter Belt-Roll +2, Venus-flyby blue gate, and
    // synodic-comet accessibility — so a fresh traverse is required.
    if (state.selectedNode) fireTraverse();
}

/**
 * Recompute and apply the visible highlight. Cheap — runs on every
 * draw, plus on click. The "starting" spot (clicked one) gets a small
 * dot indicator; the "displayed" spot (derived from active path node)
 * gets the main .selected highlight.
 */
export function updateSolarCycleDisplay() {
    const track = document.getElementById('solar-cycle-track');
    if (!track) return;
    const startingYear = (state.activeTab && state.activeTab.state.solarYear) || 1;
    const target = pickActivePathNode();
    const turns = target ? (target.turns || 1) : 1;
    const displayedYear = wrapYear(startingYear + turns - 1);

    track.querySelectorAll('.solar-cycle-spot').forEach(b => {
        const y = parseInt(b.dataset.year);
        b.classList.toggle('selected',     y === displayedYear);
        b.classList.toggle('starting-year', y === startingYear && y !== displayedYear);
    });
}

/** Compatibility shim — older callers used this name. */
export const applySelectionFromState = updateSolarCycleDisplay;

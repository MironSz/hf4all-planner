// Route-info card in the sidebar (route name, fuel/turn cost, debug
// dump, route-cycle arrows, copy-to-clipboard).
import { state } from './state.js';
import { cfgI } from './config.js';
import { formatFuelOnStrip } from './fuelStrip.js';
import { getActiveEndpoint, getActiveRouteIndex, getTreeNodeIds, getPathToRoot } from './routeTree.js';
import { updateEndpointFuelStrip } from './endpointFuelStripOverlay.js';
import { draw } from './draw.js';
import { persistTabs } from './tabs.js';
import { startFlightAnimation } from './flightAnimation.js';
import { seasonForYear } from './solarCycle.js';

// Cycle wraps the 1..12 calendar year; matches Season.atYear's modulo.
function wrapYear(y) { return ((y - 1) % 12 + 12) % 12 + 1; }

export function updateRouteInfo() {
    const panel = document.getElementById('route-info');
    const leftArrow = document.getElementById('route-arrow-left');
    const rightArrow = document.getElementById('route-arrow-right');
    // Re-evaluate the endpoint fuel-strip overlay every time route info
    // refreshes (handles pin/unpin, route switch, dry-mass change).
    // The function's own checks decide whether to actually show.
    updateEndpointFuelStrip();
    const active = getActiveEndpoint();
    if (!active) { panel.style.display = 'none'; return; }
    const treeNodeIds = getTreeNodeIds(active);
    if (!treeNodeIds || treeNodeIds.length === 0) {
        panel.style.display = 'none';
        return;
    }
    // Wrap the index BEFORE it's displayed: selectedRouteIndex can briefly
    // exceed the deduped route count (e.g. a pin restored mid-stream is
    // clamped against the raw endpoint list), and the label must agree
    // with the route actually shown.
    const idx = getActiveRouteIndex() % treeNodeIds.length;
    const pathNodes = getPathToRoot(treeNodeIds[idx]);
    const endNode = pathNodes[pathNodes.length - 1];
    const hp = state.mapData.points[active];
    if (!endNode || !hp) { panel.style.display = 'none'; return; }

    const name = hp.siteName || active.substring(0, 10);
    const routeLabel = treeNodeIds.length > 1
            ? `  [${idx + 1}/${treeNodeIds.length}]` : '';
    document.getElementById('route-info-name').textContent = name + routeLabel;
    const dry = parseInt(document.getElementById('dry-mass').value) || cfgI('ui.dry.mass.default', 4);
    document.getElementById('route-info-cost').textContent =
            'fuel spent: ' + endNode.fuelSpent
            + '  fuel left: ' + formatFuelOnStrip(dry, endNode.fuelStepsRemaining);
    document.getElementById('route-info-haz').textContent =
            'turns: ' + endNode.turns
            + '  haz: ' + endNode.hazards
            + '  rad: ' + endNode.worstRadRoll;
    const idEl = document.getElementById('route-info-id');
    const copyBtn = document.getElementById('copy-route-btn');
    if (state.debugMode) {
        idEl.textContent = 'id: ' + active;
        idEl.style.display = 'block';
        copyBtn.style.display = 'inline-block';
    } else {
        idEl.style.display = 'none';
        copyBtn.style.display = 'none';
    }
    panel.style.display = 'flex';
    const showArrows = treeNodeIds.length > 1;
    leftArrow.style.display  = showArrows ? 'flex' : 'none';
    rightArrow.style.display = showArrows ? 'flex' : 'none';
}

// Arrow clicks mirror the ArrowLeft/ArrowRight key behavior: cycle the
// selected Pareto route for the active endpoint. Pin status is preserved —
// hovered routes stay hovered, pinned routes stay pinned. (See
// routeTree.js getActiveRouteIndex / canvas.js onCanvasMouseMove for the
// per-hover index reset that makes this work.)
export function cycleRoute(delta) {
    const active = getActiveEndpoint();
    if (!active) return;
    const ids = getTreeNodeIds(active);
    if (!ids || ids.length <= 1) return;
    state.selectedRouteIndex = (state.selectedRouteIndex + delta + ids.length) % ids.length;
    draw();
    updateDebugRoute();
    updateRouteInfo();
    persistTabs();
    // Replay the flight along the newly-selected route. Internally
    // no-ops when no endpoint is pinned (hovered cycle stays silent).
    startFlightAnimation();
}

// Builds the textual route dump rendered in the debug panel and copied to
// the clipboard. Returns null when there's no active endpoint.
function buildRouteDetailText() {
    const active = getActiveEndpoint();
    if (!active) return null;
    const treeNodeIds = getTreeNodeIds(active);
    if (!treeNodeIds || treeNodeIds.length === 0) return null;
    const idx = getActiveRouteIndex() % treeNodeIds.length;
    const pathNodes = getPathToRoot(treeNodeIds[idx]);

    // Ship config — read fresh from DOM so the dump matches the current
    // form values (these are also what the last /api/traverse used).
    const dryMass = parseInt(document.getElementById('dry-mass').value) || 0;
    const fuelText = document.getElementById('fuel').value;
    const engines = Array.from(document.querySelectorAll('.engine-block')).map(b => ({
        baseThrust:   parseInt(b.querySelector('.e-base-thrust').value) || 0,
        fuel:         parseInt(b.querySelector('.e-fuel').value)        || 0,
        pivots:       parseInt(b.querySelector('.e-pivots').value)      || 0,
        solar:        b.querySelector('.e-solar').checked,
    }));

    // Pretty names for start / end nodes.
    const labelOf = (id) => {
        const p = state.mapData && state.mapData.points && state.mapData.points[id];
        if (!p) return id;
        const nm = p.siteName ? p.siteName : id;
        return `${nm} [${p.type || '?'}] (id=${id})`;
    };

    // Calendar-year context for the dump. The Sunspot Cycle starting year
    // (HF4A K1) is a per-tab setting; year-at-turn-N = wrap(start+N-1) and
    // determines season-conditioned rules (Belt-Roll +2 in red, Venus blue,
    // synodic gates).
    const startingYear = (state.activeTab && state.activeTab.state.solarYear) || 1;

    const header = [
        `Route ${idx + 1}/${treeNodeIds.length}`,
        `Start:  ${labelOf(state.selectedNode)}`,
        `End:    ${labelOf(active)}`,
        `Mass:   dry=${dryMass}, fuel=${fuelText}`,
        `Year:   starting=${startingYear} (${seasonForYear(startingYear)})`,
        `Engines (${engines.length}):`,
        ...engines.map((e, i) =>
            `  e${i + 1}: thrust=${e.baseThrust}, fuel/burn=${e.fuel}, pivots=${e.pivots}` +
            (e.solar ? ', solar' : '')),
        ''
    ].join('\n');

    const lines = pathNodes.map((n, i) => {
        const p = state.mapData.points[n.nodeId];
        const name = (p && p.siteName) ? p.siteName : n.nodeId.substring(0, 12);
        const type = (p && p.type) ? p.type : '?';
        const eng = (n.engineIndex != null && n.engineIndex >= 0)
                ? ` e${n.engineIndex + 1}` : '';
        const jet = (n.jettisonedHere > 0) ? ` JETT${n.jettisonedHere}` : '';
        const ab  = (n.afterburnedHere > 0) ? ` AB+${n.afterburnedHere}` : '';
        // Calendar year and season at this step's turn.
        const yr = wrapYear(startingYear + n.turns - 1);
        const season = seasonForYear(yr).charAt(0).toUpperCase();   // R / Y / B
        return `${i}) ${name} [${type}] t${n.turns}(y${yr}${season}) f${n.fuelSpent} h${n.hazards} r${n.worstRadRoll}${eng}${jet}${ab}`;
    });
    return header + lines.join('\n');
}

export function updateDebugRoute() {
    const el = document.getElementById('debug-route');
    if (!state.debugMode) { el.style.display = 'none'; return; }
    const text = buildRouteDetailText();
    if (text == null) { el.style.display = 'none'; return; }
    el.textContent = text;
    el.style.display = 'block';
}

function fallbackCopy(text, onOk) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); onOk(); } catch {}
    document.body.removeChild(ta);
}

// Wires the static route-arrow buttons + the copy-route button.
// Called once at startup by main.js.
export function initRouteInfo() {
    document.getElementById('route-arrow-left')
            .addEventListener('click', () => cycleRoute(-1));
    document.getElementById('route-arrow-right')
            .addEventListener('click', () => cycleRoute(+1));

    // Copy-to-clipboard button (only shown in debug mode alongside route-info).
    document.getElementById('copy-route-btn').addEventListener('click', () => {
        const btn = document.getElementById('copy-route-btn');
        const text = buildRouteDetailText();
        if (text == null) return;
        const onOk = () => {
            const orig = btn.textContent;
            btn.textContent = 'Copied!';
            btn.classList.add('copied');
            setTimeout(() => { btn.textContent = orig; btn.classList.remove('copied'); }, 1200);
        };
        // navigator.clipboard is unavailable over plain http from non-localhost
        // origins; fall back to a hidden textarea + execCommand.
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(onOk).catch(() => fallbackCopy(text, onOk));
        } else {
            fallbackCopy(text, onOk);
        }
    });
}

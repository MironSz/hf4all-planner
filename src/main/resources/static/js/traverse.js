// Fires the /api/traverse request and updates state on response.
//
// Tab/token handshake: at fire time we capture the active tab AND a
// monotonic token. Anything that switches tabs increments
// state.pendingFetchToken, so a stale response landing on the previous
// tab is silently dropped — neither the new tab gets overwritten nor
// the originating tab gets a delayed cache.
import { state } from './state.js';
import { buildTreeIndex } from './routeTree.js';
import { draw } from './draw.js';
import { persistTabs } from './tabs.js';
import { parseFuelText } from './fuelStrip.js';
import { updateRouteInfo, updateDebugRoute } from './routeInfo.js';
import { startFlightAnimation } from './flightAnimation.js';

export function fireTraverse() {
    if (!state.selectedNode) return;
    // Capture the existing pin + route index BEFORE clearing them so a
    // refire (settings tweak, share-URL paste, …) can restore the
    // user's selection if the same endpoint is still reachable in the
    // new tree. Without this, every refire forces the user to re-click
    // their endpoint — and a pasted share link would always land on
    // "no route selected".
    const intendedPin        = state.pinnedEndpoint;
    const intendedRouteIndex = state.selectedRouteIndex;
    state.pinnedEndpoint = null;
    state.selectedRouteIndex = 0;

    const engines = [];
    document.querySelectorAll('.engine-block').forEach(block => {
        const canAB = block.querySelector('.e-afterburn').checked;
        const abCost = canAB ? (parseInt(block.querySelector('.e-ab-cost').value) || 0) : 0;
        const abGain = canAB ? (parseInt(block.querySelector('.e-ab-gain').value) || 0) : 0;
        engines.push({
            baseThrust: parseInt(block.querySelector('.e-base-thrust').value) || 0,
            fuelConsumptionNum: parseInt(block.querySelector('.e-fuel').value) || 0,
            fuelConsumptionDen: 1,
            solarPowered: block.querySelector('.e-solar').checked,
            bonusPivots: parseInt(block.querySelector('.e-pivots').value) || 0,
            afterburnFuelCost: abCost,
            afterburnThrustGain: abGain,
            magSail: block.querySelector('.e-mag-sail').checked,
            decommissionsOnAerobrake: block.querySelector('.e-aerobrake-decom').checked
        });
    });

    if (engines.length === 0) {
        document.getElementById('status').textContent = 'Add at least one engine.';
        return;
    }

    // Fuel field is now free-form text supporting "5", "1+5/6", "0+3/4",
    // etc. Parse against the current dryMass; on parse failure we send 0
    // and let the UI's red-border affordance show the error (the field's
    // own input handler does that).
    const dryMass = parseInt(document.getElementById('dry-mass').value) || 0;
    const fuelText = document.getElementById('fuel').value;
    const parsed = parseFuelText(fuelText, dryMass);
    const fuelSteps = parsed.ok ? parsed.fuelSteps : 0;

    // Sunspot Cycle starting year (HF4A K1) — drives Belt-Roll +2 in red,
    // Venus flyby in blue, synodic-comet site/adjacent gating.
    const startingYear = (state.activeTab && state.activeTab.state.solarYear) || 1;

    const request = {
        startNodeId: state.selectedNode,
        engines: engines,
        dryMass: dryMass,
        fuelSteps: fuelSteps,
        allowFuelJettison: document.getElementById('allow-jettison-cb').checked,
        startingYear: startingYear
    };

    document.getElementById('status').textContent = 'Planning routes...';

    // Capture the tab + token at fire time. If the user switches tabs before
    // the response arrives, we discard it so the new tab isn't overwritten
    // and stale results don't get cached into the originating tab either.
    const tabAtFire = state.activeTab;
    const tokenAtFire = ++state.pendingFetchToken;
    // Client-measured round-trip time (network + server); we don't block
    // on the server producing its own measurement.
    const startedAt = performance.now();

    fetch('/api/traverse', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
    })
    .then(r => r.json())
    .then(data => {
        if (tokenAtFire !== state.pendingFetchToken || state.activeTab !== tabAtFire) {
            console.log('Traverse response discarded (tab switched)');
            return;
        }
        console.log('Traverse response:', data.status,
            'tree nodes:', data.tree ? 'yes' : 'no',
            'endpoints:', data.endpoints ? Object.keys(data.endpoints).length : 0);
        state.traverseResult = data;
        state.treeIndex = data.tree ? buildTreeIndex(data.tree) : null;
        console.log('Tree index built, entries:', state.treeIndex ? Object.keys(state.treeIndex).length : 0);
        const routeCount = data.endpoints ? Object.keys(data.endpoints).length : 0;
        const elapsedMs = performance.now() - startedAt;
        const elapsedStr = elapsedMs < 1000
                ? Math.round(elapsedMs) + ' ms'
                : (elapsedMs / 1000).toFixed(1) + ' s';
        document.getElementById('status').textContent =
                routeCount + ' reachable sites found in ' + elapsedStr
                + '. Hover to see routes. [' + data.status + ']';

        // Restore the prior pin + route index when the same endpoint is
        // still reachable. Route index is clamped against the new pareto
        // count — a refire with different settings can grow or shrink
        // the per-endpoint route list.
        if (intendedPin && data.endpoints && Array.isArray(data.endpoints[intendedPin])) {
            state.pinnedEndpoint = intendedPin;
            const cap = Math.max(1, data.endpoints[intendedPin].length);
            state.selectedRouteIndex = Math.max(0, Math.min(intendedRouteIndex || 0, cap - 1));
        }

        draw();
        // After a successful pin restore, the sidebar route-info panel
        // and the on-canvas junker need to refresh against the new tree.
        // (When no pin is restored, the user can hover/click to engage
        // the panel as normal — those listeners already trigger refresh.)
        if (state.pinnedEndpoint) {
            updateRouteInfo();
            updateDebugRoute();
            startFlightAnimation();
        }
        persistTabs();
    })
    .catch(err => {
        if (tokenAtFire !== state.pendingFetchToken || state.activeTab !== tabAtFire) return;
        console.error('Traverse error:', err);
        document.getElementById('status').textContent = 'Error: ' + err;
    });
}

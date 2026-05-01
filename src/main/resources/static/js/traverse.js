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

export function fireTraverse() {
    if (!state.selectedNode) return;
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
            afterburnThrustGain: abGain
        });
    });

    if (engines.length === 0) {
        document.getElementById('status').textContent = 'Add at least one engine.';
        return;
    }

    const request = {
        startNodeId: state.selectedNode,
        engines: engines,
        dryMass: parseInt(document.getElementById('dry-mass').value) || 0,
        fuel: parseInt(document.getElementById('fuel').value) || 0,
        disableVenusFlyby: document.getElementById('no-venus-cb').checked,
        allowFuelJettison: document.getElementById('allow-jettison-cb').checked
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
        draw();
        persistTabs();
    })
    .catch(err => {
        if (tokenAtFire !== state.pendingFetchToken || state.activeTab !== tabAtFire) return;
        console.error('Traverse error:', err);
        document.getElementById('status').textContent = 'Error: ' + err;
    });
}

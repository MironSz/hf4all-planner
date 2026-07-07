// Fires the /api/traverse request and updates state on response.
//
// Tab/token handshake: at fire time we capture the active tab AND a
// monotonic token. Anything that switches tabs increments
// state.pendingFetchToken, so a stale response landing on the previous
// tab is silently dropped — neither the new tab gets overwritten nor
// the originating tab gets a delayed cache.
import { state } from './state.js';
import { draw } from './draw.js';
import { persistTabs } from './tabs.js';
import { parseFuelText } from './fuelStrip.js';
import { updateRouteInfo, updateDebugRoute } from './routeInfo.js';
import { startFlightAnimation } from './flightAnimation.js';

// --- Progress bar (the #planner-progress element lives in index.html) ---
// Drives the determinate "Planned X / Y years" bar during the search, then the
// "N reachable sites" summary on completion. `fraction` is clamped to [0,1].
function setProgress(fraction, label) {
    const wrap = document.getElementById('planner-progress');
    if (!wrap) return;
    wrap.hidden = false;
    const fill = document.getElementById('planner-progress-fill');
    const lab  = document.getElementById('planner-progress-label');
    if (fill) fill.style.width = (Math.max(0, Math.min(1, fraction)) * 100) + '%';
    if (lab) lab.textContent = label;
}

function hideProgress() {
    const wrap = document.getElementById('planner-progress');
    if (wrap) wrap.hidden = true;
}

export function fireTraverse() {
    if (!state.selectedNode) return;

    const engines = [];
    document.querySelectorAll('.engine-block').forEach(block => {
        const canAB = block.querySelector('.e-afterburn').checked;
        // `|| 1` mirrors the form's own defaults (both inputs have min=1),
        // and matches what snapshotActiveTabFromDom persists.
        const abCost = canAB ? (parseInt(block.querySelector('.e-ab-cost').value) || 1) : 0;
        const abGain = canAB ? (parseInt(block.querySelector('.e-ab-gain').value) || 1) : 0;
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

    // Bail BEFORE touching the pin/route state — clearing the pin here and
    // returning without a redraw used to leave the old route line painted
    // on the canvas with no pin behind it.
    if (engines.length === 0) {
        document.getElementById('status').textContent = 'Add at least one engine.';
        return;
    }

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

    // Abort any previous in-flight stream so a refire doesn't leave the server
    // computing routes for a reader we've already discarded.
    if (state.traverseAbort) state.traverseAbort.abort();
    const abort = new AbortController();
    state.traverseAbort = abort;

    // The progress bar (index.html #planner-progress) supersedes the old
    // "Planning routes..." text; #status is now reserved for hints + errors.
    setProgress(0, 'Planning…');
    document.getElementById('status').textContent = '';

    // Capture the tab + token at fire time. If the user switches tabs before
    // the stream finishes, we discard the remaining chunks so the new tab
    // isn't overwritten and stale results don't get cached into the old tab.
    const tabAtFire = state.activeTab;
    const tokenAtFire = ++state.pendingFetchToken;
    // Client-measured round-trip time (network + server).
    const startedAt = performance.now();

    const stillCurrent = () =>
        tokenAtFire === state.pendingFetchToken && state.activeTab === tabAtFire;

    // Streamed deltas accumulate into a growing tree. Each chunk carries only
    // the nodes/endpoints new since the previous one (the server sends each
    // exactly once); we merge and redraw. We maintain BOTH a flat treeIndex /
    // reachableNodes for live use AND nested `children`, so the resulting
    // traverseResult.tree stays a self-contained tree the tab system can
    // re-index on activation (tabs.js → buildTreeIndex).
    const nodesById = {};   // tree id → node (with .children)
    const treeIndex = {};   // tree id → { node, parent }
    const reachable = {};   // map node id → [tree id, ...] (every tree node)
    const endpoints = {};   // map node id → [tree id, ...] (Pareto endpoints)
    let rootNode = null;
    let pinRestored = false;

    const applyChunk = (chunk) => {
        // Merge new tree nodes — already ordered parents-before-children, so a
        // node's parent is always present by the time we attach it.
        for (const n of (chunk.addedNodes || [])) {
            n.children = [];
            nodesById[n.id] = n;
            const parent = n.parentId >= 0 ? nodesById[n.parentId] : null;
            if (parent) parent.children.push(n); else rootNode = n;
            treeIndex[n.id] = { node: n, parent: parent };
            (reachable[n.nodeId] || (reachable[n.nodeId] = [])).push(n.id);
        }
        // Merge new endpoint route ids (append-only — superset each year).
        if (chunk.addedEndpoints) {
            for (const mapId of Object.keys(chunk.addedEndpoints)) {
                (endpoints[mapId] || (endpoints[mapId] = [])).push(...chunk.addedEndpoints[mapId]);
            }
        }

        // Publish the accumulated view. traverseResult keeps a nested tree so
        // tab snapshot/restore (buildTreeIndex) keeps working unchanged.
        state.treeIndex = treeIndex;
        state.reachableNodes = reachable;
        state.traverseResult = {
            startNodeId: chunk.startNodeId,
            endpoints: endpoints,
            tree: rootNode,
            status: chunk.status
        };

        // Restore the user's prior pin as soon as that endpoint becomes
        // reachable (once), so a refire / pasted share-link re-selects it
        // without waiting for the whole search to finish.
        if (!pinRestored && intendedPin && Array.isArray(endpoints[intendedPin])) {
            state.pinnedEndpoint = intendedPin;
            const cap = Math.max(1, endpoints[intendedPin].length);
            state.selectedRouteIndex = Math.max(0, Math.min(intendedRouteIndex || 0, cap - 1));
            pinRestored = true;
        }

        const routeCount = Object.keys(endpoints).length;

        if (!chunk.done) {
            setProgress(chunk.year / chunk.maxYear,
                    'Planned ' + chunk.year + ' / ' + chunk.maxYear + ' years');
        } else if (chunk.status === 'ok') {
            setProgress(1, routeCount + ' reachable sites');
            const elapsedMs = performance.now() - startedAt;
            const elapsedStr = elapsedMs < 1000
                    ? Math.round(elapsedMs) + ' ms'
                    : (elapsedMs / 1000).toFixed(1) + ' s';
            document.getElementById('status').textContent =
                    'Found in ' + elapsedStr + '. Hover to see routes.';
        } else {
            // Validation / internal error delivered as a lone done chunk.
            hideProgress();
            document.getElementById('status').textContent = chunk.status || 'No routes found.';
        }

        draw();
        // Refresh the route-info card on every chunk so new routes to a
        // pinned / hovered endpoint surface live: the "[i / N]" count climbs and
        // the extra routes become cycle-able as they stream in. These are cheap
        // DOM re-renders. The flight animation is heavier and would visibly
        // restart each chunk, so it stays done-only.
        if (state.pinnedEndpoint || state.hoveredNode) {
            updateRouteInfo();
            updateDebugRoute();
        }
        if (chunk.done && state.pinnedEndpoint) {
            startFlightAnimation();
        }
        if (chunk.done) persistTabs();
    };

    // Read the chunked NDJSON response: one JSON object per line, applied as it
    // arrives. We buffer partial lines across reads and parse on each newline.
    (async () => {
        let res;
        try {
            res = await fetch('/api/traverse', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(request),
                signal: abort.signal
            });
        } catch (err) {
            if (err && err.name === 'AbortError') return;   // superseded by a refire
            if (!stillCurrent()) return;
            console.error('Traverse error:', err);
            hideProgress();
            document.getElementById('status').textContent = 'Error: ' + err;
            return;
        }

        // Pre-stream failure (validation 400, 405, a 500 thrown before the
        // 200 was committed): the body is a single {"status": ...} JSON
        // object, not NDJSON — mid-stream errors arrive instead as a final
        // done chunk and are handled by applyChunk. Surface the message and
        // stop before the stream reader misreads it as a progress chunk.
        if (!res.ok) {
            let msg = 'HTTP ' + res.status;
            try {
                const body = await res.json();
                if (body && body.status) msg = body.status;
            } catch (e) { /* non-JSON error body (e.g. proxy error page) */ }
            if (!stillCurrent()) return;
            hideProgress();
            document.getElementById('status').textContent = msg;
            if (state.traverseAbort === abort) state.traverseAbort = null;
            return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        const handleLine = (line) => {
            if (!line.trim()) return;
            let chunk;
            try { chunk = JSON.parse(line); }
            catch (e) { console.warn('Discarding malformed NDJSON line:', e); return; }
            applyChunk(chunk);
        };

        try {
            for (;;) {
                const { value, done } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                let nl;
                while ((nl = buffer.indexOf('\n')) >= 0) {
                    const line = buffer.slice(0, nl);
                    buffer = buffer.slice(nl + 1);
                    // Bail if the user switched tabs / refired mid-stream.
                    if (!stillCurrent()) { await reader.cancel(); return; }
                    handleLine(line);
                }
            }
            // Flush any trailing line that had no terminating newline.
            buffer += decoder.decode();
            if (stillCurrent() && buffer.trim()) handleLine(buffer);
        } catch (err) {
            if (err && err.name === 'AbortError') return;
            if (!stillCurrent()) return;
            console.error('Traverse stream error:', err);
            hideProgress();
            document.getElementById('status').textContent = 'Error: ' + err;
        } finally {
            if (state.traverseAbort === abort) state.traverseAbort = null;
        }
    })();
}

// Entry point. Imports every module so their top-level code runs, then
// performs DOM-ref capture, kicks the eager fuel-strip preload, runs the
// main config → map → image load chain, and wires up everything that
// doesn't fit cleanly inside one of the feature modules (top-bar
// checkboxes, sidebar toggle, rotate buttons, ESC + arrow key handler,
// global Q/E + filter keyboard handler).
import { state, ROTATION_STEP_RAD } from './state.js';
import { setConfig, applyConfigToForm } from './config.js';
import { initCanvas, clearRoutes } from './canvas.js';
import { initTabs, initTabStripButton, persistTabs, applyShareToActiveTab } from './tabs.js';
import { readShareFromUrl } from './urlState.js';
import { fireTraverse } from './traverse.js';
import { draw } from './draw.js';
import { updateRouteInfo, updateDebugRoute, cycleRoute, initRouteInfo } from './routeInfo.js';
import { initEngineForm } from './engineForm.js';
import { initSearchBox } from './search.js';
import { initTooltip, loadTooltips } from './tooltip.js';
import { initSiteFilter, handleFilterKey } from './siteFilter.js';
import { initHexMask } from './hexMask.js';
import { loadStripChitCoords, bindStripImg, updateEndpointFuelStrip } from './endpointFuelStripOverlay.js';
import { setRotation, nudgeRotation } from './rotation.js';
import { getActiveEndpoint, getTreeNodeIds } from './routeTree.js';
import { startFlightAnimation } from './flightAnimation.js';
import { initSolarCycle } from './solarCycle.js';
import { initHowTo } from './howto.js';

// Capture DOM refs that other modules read off `state`. The IIFE in the
// original file did this at script-parse time; with `<script type=module>`
// being deferred, the DOM is parsed by the time this code runs.
state.bgImg = document.getElementById('bg');
state.canvas = document.getElementById('canvas');
state.ctx = state.canvas.getContext('2d');
state.mainEl = document.getElementById('main');
state.container = document.getElementById('map-container');

// One-shot wiring for the various feature modules.
initTabStripButton();
initTooltip();
// Fire-and-forget; once the JSON resolves it runs `inflateTooltips` to
// populate `data-tip` on every `[data-tip-key]` element. Failure is
// non-fatal (tooltips simply stay empty for any keyed element).
loadTooltips();
initSearchBox();
initSiteFilter();
initRouteInfo();
initEngineForm();
initHexMask();
initSolarCycle();
initHowTo();

// Top-bar checkbox listeners.
document.getElementById('debug-cb').addEventListener('change', (e) => {
    state.debugMode = e.target.checked;
    draw();
    updateDebugRoute();
    updateRouteInfo();
    persistTabs();
});

// "Show fuel strip" toggle — purely visual, no replan needed.
document.getElementById('show-fuel-strip-cb').addEventListener('change', () => {
    updateEndpointFuelStrip();
    draw();   // hides/shows the on-route green hover dot
});

document.getElementById('allow-jettison-cb').addEventListener('change', () => {
    if (state.selectedNode) fireTraverse();
    persistTabs();
});

// Independent of the main load chain — fire as soon as the script
// runs so the chit coords are usually ready by the time a route is
// pinned. Failures are swallowed inside loadStripChitCoords so they
// can't block anything else.
loadStripChitCoords();
bindStripImg();

// --- Load config, then map data, then image ---
fetch('/api/config')
    .then(r => r.json())
    .then(cfg => { setConfig(cfg); })
    .catch(err => { console.warn('Config load failed; using defaults:', err); })
    .finally(() => {
        applyConfigToForm();
        fetch('/api/map')
            .then(r => r.json())
            .then(data => {
                state.mapData = data;
                document.getElementById('status').textContent = 'Loading map image...';
                // Tabs are initialized after initCanvas() so the zoom transform
                // can be restored as part of the first activateTab().
                state.bgImg.onload = () => { initCanvas(); initTabs(); };
                state.bgImg.onerror = () => {
                    document.getElementById('status').textContent = 'Image failed to load. Using plain canvas.';
                    initCanvas();
                    initTabs();
                };
                state.bgImg.src = '/img/hf4.jpg';
            })
            .catch(err => {
                document.getElementById('status').textContent = 'Failed to load map: ' + err;
            });
    });

// Live-paste support: if the user replaces the URL hash in the address
// bar (or follows a share link in an already-open tab), re-apply the
// pasted plan onto the active tab. `history.replaceState` writes from
// our own code don't fire hashchange, so this only catches genuine
// outside changes.
window.addEventListener('hashchange', () => {
    const share = readShareFromUrl();
    if (share) applyShareToActiveTab(share);
});

// --- Keyboard #1: ESC clears selection, arrows cycle routes. ---
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        clearRoutes();
        return;
    }
    const active = getActiveEndpoint();
    if (!active) return;
    const ids = getTreeNodeIds(active);
    if (!ids || ids.length <= 1) return;
    // Arrow keys cycle the selected pareto route. They DON'T pin a hovered
    // endpoint — pin status is preserved as-is. Moving the cursor to a
    // different reachable node resets selectedRouteIndex back to 0 (handled
    // in onCanvasMouseMove).
    if (e.key === 'ArrowRight') {
        state.selectedRouteIndex = (state.selectedRouteIndex + 1) % ids.length;
        draw();
        updateDebugRoute();
        updateRouteInfo();
        persistTabs();
        startFlightAnimation();
    } else if (e.key === 'ArrowLeft') {
        state.selectedRouteIndex = (state.selectedRouteIndex - 1 + ids.length) % ids.length;
        draw();
        updateDebugRoute();
        updateRouteInfo();
        persistTabs();
        startFlightAnimation();
    }
});

// --- Sidebar collapse toggle + sidebar/rotate button wiring. ---
// Persisted to localStorage so the choice sticks across reloads.
(function initSidebarChrome() {
    const SIDEBAR_KEY = 'hf4a-sidebar-collapsed';
    const btn = document.getElementById('sidebar-toggle');
    function apply(collapsed) {
        document.body.classList.toggle('sidebar-collapsed', collapsed);
        btn.textContent = collapsed ? '«' : '»';
        btn.title = collapsed ? 'Show side panel' : 'Hide side panel';
    }
    apply(localStorage.getItem(SIDEBAR_KEY) === '1');
    btn.addEventListener('click', () => {
        const collapsed = !document.body.classList.contains('sidebar-collapsed');
        apply(collapsed);
        try { localStorage.setItem(SIDEBAR_KEY, collapsed ? '1' : '0'); } catch (e) {}
    });
    document.getElementById('sidebar-clear').addEventListener('click', clearRoutes);
    document.getElementById('sidebar-prev').addEventListener('click', () => cycleRoute(-1));
    document.getElementById('sidebar-next').addEventListener('click', () => cycleRoute(+1));
    document.getElementById('rotate-ccw')  .addEventListener('click', () => nudgeRotation(-ROTATION_STEP_RAD));
    document.getElementById('rotate-cw')   .addEventListener('click', () => nudgeRotation(+ROTATION_STEP_RAD));
    document.getElementById('rotate-reset').addEventListener('click', () => setRotation(0));
})();

// --- Keyboard #2: F / Shift+F (filter), Q / E (rotation). ---
// Ignored while typing in any input/textarea/contenteditable.
document.addEventListener('keydown', (e) => {
    if (e.ctrlKey || e.altKey || e.metaKey) return;
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA'
              || t.tagName === 'SELECT' || t.isContentEditable)) return;
    if (handleFilterKey(e)) return;          // F / Shift+F
    if (e.key === 'q' || e.key === 'Q') {
        e.preventDefault();
        nudgeRotation(-ROTATION_STEP_RAD);
    } else if (e.key === 'e' || e.key === 'E') {
        e.preventDefault();
        nudgeRotation(+ROTATION_STEP_RAD);
    }
});

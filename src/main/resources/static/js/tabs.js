// =====================================================================
// Tabs — multi-plan support.
//
// Each tab owns its own inputs (fuel, engines, filters, ...) plus its
// interactive state (selected start, pinned endpoint, route index, zoom
// transform, last traverseResult). The sidebar form DOM is shared across
// tabs; switching a tab snapshots the previous tab's DOM into its state
// and rewrites the DOM from the new tab's state. The shared canvas is
// re-drawn from the new tab's traverseResult.
// =====================================================================
import { state } from './state.js';
import { cfgI, cfgF } from './config.js';
import { buildTreeIndex } from './routeTree.js';
import { readSiteFilterFromDom, writeSiteFilterToDom, makeDefaultSiteFilter, migrateSiteFilter } from './siteFilter.js';
import { addEngineBlock } from './engineForm.js';
import { fireTraverse } from './traverse.js';
import { draw } from './draw.js';
import { updateRouteInfo, updateDebugRoute } from './routeInfo.js';
import { writeShareToUrlFromTab, readShareFromUrl, SHARE_FIELDS } from './urlState.js';
import { applySelectionFromState as applySolarSelection } from './solarCycle.js';

const TABS_STORAGE_KEY     = 'hf4a-tabs';
const TABS_STORAGE_VERSION = 1;

function makeDefaultTabState() {
    return {
        dryMass: cfgI('ui.dry.mass.default', 4),
        // fuelText is the user-typed Fuel input. Stored as a string so
        // fractional values like "1+5/6" survive a tab switch. Parsed
        // each time it's used. Old saves persisted an integer `fuel`;
        // renderActiveTabToDom() reads either form for back-compat.
        fuelText: String(cfgI('ui.fuel.default', 15)),
        engines: [{
            baseThrust:       cfgI('ui.engine.thrust.default', 3),
            fuelConsumption:  cfgI('ui.engine.fuel.default',   2),
            solarPowered:     false,
            bonusPivots:      cfgI('ui.engine.pivots.default', 0),
        }],
        allowFuelJettison: true,
        debug:             false,
        solarYear:         1,

        siteFilter:        makeDefaultSiteFilter(),
        searchQuery:       '',
        selectedNode:      null,
        pinnedEndpoint:    null,
        selectedRouteIndex: 0,
        zoomTransform:     null,   // {x,y,k} or null = use default viewport
        traverseResult:    null,
    };
}

function makeTab(name, tabState) {
    return {
        id: 'tab-' + (state.tabSeq++),
        name: name || 'Tab',
        state: tabState || makeDefaultTabState(),
    };
}

// Pull current DOM + module-level state into the active tab's state object.
function snapshotActiveTabFromDom() {
    if (!state.activeTab || !state.tabsReady) return;
    const s = state.activeTab.state;
    s.dryMass = parseInt(document.getElementById('dry-mass').value) || 0;
    s.fuelText = document.getElementById('fuel').value;
    s.engines = [];
    document.querySelectorAll('.engine-block').forEach(b => {
        s.engines.push({
            baseThrust:              parseInt(b.querySelector('.e-base-thrust').value) || 0,
            fuelConsumption:         parseInt(b.querySelector('.e-fuel').value)   || 0,
            solarPowered:            b.querySelector('.e-solar').checked,
            bonusPivots:             parseInt(b.querySelector('.e-pivots').value) || 0,
            canAfterburn:            b.querySelector('.e-afterburn').checked,
            afterburnFuelCost:       parseInt(b.querySelector('.e-ab-cost').value) || 0,
            afterburnThrustGain:     parseInt(b.querySelector('.e-ab-gain').value) || 1,
            magSail:                 b.querySelector('.e-mag-sail').checked,
            decommissionsOnAerobrake:b.querySelector('.e-aerobrake-decom').checked,
        });
    });
    s.allowFuelJettison = document.getElementById('allow-jettison-cb').checked;
    s.debug             = document.getElementById('debug-cb').checked;
    // solarYear is mutated only via the cycle widget click handler (setSolarYear)
    // which writes directly into state.activeTab.state.solarYear. Re-read it
    // here defensively in case the widget falls out of sync.
    if (s.solarYear == null) s.solarYear = 1;
    s.siteFilter = readSiteFilterFromDom();
    s.searchQuery       = document.getElementById('search-input').value;
    s.selectedNode      = state.selectedNode;
    s.pinnedEndpoint    = state.pinnedEndpoint;
    s.selectedRouteIndex = state.selectedRouteIndex;
    s.traverseResult    = state.traverseResult;
    if (state.zoomSel && state.zoomBehavior) {
        const t = d3.zoomTransform(state.zoomSel.node());
        s.zoomTransform = { x: t.x, y: t.y, k: t.k };
    }
}

function renderStartLabel(nodeId) {
    const p = state.mapData && state.mapData.points && state.mapData.points[nodeId];
    if (!p) return 'Start: ' + nodeId;
    let info = nodeId.substring(0, 8) + '... (' + (p.type || '?') + ')';
    if (p.siteName) info = p.siteName + ' (' + p.type + ')';
    return 'Start: ' + info;
}

// Push the active tab's state into the DOM + module-level vars + canvas.
function renderActiveTabToDom() {
    if (!state.activeTab) return;
    const s = state.activeTab.state;

    document.getElementById('dry-mass').value      = (s.dryMass != null) ? s.dryMass : cfgI('ui.dry.mass.default', 4);
    // Back-compat: older tab saves stored an integer `fuel`; new ones
    // store `fuelText` (which may be fractional, e.g. "1+5/6").
    document.getElementById('fuel').value =
            (s.fuelText != null) ? s.fuelText
          : (s.fuel    != null) ? String(s.fuel)
          : String(cfgI('ui.fuel.default', 15));
    document.getElementById('allow-jettison-cb').checked = (s.allowFuelJettison !== false);
    document.getElementById('debug-cb').checked    = !!s.debug;
    state.debugMode = !!s.debug;
    document.getElementById('search-input').value  = s.searchQuery || '';
    s.siteFilter = migrateSiteFilter(s.siteFilter);
    writeSiteFilterToDom(s.siteFilter);
    if (s.solarYear == null) s.solarYear = 1;
    applySolarSelection();

    // Rebuild engine blocks without firing traverse for each
    document.getElementById('engines-container').innerHTML = '';
    state.suppressFire = true;
    const enginesToRender = (s.engines && s.engines.length > 0) ? s.engines : [null];
    enginesToRender.forEach(eng => addEngineBlock(eng));
    state.suppressFire = false;

    state.selectedNode       = s.selectedNode || null;
    state.pinnedEndpoint     = s.pinnedEndpoint || null;
    state.selectedRouteIndex = s.selectedRouteIndex || 0;
    state.hoveredNode        = null;
    state.traverseResult     = s.traverseResult || null;
    state.treeIndex          = (state.traverseResult && state.traverseResult.tree) ? buildTreeIndex(state.traverseResult.tree) : null;
    if (!state.traverseResult) state.reachableNodes = null;

    document.getElementById('node-info').textContent = state.selectedNode
        ? renderStartLabel(state.selectedNode)
        : 'Click a node on the map to select start';
    document.getElementById('status').textContent = state.traverseResult
        ? ((state.traverseResult.endpoints ? Object.keys(state.traverseResult.endpoints).length : 0)
            + ' sites reachable.')
        : '';

    // Restore zoom (or use the configured initial viewport for fresh tabs)
    if (state.zoomSel && state.zoomBehavior && state.imgW) {
        if (s.zoomTransform) {
            state.zoomSel.call(state.zoomBehavior.transform,
                d3.zoomIdentity
                    .translate(s.zoomTransform.x, s.zoomTransform.y)
                    .scale(s.zoomTransform.k));
        } else {
            state.zoomSel.call(state.zoomBehavior.translateTo,
                cfgF('ui.viewport.initial.x.ratio', 0.85) * state.imgW,
                cfgF('ui.viewport.initial.y.ratio', 0.80) * state.imgH);
        }
    }

    draw();
    updateRouteInfo();
    updateDebugRoute();

    // If the tab has a start node but no cached results, plan now.
    if (state.selectedNode && !state.traverseResult) {
        fireTraverse();
    }
}

function activateTab(tab) {
    if (state.activeTab === tab) return;
    if (state.activeTab && state.tabsReady) snapshotActiveTabFromDom();
    state.activeTab = tab;
    state.pendingFetchToken++; // discard any in-flight fetch from prior tab
    if (state.mapData && state.imgW) renderActiveTabToDom();
    renderTabStrip();
    persistTabs();
}

function newTab() {
    if (state.activeTab && state.tabsReady) snapshotActiveTabFromDom();
    const tab = makeTab('Tab ' + (state.tabs.length + 1));
    state.tabs.push(tab);
    activateTab(tab);
}

function duplicateTab(srcTab) {
    if (srcTab === state.activeTab && state.tabsReady) snapshotActiveTabFromDom();
    const cloned = JSON.parse(JSON.stringify(srcTab.state));
    const tab = makeTab(srcTab.name + ' copy', cloned);
    const idx = state.tabs.indexOf(srcTab);
    state.tabs.splice(idx + 1, 0, tab);
    activateTab(tab);
}

function closeTab(tab) {
    const idx = state.tabs.indexOf(tab);
    if (idx < 0) return;
    if (state.tabs.length === 1) {
        // Replace with a blank rather than leaving zero tabs
        const fresh = makeTab('Tab 1');
        state.tabs[0] = fresh;
        state.activeTab = null;
        activateTab(fresh);
        return;
    }
    state.tabs.splice(idx, 1);
    if (tab === state.activeTab) {
        const next = state.tabs[Math.min(idx, state.tabs.length - 1)];
        state.activeTab = null;
        activateTab(next);
    } else {
        renderTabStrip();
        persistTabs();
    }
}

function renameTab(tab, name) {
    if (!name || !name.trim()) return;
    tab.name = name.trim().substring(0, 30);
    renderTabStrip();
    persistTabs();
}

function renderTabStrip() {
    const list = document.getElementById('tab-list');
    list.innerHTML = '';
    state.tabs.forEach(tab => {
        const el = document.createElement('div');
        el.className = 'tab' + (tab === state.activeTab ? ' active' : '');
        el.title = tab.name + ' — double-click to rename, middle-click to close';

        const label = document.createElement('span');
        label.className = 'tab-label';
        label.textContent = tab.name;
        label.addEventListener('click',     () => activateTab(tab));
        label.addEventListener('dblclick', (e) => {
            e.stopPropagation();
            const name = prompt('Rename tab:', tab.name);
            if (name != null) renameTab(tab, name);
        });
        el.appendChild(label);

        const dup = document.createElement('span');
        dup.className = 'tab-action';
        dup.textContent = '⎘';
        dup.title = 'Duplicate tab';
        dup.addEventListener('click', (e) => { e.stopPropagation(); duplicateTab(tab); });
        el.appendChild(dup);

        const close = document.createElement('span');
        close.className = 'tab-action';
        close.textContent = '×';
        close.title = 'Close tab';
        close.addEventListener('click', (e) => { e.stopPropagation(); closeTab(tab); });
        el.appendChild(close);

        // Middle-click anywhere on the tab also closes it.
        el.addEventListener('mousedown', (e) => {
            if (e.button === 1) { e.preventDefault(); closeTab(tab); }
        });

        list.appendChild(el);
    });
}

// ----- Persistence -----
let persistDebounce = null;
export function persistTabs() {
    clearTimeout(persistDebounce);
    persistDebounce = setTimeout(() => {
        if (state.tabsReady) snapshotActiveTabFromDom();
        // Mirror the active tab's shareable fields into the URL hash so
        // the address bar is always a paste-able plan link. Cheap when
        // unchanged (writeShareToUrlFromTab no-ops on identical hash).
        if (state.activeTab) writeShareToUrlFromTab(state.activeTab.state);
        const payload = {
            version: TABS_STORAGE_VERSION,
            activeTabId: state.activeTab ? state.activeTab.id : null,
            tabs: state.tabs.map(t => ({ id: t.id, name: t.name, state: t.state })),
        };
        try {
            localStorage.setItem(TABS_STORAGE_KEY, JSON.stringify(payload));
        } catch (err) {
            // QuotaExceeded is the realistic failure here. Drop traverseResult
            // (the heaviest field) and retry once before giving up.
            console.warn('Persisting tabs failed; dropping cached results:', err);
            try {
                const lite = {
                    version: TABS_STORAGE_VERSION,
                    activeTabId: payload.activeTabId,
                    tabs: payload.tabs.map(t => ({
                        id: t.id, name: t.name,
                        state: Object.assign({}, t.state, { traverseResult: null }),
                    })),
                };
                localStorage.setItem(TABS_STORAGE_KEY, JSON.stringify(lite));
            } catch (err2) {
                console.warn('Persisting tabs (lite) failed:', err2);
            }
        }
    }, 200);
}

function loadTabsFromStorage() {
    try {
        const raw = localStorage.getItem(TABS_STORAGE_KEY);
        if (!raw) return null;
        const data = JSON.parse(raw);
        if (!data || data.version !== TABS_STORAGE_VERSION) return null;
        if (!Array.isArray(data.tabs) || data.tabs.length === 0) return null;
        state.tabs = data.tabs.map(t => {
            const def = makeDefaultTabState();
            const tabState = Object.assign(def, t.state || {});
            tabState.siteFilter = migrateSiteFilter(tabState.siteFilter);
            const id = t.id || ('tab-' + (state.tabSeq++));
            const m = /tab-(\d+)/.exec(id);
            if (m) state.tabSeq = Math.max(state.tabSeq, parseInt(m[1]) + 1);
            return { id, name: t.name || 'Tab', state: tabState };
        });
        return data.activeTabId;
    } catch (err) {
        console.warn('Loading tabs failed; starting fresh:', err);
        return null;
    }
}

export function initTabs() {
    const savedActive = loadTabsFromStorage();
    if (state.tabs.length === 0) {
        state.tabs.push(makeTab('Tab 1'));
    }
    const target = state.tabs.find(t => t.id === savedActive) || state.tabs[0];
    state.activeTab = null;
    state.tabsReady = true;
    activateTab(target);
    // If the address bar carries a share hash, override the active tab's
    // restored state with the pasted plan and refire the traverse. The
    // share takes precedence over localStorage on purpose — opening a
    // shared link is meant to show the shared plan, not the recipient's
    // last-edited one.
    const share = readShareFromUrl();
    if (share) applyShareToActiveTab(share);
}

/**
 * Slot a decoded share payload (whitelist of SHARE_FIELDS) onto the
 * active tab and re-render. The traverseResult is dropped so
 * renderActiveTabToDom() will refire the search locally — recipients
 * recompute the tree, they don't paste it.
 *
 * Called both from initTabs (on page load with `#s=...`) and from a
 * `hashchange` listener in main.js (live paste into the address bar).
 */
export function applyShareToActiveTab(share) {
    if (!share || !state.activeTab) return;
    const s = state.activeTab.state;
    for (const k of SHARE_FIELDS) {
        if (share[k] !== undefined) s[k] = share[k];
    }
    if (s.siteFilter) s.siteFilter = migrateSiteFilter(s.siteFilter);
    s.traverseResult = null;
    if (state.mapData && state.imgW) renderActiveTabToDom();
    persistTabs();
}

// Wires the static "+" new-tab button. Called once at startup by main.js.
export function initTabStripButton() {
    document.getElementById('new-tab').addEventListener('click', newTab);
}
